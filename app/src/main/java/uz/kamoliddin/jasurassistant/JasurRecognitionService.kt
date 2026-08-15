package uz.kamoliddin.jasurassistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.concurrent.Executors

class JasurRecognitionService : RecognitionService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var speechService: SpeechService? = null
    private var model: Model? = null
    @Volatile private var cancelled = false

    override fun onStartListening(recognizerIntent: Intent?, callback: Callback) {
        cancelled = false
        stopInternal()
        worker.execute {
            try {
                val modelDir = VoskModelManager(this).ensureModel()
                if (cancelled) return@execute
                val localModel = Model(modelDir.absolutePath)
                model = localModel
                val recognizer = Recognizer(localModel, 16000.0f)
                val service = SpeechService(recognizer, 16000.0f)
                speechService = service

                callback.readyForSpeech(Bundle())
                service.startListening(object : RecognitionListener {
                    override fun onPartialResult(hypothesis: String?) {
                        val text = jsonText(hypothesis, "partial")
                        if (text.isNotBlank()) callback.partialResults(bundle(text))
                    }

                    override fun onResult(hypothesis: String?) {
                        val text = jsonText(hypothesis, "text")
                        if (text.isNotBlank()) callback.results(bundle(text))
                    }

                    override fun onFinalResult(hypothesis: String?) {
                        val text = jsonText(hypothesis, "text")
                        if (text.isNotBlank()) callback.results(bundle(text))
                        stopInternal()
                    }

                    override fun onError(exception: Exception?) {
                        callback.error(SpeechRecognizer.ERROR_SERVER)
                        stopInternal()
                    }

                    override fun onTimeout() {
                        callback.error(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
                        stopInternal()
                    }
                })
            } catch (_: Exception) {
                callback.error(SpeechRecognizer.ERROR_SERVER)
                stopInternal()
            }
        }
    }

    override fun onStopListening(callback: Callback) {
        try {
            speechService?.stop()
        } catch (_: Exception) { }
        stopInternal()
    }

    override fun onCancel(callback: Callback) {
        cancelled = true
        stopInternal()
    }

    private fun bundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
        putFloatArray(SpeechRecognizer.CONFIDENCE_SCORES, floatArrayOf(0.85f))
    }

    private fun jsonText(raw: String?, key: String): String {
        if (raw.isNullOrBlank()) return ""
        return try { JSONObject(raw).optString(key).trim() } catch (_: Exception) { "" }
    }

    private fun stopInternal() {
        try { speechService?.stop() } catch (_: Exception) { }
        try { speechService?.shutdown() } catch (_: Exception) { }
        speechService = null
        try { model?.close() } catch (_: Exception) { }
        model = null
    }

    override fun onDestroy() {
        cancelled = true
        stopInternal()
        worker.shutdownNow()
        super.onDestroy()
    }
}
