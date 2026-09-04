package me.river.nightbell

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import me.river.nightbell.data.Nightbell
import me.river.nightbell.ui.NightbellApp
import me.river.nightbell.ui.NightbellSplash

class MainActivity : ComponentActivity() {

    private var pendingMonitorId by mutableStateOf<String?>(null)

    /**
     * Set when the update notice was tapped, cleared once the app has shown it.
     *
     * The notice promises somewhere to install the update from, so it has to
     * land somewhere that offers one. Resuming the task alone was not enough:
     * the app is a single activity, so a tap put the user back on whatever
     * screen they had left it on, and a monitor's detail screen carries no
     * update anything.
     */
    private var pendingUpdate by mutableStateOf(false)

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result surfaced in Settings; nothing to do inline. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        Nightbell.install(applicationContext)
        pendingMonitorId = monitorIdFrom(intent)
        pendingUpdate = wantsUpdateFrom(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            val monitorId = pendingMonitorId
            // The app composes underneath from the first frame and keeps loading
            // while the splash plays, so this covers work that was happening
            // anyway. `rememberSaveable` and not `remember`: a rotation is a
            // recreation, and replaying the animation every time the device turns
            // is the kind of thing that makes a splash hated.
            var splashDone by rememberSaveable { mutableStateOf(false) }
            NightbellApp(
                pagedMonitorId = monitorId,
                onPagedMonitorOpened = { pendingMonitorId = null },
                showUpdate = pendingUpdate,
                onUpdateShown = { pendingUpdate = false },
            )
            if (!splashDone) {
                NightbellSplash(onFinished = { splashDone = true })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingMonitorId = monitorIdFrom(intent)
        pendingUpdate = wantsUpdateFrom(intent)
    }

    /**
     * Whether this intent came from the update notice.
     *
     * Read from the data URI rather than only from the extra, and the notice
     * carries a URI for the same reason widget rows do: `Intent.filterEquals`
     * ignores extras, so an intent that differed from a monitor page only by an
     * extra would look identical to the activity manager, which would bring the
     * task to the front without ever calling [onNewIntent].
     */
    private fun wantsUpdateFrom(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getBooleanExtra(EXTRA_SHOW_UPDATE, false)) return true
        val data = intent.data ?: return false
        return data.scheme == "nightbell" && data.host == "update"
    }

    /**
     * Notifications pass the id as an extra; widget rows use a `nightbell://monitor/<id>`
     * URI, because [android.app.PendingIntent] equality ignores extras and every
     * row would otherwise collapse onto one intent.
     */
    private fun monitorIdFrom(intent: Intent?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_MONITOR_ID)?.takeIf { it.isNotBlank() }?.let { return it }
        val data = intent.data ?: return null
        if (data.scheme != "nightbell" || data.host != "monitor") return null
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
        const val EXTRA_MONITOR_ID = "nightbell.monitor_id"
        const val EXTRA_SHOW_UPDATE = "nightbell.show_update"
    }
}
