# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# General
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keepattributes Signature
-keepattributes Exceptions


# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.android.AndroidExceptionPreHandler {
    <init>();
}

# JNA rules are provided by :androidusbflasher's consumer-rules.pro

# Download records are persisted as Gson JSON in SharedPreferences. Field and
# enum names must survive R8 so upgrades can still read old records.
-keep class com.hyntix.android.usbflasher.download.DownloadRecord { *; }
-keep class com.hyntix.android.usbflasher.download.DownloadError { *; }
-keep enum com.hyntix.android.usbflasher.download.DownloadStatus { *; }
-keep enum com.hyntix.android.usbflasher.download.DownloadKind { *; }
-keep enum com.hyntix.android.usbflasher.download.PauseReason { *; }
-keep enum com.hyntix.android.usbflasher.download.DownloadErrorCode { *; }
