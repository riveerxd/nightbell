# =============================================================================
# Pulse R8 configuration
#
# Release is minified AND resource-shrunk. Everything below exists because R8
# cannot see the reference: reflection, JS-bridge dispatch by name, or state
# persisted outside the APK.
#
# If you add a class only ever reached reflectively — a new Worker, a new
# @JavascriptInterface bridge, a new @Serializable model — check it survives:
#   ./gradlew :app:assembleReleaseTest && install and exercise it on a device.
# =============================================================================

# --- WebView JavaScript bridge ----------------------------------------------
# The picker's bridge is called *from JavaScript* by method name, so R8 has no
# reference to keep it alive. The 1.0.0 rule only matched `data.web.**`, but the
# only real bridge (PickerBridge) lives in `ui.setup` — this is deliberately
# package-agnostic so a bridge added anywhere keeps working.
-keepclasseswithmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# --- kotlinx.serialization ---------------------------------------------------
# The compiler plugin generates a `Companion.serializer()` / `$serializer` pair
# per @Serializable class and resolves them reflectively at the entry point.
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations, AnnotationDefault
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Every serialisable model in the app, plus its generated serializer.
-keep,includedescriptorclasses class me.river.pulse.**$$serializer { *; }
-keepclassmembers class me.river.pulse.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class me.river.pulse.** {
    <fields>;
    <init>(...);
}
# Enum constants are matched by name when decoding @SerialName values.
-keepclassmembers enum me.river.pulse.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- WorkManager -------------------------------------------------------------
# Workers are instantiated by fully-qualified class name from the WorkManager
# database, which survives an app update — so an obfuscated Worker breaks
# schedules persisted by the *previous* build, not just this one.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class me.river.pulse.data.work.MonitorWorker { *; }
-keep class me.river.pulse.data.work.SweepWorker { *; }

# --- Manifest-declared components -------------------------------------------
# AGP generates keep rules from the merged manifest, so most of this is belt and
# braces — but the widget provider is *also* referenced by an
# AppWidgetProviderInfo the launcher persists outside our APK, and a stale
# launcher entry pointing at a renamed class silently blanks the widget.
-keep class me.river.pulse.MainActivity { *; }
-keep class me.river.pulse.PulseApplication { *; }
-keep class me.river.pulse.widget.PulseWidgetProvider { *; }
-keep class me.river.pulse.widget.WidgetConfigActivity { *; }
-keep class me.river.pulse.data.work.PulseMonitorService { *; }
-keep class me.river.pulse.data.work.BootReceiver { *; }
-keep class me.river.pulse.data.alerts.AlertActionReceiver { *; }

# --- OkHttp / Okio -----------------------------------------------------------
# Optional platform integrations OkHttp probes for at runtime.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- Compose -----------------------------------------------------------------
# Compose ships its own consumer rules; this only silences the desktop-only
# classes the artifacts reference but never load on Android.
-dontwarn androidx.compose.**

# --- Crash readability -------------------------------------------------------
# Keep line numbers, hide the original file name. Deobfuscate a stack trace with
# app/build/outputs/mapping/release/mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
