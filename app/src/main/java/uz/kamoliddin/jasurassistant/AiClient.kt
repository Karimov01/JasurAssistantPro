package uz.kamoliddin.jasurassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AiClient(context: Context) {
    private val settings = SettingsManager(context)

    fun ask(prompt: String): Result<String> = runCatching {
        val key = settings.getApiKey()
        require(key.isNotBlank()) { "API kalit kiritilmagan" }
        val endpoint = settings.baseUrl
        require(endpoint.startsWith("https://")) { "AI URL https:// bilan boshlanishi kerak" }

        val body = JSONObject().apply {
            put("model", settings.model)
            put("store", false)
            put(
                "instructions",
                "Sen Jasur ismli Kamoliddinning shaxsiy o'zbekcha ovozli yordamchisisan. " +
                    "Javoblarni qisqa, tabiiy va ovozda aytishga qulay o'zbek tilida ber. " +
                    "Telefon amallarini o'zing bajargandek da'vo qilma; faqat foydali javob ber."
            )
            put("input", prompt)
        }

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 45_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Content-Type", "application/json")
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val message = try {
                JSONObject(text).optJSONObject("error")?.optString("message")
            } catch (_: Exception) { null }
            error(message?.takeIf { it.isNotBlank() } ?: "AI server xatosi: HTTP $code")
        }
        extractOutputText(JSONObject(text)).ifBlank { "AI javobi bo'sh qaytdi" }
    }

    private fun extractOutputText(root: JSONObject): String {
        root.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val output = root.optJSONArray("output") ?: JSONArray()
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                val type = c.optString("type")
                if (type == "output_text" || type == "text") {
                    c.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                }
            }
        }
        return parts.joinToString(" ").trim()
    }
}
