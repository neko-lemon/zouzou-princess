# ProGuard rules for Zouzou Princess App
# WebView related
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the target URL from being obfuscated
-keep class com.zouzou.princess.MainActivity { *; }
