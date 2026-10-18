# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve YoutubeDL and FFmpeg native bindings and reflection
-keep class com.yausername.** { *; }
-keep interface com.yausername.** { *; }
-dontwarn com.yausername.**

# Preserve JNI methods
-keepclasseswithmembernames class * {
    native <methods>;
}
