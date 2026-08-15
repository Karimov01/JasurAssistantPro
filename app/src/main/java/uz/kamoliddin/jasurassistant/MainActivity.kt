package uz.kamoliddin.jasurassistant

import android.Manifest
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import android.service.voice.VoiceInteractionService
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

    private val assistantRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateStatus()
        if (!isJasurActiveAssistant()) {
            openDefaultAppsSettings()
            toast("Default apps ichidan 'Digital assistant app' ni ochib Jasur Assistant ni tanlang")
        }
    }

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
            requestAssistantRole()
        }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            if (!isJasurActiveAssistant()) {
                toast("Avval Jasurni standart yordamchi sifatida tanlang")
                openDefaultAppsSettings()
                return@setOnClickListener
            }
            try {
                startActivity(Intent(Intent.ACTION_ASSIST))
            } catch (_: Exception) {
                startActivity(Intent(this, AssistActivity::class.java))
            }
        }
        findViewById<Button>(R.id.permissionsButton).setOnClickListener { requestRuntimePermissions() }
        findViewById<Button>(R.id.callRoleButton).setOnClickListener { openDefaultAppsSettings() }
        findViewById<Button>(R.id.notificationAccessButton).setOnClickListener {
            toast("System Assistant rejimida doimiy notification kerak emas")
        }
        findViewById<Button>(R.id.batteryButton).setOnClickListener {
            toast("System Assistant rejimi doimiy mikrofon foreground service ishlatmaydi")
        }
        findViewById<Button>(R.id.testAiButton).setOnClickListener {
            saveSettings(showToast = false)
            testAi()
        }
    }

    private fun requestAssistantRole() {
        val roleManager = getSystemService(RoleManager::class.java)
        if (isJasurActiveAssistant()) {
            toast("Jasur allaqachon standart yordamchi")
            updateStatus()
            return
        }
        if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
            try {
                assistantRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT))
                return
            } catch (_: Exception) { }
        }
        openDefaultAppsSettings()
        toast("Default apps → Digital assistant app → Jasur Assistant ni tanlang")
    }

    private fun openDefaultAppsSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun requestRuntimePermissions() {
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CAMERA
            )
        )
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
        val active = isJasurActiveAssistant()
        statusText.text = if (active) "● System Assistant faol" else "● Assistant sifatida tanlanmagan"
        val mic = if (hasPermission(Manifest.permission.RECORD_AUDIO)) "mikrofon ✓" else "mikrofon ✗"
        val contacts = if (hasPermission(Manifest.permission.READ_CONTACTS)) "kontakt ✓" else "kontakt ✗"
        val camera = if (hasPermission(Manifest.permission.CAMERA)) "fonar ✓" else "fonar ✗"
        statusDetails.text = if (active) {
            "$mic • $contacts • $camera\nNotification yo‘q. Power/assistant gesture bilan Jasurni chaqiring."
        } else {
            "$mic • $contacts • $camera\nSamsung: Default apps → Digital assistant app → Jasur Assistant"
        }
    }

    private fun isJasurActiveAssistant(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java)
        if (roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
            return true
        }
        val component = ComponentName(this, JasurVoiceInteractionService::class.java)
        return VoiceInteractionService.isActiveService(this, component)
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
