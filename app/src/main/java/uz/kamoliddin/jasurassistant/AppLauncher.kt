package uz.kamoliddin.jasurassistant

import android.content.Context
import android.content.Intent

class AppLauncher(private val context: Context) {
    private val aliases = mapOf(
        "telegram" to "org.telegram.messenger",
        "youtube" to "com.google.android.youtube",
        "yutub" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "instagram" to "com.instagram.android",
        "whatsapp" to "com.whatsapp"
    )

    fun open(spokenName: String): Boolean {
        val query = spokenName.trim().lowercase()
        aliases.entries.firstOrNull { query.contains(it.key) }?.let { entry ->
            context.packageManager.getLaunchIntentForPackage(entry.value)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
                return true
            }
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = context.packageManager.queryIntentActivities(launcherIntent, 0)
        val match = candidates.firstOrNull {
            it.loadLabel(context.packageManager).toString().lowercase().contains(query)
        } ?: return false
        val intent = context.packageManager.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return true
    }
}
