package me.river.pulse

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import me.river.pulse.data.Pulse
import me.river.pulse.domain.ThemeChoice
import me.river.pulse.ui.theme.PulseTheme
import me.river.pulse.ui.urgent.UrgentAlertScreen
import me.river.pulse.ui.urgent.UrgentAlertUi
import me.river.pulse.ui.urgent.UrgentAlertVariant

/**
 * The full-screen-intent target for an URGENT page.
 *
 * This is **not** the primary surface — the heads-up notification is. Android
 * only hands a full-screen intent the screen while the device is locked or the
 * screen is off; unlocked, the system shows the heads-up and this activity is
 * never started. It exists for two reasons:
 *
 *  1. `CallStyle` is demoted to an ordinary notification unless the notification
 *     is tied to a foreground service **or** carries a full-screen intent.
 *  2. A page that arrives while the phone is face-down on a nightstand should
 *     wake the screen, which is the one thing a heads-up cannot do.
 *
 * [showWhenLocked]/[turnScreenOn] are set in code as well as in the manifest so
 * the behaviour is not silently lost if the manifest entry is edited.
 */
class UrgentAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val monitorId = intent?.getStringExtra(EXTRA_MONITOR_ID).orEmpty()
        val ui = UrgentAlertUi(
            monitorName = intent?.getStringExtra(EXTRA_MONITOR_NAME).orEmpty()
                .ifBlank { "A monitor" },
            url = intent?.getStringExtra(EXTRA_URL).orEmpty(),
            headline = intent?.getStringExtra(EXTRA_HEADLINE).orEmpty()
                .ifBlank { "Not responding" },
            downForMs = intent?.getLongExtra(EXTRA_DOWN_FOR_MS, 0L) ?: 0L,
            failedChecks = intent?.getIntExtra(EXTRA_FAILED_CHECKS, 1) ?: 1,
            reminderNumber = intent?.getIntExtra(EXTRA_REMINDER, 0) ?: 0,
            repeatMinutes = intent?.getIntExtra(EXTRA_REPEAT_MINUTES, 5) ?: 5,
        )

        setContent {
            // Pinned to dark on purpose, ignoring the user's theme choice. This
            // is a full-bleed red emergency surface that appears over a lock
            // screen at 3am; it has one appearance so it is recognised instantly,
            // and a light variant of it would be a different thing entirely.
            PulseTheme(motionIntensity = 1f, theme = ThemeChoice.DARK) {
                UrgentAlertScreen(
                    variant = UrgentAlertVariant.BRIEF,
                    ui = ui,
                    onAcknowledge = {
                        val graph = Pulse.install(applicationContext)
                        graph.appScope.launch { graph.engine.acknowledgeUrgent(monitorId) }
                        finish()
                    },
                    onOpen = {
                        startActivity(
                            Intent(this, MainActivity::class.java).apply {
                                action = Intent.ACTION_VIEW
                                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                                if (monitorId.isNotBlank()) {
                                    putExtra(MainActivity.EXTRA_MONITOR_ID, monitorId)
                                }
                            },
                        )
                        finish()
                    },
                    onRecheck = {
                        val graph = Pulse.install(applicationContext)
                        graph.appScope.launch { graph.engine.run(monitorId) }
                        finish()
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    companion object {
        const val EXTRA_MONITOR_ID = "monitor_id"
        const val EXTRA_MONITOR_NAME = "monitor_name"
        const val EXTRA_URL = "url"
        const val EXTRA_HEADLINE = "headline"
        const val EXTRA_DOWN_FOR_MS = "down_for_ms"
        const val EXTRA_FAILED_CHECKS = "failed_checks"
        const val EXTRA_REMINDER = "reminder"
        const val EXTRA_REPEAT_MINUTES = "repeat_minutes"
    }
}
