package uz.kamoliddin.jasurassistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var settingsManager: SettingsManager
    private lateinit var statusText: TextView
    private lateinit var statusDetails: TextView
    private lateinit var wakeWordInput: EditText
    private lateinit var languageInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var baseUrlInput: EditText
    private lateinit var modelInput: EditText
    private lateinit var offlineSwitch: Switch
    private lateinit var aiSwitch: Switch
    private lateinit var callAnnounceSwitch: Switch
    private lateinit var telegramSwitch: Switch
    private val worker = Executors.newSingleThreadExecutor()

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { updateStatus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settingsManager = SettingsManager(this)
        bindViews()
        loadSettings()
        wireActions()
        updateStatus()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        statusDetails = findViewById(R.id.statusDetails)
        wakeWordInput = findViewById(R.id.wakeWordInput)
        languageInput = findViewById(R.id.languageInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        baseUrlInput = findViewById(R.id.baseUrlInput)
        modelInput = findViewById(R.id.modelInput)
        offlineSwitch = findViewById(R.id.offlineSwitch)
        aiSwitch = findViewById(R.id.aiSwitch)
        callAnnounceSwitch = findViewById(R.id.callAnnounceSwitch)
        telegramSwitch = findViewById(R.id.telegramSwitch)
    }

    private fun loadSettings() {
        wakeWordInput.setText(settingsManager.wakeWord)
        languageInput.setText(settingsManager.language)
        baseUrlInput.setText(settingsManager.baseUrl)
        modelInput.setText(settingsManager.model)
        apiKeyInput.setText(settingsManager.getApiKey())
        offlineSwitch.isChecked = settingsManager.offlinePreferred
        aiSwitch.isChecked = settingsManager.aiEnabled
        callAnnounceSwitch.isChecked = false
        telegramSwitch.isChecked = false
        callAnnounceSwitch.isEnabled = false
        telegramSwitch.isEnabled = false
    }

    private fun saveSettings(showToast: Boolean = true) {
        settingsManager.wakeWord = wakeWordInput.text.toString().ifBlank { "jasur" }
        settingsManager.language = languageInput.text.toString().ifBlank { "uz-UZ" }
        settingsManager.baseUrl = baseUrlInput.text.toString().ifBlank { "https://api.openai.com/v1/responses" }
        settingsManager.model = modelInput.text.toString().ifBlank { "gpt-5.6" }
        settingsManager.setApiKey(apiKeyInput.text.toString())
        settingsManager.offlinePreferred = offlineSwitch.isChecked
        settingsManager.aiEnabled = aiSwitch.isChecked
        settingsManager.callAnnounceEnabled = false
        settingsManager.telegramEnabled = false
        if (showToast) toast("Sozlamalar saqlandi")
    }

    private fun wireActions() {
        findViewById<Button>(R.id.saveButton).setOnClickListener { saveSettings() }
        findViewById<Button>(R.id.startButton).setOnClickListener {
            saveSettings(showToast = false)
            if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
                requestRuntimePermissions()
                toast("Avval mikrofon ruxsatini bering")
                return@setOnClickListener
            }
            val intent = Intent(this, VoiceForegroundService::class.java).setAction(VoiceForegroundService.ACTION_START)
            ContextCompat.startForegroundService(this, intent)
            settingsManager.assistantRunning = true
            updateStatus()
            toast("Jasur ishga tushdi")
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            startService(Intent(this, VoiceForegroundService::class.java).setAction(VoiceForegroundService.ACTION_STOP))
            settingsManager.assistantRunning = false
            updateStatus()
        }
        findViewById<Button>(R.id.permissionsButton).setOnClickListener { requestRuntimePermissions() }
        findViewById<Button>(R.id.notificationAccessButton).setOnClickListener {
            toast("Safe buildda Telegram Notification Access o'chirilgan")
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.callRoleButton).setOnClickListener {
            toast("Safe buildda Call Screening o'chirilgan")
        }
        findViewById<Button>(R.id.testAiButton).setOnClickListener {
            saveSettings(showToast = false)
            testAi()
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun testAi() {
        if (settingsManager.getApiKey().isBlank()) {
            toast("API kalit kiriting")
            return
        }
        findViewById<Button>(R.id.testAiButton).isEnabled = false
        statusDetails.text = "AI bilan ulanmoqda…"
        worker.execute {
            val result = AiClient(this).ask("Faqat 'Jasur tayyor' deb javob ber.")
            runOnUiThread {
                findViewById<Button>(R.id.testAiButton).isEnabled = true
                toast(result.getOrElse { "AI xatosi: ${it.message}" })
                updateStatus()
            }
        }
    }

    private fun updateStatus() {
        val running = settingsManager.assistantRunning
        statusText.text = if (running) "● Ishlayapti" else "● To‘xtatilgan"
        val mic = if (hasPermission(Manifest.permission.RECORD_AUDIO)) "mikrofon ✓" else "mikrofon ✗"
        val contacts = if (hasPermission(Manifest.permission.READ_CONTACTS)) "kontakt ✓" else "kontakt ✗"
        val camera = if (hasPermission(Manifest.permission.CAMERA)) "fonar ✓" else "fonar ✗"
        statusDetails.text = "$mic • $contacts • $camera\nSafe build: Telegram access va Caller ID o'chirilgan"
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onResume() {
        super.onResume()
        if (::settingsManager.isInitialized) updateStatus()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
