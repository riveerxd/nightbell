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
    namespace = "me.river.pulse"
    compileSdk = 36

    defaultConfig {
        applicationId = "me.river.pulse"
        minSdk = 26
        targetSdk = 36
        // 1.1.0 adds strict foreground monitoring, URGENT mode, latency SLOs,
        // multi-element page monitors and the home-screen widget. applicationId
        // and the DataStore key are unchanged, so 1.0.0 installs update in place
        // and keep their monitors — see PulseStore.migrate.
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
        // state 1.5.0 persisted is scrubbed on read — see PulseStore.migrate.
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
        versionCode = 12
        versionName = "2.1.0"

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
