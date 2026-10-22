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

    private const val FALLBACK_DIRECT_APK_URL =
        "https://github.com/hiekevoj/Stiham_to/raw/main/StihamTo-Plzen.apk"

    /**
     * Sémantické porovnání verzí (SemVer).
     * Vrací kladné číslo pokud v1 > v2, záporné pokud v1 < v2, 0 pokud v1 == v2.
     * Podporuje formáty jako "0.0.15", "v0.1.0", "1.0", "0.0.14-beta".
     */
    fun compareVersions(v1: String, v2: String): Int {
        val clean1 = v1.trim().removePrefix("v").removePrefix("V").substringBefore("-")
        val clean2 = v2.trim().removePrefix("v").removePrefix("V").substringBefore("-")

        val parts1 = clean1.split(".").mapNotNull { it.trim().toIntOrNull() }
        val parts2 = clean2.split(".").mapNotNull { it.trim().toIntOrNull() }

        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val num1 = parts1.getOrElse(i) { 0 }
            val num2 = parts2.getOrElse(i) { 0 }
            if (num1 != num2) {
                return num1.compareTo(num2)
            }
        }
        return 0
    }

    fun isNewerVersion(remoteVersion: String, currentVersion: String): Boolean {
        return compareVersions(remoteVersion, currentVersion) > 0
    }

    /**
     * Zkontroluje na GitHubu existenci novější verze aplikace.
     * Ověřuje jak GitHub Releases API (primární distribuční kanál), tak raw version.json.
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
        val currentName = pInfo.versionName ?: "0.1.0"

        var latestFoundCode = currentCode
        var latestFoundName = currentName
        var releaseNotes = ""
        var apkDownloadUrl = FALLBACK_DIRECT_APK_URL
        var hasNewer = false

        // 1. Zkontrolujeme GitHub Releases API (tam jsou oficiální tagy a zkompilované assety)
        try {
            val releasesUrl = URL(GITHUB_RELEASES_API_URL)
            val conn = (releasesUrl.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "StihamTo-Plzen-App")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                val rawTag = obj.optString("tag_name", "").trim()
                val tagName = rawTag.removePrefix("v").removePrefix("V")
                val notes = obj.optString("body", "").trim()

                var assetApkUrl: String? = null
                val assets = obj.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            val rawUrl = asset.optString("browser_download_url", "")
                            if (rawUrl.isNotEmpty()) {
                                assetApkUrl = rawUrl
                            }
                            break
                        }
                    }
                }

                if (tagName.isNotEmpty()) {
                    val isTagNewer = isNewerVersion(tagName, currentName)
                    val numericCode = tagName.filter { it.isDigit() }.toIntOrNull() ?: currentCode

                    if (isTagNewer || numericCode > currentCode) {
                        hasNewer = true
                        latestFoundName = tagName
                        latestFoundCode = maxOf(currentCode + 1, numericCode)
                        releaseNotes = notes.ifEmpty { "Nová verze $tagName na GitHub Releases" }
                        apkDownloadUrl = assetApkUrl ?: FALLBACK_DIRECT_APK_URL
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AppUpdateManager", "GitHub Releases API dotaz selhal: ${e.message}")
        }

        // 2. Pokud Releases API ještě novější verzi nevrátilo (nebo selhalo), zkontrolujeme raw version.json
        // Přidáváme timestamp query pro eliminaci CDN cache na raw.githubusercontent.com
        try {
            val cacheBusterUrl = "$GITHUB_VERSION_JSON_URL?nocache=${System.currentTimeMillis()}"
            val conn = (URL(cacheBusterUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "StihamTo-Plzen-App")
                setRequestProperty("Cache-Control", "no-cache, no-store")
            }

            if (conn.responseCode == 200) {
                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val obj = JSONObject(jsonStr)
                val rawCode = obj.optInt("versionCode", currentCode)
                val rawName = obj.optString("versionName", currentName).trim()
                val rawNotes = obj.optString("releaseNotes", "").trim()
                val rawApk = obj.optString("apkUrl", FALLBACK_DIRECT_APK_URL).trim()

                val isCodeNewer = rawCode > currentCode
                val isNameNewer = isNewerVersion(rawName, currentName)

                if (isCodeNewer || isNameNewer) {
                    if (!hasNewer || rawCode > latestFoundCode || isNewerVersion(rawName, latestFoundName)) {
                        hasNewer = true
                        latestFoundCode = rawCode
                        latestFoundName = rawName
                        releaseNotes = rawNotes.ifEmpty { releaseNotes }
                        if (rawApk.isNotEmpty() && !rawApk.contains("/public/")) {
                            apkDownloadUrl = rawApk
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("AppUpdateManager", "version.json dotaz selhal: ${e.message}")
        }

        UpdateCheckResult(
            isUpdateAvailable = hasNewer,
            currentVersionCode = currentCode,
            currentVersionName = currentName,
            latestVersionCode = latestFoundCode,
            latestVersionName = latestFoundName,
            releaseNotes = releaseNotes.ifEmpty { "K dispozici je nová verze aplikace Stíhám to? ($latestFoundName)." },
            apkUrl = apkDownloadUrl
        )
    }

    /**
     * Stáhne APK soubor s plnou podporou vícenásobných HTTP přesměrování (301, 302, 303, 307, 308)
     * a průběžným hlášením postupu (0.0f .. 1.0f).
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

        var currentUrl = apkUrl
        var finalConnection: HttpURLConnection? = null
        var redirectCount = 0

        while (redirectCount < 6) {
            val url = URL(currentUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "StihamTo-Plzen-App")
            conn.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")

            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER ||
                status == 307 || status == 308
            ) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Přesměrování bez hlavičky Location (HTTP $status)")
                currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                    location
                } else {
                    URL(url, location).toString()
                }
                conn.disconnect()
                redirectCount++
                continue
            }

            if (status != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                throw IllegalStateException("Server vrátil chybu při stahování aktualizace: HTTP $status")
            }

            finalConnection = conn
            break
        }

        val conn = finalConnection
            ?: throw IllegalStateException("Příliš mnoho přesměrování při stahování APK souboru.")

        val totalLength = conn.contentLength.toLong()
        var downloadedBytes = 0L

        try {
            conn.inputStream.use { input ->
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
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        if (!targetFile.exists() || targetFile.length() < 100_000L) {
            throw IllegalStateException("Stažený APK soubor je nekompletní nebo poškozený (${targetFile.length()} B).")
        }

        targetFile
    }

    /**
     * Spustí systémový instalátor staženého APK souboru.
     */
    fun startInstall(activity: Activity, apkFile: File) {
        if (!apkFile.exists()) {
            throw IllegalStateException("Soubor instalačního balíčku nebyl nalezen.")
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
