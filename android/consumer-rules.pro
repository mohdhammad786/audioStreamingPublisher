# HaishinKit 0.17.0 — keep all library classes
# R8 strips/obfuscates MediaCodec.Callback implementations,
# codec startRunning/stopRunning, and muxer output handlers,
# causing silent audio encoding failure (no data → server timeout).
-keep class com.haishinkit.** { *; }
-keepclassmembers class com.haishinkit.** { *; }
-dontwarn com.haishinkit.**
