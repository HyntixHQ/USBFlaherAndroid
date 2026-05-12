# USBFlasherAndroid

## Project Overview
This project is a hybrid application blending a modern Android Compose UI with a high-performance Rust core for low-level USB and ISO manipulation. 

## Project State (v1.0.2)
- **Minimum SDK**: 33 (Android 13)
- **NDK**: r28+ (16KB page support)
- **AGP**: 9.0+ (Built-in Kotlin support)
- **R8**: Full Mode enabled for maximum optimization.
- **Target**: ARM64 only (`aarch64-linux-android`).

### Architecture
- **Android App (`app/`)**: Built with Jetpack Compose and Kotlin (MVI architecture).
- **JNI Bridge (`androidusbflasher/`)**: Kotlin side of the JNI bridge, automatically generated using `uniffi-rs`.
- **Rust Core (`hyntix-usb-flasher`, `hyntix-usb`)**: Heavy-lifting modules responsible for SCSI command generation (BOT protocol), triple-buffered asynchronous I/O, and concurrent hashing.
- **Windows Deployment (`hyntix-windows`, `hyntix-wim`, `hyntix-fat32`)**: Specialized crates for UDF reading, FAT32 filesystem creation, and intelligent WIM/SWM splitting.

*File updated by Antigravity (v1.0.2).*

