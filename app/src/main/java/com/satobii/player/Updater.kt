package com.satobii.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

object UpdateConfig {
    const val OWNER = "Satobii"
    const val REPO = "apk-video-player"
}

/** Auto-check cuma sekali setiap kali app dibuka. */
object UpdateSession {
    var autoChecked = false
}

data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val notes: String,
    val sizeBytes: Long
)

sealed class UpdateState {
    object Checking : UpdateState()
    object UpToDate : UpdateState()
    class Available(val info: UpdateInfo) : UpdateState()
    class Downloading(val percent: Int) : UpdateState()
    class Ready(val file: File, val message: String? = null) : UpdateState()
    class Error(val message: String) : UpdateState()
}

fun currentVersionName(context: Context): String =
    try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
    } catch (_: Exception) {
        "0"
    }

private fun versionParts(v: String): List<Int> =
    v.trim().removePrefix("v").removePrefix("V").split('.').map { part ->
        part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }

fun isNewerVersion(remote: String, local: String): Boolean {
    val r = versionParts(remote)
    val l = versionParts(local)
    for (i in 0 until maxOf(r.size, l.size)) {
        val a = r.getOrElse(i) { 0 }
        val b = l.getOrElse(i) { 0 }
        if (a != b) return a > b
    }
    return false
}

/** Return null kalau belum ada release atau release tak ada APK. */
suspend fun fetchLatestRelease(): UpdateInfo? = withContext(Dispatchers.IO) {
    val conn = URL("https://api.github.com/repos/${UpdateConfig.OWNER}/${UpdateConfig.REPO}/releases/latest")
        .openConnection() as HttpURLConnection
    try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "AstraPlayer")
        val code = conn.responseCode
        if (code == 404) return@withContext null
        if (code != 200) throw Exception("GitHub balas kod $code")
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = json.optString("tag_name")
        if (tag.isBlank()) return@withContext null
        val assets = json.optJSONArray("assets") ?: return@withContext null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return@withContext UpdateInfo(
                    versionName = tag.removePrefix("v").removePrefix("V"),
                    downloadUrl = a.getString("browser_download_url"),
                    notes = json.optString("body").trim(),
                    sizeBytes = a.optLong("size", 0L)
                )
            }
        }
        null
    } finally {
        conn.disconnect()
    }
}

suspend fun downloadApk(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File =
    withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update.apk")
        val conn = URL(info.downloadUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "AstraPlayer")
            if (conn.responseCode !in 200..299) throw Exception("Muat turun gagal (kod ${conn.responseCode})")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.sizeBytes
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        done += read
                        if (total > 0) {
                            val p = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (p != last) {
                                last = p
                                onProgress(p)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }

/** Return false kalau perlu permission "Install unknown apps" dulu (settings dibuka). */
fun installApk(context: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return false
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
    return true
}

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)

@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val current = remember { currentVersionName(context) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val info = fetchLatestRelease()
                if (info != null && isNewerVersion(info.versionName, current)) UpdateState.Available(info)
                else UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Tak dapat semak update. Check internet.")
            }
        }
    }

    fun download(info: UpdateInfo) {
        state = UpdateState.Downloading(0)
        scope.launch {
            state = try {
                val file = downloadApk(context, info) { p -> state = UpdateState.Downloading(p) }
                val started = installApk(context, file)
                UpdateState.Ready(
                    file,
                    if (started) null else "Benarkan \"Install unknown apps\" untuk Astra Player, patah balik, kemudian tekan Install."
                )
            } catch (e: Exception) {
                UpdateState.Error(e.message ?: "Muat turun gagal.")
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    val textMain = Color(0xFFF4F4F4)
    val textMuted = Color(0xFF9B9B9B)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1D1D1D),
        title = { Text("App updates", color = textMain) },
        text = {
            Column {
                Text("Versi dipasang: v$current", color = textMuted, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                when (val s = state) {
                    is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp), color = textMain)
                        Spacer(Modifier.width(12.dp))
                        Text("Menyemak update…", color = textMain)
                    }
                    is UpdateState.UpToDate -> Text("Kau dah guna versi terbaru.", color = textMain)
                    is UpdateState.Available -> Column {
                        Text("Versi baharu: v${s.info.versionName}", color = textMain)
                        if (s.info.sizeBytes > 0) {
                            Text(mb(s.info.sizeBytes), color = textMuted, fontSize = 12.sp)
                        }
                        if (s.info.notes.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Text(s.info.notes, color = textMuted, fontSize = 13.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    is UpdateState.Downloading -> Column {
                        Text("Memuat turun… ${s.percent}%", color = textMain)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { s.percent / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = textMain
                        )
                    }
                    is UpdateState.Ready -> Text(
                        s.message ?: "Siap dimuat turun. Ikut skrin install Android.",
                        color = textMain
                    )
                    is UpdateState.Error -> Text(s.message, color = Color(0xFFFF6B6B))
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                is UpdateState.Available -> TextButton(onClick = { download(s.info) }) { Text("Update now") }
                is UpdateState.Ready -> TextButton(onClick = {
                    val started = installApk(context, s.file)
                    if (!started) {
                        state = UpdateState.Ready(
                            s.file,
                            "Benarkan \"Install unknown apps\" untuk Astra Player, patah balik, kemudian tekan Install."
                        )
                    }
                }) { Text("Install") }
                is UpdateState.Error -> TextButton(onClick = { check() }) { Text("Cuba lagi") }
                is UpdateState.UpToDate -> TextButton(onClick = { check() }) { Text("Semak lagi") }
                else -> {}
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
