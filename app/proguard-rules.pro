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
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-dontwarn *
-dontobfuscate
-dontoptimize

# The legacy Xposed/LSPosed entry point.
#
# R8 cannot see this class. Its only reference anywhere in the APK is the class name
# written in assets/xposed_init - a resource file the shrinker does not read - so from
# R8's point of view nothing refers to it, and shrinking removes it along with everything
# reachable only through it: the whole injected runtime. The APK then still installs,
# still declares xposedmodule=true, and still names the class in assets/xposed_init, so
# the failure is invisible to every build, every gate and every installer inspection, and
# LSPosed reports a module it cannot instantiate.
#
# Keeping the class as a root restores the runtime by reachability: the feature registry
# holds direct class references rather than names looked up at runtime, so nothing inside
# the injected code needs a keep rule of its own.
-keep class com.wax.module.ModuleEntryPoint { *; }

# (R fields are accessed and rewritten via reflection)
-keep class com.wax.module.R { *; }
-keep class com.wax.module.R$* { *; }
-keepclassmembers class com.wax.module.R$* {
     public static <fields>;
}

-keepclasseswithmembers class com.wmods.** {
     *;
}

-keepclasseswithmembernames class com.wmods.**

-keepclasseswithmembers class cz.vutbr.** {
     *;
}

-keepclasseswithmembers class com.assemblyai.api.** {
     *;
}