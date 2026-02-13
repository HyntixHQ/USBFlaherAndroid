# Contributing to USB Flasher for Android

First off, thank you for considering contributing to this project! It's people like you that make the open-source community such an amazing place to learn, inspire, and create.

## 🛠️ Development Setup

The project is an atomic workspace combining Android (Kotlin/Compose) and Rust (JNI).

### Prerequisites
- **Android Studio**: Ladybug or later.
- **Android SDK & NDK**: Version 29.0.14206865 is specified in `build.gradle.kts`.
- **Rust Toolchain**: 1.75+ installed via [rustup](https://rustup.rs/).
- **Rust Android Target**: 
  ```bash
  rustup target add aarch64-linux-android
  ```

### Project Structure
- `app/`: The Jetpack Compose UI.
- `androidusbflasher/`: The Android SDK library.
- `androidusbflasher/rust-lib/`: The core Rust engine (`hyntix-usb` and `hyntix-usb-flasher`).

## 🚀 How to Contribute

1. **Fork the Project**: Create your own fork of the repository.
2. **Create a Branch**: 
   ```bash
   git checkout -b feature/AmazingFeature
   ```
3. **Commit Changes**: Make sure your commits are descriptive.
4. **Build and Test**: Run a clean release build to ensure JNI/Rust paths are correct:
   ```bash
   ./gradlew clean assembleRelease
   ```
5. **Open a Pull Request**: Describe your changes in detail in the PR description.

## ⚖️ License

By contributing, you agree that your contributions will be licensed under the project's **AGPL v3 License**.

---
Feel free to open an issue for any bugs or feature requests!
