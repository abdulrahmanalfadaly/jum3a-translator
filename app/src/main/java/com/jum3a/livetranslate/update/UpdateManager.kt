package com.jum3a.livetranslate.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jum3a.livetranslate.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class Release(val build: Int, val title: String, val notes: String, val apkUrl: String, val size: Long)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val build: Int) : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Self-updater for development: every push to GitHub builds a release named "build-N"
 * (N = version code). The app compares N with its own version code, downloads the APK and
 * hands it to Android's installer.
 */
class UpdateManager(private val app: Context, private val http: OkHttpClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentBuild: Int = BuildConfig.VERSION_CODE
    val repo: String = BuildConfig.UPDATE_REPO

    fun check(userInitiated: Boolean) {
        val s = _state.value
        if (s is UpdateState.Checking || s is UpdateState.Downloading || s is UpdateState.Ready) return
        scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                cleanupOldDownloads()
                val latest = fetchLatest()
                when {
                    latest != null && latest.build > currentBuild -> UpdateState.Available(latest)
                    userInitiated -> UpdateState.UpToDate(currentBuild)
                    else -> UpdateState.Idle
                }
            } catch (e: Exception) {
                if (userInitiated) UpdateState.Failed("Couldn't check for updates: ${e.message}") else UpdateState.Idle
            }
        }
    }

    fun dismiss() {
        val s = _state.value
        if (s !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }

    fun download(release: Release) {
        if (_state.value is UpdateState.Downloading) return
        scope.launch {
            _state.value = UpdateState.Downloading(release, 0f)
            try {
                val dir = File(app.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "jum3a-build-${release.build}.apk")
                val partial = File(dir, "${target.name}.part")
                http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
                    val body = resp.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: release.size
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            var lastReported = 0f
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                output.write(buf, 0, n)
                                done += n
                                if (total > 0) {
                                    val p = (done.toFloat() / total).coerceIn(0f, 1f)
                                    if (p - lastReported >= 0.02f) {
                                        lastReported = p
                                        _state.value = UpdateState.Downloading(release, p)
                                    }
                                }
                            }
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) throw IOException("Couldn't save the update")
                _state.value = UpdateState.Ready(release, target)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Update download failed: ${e.message}")
            }
        }
    }

    /** True if the system installer was opened; false if we first sent the user to allow installs. */
    fun install(context: Context): Boolean {
        val ready = _state.value as? UpdateState.Ready ?: return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", ready.file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    private fun fetchLatest(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null // no releases yet
            if (!resp.isSuccessful) throw IOException("GitHub returned HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string().orEmpty())
            val tag = json.optString("tag_name")
            val build = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        build = build,
                        title = json.optString("name").ifBlank { tag },
                        notes = json.optString("body").trim(),
                        apkUrl = a.getString("browser_download_url"),
                        size = a.optLong("size"),
                    )
                }
            }
            return null
        }
    }

    private fun cleanupOldDownloads() {
        File(app.cacheDir, "updates").listFiles()?.forEach { f ->
            val build = Regex("build-(\\d+)").find(f.name)?.groupValues?.get(1)?.toIntOrNull()
            if (build == null || build <= currentBuild) f.delete()
        }
    }
}
