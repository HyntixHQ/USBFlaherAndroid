# UniFFI / JNA — keep everything needed at runtime

# Keep ALL classes in the generated UniFFI package
-keep class com.hyntix.lib.androidusbflasher.** { *; }
-keepclassmembers class com.hyntix.lib.androidusbflasher.** { *; }

# JNA core
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# JNA callback interfaces
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.PointerType { *; }