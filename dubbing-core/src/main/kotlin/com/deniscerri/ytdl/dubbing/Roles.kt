package com.deniscerri.ytdl.dubbing

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** One person in the video, as shown on the "assign voices" screen. */
@Serializable
data class Role(
    val id: String,
    /** "M" or "F" (from the pitch of this person's lines). */
    val gender: String,
    val lines: Int,
    /** A few example lines (Chinese when already translated) so the user can tell who is who. */
    val samples: List<String>,
    /** What the language model could tell about the person ("host, male, called Alex"). */
    val note: String = "",
    /** Suggested Mandarin voice; the user may change it. */
    val voice: String = "",
)

data class Labeling(val labels: List<String>, val roster: Map<String, String>)

/**
 * Works out WHO speaks each subtitle line from the text, helped by the pitch of the original voice.
 *
 * The transcript is cut into windows that are sent in order; every request carries the speakers found so far, so "S1"
 * means the same person throughout the video. The model is told the pitch hint of each line (low = M, high = F), which
 * lets it tell two people apart even when the words give no clue, and lets the text correct the pitch where it is unclear.
 */
class SpeakerLabeler(
    private val client: ChatClient,
    private val model: String,
    private val windowSize: Int = 60,
    private val log: (String) -> Unit = {},
) {
    suspend fun label(
        cues: List<Cue>,
        pitchHint: List<String?>,
        maxSpeakers: Int,
        voiceHint: List<String?> = emptyList(),
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Labeling {
        val labels = MutableList(cues.size) { "" }
        val roster = LinkedHashMap<String, String>()
        var done = 0
        for (start in cues.indices step windowSize) {
            val end = minOf(cues.size, start + windowSize)
            val window = (start until end).toList()
            val previous = ((start - 3) until start).filter { it >= 0 }
            val user = buildJsonObject {
                put("max_speakers", maxSpeakers)
                put("known_speakers", buildJsonObject { roster.forEach { (k, v) -> put(k, v) } })
                put("previous", buildJsonArray {
                    previous.forEach { i ->
                        add(buildJsonObject { put("id", cues[i].id); put("text", cues[i].src); put("speaker", labels[i]) })
                    }
                })
                put("lines", buildJsonArray {
                    window.forEach { i ->
                        add(buildJsonObject {
                            put("id", cues[i].id)
                            put("text", cues[i].src)
                            put("pitch", pitchHint[i] ?: "?")
                            voiceHint.getOrNull(i)?.let { put("voice", it) }
                            if (cues[i].turn) put("speaker_change_marker", true)
                            if (i > 0) put("pause_before_s", ((cues[i].startMs - cues[i - 1].endMs).coerceAtLeast(0) / 100) / 10.0)
                        })
                    }
                })
            }.toString()
            val reply = try {
                client.chat(ChatRequest(model, listOf(ChatMessage("system", PROMPT.format(maxSpeakers)), ChatMessage("user", user)), temperature = 0.1))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // labelling is a nicety: on a failed window carry on with the previous speaker
                log("Speaker labelling failed for lines ${cues[start].id}-${cues[end - 1].id}: ${e.message}")
                null
            }
            val parsed = reply?.let { parse(it.content) }
            val byId = parsed?.first.orEmpty()
            parsed?.second?.forEach { (k, v) -> roster[k] = v.ifBlank { roster[k].orEmpty() } }
            var last = labels.getOrNull(start - 1).orEmpty()
            for (i in window) {
                val spk = byId[cues[i].id]?.takeIf { it.isNotBlank() } ?: last.ifBlank { "S1" }
                labels[i] = spk
                last = spk
                if (spk !in roster) roster[spk] = ""
            }
            done = end
            onProgress(done, cues.size)
        }
        return Labeling(labels, roster)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val THINK = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val FENCE = Regex("```[a-zA-Z]*")

        private val PROMPT = """
            You label WHO speaks each line of a video transcript (usually an interview, podcast or conversation).
            Input JSON: max_speakers, known_speakers (id -> short description, from earlier parts), previous (the last labelled lines for context), lines (id, text, pitch, voice, speaker_change_marker, pause_before_s).
            Rules:
            1. Give every line a speaker id such as S1, S2, S3. Reuse a known id whenever it is the same person; create a new id only for a genuinely new person. Never use more than %d different ids in total.
            2. This is most likely a conversation (interview, podcast, panel) with about max_speakers people. Labelling everything S1 is almost always wrong when the lines contain questions and answers, greetings, or people addressing each other: in that case use the different ids.
            3. Clues from the text: speaker_change_marker = true means the subtitle itself marks a new speaker here; a question is usually answered by someone else; people call each other by name ("thanks, Matt"); a long pause before a line often means the other person speaks; short back-channel words ("yeah", "right", "exactly") belong to the listener.
            4. Clues measured from the real audio: pitch is M (low voice), F (high voice) or ? (unknown); voice is V1, V2... = lines whose recorded voice (timbre / microphone) sounds alike share a label. Lines with different voice labels are almost certainly different people, lines with the same label are probably the same person. Trust consistent audio labels over weak textual clues.
            5. For every id you use, give a few words about the person (role, sex, name if said), e.g. "host, male, Alex".
            6. Answer with JSON only: {"speakers":[{"id":12,"spk":"S1"}],"roster":{"S1":"host, male, Alex"}}
        """.trimIndent()

        /** Returns (line id -> speaker, speaker -> description); tolerant of fences, <think> blocks and loose shapes. */
        fun parse(content: String): Pair<Map<Int, String>, Map<String, String>>? {
            val cleaned = content.replace(THINK, "").replace(FENCE, "").trim()
            val element = runCatching { json.parseToJsonElement(cleaned) }.getOrNull()
                ?: run {
                    val a = cleaned.indexOf('{')
                    val b = cleaned.lastIndexOf('}')
                    if (a in 0 until b) runCatching { json.parseToJsonElement(cleaned.substring(a, b + 1)) }.getOrNull() else null
                } ?: return null
            val obj = element as? JsonObject ?: return null
            val ids = LinkedHashMap<Int, String>()
            val list = (obj["speakers"] ?: obj["lines"] ?: obj["labels"]) as? JsonArray
            list?.forEach { item ->
                val o = item as? JsonObject ?: return@forEach
                val id = (o["id"] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }
                val spk = listOf("spk", "speaker", "label").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.contentOrNull }
                if (id != null && spk != null) ids[id] = spk.trim()
            }
            val roster = LinkedHashMap<String, String>()
            (obj["roster"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { roster[k.trim()] = it } }
            return ids to roster
        }
    }
}

/** Turns raw labels + pitch into the roster that is shown to the user, in order of first appearance. */
object RoleBuilder {
    /**
     * @param threshold Hz boundary between low (M) and high (F) voices
     * @return the final speaker label per cue ("S1".."Sn", in order of first appearance, at most [maxSpeakers]) and the roles
     */
    fun build(
        cues: List<Cue>,
        labeling: Labeling,
        f0s: List<Double?>?,
        threshold: Double,
        maxSpeakers: Int,
        voices: VoicePool,
        acoustic: List<Int?>? = null,
    ): Pair<List<String>, List<Role>> {
        // 0. the model sometimes calls everybody "S1" although the recording clearly holds several voices: trust the audio
        var labelsIn = labeling.labels
        val collapsed = labelsIn.distinct().size == 1
        val voicesHeard = acoustic?.filterNotNull()?.distinct()?.size ?: 0
        if (collapsed && voicesHeard >= 2) {
            var previous = acoustic!!.firstOrNull { it != null } ?: 0
            labelsIn = acoustic.map { c -> (c ?: previous).also { previous = it } }.map { "V$it" }
        }
        // 1. order of first appearance, merge extras beyond the limit into the closest remaining speaker
        val order = LinkedHashMap<String, Int>()
        labelsIn.forEach { if (it !in order) order[it] = order.size + 1 }
        val rename = order.mapValues { "S${it.value}" }
        var labels = labelsIn.map { rename.getValue(it) }

        fun pitchOf(spk: String): Double? =
            f0s?.let { all -> labels.indices.filter { labels[it] == spk }.mapNotNull { all[it] }.sorted().let { v -> if (v.size >= 2) v[v.size / 2] else null } }

        if (order.size > maxSpeakers.coerceAtLeast(1)) {
            val counts = labels.groupingBy { it }.eachCount()
            val keep = counts.entries.sortedByDescending { it.value }.take(maxSpeakers).map { it.key }.toSet()
            val keepPitch = keep.associateWith { pitchOf(it) }
            val remap = counts.keys.associateWith { spk ->
                if (spk in keep) spk else {
                    val p = pitchOf(spk)
                    if (p == null) keep.maxByOrNull { counts.getValue(it) }!!
                    else keep.minByOrNull { k -> keepPitch[k]?.let { kotlin.math.abs(kotlin.math.ln(it) - kotlin.math.ln(p)) } ?: Double.MAX_VALUE }!!
                }
            }
            labels = labels.map { remap.getValue(it) }
            val reorder = LinkedHashMap<String, String>()
            labels.forEach { if (it !in reorder) reorder[it] = "S${reorder.size + 1}" }
            labels = labels.map { reorder.getValue(it) }
        }

        // 2. gender per person: median pitch of their lines; no voiced line -> what the model said, else female
        val notes = labeling.roster.entries.associate { (k, v) -> (order[k]?.let { "S$it" } ?: k) to v }
        val roles = labels.distinct().map { spk ->
            val idx = labels.indices.filter { labels[it] == spk }
            val pitch = pitchOf(spk)
            val note = notes[spk].orEmpty()
            val gender = when {
                pitch != null -> if (pitch < threshold) "M" else "F"
                Regex("\\b(female|woman|she|her|girl|lady)\\b|女", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "F"
                Regex("\\b(male|man|he|his|boy|guy)\\b|男", RegexOption.IGNORE_CASE).containsMatchIn(note) -> "M"
                else -> "F"
            }
            Role(
                id = spk,
                gender = gender,
                lines = idx.size,
                samples = idx.take(3).map { cues[it].let { c -> c.zh.ifBlank { c.src } }.take(80) },
                note = note,
                voice = voices.next(gender),
            )
        }
        return labels to roles
    }
}

/** Hands out distinct voices per sex, starting with the ones the user picked. */
class VoicePool(female: String, male: String, extraFemale: List<String> = emptyList(), extraMale: List<String> = emptyList()) {
    private val women = (listOf(female) + extraFemale).distinct()
    private val men = (listOf(male) + extraMale).distinct()
    private var w = 0
    private var m = 0

    fun next(gender: String): String =
        if (gender == "M") men[minOf(m++, men.size - 1)] else women[minOf(w++, women.size - 1)]
}
