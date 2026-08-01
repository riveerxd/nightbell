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
        // 1.3.0 stops checking entirely while the device has no connectivity —
        // losing signal is not an outage, and reporting it as one was spamming
        // real users. Both are UI/behaviour only; the store schema is untouched,
        // so 1.1.x and 1.2.x installs update in place.
        versionCode = 7
        versionName = "1.4.0"

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
