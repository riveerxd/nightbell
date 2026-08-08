package me.river.pulse

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import me.river.pulse.data.Nightbell
import me.river.pulse.ui.NightbellApp

class MainActivity : ComponentActivity() {

    private var pendingMonitorId by mutableStateOf<String?>(null)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result surfaced in Settings; nothing to do inline. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        Nightbell.install(applicationContext)
        pendingMonitorId = monitorIdFrom(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            val monitorId = pendingMonitorId
            NightbellApp(initialMonitorId = monitorId)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMonitorId = monitorIdFrom(intent)
    }

    /**
     * Notifications pass the id as an extra; widget rows use a `pulse://monitor/<id>`
     * URI, because [android.app.PendingIntent] equality ignores extras and every
     * row would otherwise collapse onto one intent.
     */
    private fun monitorIdFrom(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_MONITOR_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val data = intent.data ?: return null
        if (data.scheme != "pulse" || data.host != "monitor") return null
        return data.lastPathSegment?.takeIf { it.isNotBlank() }
    }

    /**
     * Asks for notifications on launch — unless the pager-setup screen is about
     * to, in which case this fired the system dialog straight over the top of it
     * before the user had read a word.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        val setupWillAsk = !Nightbell.install(this).store.snapshot.value.settings.hasSeenPagerSetup
        if (setupWillAsk) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_MONITOR_ID = "pulse.monitor_id"
    }
}
