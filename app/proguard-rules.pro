# Keep the JavaScript bridge used by the website-element picker + checker.
-keepclassmembers class me.river.pulse.data.web.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class kotlinx.serialization.** { *; }
-keepattributes *Annotation*, InnerClasses
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
