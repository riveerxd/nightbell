import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "me.river.nightbell"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.river.nightbell"
        minSdk = 26
        targetSdk = 36
        // 1.1.0 adds strict foreground monitoring, URGENT mode, latency SLOs,
        // multi-element page monitors and the home-screen widget. applicationId
        // and the DataStore key are unchanged, so 1.0.0 installs update in place
        // and keep their monitors — see NightbellStore.migrate.
        // 1.1.2 fixes alert notifications that could outlive the outage they
        // described — reproduced on a real device, see HANDOFF. Includes a
        // one-time repair for stale notifications left by 1.1.0/1.1.1.
        // 1.2.0 replaces the dashboard's uptime dial with the fleet banner: the
        // top of the screen now takes the worst monitor's colour, and the card
        // sparklines carry failures in the stroke instead of as dots.
        // 1.4.0 shows the site favicon on page-element cards (cached in memory
        // and on disk, with ICO unwrapping) instead of a generic cursor glyph.
        // 1.5.0 times a known-good endpoint alongside the checks and discounts
        // whatever the phone's own connection is adding, so bad wifi no longer
        // reports every monitor as slow at once.
        // 1.3.0 stops checking entirely while the device has no connectivity —
        // losing signal is not an outage, and reporting it as one was spamming
        // real users. Both are UI/behaviour only; the store schema is untouched,
        // so 1.1.x and 1.2.x installs update in place.
        // 1.6.0 stops reporting cancelled checks as crashed ones. A coroutine
        // cancellation — WorkManager replacing unique work, a foreground service
        // stopping, a screen going away — was caught by `catch (Throwable)`,
        // turned into a failed check called "Checker crashed", and escalated
        // through the down track into the URGENT nag loop. Reproduced from a real
        // device: six simultaneous ongoing DND-bypassing "URGENT · … is down"
        // notifications for six monitors that had all just passed. Background
        // scheduling is rebuilt on periodic work with UPDATE so nothing cancels a
        // check in flight in the first place, and checker faults now have their
        // own track and channel. Store schema untouched; the fabricated runtime
        // state 1.5.0 persisted is scrubbed on read — see NightbellStore.migrate.
        // 1.6.0 also makes a placed widget's settings reachable again — a cog in
        // the widget, `widgetFeatures="reconfigurable"`, and a list in Settings —
        // and adds custom background/text colours with a background-opacity
        // slider that goes all the way to fully transparent. Widget configs are
        // forward-compatible: every new field defaults, so widgets placed by 1.5.0
        // keep their exact look.
        // 1.7.0 adds export and import in Settings: the whole store as one JSON
        // file the user picks the destination for. Shipped ahead of 2.0.0 on
        // purpose — see below.
        // 2.0.0 sets applicationId to me.river.pulse. This is the one release in
        // the list that does NOT update an earlier install: Android identifies an
        // app by its applicationId, so a build carrying a different one installs
        // alongside the old app with an empty data directory, and no signing key
        // or manifest setting changes that. The only route across is 1.7.0's
        // export/import, driven by hand. Placed widgets do not survive either —
        // a launcher stores the provider as a fully-qualified ComponentName.
        // The store itself is untouched: the DataStore name, its key and
        // SCHEMA_VERSION were never derived from the package, so an imported
        // snapshot lands in exactly the shape it left.
        // 2.1.0 makes URGENT actually page. Up to 2.0.0 it posted an ordinary
        // HIGH-importance notification: no screen wake, nothing on a locked phone
        // beyond a normal row, one chime and then minutes of silence, and — on any
        // install that had ever run 1.1.0 — no Do Not Disturb bypass at all,
        // because a channel's importance and DND flags are frozen at creation and
        // the id had never changed. The page is now the foreground service's own
        // notification (the only place Android honours `setColorized`, so the only
        // place the card is red), it loops an alarm-stream sound until
        // acknowledged, it carries Ack / Re-check / Mute-1h, and it escalates to a
        // full-screen alert on a locked device. Four paging bugs went with it: the
        // repeat loop and the reconciliation sweep now also run from `SweepWorker`
        // instead of only inside a service that Android often refuses to start
        // from the background; a repeat re-checks rather than re-asserting a
        // verdict up to a quarter of an hour old; pausing a monitor ends its page
        // instead of leaving an un-dismissable one; and `sync()` no longer stops a
        // service that has not yet promoted itself, which was killing the process
        // with ForegroundServiceDidNotStartInTimeException.
        // 2.2.0 does two things 2.1.0 left undone. URGENT pages now follow the
        // ringer switch: sound and haptics on Normal, haptics only on Vibrate and
        // Silent. 2.1.0 looped on the alarm stream, which the platform exempts
        // from the ringer on purpose — correct for an alarm clock, wrong here, so
        // a phone set to vibrate got a full-volume siren. The channel had to be
        // versioned by stream for the same reason as before: its audio attributes
        // are frozen at creation, so the ringer-respecting variant is a separate
        // id. Off in Settings restores the alarm-stream behaviour for anyone who
        // wants a pager that answers to nothing.
        //
        // And there is now a setup screen in front of the dashboard on a fresh
        // install, because four separate grants across three settings sections is
        // not something anyone should have to be told where to find. Two of them
        // (notifications, Doze exemption) are asked for with a system dialog in
        // place; the other two are "special app access" toggles that Android
        // exposes no API for, so the screen deep-links straight to each one and
        // re-checks on resume so it advances by itself. It gates once and is
        // skippable — a monitoring app that will not show you your monitors until
        // you have flipped four toggles is worse than one with a degraded pager.
        // 2.2.1 fixes the pager-setup screen's primary button, which built its
        // label by lowercasing the row title and so read "Set up get through do
        // not disturb". Each step now has its own wording, and it says whether the
        // tap opens a dialog ("Allow …") or leaves for Settings ("Open …") — which
        // is the difference between one tap and a round trip.
        // 2.2.2 makes acknowledging instant. The service loop is the only thing
        // that stops the looping alarm and re-renders the page, and after a page it
        // slept on a plain `delay` for `nextWakeDelayMs()` — floored at 15s, capped
        // at 60s. So an ack cancelled the notification and persisted the state
        // immediately, then the phone kept vibrating for up to a minute. `sync()`
        // could not help: it re-delivers `onStartCommand`, which sees the loop
        // already running and returns. The sleep is now interruptible by a
        // conflated wake signal, the alarm is a single instance shared through the
        // graph so an ack can silence it directly, and both are driven from
        // `notifyStateChanged` — so acknowledge, mute and recovery are all felt at
        // once. Regression test asserts under two seconds and was confirmed to
        // fail against the old code.
        // 3.0.0 renames the app to Nightbell and moves applicationId to
        // me.river.nightbell. Second in the list that does NOT update an earlier
        // install, for exactly the reason 2.0.0 did not: a different applicationId
        // is a different app. A 2.x install stays where it is, side by side, and
        // the only route across is export/import driven by hand. Placed widgets do
        // not survive, because a launcher stores the provider as a fully-qualified
        // ComponentName.
        //
        // Pulse was not a defensible name. Pulse Pager and Pulse UpTime both ship
        // uptime monitoring under the same word, with two more alongside them, so
        // the brand term was unwinnable before a line of it was written.
        //
        // Because the package moves anyway, the persisted identifiers moved with
        // it: the DataStore names, the notification channel ids, the WorkManager
        // unique names and the backup filename prefix are all `nightbell.*` now.
        // Those were frozen through the 2.5.0 rename precisely because they would
        // have orphaned a live install's channels and monitors. A new package has
        // no live install to orphan, so keeping `pulse.*` inside an app called
        // Nightbell would have been carrying the cost of a migration nobody gets.
        // Backups written by 2.x still import: the reader validates the JSON
        // envelope's `format`, never the filename.
        // 3.0.1 is signed with a new key. The certificate subject read
        // CN=Pulse Monitor, and a DN cannot be edited without issuing a new
        // certificate, so the only way to make it say Nightbell was a new key.
        // Android refuses an update signed by a different key than the installed
        // build, so a 3.0.0 install cannot update to this: it has to be
        // uninstalled and reinstalled. That cost was paid deliberately and now,
        // an hour after 3.0.0 went out to nobody, rather than later at any scale.
        // The old key is archived at keystore/pulse-legacy.jks; it still verifies
        // every release up to and including 3.0.0.
        // 3.0.2 fixes the icon and adds the cold-start animation. The widget
        // header and the status-bar glyph had shipped as solid bells since 3.0.0,
        // on a claim that the cutout was "under two pixels" at that size. That was
        // the dp figure read as pixels: the slot is 2px at xhdpi and 3px at
        // xxhdpi, which is a legible line, and the two solid tiles simply
        // disagreed with the launcher icon beside them. All six drawables carry
        // the trace now, with the 24dp canvases widening it by a third so the hole
        // survives 1x and the antialiasing of a very small render.
        //
        // Same signing key as 3.0.1, so this updates in place.
        // 3.0.3 changes no code at all. It exists so F-Droid can reproduce the
        // build and therefore publish the APK under this key rather than theirs.
        //
        // F-Droid's verification builds from source in their container and then
        // compares the result, byte for byte, with the APK attached to the GitHub
        // release. Against 3.0.2 that comparison failed on two files:
        // assets/dexopt/baseline.prof and classes.dex. One cause, not two. Their
        // container runs openjdk 21.0.12+8 and 3.0.2 was built here on 21.0.11+10,
        // and a different JDK gives R8 a different dex; the baseline profile is
        // compiled from app/src/main/baselineProfiles/*.txt into dex method
        // indices, so once the dex moves the profile moves with it.
        //
        // So this release is 3.0.2's tree built on 21.0.12+8. There is nothing to
        // read in the diff between the two tags except this comment and the
        // version, which is the point: if the dex still differs after matching the
        // JDK, the remaining cause is somewhere other than the JDK.
        //
        // Keep this in mind when cutting future releases. The published APK has to
        // come from a JDK matching F-Droid's buildserver or verification breaks and
        // the app silently falls back to being signed with their key.
        //
        // Same signing key again, so 3.0.2 updates in place.
        // 3.0.4 drops the Play dependency-metadata blob out of the APK signing
        // block. See dependenciesInfo below for what it was and why it had to go.
        //
        // It is a separate release rather than a rebuild of 3.0.3 because that blob
        // lives or dies by a build setting, and the setting is source. F-Droid
        // builds the tag, so a tag without dependenciesInfo produces an APK with
        // the blob no matter what is attached to the release, and the comparison
        // fails. Moving the v3.0.3 tag instead would have broken the other half of
        // this: the APK records the revision it was built at, and that revision has
        // to be the tag's own commit.
        //
        // Which is the general shape of releasing this app now. Three things have
        // to line up or F-Droid quietly falls back to signing with their key:
        // the JDK matches their buildserver, the version bump is committed before
        // the APK is built, and the APK carries no signing block beyond the
        // signature itself.
        // 3.0.5 answers a security review of 3.0.4 on the F-Droid merge request.
        // Its verdict was pass with recommendations, so none of this was a defect,
        // but three of the four were worth doing and the fourth was worth writing
        // down rather than changing.
        //
        // The cleartext policy moved out of the manifest. usesCleartextTraffic was a
        // single boolean saying "anything goes"; res/xml/network_security_config.xml
        // says the same thing for user-entered hosts, which it has to, and pins
        // www.gstatic.com to HTTPS because that probe is the app's own traffic
        // rather than the user's. The reviewer asked for cleartext to be scoped to
        // user-configured domains, which cannot be done: the file is compiled at
        // build time and the domains are typed later, with no runtime API to add
        // one. That is argued in the file.
        //
        // The element picker's WebView closed doors it never used. allowFileAccess
        // is the one that mattered, because it defaults to true below API 30 and
        // minSdk here is 26, so on API 26 to 29 a page loaded from a user's URL
        // could reach file:// with script enabled and a bridge attached.
        //
        // R8 now drops Log.d and Log.v. Log.i, w and e stay, because a release with
        // no log cannot be diagnosed from a bug report.
        //
        // BootReceiver was left alone. It already returns unless the action is
        // BOOT_COMPLETED or MY_PACKAGE_REPLACED, and directBootAware stays false on
        // purpose: the store it needs is credential-encrypted, so a direct-boot
        // receiver would run before it could be read.
        // 3.1.0 adds a pause and a SOCKS5 route, and fixes three things the first
        // people to file issues found.
        //
        // The pause is a dashboard button that stops the whole fleet for a chosen
        // stretch, or until it is turned back on. 1.3.0 already stopped checking
        // while the device had no connectivity, and that turns out to cover the
        // wrong half of the problem: one bar of signal counts as online, so every
        // check times out at once and every one of those alerts is about the walk
        // rather than the services. The user picks what a pause stops. Stopping the
        // checks keeps false samples out of the uptime history; staying silent
        // keeps the dashboard live and only holds the alerts. A pause is felt as
        // the master alert switch being off, so no track can page through one, and
        // `force` still gets through it because a pause stops the schedule and is
        // not a lock on the app.
        //
        // SOCKS5 routing is per monitor, with the address in Settings and an
        // override on the monitor, because Tor listens on 9050 and I2P's SOCKS
        // proxy on 4447 and one address cannot serve both. OkHttp builds an
        // unresolved socket address for a SOCKS route, so the hostname is resolved
        // at the proxy rather than here, which is the only reason an .onion address
        // resolves at all. Page-element monitors are routed too: ProxyConfig takes
        // a SOCKS scheme, contrary to a claim this app briefly made in its own UI,
        // and the override is process-wide so those loads are serialised.
        //
        // Routing fails closed. A monitor that asked for a proxy and has no usable
        // address fails the check instead of going out directly, because the
        // alternative publishes the hostname the proxy existed to hide.
        //
        // The three issues: VibrationAttributes was built above the branch that
        // used it, which is API 30 against a minSdk of 26 and so a hard crash on
        // Android 10 and below. Connections that died before answering, whether a
        // reaped keep-alive or a fresh one that broke mid-handshake on a poor link,
        // were reported as outages; each now gets one retry, and only for methods
        // that are safe to repeat. Importing a backup ran the whole check pass
        // before reporting anything, which read as a frozen screen.
        //
        // 3.1.1 finishes the routing that 3.1.0 left half done. The element picker
        // loaded its page outside the proxy, so setting a monitor up on a hidden
        // service published that hostname to this device's own resolver at pick
        // time, before the first check ran. The picker now takes the monitor's
        // route, holds the process-wide override for as long as it is open, and
        // refuses to open rather than loading a hidden service directly. Settings
        // claimed the opposite of all of it and no longer does, and the routing
        // switch renders on every setup screen that offers Test rather than two
        // screens past the first one that does.
        versionCode = 29
        versionName = "3.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        // A minified build that is still debuggable and still signed with the
        // debug key, so the R8 configuration can be smoke-tested on a device
        // without swapping the installed release APK.
        create("releaseTest") {
            initWith(getByName("release"))
            applicationIdSuffix = ".minified"
            versionNameSuffix = "-minified"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll("-opt-in=kotlin.time.ExperimentalTime")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // AGP puts a "Dependency metadata" blob in the APK signing block by default,
    // 6,486 bytes of it here, so that Play can report on the libraries a build
    // pulled in. It is a Play feature and this app is not on Play.
    //
    // It also fails F-Droid's APK scan outright: `found extra signing block
    // 'Dependency metadata'`, raised as CRITICAL, because a signing block that no
    // tool outside Google can read is a blob nobody reviewing the app can account
    // for. The scan runs on the published APK, so turning this off is not optional
    // for a release that is meant to be distributed there.
    //
    // The same argument covers the bundle, which this project does not build.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    sourceSets {
        getByName("main") { java.srcDirs("src/main/kotlin") }
        // testShared holds helpers (e.g. TinyHttpServer) used by both the JVM
        // unit tests and the on-device instrumentation tests.
        getByName("test") { java.srcDirs("src/test/kotlin", "src/testShared/kotlin") }
        getByName("androidTest") { java.srcDirs("src/androidTest/kotlin", "src/testShared/kotlin") }
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.testLogging { events("passed", "failed", "skipped") } }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // Installs app/src/main/baselineProfiles/*.txt on API 28–30, where the
    // platform installer does not read them from the APK by itself.
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    // ProxyController, so a page-element check can be routed through SOCKS5 too.
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
