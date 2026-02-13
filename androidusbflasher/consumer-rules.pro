# UniFFI / JNA — keep everything needed at runtime
# These rules are consumed by the app module via consumerProguardFiles

# Keep ALL classes in the generated UniFFI package (native methods, callbacks, structures)
-keep class com.hyntix.lib.androidusbflasher.** { *; }
-keepclassmembers class com.hyntix.lib.androidusbflasher.** { *; }

# JNA core — R8 must not touch these
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# JNA callback interfaces (UniFFI uses these heavily)
-keep class * implements com.sun.jna.Callback { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * extends com.sun.jna.PointerType { *; }
