# ⚡ Hyntix USB Flasher

[![License](https://img.shields.io/badge/License-AGPL%20v3-red.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![Rust](https://img.shields.io/badge/Language-Rust-black?logo=rust&logoColor=white)](https://www.rust-lang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

A high-performance, rootless, and open-source USB flashing utility for Android. Designed for speed and reliability, it enables users to create bootable Linux and Windows drives directly from their Android device with a single tap.

---

## ✨ Key Features

- **🚀 High-Saturation Parallel Engine**: Custom Rust core utilizing a 32-URB asynchronous pipeline for maximum USB 2.0/3.0 throughput.
- **🪟 Windows Deployment Core**: Full support for Windows 10/11 ISOs (UDF) with automatic FAT32 formatting and intelligent WIM/SWM splitting for files >4GB.
- **🛡️ Safety-First Design**: Mandatory data loss confirmation dialogs and automated drive ejection (SCSI START STOP UNIT) to prevent data corruption.
- **⚡ Ultra-Lightweight**: Entire application package is optimized to be **under 3MB** while maintaining premium performance.
- **🔍 Real-Time Telemetry**: Detailed live logger providing instant feedback on write speed, IOPS, and deployment progress.
- **🚫 OS Support Note**: Supports Linux (ISO) and Windows (ISO/UDF). macOS (DMG/ISO) is currently **not supported**.

## 🏗️ Architecture

Hyntix USB Flasher is built on a hybrid architecture to balance modern UI flexibility with low-level performance:

1.  **Android App (`:app`)**: A modern Jetpack Compose interface following MVI (Model-View-Intent) principles for a reactive, smooth user experience.
2.  **Native SDK (`:androidusbflasher`)**: A robust JNI bridge generated via UniFFI, exposing high-performance Rust primitives to the Kotlin layer.
3.  **Rust Core**:
    *   `hyntix-usb`: Low-level SCSI command generation (BOT protocol) and URB pipelining.
    *   `hyntix-windows`: Specialized crate for UDF parsing and intelligent WIM splitting.
    *   `hyntix-fat32`: High-speed FAT32/GPT formatting logic.

## 🛠️ Performance Tuning

The engine is tuned for the specific constraints of mobile hardware:
- **Triple-Pipelined Verification**: Simultaneous USB reading, SHA-256/BLAKE3 hashing, and progress reporting to eliminate idle bus time.
- **Adaptive DMA Chunks**: Automatically adjusts URB sizes based on the Android kernel's DMA memory pool availability (AIMD algorithm).
- **LTO Optimization**: All native binaries are compiled with Link-Time Optimization and optimized for binary size without sacrificing hot-path performance.

## 🚀 Getting Started

### Prerequisites
- Android 13 (Tiramisu) or higher (API 33).
- A USB-OTG adapter.
- A USB 2.0 or 3.0 flash drive.

### Building from Source
1.  **Environment Setup**:
    *   Install Android NDK (r28+ required for 16KB page size support on Android 15+).
    *   Install Rust: `rustup target add aarch64-linux-android`.
2.  **Compile & Install**:
    ```bash
    ./gradlew assembleRelease
    ```
    The output APK will be located in `app/build/outputs/apk/release/`.

## 📜 License

This project is licensed under the **GNU Affero General Public License Version 3 (AGPL-3.0)**. We believe in open, transparent software that respects user freedom. See the [LICENSE](LICENSE) file for details.

---

<p>
  Built with precision by the <b>Hyntix Team</b>.
</p>
