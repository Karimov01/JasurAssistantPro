package uz.kamoliddin.jasurassistant

import android.content.Context

class SettingsManager(context: Context) {
    private val prefs = context.getSharedPreferences("jasur_settings", Context.MODE_PRIVATE)
    private val secretStore = SecretStore(context)

    var wakeWord: String
        get() = prefs.getString("wake_word", "jasur") ?: "jasur"
        set(value) = prefs.edit().putString("wake_word", value.trim().lowercase()).apply()

    var language: String
        get() = prefs.getString("language", "uz-UZ") ?: "uz-UZ"
        set(value) = prefs.edit().putString("language", value.trim()).apply()

    var offlinePreferred: Boolean
        get() = prefs.getBoolean("offline_preferred", true)
        set(value) = prefs.edit().putBoolean("offline_preferred", value).apply()

    var aiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", true)
        set(value) = prefs.edit().putBoolean("ai_enabled", value).apply()

    var callAnnounceEnabled: Boolean
        get() = prefs.getBoolean("call_announce", true)
        set(value) = prefs.edit().putBoolean("call_announce", value).apply()

    var telegramEnabled: Boolean
        get() = prefs.getBoolean("telegram_enabled", true)
        set(value) = prefs.edit().putBoolean("telegram_enabled", value).apply()

    var baseUrl: String
        get() = prefs.getString("base_url", "https://api.openai.com/v1/responses")
            ?: "https://api.openai.com/v1/responses"
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", "gpt-5.6") ?: "gpt-5.6"
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    var assistantRunning: Boolean
        get() = prefs.getBoolean("assistant_running", false)
        set(value) = prefs.edit().putBoolean("assistant_running", value).apply()

    fun setApiKey(value: String) = secretStore.put("ai_api_key", value.trim())
    fun getApiKey(): String = secretStore.get("ai_api_key") ?: ""
}
