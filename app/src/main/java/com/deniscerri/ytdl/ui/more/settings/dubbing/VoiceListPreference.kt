package com.deniscerri.ytdl.ui.more.settings.dubbing

import android.content.Context
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.preference.ListPreference
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.util.dubbing.DubbingFactory
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A voice list where every row has its own "listen" button, so voices can be compared without leaving the dialog.
 * The sample is spoken with the engine and settings currently chosen, in the voice of that row.
 */
class VoiceListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ListPreference(context, attrs) {

    override fun onClick() {
        val labels = entries?.map { it.toString() } ?: return
        val values = entryValues?.map { it.toString() } ?: return
        val dp = context.resources.displayMetrics.density
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var player: MediaPlayer? = null
        var playing: Job? = null
        lateinit var dialog: androidx.appcompat.app.AlertDialog

        fun stop() {
            playing?.cancel()
            runCatching { player?.release() }
            player = null
        }

        fun listen(voice: String) {
            stop()
            playing = scope.launch {
                runCatching {
                    val factory = DubbingFactory(context)
                    val (tts, system) = factory.createTts()
                    try {
                        val file = File(context.cacheDir, "voice_preview.${tts.fileExtension}")
                        withContext(Dispatchers.IO) { tts.synthesize(context.getString(R.string.dubbing_voice_sample), voice, 0, file) }
                        player = MediaPlayer().apply {
                            setDataSource(file.absolutePath)
                            prepare()
                            start()
                            setOnCompletionListener { runCatching { release() }; player = null }
                        }
                    } finally {
                        system?.shutdown()
                    }
                }.onFailure {
                    if (it !is kotlinx.coroutines.CancellationException) {
                        Toast.makeText(context, (it.message?.takeIf { m -> m.isNotBlank() } ?: it.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        values.forEachIndexed { position, voice ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((20 * dp).toInt(), (4 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                minimumHeight = (56 * dp).toInt()
                isClickable = true
                isFocusable = true
                val tv = android.util.TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) setBackgroundResource(tv.resourceId)
                setOnClickListener {
                    if (callChangeListener(voice)) value = voice
                    dialog.dismiss()
                }
            }
            row.addView(RadioButton(context).apply {
                isChecked = voice == value
                isClickable = false
                isFocusable = false
            })
            row.addView(TextView(context).apply {
                text = labels[position]
                textSize = 16f
                setPadding((12 * dp).toInt(), 0, (8 * dp).toInt(), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "▶ " + context.getString(R.string.dubbing_roles_listen)
                setOnClickListener {
                    Toast.makeText(context, labels[position], Toast.LENGTH_SHORT).show()
                    listen(voice)
                }
            })
            box.addView(row)
        }
        val scroll = android.widget.ScrollView(context).apply { addView(box) }

        dialog = MaterialAlertDialogBuilder(context)
            .setTitle(dialogTitle ?: title)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { stop(); scope.cancel() }
        dialog.show()
    }
}
