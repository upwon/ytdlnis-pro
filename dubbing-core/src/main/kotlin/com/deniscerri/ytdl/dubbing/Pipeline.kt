package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DubbingConfig(
    val voice: String = "zh-CN-XiaoxiaoNeural",
    val baseRatePercent: Int = 0,
    /** Upper bound for the speaking-rate boost requested from the TTS engine before ffmpeg tempo kicks in. */
    val maxSynthRatePercent: Int = 40,
    val charsPerSecond: Double = 4.5,
    val ttsConcurrency: Int = 3,
    val mergeSentences: Boolean = true,
    val sourceLanguageCode: String = "en",
    val asrSegmentSeconds: Int = 600,
    val embedChineseSubtitle: Boolean = false,
    /** Dub men and women with different voices (guessed from the pitch of the original audio). */
    val multiVoice: Boolean = false,
    /** Voice for lines guessed as male when [multiVoice] is on; [voice] is used for female / unknown. */
    val maleVoice: String = "zh-CN-YunxiNeural",
    /** How many speakers to tell apart (by pitch, or by the language model when [llmRoles] is on). */
    val maxSpeakers: Int = 2,
    /** Let the language model work out who speaks each line (needs a labeler); voices are then chosen per person. */
    val llmRoles: Boolean = false,
    /** Voice per person ("S1", "S2"...) chosen on the assignment screen; missing ones use the suggested voice. */
    val roleVoices: Map<String, String> = emptyMap(),
    /** Extra Mandarin voices handed to people of the same sex after the two main voices. */
    val extraFemaleVoices: List<String> = emptyList(),
    val extraMaleVoices: List<String> = emptyList(),
    /** Optional per-speaker voices ("M1", "M2", "F1", "F2"...); labels missing here use [maleVoice] / [voice]. */
    val speakerVoices: Map<String, String> = emptyMap(),
    /** When set, the Chinese subtitles are also written to this external .srt next to the dubbed video. */
    val externalSubtitle: File? = null,
    val externalSubtitleBilingual: Boolean = false,
    val align: AlignConfig = AlignConfig(),
    val mux: MuxOptions = MuxOptions(),
)

data class DubbingReport(
    val cues: Int,
    val spoken: Int,
    val align: AlignReport,
    val output: File,
    val usedAsr: Boolean,
    /** Sentences whose speech could not be generated and were left silent. */
    val skipped: Int = 0,
)

@Serializable
private data class Checkpoint(val sourceHash: String, val cues: List<Cue>)

@Serializable
internal data class RolesStored(val hash: String, val roles: List<Role>)

/**
 * subtitles (or speech recognition) -> translation -> TTS -> timeline alignment -> new audio track in the video.
 * Every expensive step is checkpointed in `workDir`, so a failed run resumes without repeating API calls.
 */
