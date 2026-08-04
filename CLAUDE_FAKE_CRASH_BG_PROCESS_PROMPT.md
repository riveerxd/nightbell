# Claude Code task: investigate/fix fake checker crash alerts + rebuild Android background monitoring correctly

You are working in the Pulse Android monitoring app repo at `/home/river/Projects/monitoring app`.

## User-reported bug
The phone still vibrates and shows notifications saying something like **"checker crashed"** even when nothing actually crashed. These appear to be fake/stale crash notifications from the background checking pipeline rather than real app/process crashes.

The user suspects the background checking process should be remade to run in the background in a more reliable, real native Android way instead of the current fragile approach.

## Primary goal
Investigate the source of these false **checker crashed** notifications/vibrations, then implement a robust Android-native background monitoring architecture so real checks keep running reliably and alerts only fire for real monitor failures or real unrecoverable checker failures.

## Requirements
1. **Find the exact source of the fake crash notification**
   - Search for all strings/logic related to `checker crashed`, `crashed`, crash notifications, vibration, urgent/repeated notifications, background checker failures, exceptions, workers, schedulers, services, and notification channels.
   - Identify whether the alert is caused by stale state, broad exception handling, worker cancellation, timeout, process death, foreground service lifecycle, DataStore/runtime status recovery, notification reuse, or retry/reschedule logic.

2. **Stop false crash/vibration alerts**
   - Do not show or vibrate **"checker crashed"** unless there is a verified, current, unrecoverable checker failure.
   - Clear stale crash state after app restart, successful check, monitor disable/delete, or scheduler/service replacement.
   - Avoid treating normal Android lifecycle events as crashes: Doze delays, WorkManager cancellation/reschedule, app process recreation, connectivity absence, battery optimization, user swipe-away, or service restart.
   - Make notification IDs/tags/channels deterministic so stale crash notifications can be updated/cancelled reliably.

3. **Redesign background checks the native Android way**
   - Use a robust Android-native architecture for background monitoring:
     - periodic/best-effort checks through WorkManager where appropriate;
     - a user-visible Foreground Service for strict/continuous monitoring mode where Android requires it;
     - proper notification channel(s), foreground notification, lifecycle handling, and cancellation;
     - clean boot/app-update rescheduling if supported by existing permissions/manifests;
     - clear separation between monitor-result alerts and checker-health alerts.
   - Respect modern Android background limits. Do not rely on fragile infinite coroutines from UI scope, hidden background loops, or process-alive assumptions.
   - If exact-interval background checks are impossible under normal Android restrictions, document this honestly in app UI/docs and use foreground service mode for strict behavior.

4. **Preserve app data and compatibility**
   - Do not change release `applicationId` (`me.river.pulse`).
   - Do not break existing DataStore keys/schema without migration.
   - Preserve monitor definitions, settings, runtime history, notification preferences, and widget/settings data where possible.

5. **User experience**
   - The foreground monitoring notification should be understandable and not scary.
   - Crash/health notifications should distinguish:
     - real monitor target failure (site down, selector missing, HTTP error, timeout);
     - degraded/system-limited state (Android delayed background execution, no network, battery saver);
     - real checker internal crash (unexpected exception in checker code).
   - Vibration should only happen for configured urgent real monitor failures or verified real internal checker crash, never for stale or expected scheduling events.

6. **Tests/verification**
   - Add or update unit tests for the checker-health/crash-state logic.
   - Add tests for stale crash state clearing and no false vibration/notification on normal scheduler cancellation/reschedule.
   - Run the relevant Gradle tests and a release/debug assemble task before stopping.
   - If an emulator/device is not available, still run JVM/unit tests and clearly list what needs device verification.

7. **Deliverables**
   - Implement the fix in code, not just a plan.
   - Update docs/HANDOFF if there are Android OS limitations or manual battery optimization steps.
   - Build an APK artifact into the existing `artifacts/` directory with a clear versioned filename if the project supports it.
   - Final response must include:
     - root cause summary;
     - files changed;
     - exact tests/build commands run and results;
     - APK path + SHA-256 if built;
     - any remaining device/manual verification steps.

## Execution instructions
Start working immediately and autonomously. Do not wait for more confirmation. Inspect the repo, implement the best fix, test/build it, and stop only when you have either a verified artifact or a clearly explained blocker.
