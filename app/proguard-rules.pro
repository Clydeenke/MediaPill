# Default ProGuard rules for Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable

# Xposed entry points - keep them
-keep class com.clydeenke.mediapill.xposed.** { *; }
-keep class de.robv.android.xposed.** { *; }
-keep class io.github.libxposed.api.** { *; }

# Keep module scope
-keepclassmembers class * implements de.robv.android.xposed.IXposedHookLoadPackage {
    public *;
}

# Don't warn on Xposed framework references
-dontwarn de.robv.android.xposed.**
-dontwarn io.github.libxposed.api.**
