package com.deniscerri.ytdl.util.dubbing

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.deniscerri.ytdl.dubbing.TtsProvider
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Offline fallback: whatever Chinese voice the phone's TTS engine provides. Clips are rendered to WAV files. */
class SystemTtsProvider(
    private val context: Context,
    private val locale: Locale = Locale.SIMPLIFIED_CHINESE,
) : TtsProvider {
    override val fileExtension: String get() = "wav"

    private val mutex = Mutex() // the system engine processes one utterance at a time
    private val pending = ConcurrentHashMap<String, CancellableContinuation<Unit>>()
    private var engine: TextToSpeech? = null

    private suspend fun ensureEngine(): TextToSpeech {
        engine?.let { return it }
        val created = suspendCancellableCoroutine<TextToSpeech> { cont ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context.applicationContext) { status ->
                val t = tts
                if (status == TextToSpeech.SUCCESS && t != null) cont.resume(t)
                else cont.resumeWithException(IOException("System text-to-speech is not available"))
            }
            cont.invokeOnCancellation { tts?.shutdown() }
        }
        val result = created.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            created.shutdown()
            throw IOException("The system text-to-speech engine has no Chinese voice installed")
        }
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                pending.remove(utteranceId)?.resume(Unit)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                pending.remove(utteranceId)?.resumeWithException(IOException("System TTS failed"))
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                pending.remove(utteranceId)?.resumeWithException(IOException("System TTS failed (error $errorCode)"))
            }
        })
        engine = created
        return created
    }

    override suspend fun synthesize(text: String, voice: String, ratePercent: Int, outFile: File) {
        mutex.withLock {
            val tts = ensureEngine()
            tts.setSpeechRate((1.0f + ratePercent / 100f).coerceIn(0.5f, 3.0f))
            val id = UUID.randomUUID().toString()
            suspendCancellableCoroutine { cont ->
                pending[id] = cont
                cont.invokeOnCancellation { pending.remove(id); tts.stop() }
                val rc = tts.synthesizeToFile(text, Bundle(), outFile, id)
                if (rc != TextToSpeech.SUCCESS) {
                    pending.remove(id)
                    cont.resumeWithException(IOException("System TTS rejected the request"))
                }
            }
        }
    }

    fun shutdown() {
        engine?.shutdown()
        engine = null
    }
}
