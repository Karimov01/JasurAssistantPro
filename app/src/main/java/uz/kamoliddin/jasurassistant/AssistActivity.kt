package uz.kamoliddin.jasurassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class AssistActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var settings: SettingsManager
    private lateinit var status: TextView
    private lateinit var heard: TextView
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val worker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsManager(this)
        buildUi()
        tts = TextToSpeech(this, this)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 120, 48, 48)
        }
        status = TextView(this).apply {
            text = "Jasur"
            textSize = 32f
        }
        heard = TextView(this).apply {
            text = "Eshitaman…"
            textSize = 18f
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)
        root.addView(heard)
        root.addView(ProgressBar(this))
        setContentView(root)
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            ttsReady = true
            val locale = Locale.forLanguageTag(settings.language)
            val res = tts?.setLanguage(locale)
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            startListening()
        } else {
            startListening()
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            heard.text = "Mikrofon ruxsati kerak"
            return
        }
        stopRecognition()
        recognizer = try {
            if (Build.VERSION.SDK_INT >= 31 && settings.offlinePreferred && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else SpeechRecognizer.createSpeechRecognizer(this)
        } catch (_: Exception) {
            try { SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Exception) { null }
        }
        val r = recognizer ?: run {
            heard.text = "Ovoz tanish xizmati topilmadi"
            return
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { heard.text = "Gapiring…" }
            override fun onBeginningOfSpeech() { heard.text = "Tinglayapman…" }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { heard.text = "Tushunyapman…" }
            override fun onError(error: Int) { speakAndFinish("Eshitmadim") }
            override fun onResults(results: Bundle?) {
                val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (command.isBlank()) speakAndFinish("Buyruqni eshitmadim") else handleCommand(command)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard.text = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (settings.offlinePreferred) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { r.startListening(i) } catch (_: Exception) { heard.text = "Ovoz tanishni ishga tushirib bo‘lmadi" }
    }

    private fun handleCommand(command: String) {
        heard.text = command
        val local = CommandRouter(this).handle(command)
        if (local.handled) {
            speakAndFinish(local.speech)
            return
        }
        if (!settings.aiEnabled || settings.getApiKey().isBlank()) {
            speakAndFinish("Bu buyruqni hozircha bilmayman")
            return
        }
        status.text = "Jasur AI"
        heard.text = "Javob tayyorlayapman…"
        worker.execute {
            val result = AiClient(this).ask(command)
            runOnUiThread { speakAndFinish(result.getOrElse { "AI bilan ulanishda xato" }) }
        }
    }

    private fun speakAndFinish(text: String) {
        stopRecognition()
        heard.text = text
        if (ttsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "assist_answer")
            window.decorView.postDelayed({ finish() }, 2200)
        } else {
            window.decorView.postDelayed({ finish() }, 1200)
        }
    }

    private fun stopRecognition() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    override fun onDestroy() {
        stopRecognition()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
