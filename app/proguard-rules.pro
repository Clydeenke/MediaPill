-dontwarn io.github.libxposed.annotation.**
-dontwarn androidx.window.**
-adaptresourcefilecontents META-INF/xposed/java_init.list

# Xposed entry points
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}
-keep class com.clydeenke.mediapill.xposed.PillHookEntry {
    public <init>();
}
-keep class com.clydeenke.mediapill.config.RemotePrefProvider {
    public <init>();
}
-keep class com.clydeenke.mediapill.config.Config { *; }
-keep class com.clydeenke.mediapill.config.Config$* { *; }

# Stage 2: 保留所有 xposed 包中的类（反射 + 跨类加载器调用）
-keep class com.clydeenke.mediapill.xposed.** { *; }
