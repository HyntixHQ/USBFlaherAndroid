# AGENTS.md

## Build

- **Full build:** `./gradlew assembleRelease` — compiles Kotlin, cross-compiles Rust for ARM64, runs `uniffi-bindgen` to regenerate `UsbFlasherNative.kt`
- **Rust only:** `cargo build --release --target aarch64-linux-android -p hyntix-usb-flasher-jni`
- `uniffi-bindgen` must be on `$PATH` (from `cargo install uniffi`, version matching 0.32.2)

## Architecture

Three layers, no DI. App-level screens remain conditional overlays; Navigation 3 is used
only for the catalog → distribution detail back stack inside `DownloadScreen`:

1. **`:app` module** — Jetpack Compose UI (MVI-lite). Single `MainActivity`, single screen with conditional overlays. One `FlashViewModel` with `StateFlow<FlashState>`.
2. **`:androidusbflasher` module** — UniFFI + JNA bridge. `AndroidUsbFlasher.kt` is the hand-written wrapper; `UsbFlasherNative.kt` is **auto-generated** by UniFFI.
3. **Rust workspace** (`androidusbflasher/rust-lib/`) — 14 crates, produces `libusbflasher.so` (cdylib). UniFFI 0.32.2, JNA 5.19.1, Rust edition 2024 (requires Rust 1.85+).

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

## Persistence architecture

- Persistence is a second native call (`applyPersistence`) on the **same open USB fd** after
  `flashDevice`; `flash_raw` and the Windows path are untouched when persistence is off.
- Feature logic is isolated in `hyntix-storage`, `hyntix-partition`, and `hyntix-persistence`.
  Do not add persistence logic to `hyntix-usb-flasher`, `hyntix-linux`, `hyntix-fat32`, or
  `hyntix-usb`.
- The persistence partition is **appended only**: never move, shrink, or reformat ISO partitions.
  MBR type `0x83`; GPT type `0FC63DAF-8483-4772-8E79-3D69D8477DE4`.
- ext2 only: 4 KiB blocks, 256-byte inodes, no journal, `lost+found`, and `/persistence.conf`
  containing `/ union` for Debian Live.
- Partition table commit is last. A mid-apply failure restores boot-config patches and leaves
  the ISO bootable; Kotlin reports partial success with a retry action, not a fatal error.
- Hybrid MBRs are rejected unless every legacy entry matches a GPT entry; 4Kn drives are rejected.
- v1 supports casper (Ubuntu/Mint/Pop!_OS) and Debian Live only. No encryption, Windows,
  Fedora/Arch/openSUSE/Kali/Tails/Parrot, Ventoy, or 4Kn persistence.

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
- Catalog → distribution detail uses Navigation 3 (`NavBackStack` + `NavDisplay`) with serializable,
  typed keys. Keep the default `SaveableStateHolder` entry decorator: it preserves catalog scroll
  while a detail is on the stack and drops a detail's saved state when that entry is popped.
- Splash screen via `androidx.core:core-splashscreen` (Android 12+ API). Two logo variants:
  `ic_splash_logo_white` (dark mode) and `ic_splash_logo_dark` (light mode, #2B2C30).
- All user-visible strings in `res/values/strings.xml` — use `stringResource()` in composables
  and `context.getString()` in ViewModel/Repository.

## Tests

- `cargo test` covers all Rust crates. The persistence crates include host integration tests
  against sparse disk images: MBR/GPT append, ext2 formatting (`e2fsck`), casper/Debian boot
  activation, reuse/repair, rollback on an injected GPT commit failure, and cancellation after
  boot patching but before the table commit.
- Clippy gate for feature work: `cargo clippy -p hyntix-storage -p hyntix-partition -p hyntix-persistence --all-targets -- -D warnings`.
- JVM unit tests cover the download helpers in `app/src/test/.../download/`. There are no
  `FlashViewModel` or instrumentation tests yet (the repository remains a concrete class). Verify
  UI changes with `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:testDebugUnitTest`, and a
  manual device walkthrough.

## Environment

- Rust 1.85+ (edition 2024), Kotlin 2.4.21, AGP 9.4.1, Compose BOM 2026.09.00,
  Navigation 3 1.2.0, kotlinx-serialization 1.9.0
- `rustup target add aarch64-linux-android`
- `ANDROID_NDK_HOME` pointing to NDK 30+

## File layout

### Kotlin sources
```
app/src/main/java/com/hyntix/android/usbflasher/
├── MainActivity.kt — entry point, USB permission BroadcastReceiver
├── data/FlashState.kt — sealed state: Idle -> Ready -> Flashing/Verifying/Persisting -> Success/Error
├── domain/FlashRepository.kt — flash orchestration, delegates to AndroidUsbFlasher
├── ui/FlashViewModel.kt — single ViewModel, MVI-lite (method calls, no Action/Event sealed classes)
├── ui/MainScreen.kt — primary composable screen, persistence toggle and size chips
├── ui/UiComponents.kt — StatusCard, PersistenceCard, FlashingSheet (incl. partial success)
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
├── hyntix-storage/ — BlockDevice abstraction, MemBlockDevice, FileBlockDevice, unaligned I/O
├── hyntix-partition/ — append-only MBR/GPT editing, journaled commit, rollback, free-tail sizing
└── hyntix-persistence/ — ISO probe, ext2 formatter, boot activation, plan/apply/verify

docs/features/persistence.md — supported profiles, limitations, boot instructions
docs/features/iso-catalog.md — catalog schema v2, host allowlists, checksum formats, update checklist
```

## ISO catalog

- `app/src/main/assets/iso-catalog.json` is schema v2: `categories` → `distributions` → `variants`.
- Download hosts, checksum URLs, static SHA-256 values, and persistence metadata live in the catalog; do not add distribution-specific host lists or edition mappings in Kotlin.
- Persistence `support` is `app` (Hyntix-managed), `distro` (configured inside the live session), or `none`.
- Catalog integrity is covered by `IsoCatalogParserTest.bundledCatalogIsValidAndMaintainable`.
