# ⚡ Hyntix USB Flasher

[![License](https://img.shields.io/badge/License-AGPL%20v3-red.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![Rust](https://img.shields.io/badge/Language-Rust-black?logo=rust&logoColor=white)](https://www.rust-lang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

A high-performance, rootless, open-source USB flashing utility for Android. Enables creating bootable Linux and Windows drives directly from your device.

---

## ✨ Key Features

- **Direct SCSI Engine**: Rust core using `USBDEVFS_BULK` synchronous ioctl — kernel manages DMA internally, bypassing the `usbfs_memory_mb` userspace pool limit.
- **Adaptive AIMD Flow Control**: Chunk size halves on ENOMEM, additively recovers after 200 cycles, but never exceeds `last_failing_size / 2`. Prevents 64KB↔128KB DMA oscillation.
- **Windows Deployment Core**: Full Windows 10/11 ISO support with FAT32 formatting and intelligent WIM/SWM splitting for files >4GB.
- **Per-SCSI Progress**: `physical_position` updates after each SCSI command, polled at 10Hz from the main thread — smooth UI without jank.
- **BLAKE3 Verification**: Triple-buffered read-ahead verification with BLAKE3 hashing computes integrity check inline during source reads.
- **ISO Validation**: File content-probed at selection via Rust probes — only valid Linux or Windows ISOs accepted, with Snackbar feedback for unsupported files.
- **Ultra-Lightweight**: Full APK under 3MB.
- **Auto-Eject**: Drive ejected via SCSI START STOP UNIT on completion.

## 🏗️ Architecture

1.  **Android App (`:app`)**: Jetpack Compose UI (MVI-lite). Single `MainActivity`, single screen with conditional overlays.
2.  **Native SDK (`:androidusbflasher`)**: UniFFI + JNA bridge. `AndroidUsbFlasher.kt` wraps the native calls; `UsbFlasherNative.kt` is auto-generated.
3.  **Rust Core (11 crates)**:
    *   `hyntix-usb-flasher-jni`: CDYLIB entry point, UniFFI exports → `libusbflasher.so`
    *   `hyntix-usb`: SCSI BOT protocol, `USBDEVFS_BULK` with AIMD flow control
    *   `hyntix-usb-flasher`: Flash orchestration, BLAKE3 verification, progress polling
    *   `hyntix-windows`, `hyntix-fat32`: UDF parsing, FAT32/GPT formatting, WIM splitting
    *   `hyntix-iso`, `hyntix-udf`, `hyntix-wim`: Filesystem parsers

## 💻 Performance

- **USB 2.0 ceiling**: ~19 MB/s write, ~20 MB/s read verify (NAND-limited on typical flash drives)
- **Initial burst**: Pipeline starts at 256KB chunks, AIMD settles at stable size (64KB on constrained DMA pools, 128KB+ on larger pools)
- **DMA constraint**: Most Android devices have `usbfs_memory_mb` < 8MB, forcing small URB sizes in a `SUBMITURB`/`REAPURB` pipeline. `USBDEVFS_BULK` bypasses this by delegating DMA to the kernel.

## 🚀 Getting Started

### Prerequisites
- Android 13+ (API 33)
- USB-OTG adapter + flash drive

### Building from Source
1.  Install Android NDK r28+, Rust 1.85+, `rustup target add aarch64-linux-android`
2.  Install `uniffi-bindgen` matching the crate version: `cargo install uniffi --version 0.31.2 --features cli`
3.  Build: `./gradlew assembleRelease`
4.  APK at `app/build/outputs/apk/release/app-arm64-v8a-release.apk`

## 📜 License

AGPL-3.0. See [LICENSE](LICENSE).
