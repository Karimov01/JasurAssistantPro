package uz.kamoliddin.jasurassistant

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
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

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
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
        callAnnounceSwitch.isChecked = settingsManager.callAnnounceEnabled
        telegramSwitch.isChecked = settingsManager.telegramEnabled
    }

    private fun saveSettings(showToast: Boolean = true) {
        settingsManager.wakeWord = wakeWordInput.text.toString().ifBlank { "jasur" }
        settingsManager.language = languageInput.text.toString().ifBlank { "uz-UZ" }
        settingsManager.baseUrl = baseUrlInput.text.toString().ifBlank { "https://api.openai.com/v1/responses" }
        settingsManager.model = modelInput.text.toString().ifBlank { "gpt-5.6" }
        settingsManager.setApiKey(apiKeyInput.text.toString())
        settingsManager.offlinePreferred = offlineSwitch.isChecked
        settingsManager.aiEnabled = aiSwitch.isChecked
        settingsManager.callAnnounceEnabled = callAnnounceSwitch.isChecked
        settingsManager.telegramEnabled = telegramSwitch.isChecked
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
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        findViewById<Button>(R.id.callRoleButton).setOnClickListener { requestCallScreeningRole() }
        findViewById<Button>(R.id.testAiButton).setOnClickListener {
            saveSettings(showToast = false)
            testAi()
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.POST_NOTIFICATIONS
        permissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun requestCallScreeningRole() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Call Screening roli Android 10+ uchun")
            return
        }
        val roleManager = getSystemService(RoleManager::class.java)
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
            toast("Bu telefonda Call Screening roli mavjud emas")
            return
        }
        if (roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)) {
            toast("Caller ID roli allaqachon yoqilgan")
            return
        }
        roleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
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
        val call = if (hasPermission(Manifest.permission.CALL_PHONE)) "qo‘ng‘iroq ✓" else "qo‘ng‘iroq ✗"
        val role = if (hasCallScreeningRole()) "caller ID ✓" else "caller ID ✗"
        val notif = if (isNotificationAccessEnabled()) "Telegram access ✓" else "Telegram access ✗"
        statusDetails.text = "$mic • $contacts • $call\n$role • $notif"
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun hasCallScreeningRole(): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val roleManager = getSystemService(RoleManager::class.java)
        return roleManager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING) &&
            roleManager.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)
    }

    private fun isNotificationAccessEnabled(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return manager.isNotificationListenerAccessGranted(
            android.content.ComponentName(this, TelegramNotificationListener::class.java)
        )
    }

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
