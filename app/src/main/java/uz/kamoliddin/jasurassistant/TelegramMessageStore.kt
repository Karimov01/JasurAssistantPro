package uz.kamoliddin.jasurassistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TelegramMessage(val sender: String, val text: String, val time: Long)

class TelegramMessageStore(context: Context) {
    private val prefs = context.getSharedPreferences("telegram_messages", Context.MODE_PRIVATE)

    @Synchronized
    fun add(message: TelegramMessage) {
        val items = getRecent(19).toMutableList()
        items.add(0, message)
        val arr = JSONArray()
        items.take(20).forEach {
            arr.put(JSONObject().apply {
                put("sender", it.sender)
                put("text", it.text)
                put("time", it.time)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun getRecent(limit: Int = 5): List<TelegramMessage> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until minOf(arr.length(), limit)) {
                    val o = arr.getJSONObject(i)
                    add(TelegramMessage(o.optString("sender"), o.optString("text"), o.optLong("time")))
                }
            }
        } catch (_: Exception) { emptyList() }
    }
}
