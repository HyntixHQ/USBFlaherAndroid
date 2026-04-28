# USB Flasher for Android

[![License](https://img.shields.io/badge/License-AGPL%20v3-red.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-green.svg)](https://www.android.com/)

A high-performance, rootless USB flashing utility for Android. Powered by a custom **High-Saturation Parallel I/O Engine** and a specialized **Windows Deployment Core** written in Rust.

## 🚀 The "High-Saturation" Engine

Unlike traditional single-threaded flasher implementations, this project uses a massively parallel architecture to saturate the USB host controller and minimize Disk I/O bottlenecks.

- **32-URB Async Pipeline**: Keeps the USB's DMA engine 100% busy with zero idle time between transfers.
- **256MB Prefetch Pool**: Decouples Disk reading from USB writing. While one block is being flashed, the next 256MB are already being read-ahead into RAM in parallel.
- **Direct SCSI Alignment**: Optimizes transfer chunks to the exact hardware limits of the USB mass storage protocol (65,535 blocks), eliminating overhead.
- **High-Fidelity UI**: Real-time progress updates at 10Hz (100ms) with instant phase-sync for a professional, smooth experience.

## ✨ Features

- **Windows ISO Support**: Advanced UEFI-compatible flashing for Windows 10/11 ISOs.
  - **Auto SWM Splitting**: Dynamically splits large `install.wim` files (>4GB) into FAT32-compatible `.swm` parts.
  - **Master Lookup Table**: Implements a global WIM index ensuring 100% compatibility with the official Windows Setup engine.
- **Safety First**: Prevents common user errors with built-in hardware compatibility checks.

## 🛠️ Building

The project is now a single, atomic unit containing the Android UI, the SDK module, and the Rust core.

1. **Prerequisites**:
   - Android SDK & NDK r25+
   - Rust 1.75+ with `aarch64-linux-android` target installed via `rustup`.

2. **Clean Build & Install**:
   ```bash
   ./gradlew clean installRelease
   ```

## 📜 License

This project is licensed under the **GNU Affero General Public License Version 3 (AGPL-3.0)**. See the [LICENSE](LICENSE) file for the full text.

---
Built with ❤️ by the Hyntix Team.
