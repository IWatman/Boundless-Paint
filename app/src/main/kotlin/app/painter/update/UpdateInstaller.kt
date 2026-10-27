package app.painter.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Outcome of a [PackageInstaller] session. */
sealed interface InstallStatus {
    /** The package was installed; Android usually restarts the app afterwards. */
    data object Success : InstallStatus

    /** The user cancelled the system install dialog. */
    data object Aborted : InstallStatus

    /** The system install dialog was opened; the final status follows (or never comes, see the view model). */
    data object ConfirmShown : InstallStatus

    /** Installation failed. */
    data class Failure(val message: String) : InstallStatus
}

/** Process-wide result of the install handshake, fed by [UpdateInstallReceiver]. */
object UpdateInstallBus {

    private val _status = MutableStateFlow<InstallStatus?>(null)

    /** Last reported install status, `null` while nothing was reported. */
    val status: StateFlow<InstallStatus?> = _status.asStateFlow()

    /** Clears the stored status so a result is handled only once. */
    fun consume() {
        _status.value = null
    }

    internal fun publish(status: InstallStatus) {
        _status.value = status
    }
}

/** Downloads update APKs and installs them through a [PackageInstaller] session. */
class UpdateInstaller(context: Context) {

    private val appContext = context.applicationContext

    /** True when the user already allowed this app to install unknown packages. */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    /** Opens the system screen where the install permission can be granted. */
    fun requestInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData("package:${appContext.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    /**
     * Downloads [info] into `cacheDir/updates`, reporting [onProgress] as downloaded bytes and
     * total bytes (0 when unknown). Throws [IOException] when the size does not match.
     */
    suspend fun download(info: UpdateInfo, onProgress: (Long, Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val target = File(dir, "painter-${info.tagName.removePrefix("v")}.apk")
            val connection = URL(info.apkUrl).openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/octet-stream")
                connection.setRequestProperty("User-Agent", USER_AGENT)
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("Download HTTP $code")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                connection.inputStream.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written, total)
                        }
                    }
                }
            } finally {
                connection.disconnect()
            }
            val expected = if (info.apkSize > 0) info.apkSize else target.length()
            val actual = target.length()
            if (actual != expected) {
                target.delete()
                throw IOException("Downloaded $actual of $expected bytes")
            }
            target
        }

    /** True while the install session [sessionId] still exists. */
    fun isSessionAlive(sessionId: Int): Boolean =
        runCatching { appContext.packageManager.packageInstaller.getSessionInfo(sessionId) != null }.getOrDefault(false)

    /** Commits [apk] to a full install session and returns its id; the result arrives through [UpdateInstallBus]. */
    fun install(apk: File): Int {
        val installer = appContext.packageManager.packageInstaller
        val sessionId = installer.createSession(
            PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL),
        )
        installer.openSession(sessionId).use { session ->
            session.openWrite(SESSION_NAME, 0, apk.length()).use { output ->
                apk.inputStream().use { it.copyTo(output) }
                session.fsync(output)
            }
            session.commit(confirmationIntent(sessionId).intentSender)
        }
        return sessionId
    }

    private fun confirmationIntent(sessionId: Int): PendingIntent {
        val intent = Intent(appContext, UpdateInstallReceiver::class.java).setAction(ACTION_INSTALL_STATUS)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
        return PendingIntent.getBroadcast(appContext, sessionId, intent, flags)
    }

    companion object {
        /** Action of the explicit broadcast that delivers the session status. */
        const val ACTION_INSTALL_STATUS = "app.painter.update.INSTALL_STATUS"
        private const val SESSION_NAME = "painter-update"
        private const val TIMEOUT_MS = 30_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val USER_AGENT = "Painter-Android"
    }
}

/** Receives [PackageInstaller] session status and forwards user confirmation to the system UI. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirmation == null) {
                    UpdateInstallBus.publish(InstallStatus.Failure("Install confirmation missing"))
                } else {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    UpdateInstallBus.publish(InstallStatus.ConfirmShown)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> UpdateInstallBus.publish(InstallStatus.Success)
            PackageInstaller.STATUS_FAILURE_ABORTED -> UpdateInstallBus.publish(InstallStatus.Aborted)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                UpdateInstallBus.publish(
                    InstallStatus.Failure(message?.takeIf { it.isNotBlank() } ?: "Installer status $status"),
                )
            }
        }
    }
}
