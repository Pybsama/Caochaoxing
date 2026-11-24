# SPDX-License-Identifier: AGPL-3.0-only
# Copyright (c) 2026 Pybsama.

# Keep all application, protocol core, serialization, JNA, OkHttp, and Skiko classes intact.
-dontobfuscate
-dontoptimize
-ignorewarnings

-keep class com.caochaoxing.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class kotlinx.serialization.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.zxing.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    public static ** Companion;
}
