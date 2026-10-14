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

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# The Rust core is reached through JNA: UniFFI's generated classes, their
# callback interfaces and JNA itself are looked up by name at runtime.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.** { *; }
-keep class dev.nexfy.core.** { *; }
-dontwarn java.awt.**

# WorkManager (pulled in by Glance) builds its Room database from the
# generated *_Impl class found by name; R8 strips it otherwise.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# WorkManager builds its input mergers and workers by reflection (Glance
# widgets render through a worker): without their constructors no widget
# ever leaves its loading layout.
-keep class * extends androidx.work.InputMerger { <init>(); }
-keep class * extends androidx.work.ListenableWorker { <init>(android.content.Context, androidx.work.WorkerParameters); }
