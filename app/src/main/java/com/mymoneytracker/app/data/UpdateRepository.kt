package com.mymoneytracker.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mymoneytracker.app.BuildConfig
import com.mymoneytracker.core.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LatestRelease(
    val versionCode: Int,
    val title: String,
    val apkUrl: String,
)

/** GitHub 릴리스(dev-latest)에서 새 버전을 확인하고 APK 를 내려받는다. */
class UpdateRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    val installedVersionCode: Int get() = BuildConfig.VERSION_CODE
    val installedVersionName: String get() = BuildConfig.VERSION_NAME

    var skippedVersionCode: Int?
        get() = prefs.getInt(KEY_SKIPPED, -1).takeIf { it >= 0 }
        set(value) = prefs.edit().putInt(KEY_SKIPPED, value ?: -1).apply()

    /** 최신 릴리스 정보. 네트워크 오류 등으로 확인하지 못하면 예외를 던진다. */
    suspend fun fetchLatest(): LatestRelease? = withContext(Dispatchers.IO) {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (connection.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val name = json.optString("name")
            val versionCode = UpdateInfo.parseVersionCode(name, json.optString("body")) ?: return@withContext null
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) {
                        apkUrl = asset.optString("browser_download_url")
                        break
                    }
                }
            }
            apkUrl?.let { LatestRelease(versionCode, name, it) }
        } finally {
            connection.disconnect()
        }
    }

    /** APK 를 앱 캐시 폴더에 내려받는다. */
    suspend fun download(release: LatestRelease, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "my-money-tracker-${release.versionCode}.apk")
        val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress(copied.toFloat() / total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        target
    }

    /** "출처를 알 수 없는 앱 설치" 가 허용되어 있는지. */
    fun canInstallPackages(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private companion object {
        const val RELEASE_API = "https://api.github.com/repos/Ares-wjd/my-money-tracker/releases/tags/dev-latest"
        const val KEY_SKIPPED = "skipped_version_code"
    }
}
