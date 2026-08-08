package me.river.nightbell.data.transfer

import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MonitorRuntime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The whole store as one portable JSON document, so a user can carry their
 * monitors between two installs of Nightbell.
 *
 * This was written for 2.0.0's `applicationId` change, which on Android is not a
 * rename. The identifier *is* the app: it names `/data/data/<id>/` and it is what
 * the installer matches to decide "update" against "new app". 2.0.0 therefore
 * installed **alongside** the older build, with its own empty data directory, and
 * no key, manifest or Gradle setting changes that. One app cannot read another's
 * files either, so the only path that carried a fleet across was one the user
 * drove by hand: export from the old install, import into the new one.
 *
 * That migration is done, and this stays as an ordinary feature — moving to a new
 * phone, or keeping a copy before a risky edit.
 *
 * The format is the store's own [NightbellSnapshot] inside a thin envelope rather
 * than a bespoke schema. That is deliberate — `NightbellSnapshot` already decodes
 * with `ignoreUnknownKeys` and has a default for everything added since 1.0.0,
 * so a file written here imports into a later build for free, and an export
 * cannot drift away from what the app actually stores.
 */
@Serializable
data class NightbellBackup(
    /** Bumped only for a change this build could not read. See [BackupCodec.decode]. */
    val format: Int = FORMAT_VERSION,
    /**
     * The `applicationId` that wrote the file.
     *
     * Recorded for the human opening it in a text editor and for support: after
     * the rename there will be files from two different package names in
     * circulation, and "which app made this" stops being obvious.
     */
    val app: String = "",
    val versionName: String = "",
    val versionCode: Int = 0,
    val exportedAt: Long = 0L,
    /**
     * Redundant with `snapshot.monitors.size`, and worth the duplication: it lets
     * a human reading the file, or a future importer that cannot decode the
     * snapshot, still say how much is in it.
     */
    val monitorCount: Int = 0,
    val snapshot: NightbellSnapshot = NightbellSnapshot(),
) {
    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** Why an import did not happen, in words that can go straight into a toast. */
sealed interface BackupError {
    val message: String

    /** Not JSON, or not JSON shaped like a backup. */
    data object Unreadable : BackupError {
        override val message: String = "That file isn't a Nightbell backup"
    }

    /** Written by a build that knows a format this one does not. */
    data class TooNew(val format: Int) : BackupError {
        override val message: String =
            "That backup was written by a newer version of Nightbell (format $format)"
    }

    /** Readable, valid, and contains nothing to import. */
    data object Empty : BackupError {
        override val message: String = "That backup has no monitors in it"
    }
}

/**
 * Reads and writes [NightbellBackup] documents.
 *
 * Pure and free of Android, so the format is covered by JVM tests rather than by
 * an instrumented one.
 */
object BackupCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** Strict on the way in: a half-decoded backup is worse than a rejected one. */
    private val strict = Json { ignoreUnknownKeys = true }

    fun encode(
        snapshot: NightbellSnapshot,
        applicationId: String,
        versionName: String,
        versionCode: Int,
        nowMs: Long,
    ): String = json.encodeToString(
        NightbellBackup(
            app = applicationId,
            versionName = versionName,
            versionCode = versionCode,
            exportedAt = nowMs,
            monitorCount = snapshot.monitors.size,
            snapshot = snapshot,
        ),
    )

    /**
     * Parses a backup, or says why it could not.
     *
     * A newer `format` is refused rather than best-efforted. The envelope is
     * versioned precisely so that a future change which *cannot* be read by this
     * build has a way to say so, and silently importing a file whose meaning has
     * changed would defeat the point of having the field at all.
     */
    fun decode(raw: String): Result<NightbellBackup> {
        val backup = runCatching { strict.decodeFromString<NightbellBackup>(raw) }
            .getOrElse { return Result.failure(BackupFailure(BackupError.Unreadable)) }
        if (backup.format > NightbellBackup.FORMAT_VERSION) {
            return Result.failure(BackupFailure(BackupError.TooNew(backup.format)))
        }
        // A file that parses but carries nothing is far more likely to be some
        // other app's JSON than an empty fleet somebody meant to move.
        if (backup.snapshot.monitors.isEmpty()) {
            return Result.failure(BackupFailure(BackupError.Empty))
        }
        return Result.success(backup)
    }

    /** Carries a [BackupError] out through [Result]. */
    class BackupFailure(val error: BackupError) : Exception(error.message)
}

/**
 * The snapshot as it should land in a fresh install.
 *
 * Monitors, settings, mute windows and observed history carry over verbatim.
 * What does not carry over is every field that is *bookkeeping about
 * notifications already posted*, because none of it is true here: the shade is
 * empty, no outage has been announced on this install, and nothing has been
 * acknowledged on it.
 *
 * That is not tidiness, it is the same pair of traps [
 * me.river.nightbell.domain.LegacyCrashRepair] exists to undo. The down
 * track is transition-driven, so an imported `alerting = true` suppresses the
 * first genuine down alert for the whole outage; and `urgentAcknowledged`
 * silences the urgent track until a *successful* check, which never arrives
 * while a site is actually down.
 *
 * Health resets to UNKNOWN rather than to whatever the old device last saw —
 * nothing has been checked here, and UP would be a claim this install cannot
 * support. `lastCheckedAt` goes with it so every monitor reads as due
 * immediately and the import is followed by a real pass instead of a screen of
 * stale verdicts. The last-check verdict fields are cleared for the same reason:
 * with health UNKNOWN they would be a message with nothing behind it.
 *
 * Two things are dropped rather than reset. `checkerStreak` is evidence about a
 * checker process that no longer exists, and the latency `reference` window is a
 * measurement of one device's connection; neither says anything about the user's
 * monitors, which is all a backup is for.
 */
fun NightbellBackup.toImportableSnapshot(): NightbellSnapshot {
    val monitors = snapshot.monitors.map { it.migrated }
    val paused = monitors.filterNot { it.enabled }.map { it.id }.toSet()
    val runtimes = snapshot.runtimes
        // A runtime with no monitor is unreachable weight; the old install may
        // have had one if the file was written mid-delete.
        .filterKeys { id -> monitors.any { it.id == id } }
        .mapValues { (id, runtime) -> runtime.forFreshInstall(id in paused) }
    return NightbellSnapshot(
        monitors = monitors,
        runtimes = runtimes,
        settings = snapshot.settings,
    )
}

private fun MonitorRuntime.forFreshInstall(paused: Boolean): MonitorRuntime = copy(
    health = if (paused) Health.PAUSED else Health.UNKNOWN,
    lastCheckedAt = 0L,
    lastLatencyMs = 0L,
    lastCode = 0,
    lastMessage = "",
    lastDetail = "",
    consecutiveFailures = 0,
    consecutiveSuccesses = 0,
    lastAlertAt = 0L,
    alerting = false,
    // Cleared, not carried: the first check here establishes what the element
    // says, rather than comparing against what it said on another install.
    lastElementText = "",
    lastElementTexts = emptyList(),
    lastNetworkExcessMs = 0L,
    lastLatencySuspect = false,
    degradedAlerting = false,
    lastDegradedAlertAt = 0L,
    urgentActive = false,
    urgentAcknowledged = false,
    lastUrgentAlertAt = 0L,
    // mutedUntil and samples survive: a mute is a standing instruction with an
    // absolute deadline, and the samples are observations that really happened.
)
