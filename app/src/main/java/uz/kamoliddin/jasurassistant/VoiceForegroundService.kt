package uz.kamoliddin.jasurassistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class VoiceForegroundService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val ACTION_START = "uz.kamoliddin.jasurassistant.START"
        const val ACTION_STOP = "uz.kamoliddin.jasurassistant.STOP"
        private const val CHANNEL_ID = "jasur_voice"
        private const val NOTIFICATION_ID = 41
    }

    private lateinit var settings: SettingsManager
    private lateinit var router: CommandRouter
    private lateinit var ai: AiClient
    private val handler = Handler(Looper.getMainLooper())
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopped = false
    private var ttsReady = false

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        router = CommandRouter(this)
        ai = AiClient(this)
        createChannel()
        tts = TextToSpeech(this, this)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JasurAssistant:VoiceWakeLock").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopAssistant()
            return START_NOT_STICKY
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Jasur ishga tushdi — “${settings.wakeWord}” so‘zini kutyapman"))
        stopped = false
        settings.assistantRunning = true
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
        handler.postDelayed({ startWakeListening() }, 700)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            val locale = Locale.forLanguageTag(settings.language)
            val result = tts?.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.language = Locale.getDefault()
            }
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onError(utteranceId: String?) {
                    handler.post { afterSpeech(utteranceId.orEmpty()) }
                }
                override fun onDone(utteranceId: String?) {
                    handler.post { afterSpeech(utteranceId.orEmpty()) }
                }
            })
        }
    }

    private fun afterSpeech(id: String) {
        if (stopped) return
        when (id) {
            "wake_ack" -> startCommandListening()
            "answer" -> startWakeListening()
        }
    }

    private fun speak(text: String, id: String) {
        if (text.isBlank()) {
            if (id == "answer") startWakeListening() else startCommandListening()
            return
        }
        if (!ttsReady) {
            handler.postDelayed({ speak(text, id) }, 300)
            return
        }
        stopRecognitionOnly()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun makeRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return null
        return try {
            if (Build.VERSION.SDK_INT >= 31 && settings.offlinePreferred && SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }
        } catch (_: Exception) {
            try { SpeechRecognizer.createSpeechRecognizer(this) } catch (_: Exception) { null }
        }
    }

    private fun baseIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, settings.language)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, settings.language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
        if (settings.offlinePreferred) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    private fun startWakeListening() {
        if (stopped) return
        updateNotification("“${settings.wakeWord}” so‘zini kutyapman")
        startRecognizer(mode = "wake")
    }

    private fun startCommandListening() {
        if (stopped) return
        updateNotification("Buyruqni tinglayapman…")
        startRecognizer(mode = "command")
    }

    private fun startRecognizer(mode: String) {
        stopRecognitionOnly()
        recognizer = makeRecognizer()
        val local = recognizer ?: run {
            updateNotification("Ovoz tanish xizmati topilmadi")
            handler.postDelayed({ if (!stopped) startWakeListening() }, 3000)
            return
        }
        local.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                if (stopped) return
                handler.postDelayed({
                    if (mode == "command") {
                        speak("Buyruqni eshitmadim", "answer")
                    } else {
                        startWakeListening()
                    }
                }, if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 1000 else 350)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (mode != "wake") return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.joinToString(" ").orEmpty().lowercase(Locale.getDefault())
                if (containsWakeWord(text)) {
                    speak("Eshitaman", "wake_ack")
                }
            }

            override fun onResults(results: Bundle?) {
                val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (mode == "wake") {
                    val hit = list.any { containsWakeWord(it.lowercase(Locale.getDefault())) }
                    if (hit) speak("Eshitaman", "wake_ack") else handler.postDelayed({ startWakeListening() }, 250)
                } else {
                    val command = list.firstOrNull().orEmpty()
                    if (command.isBlank()) speak("Buyruqni eshitmadim", "answer") else handleCommand(command)
                }
            }
        })
        try {
            local.startListening(baseIntent())
        } catch (_: Exception) {
            handler.postDelayed({ if (mode == "command") speak("Ovoz tanish xatosi", "answer") else startWakeListening() }, 700)
        }
    }

    private fun containsWakeWord(text: String): Boolean {
        val wake = settings.wakeWord.trim().lowercase(Locale.getDefault())
        if (wake.isBlank()) return false
        return text.split(Regex("\\s+")).any { token ->
            token == wake || token.startsWith(wake) || levenshtein(token, wake) <= 1
        }
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        val costs = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            var last = i
            costs[0] = i + 1
            for (j in b.indices) {
                val old = costs[j + 1]
                costs[j + 1] = minOf(costs[j + 1] + 1, costs[j] + 1, last + if (a[i] == b[j]) 0 else 1)
                last = old
            }
        }
        return costs[b.length]
    }

    private fun handleCommand(command: String) {
        updateNotification("Buyruq: $command")
        val local = router.handle(command)
        if (local.handled) {
            speak(local.speech, "answer")
            return
        }
        if (!settings.aiEnabled) {
            speak("Bu buyruqni hozircha bilmayman", "answer")
            return
        }
        if (settings.getApiKey().isBlank()) {
            speak("AI kaliti kiritilmagan. Sozlamalardan API kalit kiriting", "answer")
            return
        }
        updateNotification("AI javobini olayapman…")
        networkExecutor.execute {
            val result = ai.ask(command)
            handler.post {
                if (stopped) return@post
                speak(result.getOrElse { "AI bilan ulanishda xato: ${it.message ?: "noma'lum xato"}" }, "answer")
            }
        }
    }

    private fun stopRecognitionOnly() {
        try { recognizer?.cancel() } catch (_: Exception) { }
        try { recognizer?.destroy() } catch (_: Exception) { }
        recognizer = null
    }

    private fun stopAssistant() {
        stopped = true
        settings.assistantRunning = false
        stopRecognitionOnly()
        try { tts?.stop() } catch (_: Exception) { }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        settings.assistantRunning = false
        stopRecognitionOnly()
        tts?.shutdown()
        networkExecutor.shutdownNow()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jasur ovozli yordamchi",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Jasur fon rejimida mikrofon orqali wake word kutadi"
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, VoiceForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jasur Assistant")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "To‘xtatish", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
