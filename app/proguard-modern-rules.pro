# Framework instantiates the module through META-INF/xposed/java_init.list.
# Keep the name and default constructor and ensure resource paths are not rewritten.
-keep class com.wax.module.modern.ModernXposedEntry { public <init>(); *; }
-keep class com.wax.module.modern.ModernHookBridge { *; }
-keep class com.wax.module.modern.ModernTargetPolicy { *; }
# R8 has no -keepresourcefiles option; APK metadata integrity is checked after packaging.
