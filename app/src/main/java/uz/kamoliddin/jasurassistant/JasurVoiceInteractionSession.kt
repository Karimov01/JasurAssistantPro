package uz.kamoliddin.jasurassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class JasurVoiceInteractionSession(private val ctx: Context) : VoiceInteractionSession(ctx), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val settings = SettingsManager(ctx)
    private val router = CommandRouter(ctx)
    private val ai = AiClient(ctx)
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var statusText: TextView? = null
    private var heardText: TextView? = null
    private var spinner: ProgressBar? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(ctx, this)
    }

    override fun onCreateContentView(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 34, 48, 42)
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 22, 28))
                cornerRadius = 40f
            }
        }
        statusText = TextView(ctx).apply {
            text = "Jasur"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        heardText = TextView(ctx).apply {
            text = "Eshitaman…"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, 14, 0, 14)
        }
        spinner = ProgressBar(ctx)
        root.addView(statusText, LinearLayout.LayoutParams(-1, -2))
        root.addView(heardText, LinearLayout.LayoutParams(-1, -2))
        root.addView(spinner, LinearLayout.LayoutParams(-2, -2))
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        statusText?.text = "Jasur"
        heardText?.text = "Eshitaman…"
        spinner?.visibility = View.VISIBLE
        handler.postDelayed({ startListening() }, 250)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            val locale = Locale.forLanguageTag(settings.language)
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) { handler.postDelayed({ hide() }, 250) }
                override fun onDone(utteranceId: String?) { handler.postDelayed({ hide() }, 250) }
            })
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            showAnswer("Mikrofon ruxsati kerak")
            return
        }
        stopRecognition()
        recognizer = try {
            if (Build.VERSION.SDK_INT >= 31 && settings.offlinePreferred && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            } else {
                SpeechRecognizer.createSpeechRecognizer(ctx)
            }
        } catch (_: Exception) {
            try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Exception) { null }
        }

        val local = recognizer ?: run {
            showAnswer("Ovoz tanish xizmati topilmadi")
            return
        }
        local.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { heardText?.text = "Gapiring…" }
            override fun onBeginningOfSpeech() { heardText?.text = "Tinglayapman…" }
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { heardText?.text = "Tushunyapman…" }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onError(error: Int) { showAnswer("Eshitmadim. Yana bir marta chaqiring") }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) heardText?.text = text
            }
            override fun onResults(results: Bundle?) {
                val command = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (command.isBlank()) showAnswer("Buyruqni eshitmadim") else handleCommand(command)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (settings.offlinePreferred) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { local.startListening(intent) } catch (_: Exception) { showAnswer("Ovoz tanishni ishga tushirib bo‘lmadi") }
    }

    private fun handleCommand(command: String) {
        heardText?.text = command
        spinner?.visibility = View.VISIBLE
        val local = router.handle(command)
        if (local.handled) {
            showAnswer(local.speech)
            return
        }
        if (!settings.aiEnabled) {
            showAnswer("Bu buyruqni hozircha bilmayman")
            return
        }
        if (settings.getApiKey().isBlank()) {
            showAnswer("AI kaliti kiritilmagan")
            return
        }
        statusText?.text = "Jasur AI"
        heardText?.text = "Javob tayyorlayapman…"
        worker.execute {
            val result = ai.ask(command)
            handler.post { showAnswer(result.getOrElse { "AI bilan ulanishda xato" }) }
        }
    }

    private fun showAnswer(text: String) {
        stopRecognition()
        spinner?.visibility = View.GONE
        heardText?.text = text
        if (!ttsReady) {
            handler.postDelayed({ showAnswer(text) }, 250)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jasur_answer")
    }

    private fun stopRecognition() {
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
    }

    override fun onHide() {
        stopRecognition()
        try { tts?.stop() } catch (_: Exception) { }
        super.onHide()
    }

    override fun onDestroy() {
        stopRecognition()
        tts?.shutdown()
        worker.shutdownNow()
        super.onDestroy()
    }
}
