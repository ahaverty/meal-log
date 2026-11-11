package studio.alans.meallog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/**
 * The mic button on the Plan page: the phone's own speech recogniser, words appearing in the box as
 * he speaks (Alan, 11 Nov: he dictates most meals and was using a floating Wispr Flow button).
 * The text stays in the box to check before Send. Without an in-app recogniser it falls back to
 * Android's voice typing dialog.
 */
class VoiceState internal constructor() {
    var listening by mutableStateOf(false)
        internal set
    /** 0..1, how loud he is, for the mic ring. */
    var level by mutableFloatStateOf(0f)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    internal var toggle: () -> Unit = {}
    fun toggle() = toggle.invoke()
}

/** [onText] gets the words so far (partial = true while he's still talking). */
@Composable
fun rememberVoice(onText: (String, Boolean) -> Unit): VoiceState {
    val c = LocalContext.current
    val state = remember { VoiceState() }
    val emit by rememberUpdatedState(onText)
    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(c)) SpeechRecognizer.createSpeechRecognizer(c) else null
    }
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { emit(it, false) }
    }

    fun intent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "What are you eating?")
        // he pauses to look at the plate: don't cut him off after a short gap
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)

    fun start() {
        state.error = null
        val sr = recognizer ?: return runCatching { dialog.launch(intent()) }
            .getOrElse { state.error = "No voice typing on this phone" }
        state.listening = true
        state.level = 0f
        sr.startListening(intent())
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else state.error = "Allow the microphone to talk to it"
    }

    state.toggle = {
        when {
            state.listening -> recognizer?.stopListening()
            recognizer != null && ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED -> permission.launch(Manifest.permission.RECORD_AUDIO)
            else -> start()
        }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { state.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { state.level = 0f }
            override fun onError(error: Int) {
                state.listening = false; state.level = 0f
                state.error = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that, tap the mic and try again"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Allow the microphone to talk to it"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice needs a connection"
                    SpeechRecognizer.ERROR_CLIENT -> null   // stopped by us
                    else -> "Voice stopped (error $error)"
                }
            }
            override fun onResults(results: Bundle?) {
                state.listening = false; state.level = 0f
                results.words()?.let { emit(it, false) }
            }
            override fun onPartialResults(partial: Bundle?) { partial.words()?.let { emit(it, true) } }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { recognizer?.destroy() }
    }
    return state
}

private fun Bundle?.words(): String? =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }
