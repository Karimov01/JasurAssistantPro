package uz.kamoliddin.jasurassistant

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.util.Locale
import java.util.concurrent.Executors

class VoiceForegroundService : Service(), TextToSpeech.OnInitListener, RecognitionListener {
    companion object {
        const val ACTION_START = "uz.kamoliddin.jasurassistant.START"
        const val ACTION_STOP = "uz.kamoliddin.jasurassistant.STOP"
        private const val CHANNEL_ID = "jasur_voice"
        private const val NOTIFICATION_ID = 41
        private const val SAMPLE_RATE = 16000.0f
        private const val COMMAND_TIMEOUT_MS = 9_000L
    }

    private enum class ListenMode { NONE, WAKE, COMMAND }

    private lateinit var settings: SettingsManager
    private lateinit var router: CommandRouter
    private lateinit var ai: AiClient
    private lateinit var modelManager: VoskModelManager

    private val handler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val networkExecutor = Executors.newSingleThreadExecutor()

    private var voskModel: Model? = null
    private var speechService: SpeechService? = null
    private var listenMode = ListenMode.NONE
    private var tts: TextToSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopped = false
    private var ttsReady = false
    private var wakeTriggered = false
    private var commandHandled = false

    private val commandTimeout = Runnable {
        if (!stopped && listenMode == ListenMode.COMMAND && !commandHandled) {
            commandHandled = true
            stopVoskListening()
            speak("Buyruqni eshitmadim", "answer")
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsManager(this)
        router = CommandRouter(this)
        ai = AiClient(this)
        modelManager = VoskModelManager(this)
        createChannel()
        tts = TextToSpeech(this, this)
        LibVosk.setLogLevel(LogLevel.WARNINGS)

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JasurAssistant:VoskWakeLock").apply {
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

        startForeground(NOTIFICATION_ID, buildNotification("Offline Uzbek model tayyorlanmoqda…"))
        stopped = false
        settings.assistantRunning = true
        if (wakeLock?.isHeld != true) wakeLock?.acquire()
        prepareOfflineModel()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun prepareOfflineModel() {
        if (voskModel != null) {
            handler.post { startWakeListening() }
            return
        }
        ioExecutor.execute {
            try {
                val dir = modelManager.ensureModel { progress ->
                    handler.post {
                        if (!stopped) updateNotification("Uzbek offline model yuklanmoqda: $progress%")
                    }
                }
                val model = Model(dir.absolutePath)
                handler.post {
                    if (stopped) {
                        try { model.close() } catch (_: Exception) { }
                    } else {
                        voskModel = model
                        updateNotification("Offline model tayyor ✓")
                        startWakeListening()
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (!stopped) {
                        updateNotification("Model xatosi: ${e.message ?: "noma'lum"}. 30 soniyada qayta urinaman")
                        handler.postDelayed({ if (!stopped) prepareOfflineModel() }, 30_000)
                    }
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            val preferred = Locale.forLanguageTag(settings.language)
            val result = tts?.setLanguage(preferred)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                val uz = Locale("uz", "UZ")
                val uzResult = tts?.setLanguage(uz)
                if (uzResult == TextToSpeech.LANG_MISSING_DATA || uzResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.language = Locale.getDefault()
                }
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
        if (stopped) return
        stopVoskListening()
        if (text.isBlank()) {
            afterSpeech(id)
            return
        }
        if (!ttsReady) {
            handler.postDelayed({ speak(text, id) }, 250)
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    private fun startWakeListening() {
        if (stopped) return
        val model = voskModel ?: run {
            prepareOfflineModel()
            return
        }
        stopVoskListening()
        wakeTriggered = false
        commandHandled = false
        listenMode = ListenMode.WAKE
        updateNotification("“${settings.wakeWord}” so‘zini OFFLINE kutyapman")

        try {
            val wake = settings.wakeWord.trim().lowercase(Locale.getDefault()).ifBlank { "jasur" }
            val grammar = "[\"${wake.replace("\"", "")}\", \"[unk]\"]"
            val recognizer = Recognizer(model, SAMPLE_RATE, grammar)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this) }
        } catch (e: Exception) {
            listenMode = ListenMode.NONE
            updateNotification("Vosk wake xatosi: ${e.message ?: "noma'lum"}")
            handler.postDelayed({ if (!stopped) startWakeListening() }, 2_000)
        }
    }

    private fun startCommandListening() {
        if (stopped) return
        val model = voskModel ?: return
        stopVoskListening()
        commandHandled = false
        listenMode = ListenMode.COMMAND
        updateNotification("Buyruqni OFFLINE tinglayapman…")
        handler.removeCallbacks(commandTimeout)
        handler.postDelayed(commandTimeout, COMMAND_TIMEOUT_MS)

        try {
            val recognizer = Recognizer(model, SAMPLE_RATE)
            speechService = SpeechService(recognizer, SAMPLE_RATE).also { it.startListening(this) }
        } catch (e: Exception) {
            handler.removeCallbacks(commandTimeout)
            listenMode = ListenMode.NONE
            speak("Ovoz tanish xatosi", "answer")
        }
    }

    override fun onPartialResult(hypothesis: String?) {
        if (stopped || hypothesis.isNullOrBlank()) return
        val partial = jsonValue(hypothesis, "partial")
        when (listenMode) {
            ListenMode.WAKE -> {
                if (!wakeTriggered && containsWakeWord(partial)) triggerWake()
            }
            ListenMode.COMMAND -> {
                if (partial.isNotBlank()) updateNotification("Eshityapman: $partial")
            }
            else -> Unit
        }
    }

    override fun onResult(hypothesis: String?) {
        if (stopped || hypothesis.isNullOrBlank()) return
        val text = jsonValue(hypothesis, "text")
        processVoskText(text)
    }

    override fun onFinalResult(hypothesis: String?) {
        if (stopped || hypothesis.isNullOrBlank()) return
        val text = jsonValue(hypothesis, "text")
        processVoskText(text)
    }

    override fun onError(exception: Exception?) {
        if (stopped) return
        handler.post {
            if (stopped) return@post
            when (listenMode) {
                ListenMode.COMMAND -> {
                    handler.removeCallbacks(commandTimeout)
                    speak("Buyruqni eshitishda xato bo‘ldi", "answer")
                }
                ListenMode.WAKE -> handler.postDelayed({ startWakeListening() }, 1_000)
                else -> Unit
            }
        }
    }

    override fun onTimeout() {
        if (stopped) return
        handler.post {
            if (listenMode == ListenMode.COMMAND && !commandHandled) {
                commandTimeout.run()
            } else if (listenMode == ListenMode.WAKE) {
                startWakeListening()
            }
        }
    }

    private fun processVoskText(text: String) {
        when (listenMode) {
            ListenMode.WAKE -> {
                if (!wakeTriggered && containsWakeWord(text)) triggerWake()
            }
            ListenMode.COMMAND -> {
                if (!commandHandled && text.isNotBlank()) {
                    commandHandled = true
                    handler.removeCallbacks(commandTimeout)
                    stopVoskListening()
                    handleCommand(text)
                }
            }
            else -> Unit
        }
    }

    private fun triggerWake() {
        if (wakeTriggered || stopped || listenMode != ListenMode.WAKE) return
        wakeTriggered = true
        stopVoskListening()
        updateNotification("Jasur sizni eshitdi ✓")
        speak("Eshitaman", "wake_ack")
    }

    private fun jsonValue(json: String, key: String): String {
        return try { JSONObject(json).optString(key, "").trim() } catch (_: Exception) { "" }
    }

    private fun containsWakeWord(text: String): Boolean {
        val wake = settings.wakeWord.trim().lowercase(Locale.getDefault()).ifBlank { "jasur" }
        val normalized = text.lowercase(Locale.getDefault()).trim()
        if (normalized.isBlank()) return false
        return normalized.split(Regex("\\s+")).any { token ->
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
                costs[j + 1] = minOf(
                    costs[j + 1] + 1,
                    costs[j] + 1,
                    last + if (a[i] == b[j]) 0 else 1
                )
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
                if (!stopped) {
                    speak(result.getOrElse { "AI bilan ulanishda xato: ${it.message ?: "noma'lum xato"}" }, "answer")
                }
            }
        }
    }

    private fun stopVoskListening() {
        handler.removeCallbacks(commandTimeout)
        listenMode = ListenMode.NONE
        val service = speechService
        speechService = null
        try { service?.stop() } catch (_: Exception) { }
        try { service?.shutdown() } catch (_: Exception) { }
    }

    private fun stopAssistant() {
        stopped = true
        settings.assistantRunning = false
        handler.removeCallbacksAndMessages(null)
        stopVoskListening()
        try { tts?.stop() } catch (_: Exception) { }
        if (wakeLock?.isHeld == true) wakeLock?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        settings.assistantRunning = false
        handler.removeCallbacksAndMessages(null)
        stopVoskListening()
        try { voskModel?.close() } catch (_: Exception) { }
        voskModel = null
        tts?.shutdown()
        ioExecutor.shutdownNow()
        networkExecutor.shutdownNow()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Jasur ovozli yordamchi",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Jasur Vosk orqali offline wake word kutadi"
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
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
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_media_pause, "To‘xtatish", stopIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }
}
