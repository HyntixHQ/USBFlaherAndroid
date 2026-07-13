# USBFlasherAndroid — AI Context

## Project State (v1.0.3)
- **Min SDK**: 33 (Android 13)
- **NDK**: r28+
- **AGP**: 9.2.1
- **Rust**: 1.85+, edition 2024
- **Target**: ARM64 only (`arm64-v8a`)
- **R8**: Full mode

## Architecture
- **App (`app/`)**: Jetpack Compose UI, MVI-lite, single Activity, no DI. File picker filters ISO/octet-stream MIMEs; invalid files rejected with Snackbar via `feedbackMessage` SharedFlow.
- **Bridge (`androidusbflasher/`)**: UniFFI 0.31.2 + JNA 5.18.1. `AndroidUsbFlasher.kt` is hand-written; `UsbFlasherNative.kt` is auto-generated. Do NOT edit the generated file.
- **Rust Core** (11 crates in `androidusbflasher/rust-lib/crates/`):
  - `hyntix-usb-flasher-jni/`: cdylib entry point → `libusbflasher.so`
  - `hyntix-usb/`: SCSI BOT protocol via `USBDEVFS_BULK` ioctl with AIMD flow control
  - `hyntix-usb-flasher/`: Flash orchestration, BLAKE3 verification, 10Hz progress polling
  - `hyntix-windows/`, `hyntix-fat32/`: Windows ISO deployment (UDF, FAT32, WIM splitting)

## USB Stack
- **USBDEVFS_BULK** (not SUBMITURB/REAPURB). Kernel manages DMA, bypassing `usbfs_memory_mb`.
- **AIMD with floor locking**: chunk size halves on ENOMEM, recovery capped at `last_failing_size / 2`.
- **SCSI**: 4MB WRITE(10), 32MB async buffers, 4× buffer pool.
- **Progress**: per-SCSI `physical_position` updates, polled at 10Hz from main thread.

## Build
```bash
./gradlew assembleRelease
cargo check    # verify Rust without NDK
```
`uniffi-bindgen` must be on `$PATH` (install via `cargo install uniffi --version 0.31.2 --features cli`).

## Performance ceiling (USB 2.0 flash drive)
- Write: ~19 MB/s (NAND-limited)
- Read verify: ~20 MB/s
