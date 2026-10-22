package cz.plzen.stihamto

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class UpdateCheckResult(
    val isUpdateAvailable: Boolean,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val latestVersionCode: Int,
    val latestVersionName: String,
    val releaseNotes: String,
    val apkUrl: String
)

object AppUpdateManager {

    private const val GITHUB_VERSION_JSON_URL =
        "https://raw.githubusercontent.com/hiekevoj/Stiham_to/main/version.json"

    private const val GITHUB_RELEASES_API_URL =
        "https://api.github.com/repos/hiekevoj/Stiham_to/releases/latest"

    /**
     * Zkontroluje na GitHubu existenci novější verze aplikace.
     */
    suspend fun checkForUpdates(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }

        val currentCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode
        }
        val currentName = pInfo.versionName ?: "0.0.14"

        // 1. Zkusíme raw version.json
        try {
            val url = URL(GITHUB_VERSION_JSON_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "StihamTo-Updater")

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                val latestCode = obj.optInt("versionCode", currentCode)
                val latestName = obj.optString("versionName", currentName)
                val apkUrl = obj.optString(
                    "apkUrl",
                    "https://raw.githubusercontent.com/hiekevoj/Stiham_to/main/public/StihamTo-Plzen.apk"
                )
                val notes = obj.optString("releaseNotes", "Nová verze aplikace Stíhám to?")

                return@withContext UpdateCheckResult(
                    isUpdateAvailable = latestCode > currentCode,
                    currentVersionCode = currentCode,
                    currentVersionName = currentName,
                    latestVersionCode = latestCode,
                    latestVersionName = latestName,
                    releaseNotes = notes,
                    apkUrl = apkUrl
                )
            }
        } catch (_: Exception) {
        }

        // 2. Fallback na GitHub Releases API
        try {
            val url = URL(GITHUB_RELEASES_API_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 7000
            conn.readTimeout = 7000
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "StihamTo-Updater")
            conn.setRequestProperty("Accept", "application/vnd.github.v3+json")

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                val tagName = obj.optString("tag_name", "").removePrefix("v")
                val notes = obj.optString("body", "Nová verze na GitHub Releases")

                var downloadUrl = "https://raw.githubusercontent.com/hiekevoj/Stiham_to/main/public/StihamTo-Plzen.apk"
                val assets = obj.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", downloadUrl)
                            break
                        }
                    }
                }

                val remoteCode = tagName.filter { it.isDigit() }.toIntOrNull() ?: currentCode
                return@withContext UpdateCheckResult(
                    isUpdateAvailable = remoteCode > currentCode,
                    currentVersionCode = currentCode,
                    currentVersionName = currentName,
                    latestVersionCode = remoteCode,
                    latestVersionName = tagName.ifEmpty { currentName },
                    releaseNotes = notes,
                    apkUrl = downloadUrl
                )
            }
        } catch (_: Exception) {
        }

        // Pokud se nepodařilo připojit k internetu
        UpdateCheckResult(
            isUpdateAvailable = false,
            currentVersionCode = currentCode,
            currentVersionName = currentName,
            latestVersionCode = currentCode,
            latestVersionName = currentName,
            releaseNotes = "",
            apkUrl = "https://raw.githubusercontent.com/hiekevoj/Stiham_to/main/public/StihamTo-Plzen.apk"
        )
    }

    /**
     * Stáhne APK soubor s průběžným hlášením postupu (0.0f .. 1.0f).
     */
    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }
        val targetFile = File(updatesDir, "StihamTo-Plzen-update.apk")
        if (targetFile.exists()) {
            targetFile.delete()
        }

        val url = URL(apkUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 20000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "StihamTo-Updater")

        // Zpracování případného přesměrování (např. GitHub Releases download redirect)
        var redirectedConn: HttpURLConnection = conn
        var status = conn.responseCode
        if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
            status == HttpURLConnection.HTTP_MOVED_PERM ||
            status == 307 || status == 308
        ) {
            val newUrl = conn.getHeaderField("Location")
            redirectedConn = URL(newUrl).openConnection() as HttpURLConnection
            redirectedConn.setRequestProperty("User-Agent", "StihamTo-Updater")
        }

        val totalLength = redirectedConn.contentLength.toLong()
        var downloadedBytes = 0L

        redirectedConn.inputStream.use { input ->
            FileOutputStream(targetFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead
                    if (totalLength > 0) {
                        val progress = (downloadedBytes.toFloat() / totalLength).coerceIn(0f, 1f)
                        onProgress(progress)
                    }
                }
            }
        }

        targetFile
    }

    /**
     * Spustí systémový instalátor APK souboru.
     */
    fun startInstall(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            throw IllegalStateException("Soubor APK nebyl nalezen.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
                return
            }
        }

        val apkUri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apkFile
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        activity.startActivity(installIntent)
    }
}
