package uz.kamoliddin.jasurassistant

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

class VoskModelManager(private val context: Context) {
    companion object {
        const val MODEL_NAME = "vosk-model-small-uz-0.22"
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-uz-0.22.zip"
    }

    private val baseDir = File(context.filesDir, "vosk")
    val modelDir = File(baseDir, MODEL_NAME)
    private val marker = File(modelDir, ".jasur_ready")

    fun isReady(): Boolean = marker.exists() && File(modelDir, "conf").exists()

    @Throws(Exception::class)
    fun ensureModel(onProgress: (Int) -> Unit = {}): File {
        if (isReady()) return modelDir
        baseDir.mkdirs()

        val zipFile = File(context.cacheDir, "$MODEL_NAME.zip.part")
        val staging = File(baseDir, "$MODEL_NAME.staging")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()

        download(zipFile, onProgress)
        unzip(zipFile, staging)

        val extractedRoot = File(staging, MODEL_NAME).takeIf { it.isDirectory }
            ?: staging.listFiles()?.firstOrNull { it.isDirectory }
            ?: throw IllegalStateException("Vosk model arxivi ichida model papkasi topilmadi")

        if (modelDir.exists()) modelDir.deleteRecursively()
        if (!extractedRoot.renameTo(modelDir)) {
            extractedRoot.copyRecursively(modelDir, overwrite = true)
        }
        staging.deleteRecursively()
        zipFile.delete()

        if (!File(modelDir, "conf").exists()) {
            modelDir.deleteRecursively()
            throw IllegalStateException("Vosk model tuzilmasi noto'g'ri")
        }
        marker.writeText("ok")
        onProgress(100)
        return modelDir
    }

    private fun download(destination: File, onProgress: (Int) -> Unit) {
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "JasurAssistant/1.2")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Model yuklash HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            BufferedInputStream(connection.inputStream, 64 * 1024).use { input ->
                BufferedOutputStream(FileOutputStream(destination), 64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var lastProgress = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) {
                            val progress = ((downloaded * 100) / total).toInt().coerceIn(0, 99)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zipFile: File, destination: File) {
        val destinationPath = destination.canonicalPath + File.separator
        ZipInputStream(BufferedInputStream(zipFile.inputStream(), 64 * 1024)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val outFile = File(destination, entry.name)
                val canonical = outFile.canonicalPath
                if (!canonical.startsWith(destinationPath)) {
                    throw SecurityException("Xavfsiz bo'lmagan ZIP yo'li")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    BufferedOutputStream(FileOutputStream(outFile), 64 * 1024).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count <= 0) break
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
