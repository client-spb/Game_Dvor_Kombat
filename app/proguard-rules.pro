# Правила для уменьшенного релизного APK

# Yandex Mobile Ads SDK — рефлексия внутри SDK, нельзя минифицировать классы
-keep class com.yandex.mobile.ads.** { *; }
-dontwarn com.yandex.mobile.ads.**

# WebView JS-мост: методы AndroidBridge вызываются из JavaScript по имени — имена должны остаться
-keepclassmembers class mob.dev.game_dvor_kombat.**Bridge* {
    public *;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# Compose/lifecycle — стандартные отступления не нужны, но на всякий случай:
-dontwarn androidx.**
