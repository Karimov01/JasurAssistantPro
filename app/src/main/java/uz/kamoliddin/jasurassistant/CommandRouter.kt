package uz.kamoliddin.jasurassistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CommandResult(val speech: String, val handled: Boolean = true)

class CommandRouter(private val context: Context) {
    private val contacts = ContactsHelper(context)
    private val apps = AppLauncher(context)

    fun handle(raw: String): CommandResult {
        val text = raw.trim().lowercase(Locale.getDefault())

        if (listOf("soat nechchi", "vaqt nechchi", "soatni ayt").any { text.contains(it) }) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            return CommandResult("Hozir soat $time")
        }

        if (text.contains("fonar") || text.contains("chiroq")) {
            val enable = text.contains("yoq") || text.contains("yon")
            val disable = text.contains("o'ch") || text.contains("ochir") || text.contains("o‘chir")
            if (enable || disable) {
                val ok = setTorch(enable)
                return CommandResult(if (ok) "Xo'p" else "Fonarni boshqarish uchun kamera ruxsati kerak")
            }
        }

        if (text.contains("telegram") && (text.contains("xabar") || text.contains("habar")) &&
            (text.contains("o'qi") || text.contains("oqi") || text.contains("ayt"))) {
            return CommandResult("Safe versiyada Telegram xabarlarini o'qish vaqtincha o'chirilgan")
        }

        if ((text.contains("och") || text.contains("ishga tushir")) && !text.contains("qo'ng'iroq")) {
            val appName = text
                .replace("ni och", "")
                .replace("och", "")
                .replace("ishga tushir", "")
                .trim()
            if (appName.isNotBlank()) {
                return CommandResult(if (apps.open(appName)) "$appName ochildi" else "$appName ilovasini topolmadim")
            }
        }

        if (text.contains("qo'ng'iroq qil") || text.contains("qo‘ng‘iroq qil") || text.contains("telefon qil")) {
            val name = text
                .replace("ga qo'ng'iroq qil", "")
                .replace("ga qo‘ng‘iroq qil", "")
                .replace("qo'ng'iroq qil", "")
                .replace("qo‘ng‘iroq qil", "")
                .replace("ga telefon qil", "")
                .replace("telefon qil", "")
                .trim()
            if (name.isBlank()) return CommandResult("Kimga qo'ng'iroq qilishni ayting")
            val contact = contacts.findByName(name)
                ?: return CommandResult("$name nomli kontaktni topolmadim")
            val uri = Uri.parse("tel:${Uri.encode(contact.number)}")
            context.startActivity(Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return CommandResult("${contact.name} raqamini terish oynasini ochdim")
        }

        if (text.contains("sozlamalarni och")) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return CommandResult("Sozlamalarni ochdim")
        }

        return CommandResult("", handled = false)
    }

    private fun setTorch(enabled: Boolean): Boolean {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return false
            }
            val manager = context.getSystemService(CameraManager::class.java)
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return false
            manager.setTorchMode(cameraId, enabled)
            true
        } catch (_: Exception) { false }
    }
}