class DubbingPipeline(
    private val ffmpeg: FfmpegRunner,
    private val translator: Translator,
    private val tts: TtsProvider,
    private val config: DubbingConfig = DubbingConfig(),
    private val asr: AsrProvider? = null,
    private val log: (String) -> Unit = {},
    private val onProgress: (Progress) -> Unit = {},
    /** Free-text "what is happening now" (the sentence being translated / voiced) for the UI. */
    private val onPreview: (String) -> Unit = {},
    private val labeler: SpeakerLabeler? = null,
) {
    private val json = Json { prettyPrint = false; ignoreUnknownKeys = true }

    private class Prepared(
        val cues: List<Cue>,
        val sourceHash: String,
        val save: suspend (String, List<Cue>) -> Unit,
        val usedAsr: Boolean,
    )

    /** Steps 1 and 2 (source text + translation), shared by dubbing and subtitle-only runs. Checkpointed in [workDir]. */
    private suspend fun prepare(video: File?, info: MediaInfo?, subtitle: File?, workDir: File): Prepared {
        workDir.mkdirs()
        val checkpointFile = File(workDir, "cues.json")
        val checkpointLock = Mutex()
        val save: suspend (String, List<Cue>) -> Unit = { hash, list ->
            checkpointLock.withLock {
                val tmp = File(workDir, "cues.json.tmp")
                tmp.writeText(json.encodeToString(Checkpoint(hash, list)))
                Files.move(tmp.toPath(), checkpointFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        // 1. Source text -------------------------------------------------------------------------
        val usedAsr = subtitle == null
        val sourceHash = if (subtitle != null) sha256(subtitle.readBytes())
        else "asr:${video?.length()}:${config.sourceLanguageCode}:${config.asrSegmentSeconds}"
        var cues = loadCheckpoint(checkpointFile, sourceHash)
        if (cues != null) {
            log("Resuming from checkpoint (${cues.size} cues)")
        } else {
            onProgress(Progress(Stage.SOURCE, 0, 1))
            cues = if (subtitle != null) {
                SubtitleCleaner.normalize(SubtitleParser.parse(subtitle.readText()), config.mergeSentences)
            } else {
                recognize(video ?: throw DubbingException("No subtitle source"), info ?: ffmpeg.probe(video), workDir)
            }
            if (cues.isEmpty()) throw DubbingException("No spoken text found in the subtitles / audio")
            save(sourceHash, cues)
        }
        onProgress(Progress(Stage.SOURCE, 1, 1))

        // 2. Translate ---------------------------------------------------------------------------
        cues = translator.translate(
            cues,
            onProgress = { d, t -> onProgress(Progress(Stage.TRANSLATE, d, t)) },
            onBatch = { all ->
                save(sourceHash, all)
                all.lastOrNull { it.zh.isNotBlank() }?.let { onPreview("${it.src}\n→ ${it.zh}") }
            },
        )
        save(sourceHash, cues)
        return Prepared(cues, sourceHash, save, usedAsr)
    }

    /**
     * Subtitle-only mode: translates the English subtitles (or the recognized speech) and writes an external SRT.
     * With [bilingual] each entry holds the Chinese line above the original line. Returns the number of entries.
     */
    suspend fun translateSubtitles(video: File?, subtitle: File?, workDir: File, output: File, bilingual: Boolean = false): Int {
        if (subtitle == null && video == null) throw DubbingException("No subtitle source")
        val prepared = prepare(video, null, subtitle, workDir)
        val text = if (bilingual) SrtWriter.formatBilingual(prepared.cues) else SrtWriter.format(prepared.cues)
        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(text)
        return prepared.cues.count { it.zh.isNotBlank() }
    }

    /**
     * First half of a "multi-role" dub: source text, translation, then who speaks which line. Everything is
     * checkpointed in [workDir], so the following [run] only has to speak. Returns the people found, each with a
     * suggested voice, for the assignment screen.
     */
    suspend fun analyzeRoles(video: File, subtitle: File?, workDir: File): List<Role> {
        val info = ffmpeg.probe(video)
        val prepared = prepare(video, info, subtitle, workDir)
        return ensureRoles(video, prepared, workDir).second
    }

    private suspend fun ensureRoles(video: File, p: Prepared, workDir: File): Pair<List<Cue>, List<Role>> {
        val file = File(workDir, "roles.json")
        if (p.cues.all { it.speaker.startsWith("S") }) {
            val stored = runCatching { json.decodeFromString<RolesStored>(file.readText()) }.getOrNull()
            if (stored != null && stored.hash == p.sourceHash) return p.cues to stored.roles
        }
        val labeler = labeler ?: throw DubbingException("Multi-role dubbing needs a translation service for the speaker labels")
        onPreview("Measuring the pitch of every voice…")
        val classifier = SpeakerClassifier(ffmpeg, log)
        val f0s = classifier.measure(video, p.cues, workDir)
        val threshold = SpeakerClassifier.genderThreshold(f0s?.filterNotNull().orEmpty())
        val hints = p.cues.indices.map { i -> f0s?.get(i)?.let { if (it < threshold) "M" else "F" } }
        val labeling = labeler.label(p.cues, hints, config.maxSpeakers.coerceIn(2, 6)) { d, t -> onPreview("Identifying speakers $d/$t") }
        val pool = VoicePool(config.voice, config.maleVoice, config.extraFemaleVoices, config.extraMaleVoices)
        val (labels, roles) = RoleBuilder.build(p.cues, labeling, f0s, threshold, config.maxSpeakers.coerceIn(2, 6), pool)
        val cues = p.cues.mapIndexed { i, c -> c.copy(speaker = labels[i]) }
        p.save(p.sourceHash, cues)
        file.writeText(json.encodeToString(RolesStored(p.sourceHash, roles)))
        log("Speakers: " + roles.joinToString { "${it.id}=${it.gender}(${it.lines})" })
        return cues to roles
    }

    suspend fun run(video: File, subtitle: File?, workDir: File, output: File): DubbingReport {
        val info = ffmpeg.probe(video)
        val prepared = prepare(video, info, subtitle, workDir)
        var cues = prepared.cues
        val sourceHash = prepared.sourceHash
        val save = prepared.save
        val usedAsr = prepared.usedAsr
        config.externalSubtitle?.let { external ->
            external.absoluteFile.parentFile?.mkdirs()
            external.writeText(
                if (config.externalSubtitleBilingual) SrtWriter.formatBilingual(cues) else SrtWriter.format(cues)
            )
        }

        // 2b. Who is speaking -------------------------------------------------------------------
        if (config.llmRoles) {
            val (labelled, roles) = ensureRoles(video, Prepared(cues, sourceHash, save, usedAsr), workDir)
            cues = labelled
            roleVoiceMap = roles.associate { it.id to (config.roleVoices[it.id] ?: it.voice) }
        } else if (config.multiVoice && cues.any { it.speaker.isBlank() }) {
            cues = SpeakerClassifier(ffmpeg, log, config.maxSpeakers).classify(video, cues, workDir)
            save(sourceHash, cues)
        }

        // 3. Text to speech ----------------------------------------------------------------------
        cues = synthesize(cues, workDir, sourceHash, save)

        // 4. Align -------------------------------------------------------------------------------
        onProgress(Progress(Stage.ALIGN, 0, 1))
        val pcm = File(workDir, "dub.pcm")
        val alignReport = TimelineAligner(ffmpeg, config.align, log).align(
            cues, workDir, if (info.durationMs > 0) info.durationMs else 0L, pcm
        )
        if (alignReport.clips == 0) throw DubbingException("No audio clips were produced")
        onProgress(Progress(Stage.ALIGN, 1, 1))

        // 5. Mux ---------------------------------------------------------------------------------
        onProgress(Progress(Stage.MUX, 0, 1))
        var muxOptions = config.mux
        if (config.embedChineseSubtitle) {
            val srt = File(workDir, "zh.srt").apply { writeText(SrtWriter.format(cues)) }
            muxOptions = muxOptions.copy(extraSubtitle = srt)
        }
        val part = File(output.absoluteFile.parentFile, "${output.nameWithoutExtension}.part.${output.extension}")
        output.absoluteFile.parentFile?.mkdirs()
        val args = DubMuxer.buildArgs(video, pcm, part, info, muxOptions, config.align.sampleRate)
        val r = ffmpeg.run(args)
        if (!r.ok || !part.exists() || part.length() == 0L) {
            part.delete()
            throw DubbingException("ffmpeg failed to write the dubbed file:\n${r.log.takeLast(800)}")
        }
        Files.move(part.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING)
        onProgress(Progress(Stage.MUX, 1, 1))

        return DubbingReport(cues.size, alignReport.clips, alignReport, output, usedAsr, skippedSentences)
    }

    private suspend fun synthesize(
        input: List<Cue>,
        workDir: File,
        sourceHash: String,
        save: suspend (String, List<Cue>) -> Unit,
    ): List<Cue> = coroutineScope {
        val ttsDir = File(workDir, "tts").apply { mkdirs() }
        val cues = input.toMutableList()
        val lock = Mutex()
        val gate = Semaphore(max(1, config.ttsConcurrency))
        val total = cues.size
        var done = 0
        val failures = java.util.Collections.synchronizedList(ArrayList<Pair<Int, String>>())
        onProgress(Progress(Stage.SYNTHESIZE, 0, total))

        cues.indices.map { i ->
            async {
                gate.withPermit {
                    val cue = input[i]
                    val text = TtsText.clean(cue.zh)
                    if (text.isNotBlank()) onPreview(text)
                    var result: Cue? = cue
                    val existing = cue.ttsFile?.let { File(workDir, it) }
                    if (existing != null && existing.exists() && existing.length() > 0) {
                        // already synthesized in a previous run
                    } else if (!TtsText.speakable(text)) {
                        result = cue.copy(ttsFile = null)
                    } else {
                        val file = File(ttsDir, "%05d.%s".format(cue.id, tts.fileExtension))
                        try {
                            retrying(3, 1000) {
                                tts.synthesize(text, voiceFor(cue), estimateRatePercent(input, i), file)
                                if (!file.exists() || file.length() == 0L) throw java.io.IOException("empty TTS output")
                            }
                        } catch (e: CancellationException) {
                            file.delete()
                            throw e
                        } catch (e: Exception) {
                            // one stubborn sentence must not lose the whole video: leave it silent and carry on
                            file.delete()
                            log("Speech synthesis failed for cue ${cue.id} (skipped): ${e.message}")
                            lock.withLock { failures.add(cue.id to (e.message ?: e.javaClass.simpleName)) }
                            result = null
                        }
                        if (result == null) result = cue.copy(ttsFile = null) else result = cue.copy(ttsFile = "tts/${file.name}")
                    }
                    lock.withLock {
                        cues[i] = result!!
                        done++
                        onProgress(Progress(Stage.SYNTHESIZE, done, total))
                        if (done % 10 == 0) save(sourceHash, cues.toList())
                    }
                }
            }
        }.awaitAll()
        save(sourceHash, cues.toList())
        val speakable = cues.count { TtsText.speakable(TtsText.clean(it.zh)) }
        // a few skipped sentences are acceptable; a mostly silent video is a failed dub (retrying resumes from the checkpoint)
        if (failures.isNotEmpty() && (failures.size >= speakable || failures.size > max(3, speakable * 15 / 100))) {
            throw DubbingException(
                "Speech synthesis failed for ${failures.size} of $speakable sentences, e.g. cue ${failures.first().first}: ${failures.first().second}"
            )
        }
        skippedSentences = failures.size
        cues
    }

    private var skippedSentences = 0

    private var roleVoiceMap: Map<String, String> = emptyMap()

    internal fun voiceFor(cue: Cue): String {
        if (config.llmRoles) return roleVoiceMap[cue.speaker] ?: config.voice
        if (!config.multiVoice) return config.voice
        config.speakerVoices[cue.speaker]?.let { return it }
        return if (cue.speaker.startsWith("M")) config.maleVoice else config.voice
    }

    /** Ask the voice to speak faster up front when the translation is long for its time slot. */
    internal fun estimateRatePercent(all: List<Cue>, i: Int): Int {
        val cue = all[i]
        val natural = TtsText.spokenLength(cue.zh) / config.charsPerSecond
        val next = all.getOrNull(i + 1)?.startMs
        val windowMs = if (next == null) cue.durationMs
        else max(cue.durationMs, min(next - cue.startMs, cue.durationMs + config.align.maxBorrowMs))
        val window = max(config.align.minWindowMs, windowMs) / 1000.0
        var rate = config.baseRatePercent
        if (natural > window) {
            rate = max(rate, ((natural / window - 1.0) * 100).roundToInt().coerceAtMost(config.maxSynthRatePercent))
        }
        return rate
    }

    private suspend fun recognize(video: File, info: MediaInfo, workDir: File): List<Cue> {
        val provider = asr ?: throw DubbingException(
            "This video has no subtitles and no speech-recognition service is configured"
        )
        if (info.audioStreams.isEmpty()) throw DubbingException("The video has no audio to recognise")
        val dir = File(workDir, "asr").apply { mkdirs() }
        val seconds = config.asrSegmentSeconds
        val marker = File(dir, "segmented.ok")
        if (!marker.exists()) {
            dir.listFiles()?.forEach { it.delete() }
            val r = ffmpeg.run(
                listOf(
                    "-i", video.absolutePath, "-vn", "-map", "0:a:0", "-ac", "1", "-ar", "16000",
                    "-c:a", "libmp3lame", "-b:a", "32k",
                    "-f", "segment", "-segment_time", seconds.toString(), "-reset_timestamps", "1",
                    File(dir, "seg_%03d.mp3").absolutePath,
                )
            )
            if (!r.ok || dir.listFiles { f -> f.name.startsWith("seg_") }.isNullOrEmpty()) {
                throw DubbingException("Could not extract audio:\n${r.log.takeLast(500)}")
            }
            marker.writeText("ok")
        }
        val segments = dir.listFiles { f -> f.name.startsWith("seg_") && f.extension == "mp3" }?.sortedBy { it.name }.orEmpty()

        val all = ArrayList<Cue>()
        segments.forEachIndexed { idx, file ->
            val offset = idx * seconds * 1000L
            val remaining = max(1L, info.durationMs - offset)
            // every recognised segment is cached: a failure at hour two must not redo hour one
            val cache = File(dir, "${file.nameWithoutExtension}.json")
            val part: List<Cue> = cache.takeIf { it.exists() }?.let { c ->
                runCatching { json.decodeFromString<List<Cue>>(c.readText()) }.getOrNull()
            } ?: provider.transcribe(file, config.sourceLanguageCode, min(seconds * 1000L, remaining)).also {
                cache.writeText(json.encodeToString(it))
            }
            part.forEach { all += it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
            onProgress(Progress(Stage.SOURCE, idx + 1, segments.size))
        }
        return SubtitleCleaner.normalize(all, config.mergeSentences)
    }

    private fun loadCheckpoint(file: File, hash: String): List<Cue>? = try {
        if (!file.exists()) null
        else json.decodeFromString<Checkpoint>(file.readText()).takeIf { it.sourceHash == hash }?.cues
    } catch (e: Exception) {
        null
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
