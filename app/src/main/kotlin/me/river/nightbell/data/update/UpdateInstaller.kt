package me.river.nightbell.data.update

import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent
import me.river.nightbell.domain.LogField
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a Nightbell release and hands it to Android's installer.
 *
 * This exists because the alternative was worse, not because self-updating is a
 * feature an uptime monitor ought to want. Until 3.6 the only route from "there
 * is a new version" to having it was: open a browser, download an APK, find it
 * in a file manager, tap it, answer the unknown-sources prompt, and answer the
 * install prompt. Five steps outside the app, at least two of which need a
 * different app, and the last two happen anyway. What follows removes the first
 * three and touches neither of the last two.
 *
 * The rules it keeps to:
 *
 *  - **Nothing happens without a tap.** There is no background download, no
 *    "downloading in the background" notification, and no automatic install. A
 *    monitoring app that swaps its own binary while nobody is looking is
 *    indistinguishable from the thing users are warned about.
 *  - **The system prompt is never bypassed.** `PackageInstaller` shows Android's
 *    own confirmation, and the app cannot answer it. Nightbell also does not
 *    hold `INSTALL_PACKAGES`; it holds `REQUEST_INSTALL_PACKAGES`, which is the
 *    permission that means "may ask".
 *  - **The download is checked before it is handed over.** [verify] refuses an
 *    APK whose package name is not this one or whose signing certificate is not
 *    the one already installed, because Android rejects that install with
 *    `INSTALL_FAILED_UPDATE_INCOMPATIBLE` after a 15 MB download and a system
 *    dialog, and says nothing a person could act on.
 *
 *    It should not fire for anyone. F-Droid builds Nightbell reproducibly and
 *    republishes the maintainer's own signed APK, so the F-Droid copy and the
 *    GitHub asset carry the same certificate and either updates the other. The
 *    check is here for the case that is not supposed to happen: a release page
 *    that ends up carrying somebody else's file.
 */
