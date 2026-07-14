# AGENTS.md

## Build

- **Full build:** `./gradlew assembleRelease` — compiles Kotlin, cross-compiles Rust for ARM64, runs `uniffi-bindgen` to regenerate `UsbFlasherNative.kt`
- **Rust only:** `cargo build --release --target aarch64-linux-android -p hyntix-usb-flasher-jni`
- `uniffi-bindgen` must be on `$PATH` (from `cargo install uniffi`, version matching 0.31.2)

## Architecture

Three layers, no DI, no Jetpack Navigation:

1. **`:app` module** — Jetpack Compose UI (MVI-lite). Single `MainActivity`, single screen with conditional overlays. One `FlashViewModel` with `StateFlow<FlashState>`.
2. **`:androidusbflasher` module** — UniFFI + JNA bridge. `AndroidUsbFlasher.kt` is the hand-written wrapper; `UsbFlasherNative.kt` is **auto-generated** by UniFFI.
3. **Rust workspace** (`androidusbflasher/rust-lib/`) — 11 crates, produces `libusbflasher.so` (cdylib). UniFFI 0.31.2, JNA 5.19.1, Rust edition 2024 (requires Rust 1.85+).

## USB stack architecture

- **USBDEVFS_BULK** synchronous ioctl (not `SUBMITURB`/`REAPURB`). Kernel manages DMA internally,
  bypassing the `usbfs_memory_mb` userspace pool limit that forced 32KB URBs with the old pipeline.
- **32-URB depth** is a holdover constant; actual pipelining is managed inside the kernel.
- **AIMD with floor locking**: chunk size halved on ENOMEM, additively increases after 200 clean
  calls, but NEVER exceeds `last_failing_size / 2`. This prevents 64KB↔128KB oscillation.
- **SCSI 4MB WRITE(10)** per command, 32MB async buffers, 4× buffer pool (128MB total).
- **Per-SCSI progress**: `write_blocks_with_progress()` updates `physical_pos` after each SCSI command,
  polled at 10Hz from the main thread during both read and acquire-wait phases.
- **BLAKE3 hashing** computed inline during source reads (not as a post-read batch).

## Key conventions & gotchas

- **ARM64 only** (`arm64-v8a`). No universal APK, no x86/x86_64.
- **Min SDK 33** (Android 13), **Target SDK 37** (Android 16), **NDK r28+**.
- Rust `[profile.release]`: `lto = true`, `opt-level = "z"`, `codegen-units = 1`, `panic = "abort"`.
- **UniFFI + JNA**, not traditional JNI. Do NOT edit `UsbFlasherNative.kt` by hand — modify the Rust `hyntix-usb-flasher-jni` crate and regenerate.
- `.cargo/config.toml` has machine-specific absolute NDK paths (not portable). The Gradle `cargoBuild` task sets `CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER` dynamically.
- `keystore.properties` is tracked in git with plaintext passwords. Treat as sensitive.
- Rust logs tagged `"UsbFlasherRust"`; `AppLogger` captures them from logcat via a daemon thread.
- Native flash calls block the calling thread — `AndroidUsbFlasher` wraps them in `Thread(runnable).start()`.
- No DI framework: `ViewModelProvider.Factory` manual construction in `MainActivity`.
- Splash screen via `androidx.core:core-splashscreen` (Android 12+ API). Two logo variants:
  `ic_splash_logo_white` (dark mode) and `ic_splash_logo_dark` (light mode, #2B2C30).
- All user-visible strings in `res/values/strings.xml` — use `stringResource()` in composables
  and `context.getString()` in ViewModel/Repository.

## Tests

No tests are currently written for any layer. Stub test files and their dependencies were removed in v1.0.4.

## Environment

- Rust 1.85+ (edition 2024), Kotlin 2.3.21, AGP 9.2.1, Compose BOM 2026.05.00
- `rustup target add aarch64-linux-android`
- `ANDROID_NDK_HOME` pointing to NDK 30+

## File layout

### Kotlin sources
```
app/src/main/java/com/hyntix/android/usbflasher/
├── MainActivity.kt — entry point, USB permission BroadcastReceiver
├── data/FlashState.kt — sealed state: Idle -> Ready -> Flashing/Verifying -> Success/Error
├── domain/FlashRepository.kt — flash orchestration, delegates to AndroidUsbFlasher
├── ui/FlashViewModel.kt — single ViewModel, MVI-lite (method calls, no Action/Event sealed classes)
├── ui/MainScreen.kt — primary composable screen
├── ui/UiComponents.kt — StatusCard, FlashingSheet
├── ui/LogViewerScreen.kt — full-screen log viewer
└── util/AppLogger.kt — singleton in-app logger

androidusbflasher/src/main/java/com/hyntix/lib/androidusbflasher/
├── AndroidUsbFlasher.kt — hand-written Kotlin wrapper (edit this for bridge changes)
└── UsbFlasherNative.kt — UniFFI-generated (do NOT edit manually)
```

### Rust crates
```
androidusbflasher/rust-lib/crates/
├── hyntix-usb-flasher-jni/ — cdylib entry point, UniFFI exports -> libusbflasher.so
├── hyntix-usb-flasher/ — core flash orchestration logic
├── hyntix-usb/ — SCSI BOT protocol, USBDEVFS_BULK
├── hyntix-common/ — shared error types
├── hyntix-iso/, hyntix-udf/, hyntix-wim/ — filesystem parsers
├── hyntix-fat32/ — FAT32 formatting + GPT partition table
├── hyntix-linux/, hyntix-windows/ — platform-specific ISO detection
└── hyntix-windows-cli/ — removed in v1.0.4 (was CLI test tool)
```
