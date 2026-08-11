# Xposed entry point
-keep class com.hamer.dockshortcut.HookInit { *; }

# Status check class accessed via reflection/hook
-keep class com.hamer.dockshortcut.XposedStatus { *; }

# Keep Xposed API (optional as it's compileOnly, but good for completeness)
-keep class de.robv.android.xposed.** { *; }
-dontwarn de.robv.android.xposed.**