class UpdateInstaller(
    private val context: Context,
    private val scope: CoroutineScope,
    baseClient: OkHttpClient? = null,
) {

    /** Where the download has got to, for the button that started it. */
    sealed interface Stage {
        data object Idle : Stage

        /** [total] is 0 when the server sent no length; show a spinner then. */
        data class Downloading(val received: Long, val total: Long) : Stage {
            val fraction: Float
                get() = if (total <= 0L) 0f else (received.toFloat() / total).coerceIn(0f, 1f)
        }

        /** Reading the archive's package name and certificate. Brief. */
        data object Checking : Stage

        /** Android's installer is on screen, or about to be. */
        data object Installing : Stage

        /**
         * Nothing was installed, and [reason] says what a person can do about it.
         *
         * Not an exception type and not an error code: every value of this is a
         * sentence that goes on the screen under the button, so it is written
         * once, here, where the thing that went wrong is known.
         */
        data class Failed(val reason: String) : Stage
    }

    private val client: OkHttpClient = (baseClient ?: OkHttpClient())
        .newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        // No call timeout. A 15 MB APK over a bad connection is slow rather than
        // broken, and a deadline on the whole transfer would cancel exactly the
        // downloads that most needed to finish.
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _stage = MutableStateFlow<Stage>(Stage.Idle)
    val stage: StateFlow<Stage> = _stage.asStateFlow()

    private var job: Job? = null

    /**
     * Which app installed this one, or null when nothing claims it.
     *
     * Null is the normal answer for a sideload: an APK opened from a file
     * manager may report that manager, one pushed over adb reports the shell or
     * nothing at all.
     */
    @Suppress("DEPRECATION")
    fun installerPackage(): String? = runCatching {
        val manager = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            manager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    /** Whether the platform will let this app ask to install at all. */
    fun canRequestInstall(): Boolean = runCatching {
        context.packageManager.canRequestPackageInstalls()
    }.getOrDefault(false)

    /** Settings → Install unknown apps, for this app. */
    fun unknownSourcesIntent(): Intent = Intent(
        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
        "package:${context.packageName}".toUri(),
    )

    /**
     * Sends the user to that screen.
     *
     * Here rather than in each caller because it is the same round trip from the
     * dashboard and from Settings, and because a device with the screen missing
     * (some heavily modified builds) has to fail as a no-op rather than a crash.
     */
    fun openInstallSettings() {
        val intent = unknownSourcesIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { _stage.value = Stage.Failed("This device has no screen for that setting.") }
    }

    /** Puts the button back after a failure has been read. */
    fun dismiss() {
        if (_stage.value is Stage.Failed) _stage.value = Stage.Idle
    }

    fun cancel() {
        job?.cancel()
        job = null
        _stage.value = Stage.Idle
    }

    /**
     * Downloads [url], checks it, and asks Android to install it.
     *
     * @param expectedVersion the version the check said this is, used only to
     *   name the cached file and to catch a link that points at something else.
     */
    fun start(url: String, expectedVersion: String, expectedSize: Long = 0L) {
        if (job?.isActive == true) return
        if (url.isBlank()) {
            _stage.value = Stage.Failed("This release has no APK to download. Open the release page instead.")
            return
        }
        if (!canRequestInstall()) {
            _stage.value = Stage.Failed(
                "Android needs permission to let Nightbell install apps. " +
                    "Grant it in Settings, then tap Install again.",
            )
            return
        }
        job = scope.launch {
            try {
                val file = download(url, expectedVersion, expectedSize)
                _stage.value = Stage.Checking
                val refusal = verify(file)
                if (refusal != null) {
                    file.delete()
                    _stage.value = Stage.Failed(refusal)
                    return@launch
                }
                _stage.value = Stage.Installing
                install(file)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Diag.log(LogEvent.UPDATE_DOWNLOAD_FAILED, LogField.error("why", error))
                _stage.value = Stage.Failed(readable(error))
            }
        }
    }

    // ---- the download -------------------------------------------------------

    private suspend fun download(url: String, version: String, expectedSize: Long): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            // One file per version, replaced rather than appended to. A partial
            // download left by a killed process must not be mistaken for a whole
            // one, and resuming a range request is not worth the failure modes
            // when the whole thing is 15 MB.
            dir.listFiles()?.forEach { it.delete() }
            val file = File(dir, "nightbell-${version.ifBlank { "update" }}.apk")

            _stage.value = Stage.Downloading(0, expectedSize)
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The download failed with HTTP ${response.code}.")
                }
                val total = response.body.contentLength().takeIf { it > 0 } ?: expectedSize
                var received = 0L
                var lastPublished = 0L
                response.body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            // Publishing every buffer would put ~240 recompositions
                            // through the button on a 15 MB file for no visible
                            // gain. A percent is the smallest change worth drawing.
                            if (total <= 0 || received - lastPublished >= total / 100 || received == total) {
                                lastPublished = received
                                _stage.value = Stage.Downloading(received, total)
                            }
                        }
                    }
                }
            }
            file
        }

    // ---- the checks ---------------------------------------------------------

    /** @return null when the archive is safe to offer, or the reason it is not. */
    private fun verify(file: File): String? {
        if (!file.isFile || file.length() == 0L) return "The download arrived empty. Try again."
        val manager = context.packageManager
        val archive = runCatching {
            manager.getPackageArchiveInfo(file.absolutePath, signingFlags())
        }.getOrNull() ?: return "That file is not an Android package. Open the release page instead."

        if (archive.packageName != context.packageName) {
            return "That APK is ${archive.packageName}, not ${context.packageName}. " +
                "Installing it would add a second app rather than update this one."
        }
        val installed = runCatching {
            manager.getPackageInfo(context.packageName, signingFlags())
        }.getOrNull() ?: return null

        val mine = certificates(installed.signaturesCompat())
        val theirs = certificates(archive.signaturesCompat())
        if (mine.isEmpty() || theirs.isEmpty()) return null
        if (mine.intersect(theirs).isEmpty()) {
            return "That build is signed with a different key than the copy you have, so " +
                "Android will refuse to install it over the top. Get it from wherever you " +
                "installed this one, or export your monitors and reinstall."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun android.content.pm.PackageInfo.signaturesCompat(): Array<Signature> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ->
            signingInfo?.apkContentsSigners ?: emptyArray()

        else -> signatures ?: emptyArray()
    }

    @Suppress("DEPRECATION")
    private fun signingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }

    private fun certificates(signatures: Array<Signature>): Set<String> =
        signatures.map { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()

    // ---- handing it over ----------------------------------------------------

    private suspend fun install(file: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL,
        ).apply {
            setAppPackageName(context.packageName)
        }
        // No setRequireUserAction. The only value that would skip the system
        // prompt is USER_ACTION_NOT_REQUIRED, and an uptime monitor replacing its
        // own binary without asking is the exact shape of the thing users are
        // warned about. UNSPECIFIED is the default anyway, so saying it out loud
        // would only have looked like a decision that had been made.
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("nightbell", 0, file.length()).use { output ->
                file.inputStream().use { input -> input.copyTo(output) }
                session.fsync(output)
            }
            session.commit(statusIntent(sessionId).intentSender)
        }
    }

    private fun statusIntent(sessionId: Int): PendingIntent {
        val intent = Intent(ACTION_INSTALL_STATUS).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    /**
     * Registered from the graph, for the life of the process.
     *
     * `PackageInstaller` reports through a broadcast, and the first thing it
     * usually reports is `STATUS_PENDING_USER_ACTION`: the confirmation dialog
     * has been prepared and somebody has to start it. Missing that broadcast
     * means the install silently never happens, which is the failure mode this
     * whole class exists to avoid.
     */
    fun registerStatusReceiver() {
        val filter = IntentFilter(ACTION_INSTALL_STATUS)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receivedIn: Context, intent: Intent) {
                onStatus(intent)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun onStatus(intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.confirmationIntent() ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { _stage.value = Stage.Failed("Android would not open the install prompt.") }
            }

            PackageInstaller.STATUS_SUCCESS -> _stage.value = Stage.Idle

            PackageInstaller.STATUS_FAILURE_ABORTED ->
                // The user said no. That is an answer, not an error, so it leaves
                // no red text behind explaining what they already know.
                _stage.value = Stage.Idle

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Diag.log(
                    LogEvent.UPDATE_INSTALL_STATUS,
                    LogField.of("status", status),
                    LogField.text("message", message.orEmpty()),
                )
                _stage.value = Stage.Failed(
                    message?.takeIf { it.isNotBlank() }?.let { "Android refused the install: $it" }
                        ?: "Android refused the install.",
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.confirmationIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_INTENT) as? Intent
        }

    private fun readable(error: Throwable): String = when (error) {
        is IOException -> error.message?.takeIf { it.endsWith(".") }
            ?: "The download did not finish. Check the connection and try again."

        else -> "The download did not finish. Check the connection and try again."
    }

    companion object {
        private const val TAG = "UpdateInstaller"
        private const val ACTION_INSTALL_STATUS = "me.river.nightbell.action.INSTALL_STATUS"
    }
}
