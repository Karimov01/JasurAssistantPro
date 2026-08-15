package uz.kamoliddin.jasurassistant

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class TelegramNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName != "org.telegram.messenger") return
        val settings = SettingsManager(this)
        if (!settings.telegramEnabled) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        TelegramMessageStore(this).add(
            TelegramMessage(
                sender = title.ifBlank { "Telegram" },
                text = text,
                time = System.currentTimeMillis()
            )
        )
    }
}
