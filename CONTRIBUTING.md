# Contributing to USB Flasher for Android

## 🛠️ Development Setup

The project is an atomic workspace combining Android (Kotlin/Compose) and Rust (USB core).

### Prerequisites
- **Android Studio**: Ladybug or later.
- **Android SDK & NDK**: API 33+ (Android 13), NDK r28+ (required for 16KB page support on Android 15+).
- **Rust**: 1.85+ installed via [rustup](https://rustup.rs/), edition 2024.
- **Rust Android Target**: `rustup target add aarch64-linux-android`
- **UniFFI Bindgen**: `cargo install uniffi --version 0.31.2 --features cli` (must match the crate version in `Cargo.toml`)

### Project Structure
- `app/`: Jetpack Compose UI (MVI-lite).
- `androidusbflasher/`: Android SDK library + UniFFI JNA bridge.
- `androidusbflasher/rust-lib/`: Rust workspace with 11 crates.

### Key Architecture
- **USB I/O**: `USBDEVFS_BULK` synchronous ioctl (not `SUBMITURB`/`REAPURB`). Kernel manages DMA internally.
- **SCSI**: BOT protocol, 4MB WRITE(10) commands, 32MB async buffers, 4× buffer pool.
- **AIMD Flow Control**: Chunk size halves on ENOMEM, additively increases after 200 clean cycles, but never exceeds `last_failing_size / 2`.
- **Progress**: `physical_position` updates per-SCSI command, polled at 10Hz from main thread.
- **Verification**: Triple-buffered BLAKE3 read-ahead (separate reader thread + main hash thread).

## 🚀 How to Contribute

1. **Fork the Project**
2. **Create a Branch**: `git checkout -b feature/AmazingFeature`
3. **Commit Changes**: Descriptive commit messages following conventional commits.
4. **Build and Test**: `./gradlew clean assembleRelease` (ensure `uniffi-bindgen` is on PATH).
5. **Open a Pull Request**

### Developer Commands
```bash
# Full release build (Kotlin + Rust cross-compile + UniFFI bindings)
./gradlew assembleRelease

# Rust only (verify compilation without NDK)
cargo check

# Rust with Android target
cargo build --release --target aarch64-linux-android -p hyntix-usb-flasher-jni
```

## ⚠️ Gotchas
- Do NOT edit `UsbFlasherNative.kt` by hand — modify the Rust `hyntix-usb-flasher-jni` crate and regenerate via `uniffi-bindgen`.
- `.cargo/config.toml` has machine-specific NDK paths (not portable). The Gradle `cargoBuild` task sets the linker dynamically.
- ARM64 only (`arm64-v8a`). No x86/x86_64.
- `keystore.properties` is tracked in git (plaintext passwords — treat as sensitive).
- No DI framework: `ViewModelProvider.Factory` manual construction in `MainActivity`.

## ⚖️ License

By contributing, you agree that your contributions will be licensed under AGPL v3.
