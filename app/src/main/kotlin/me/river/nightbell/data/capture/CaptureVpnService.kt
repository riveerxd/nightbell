package me.river.nightbell.data.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import me.river.nightbell.MainActivity
import me.river.nightbell.R
import me.river.nightbell.ui.i18n.L10n
import java.io.FileInputStream
import java.net.Inet4Address

/**
 * Turns capture on for the whole phone without root.
 *
 * Android does not let one app read another app's sockets. The supported route
 * is a local VPN: `VpnService.Builder.setHttpProxy` (API 29+) announces a proxy
 * to every app that honours the system HTTP proxy, and that proxy is a plain
 * ServerSocket on 127.0.0.1 in this process. The VPN itself carries no user
 * traffic — only a throwaway route exists so `establish()` has an interface —
 * so DNS, UDP, QUIC and apps with their own stacks keep using the real network
 * untouched. Nothing is decrypted: TLS arrives as CONNECT and is piped through
 * byte-for-byte.
 *
 * Consequences worth being upfront about in the UI:
 *  - Plain HTTP is captured fully (method, path, query, headers, body).
 *  - HTTPS for allowlisted hosts can be decrypted with a locally generated CA
 *    that the user installs; every other CONNECT is tunnelled untouched.
 *  - Apps that ignore the system proxy, and UDP protocols, are not seen.
 *  - Android 10+ gets one tap. Older versions run the same proxy but the user
 *    has to point the Wi-Fi proxy at it by hand.
 */
class CaptureVpnService : VpnService() {

    companion object {
        private const val CHANNEL = "nightbell.capture"
        private const val NOTIFICATION_ID = 4101
        const val ACTION_START = "me.river.nightbell.capture.START"
        const val ACTION_STOP = "me.river.nightbell.capture.STOP"

        /** `VpnService.prepare` must be called from an Activity before [start]. */
        fun prepare(context: Context): Intent? = VpnService.prepare(context)

        fun start(context: Context) {
            val intent = Intent(context, CaptureVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, CaptureVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var proxy: CaptureProxyServer? = null
    private var drain: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                shutdown()
                stopSelf()
            }
            ACTION_START, null -> startCapture()
            // An unknown action must not silently rebuild the VPN on top of an
            // already-running capture session.
            else -> Unit
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    /** The user revoked the VPN consent from system settings. */
    override fun onRevoke() {
        shutdown()
        stopSelf()
        super.onRevoke()
    }

    private fun startCapture() {
        // First, before anything slow: a foreground service has seconds to
        // promote itself, and building the proxy and VPN is not guaranteed to
        // finish inside that window on a cold start.
        startForeground(NOTIFICATION_ID, notification())
        CaptureSettings.init(this)
        CaptureLogRepository.init(this)

        val server = try {
            CaptureProxyServer(this, CaptureRepository::record, CaptureRepository::updateResult)
        } catch (e: Exception) {
            CaptureRepository.fail(
                L10n.pick("Could not start the local proxy: ", "无法启动本地代理：") + (e.message ?: ""),
            )
            shutdown()
            stopSelf()
            return
        }
        val port = try {
            server.start()
        } catch (e: Exception) {
            CaptureRepository.fail(
                L10n.pick("Could not bind a local port: ", "无法绑定本地端口：") + (e.message ?: ""),
            )
            shutdown()
            stopSelf()
            return
        }
        proxy = server
        CaptureProxyControl.bind { server.closeOpenTunnels() }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // No setHttpProxy before Android 10. The proxy still runs; the user
            // points the Wi-Fi proxy at 127.0.0.1:port by hand, which the screen
            // tells them how to do.
            CaptureRepository.setRunning(port, manual = true)
            return
        }

        try {
            val builder = Builder()
                .setSession(L10n.pick("Nightbell interface capture", "Nightbell 接口抓包"))
                .addAddress("10.233.0.2", 32)
                // A throwaway route. There is no default route into the TUN, so
                // real traffic never has to be relayed by user-space TCP code.
                .addRoute("10.233.0.0", 30)
                .setMtu(1500)
                .setMetered(false)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", port))
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this,
                        20,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )

            // Nightbell's own checks and the proxy's own upstream sockets must
            // not be routed back into the VPN they are serving.
            runCatching { builder.addDisallowedApplication(packageName) }
            // Keep the VPN network able to resolve names on the local network.
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val dns = runCatching {
                connectivity?.getLinkProperties(connectivity.activeNetwork)?.dnsServers.orEmpty()
            }.getOrDefault(emptyList())
            dns.filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress?.takeIf(String::isNotBlank) }
                .filterNot { it == "127.0.0.1" }
                .take(3)
                .forEach { builder.addDnsServer(it) }

            val descriptor = builder.establish()
                ?: error(L10n.pick("VPN consent was not granted", "未获得 VPN 授权"))
            tun = descriptor
            drain = Thread {
                val stream = runCatching { FileInputStream(descriptor.fileDescriptor) }.getOrNull()
                val buffer = ByteArray(2048)
                try {
                    while (true) {
                        val read = stream?.read(buffer) ?: break
                        if (read < 0) break
                    }
                } catch (_: Exception) {
                } finally {
                    runCatching { stream?.close() }
                }
            }.apply { isDaemon = true; start() }
            CaptureRepository.setRunning(port)
        } catch (e: Exception) {
            CaptureRepository.fail(
                L10n.pick("Could not start capture: ", "无法开启抓包：") + (e.message ?: ""),
            )
            shutdown()
            stopSelf()
        }
    }

    private fun shutdown() {
        CaptureProxyControl.unbind()
        runCatching { proxy?.stop() }
        proxy = null
        runCatching { tun?.close() }
        tun = null
        runCatching { drain?.interrupt() }
        drain = null
        CaptureRepository.setStopped()
        CaptureLogRepository.flush()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    L10n.pick("Interface capture", "接口抓包"),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = L10n.pick(
                        "Shown while interface capture is running",
                        "接口抓包运行中显示",
                    )
                },
            )
        }
        val stop = PendingIntent.getService(
            this,
            21,
            Intent(this, CaptureVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ok)
            .setContentTitle(L10n.pick("Interface capture is on", "接口抓包已开启"))
            .setContentText(
                L10n.pick(
                    "Requests are being listed in Nightbell",
                    "捕获到的接口正在 Nightbell 中列出",
                ),
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    20,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stat_ok, L10n.pick("Stop", "停止"), stop)
            .build()
    }
}
