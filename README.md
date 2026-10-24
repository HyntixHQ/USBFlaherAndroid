# Hyntix USB Flasher

[![License](https://img.shields.io/badge/License-GPL%20v3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](https://www.android.com/)
[![Rust](https://img.shields.io/badge/Language-Rust-black?logo=rust&logoColor=white)](https://www.rust-lang.org/)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)

A high-performance, rootless, open-source USB flashing utility for Android. Create bootable Linux and Windows drives directly from your device — no root, no PC required.

---

## Key Features

- **Direct SCSI Engine**: Rust core using `USBDEVFS_BULK` synchronous ioctl — kernel manages DMA internally, bypassing the `usbfs_memory_mb` userspace pool limit.
- **Adaptive AIMD Flow Control**: Chunk size halves on ENOMEM, additively recovers after 200 clean cycles, but never exceeds `last_failing_size / 2`. Prevents 64KB–128KB DMA oscillation.
- **Windows Deployment Core**: Full Windows 10/11 ISO support with FAT32 formatting and intelligent WIM/SWM splitting for files >4 GB.
- **Per-SCSI Progress**: `physical_position` updates after each SCSI WRITE(10) command, polled at 10 Hz from the main thread — smooth UI without jank.
- **BLAKE3 Verification**: Triple-buffered read-ahead verification with BLAKE3 hashing computes integrity check inline during source reads.
- **Linux Persistence (Opt-in)**: Appends an ext2 `casper-rw` (Ubuntu, Mint, Pop!_OS) or `persistence` (Debian Live) partition after the ISO so changes survive reboots. See [persistence documentation](docs/features/persistence.md).
- **ISO Validation**: File content-probed at selection via Rust probes — only valid Linux or Windows ISOs accepted, with Snackbar feedback for unsupported files.
- **USB Verification (Read-Only)**: Verify any USB drive against its source ISO SHA-256 without re-flashing.
- **Backup USB to Image**: Create full drive image backup to SAF destination with SHA-256 verification.
- **In-App Linux ISO Downloads**: Browse 20 distributions, download directly from official mirrors with SHA-256 verification, pause/resume/cancel support.
- **Auto-Eject**: Drive ejected via SCSI START STOP UNIT on completion.
- **Auto Theme**: Follows system light/dark theme automatically — no manual toggle needed.
- **Ultra-Lightweight**: Full APK under 4 MB.

---

## Architecture

| Layer | Description |
|-------|-------------|
| **Android App (`:app`)** | Jetpack Compose UI (MVI-lite). Single `MainActivity`, single screen with conditional overlays. |
| **Native SDK (`:androidusbflasher`)** | UniFFI + JNA bridge. `AndroidUsbFlasher.kt` wraps native calls; `UsbFlasherNative.kt` is auto-generated. |
| **Rust Core (14 crates)** | `hyntix-usb-flasher-jni` (cdylib), `hyntix-usb` (SCSI BOT, `USBDEVFS_BULK`), `hyntix-usb-flasher` (orchestration), `hyntix-windows`/`hyntix-fat32` (UDF/FAT32), `hyntix-iso/udf/wim` (parsers), `hyntix-storage/partition/persistence` (block devices, partition editing, ext2 persistence). |

---

## Performance

| Metric | Value |
|--------|-------|
| **USB 2.0 ceiling** | ~19 MB/s write, ~20 MB/s read verify (NAND-limited on typical flash drives) |
| **Initial burst** | Pipeline starts at 4 MB chunks, AIMD settles at stable size (4–16 MB on typical devices) |
| **DMA constraint** | Most Android devices have `usbfs_memory_mb` < 8 MB, forcing small URB sizes in legacy `SUBMITURB`/`REAPURB` pipelines. `USBDEVFS_BULK` bypasses this by delegating DMA to the kernel. |
| **Initial chunk** | 4 MB (configurable via `INITIAL_URB_CHUNK_SIZE`) |
| **Max SCSI transfer** | 16 MB (`SCSI_MAX_TRANSFER_SIZE`) |
| **Buffer pool** | 4 × 32 MB = 128 MB total |

---

## Getting Started

### Prerequisites
- Android 13+ (API 33)
- USB-OTG adapter + flash drive

### Building from Source
1. Install Android NDK r28+, Rust 1.85+, `rustup target add aarch64-linux-android`
2. Install `uniffi-bindgen` matching the crate version:
   ```bash
   cargo install uniffi --version 0.32.2 --features cli
   ```
3. Build:
   ```bash
   ./gradlew assembleRelease
   ```
4. APK at `app/build/outputs/apk/release/app-arm64-v8a-release.apk`

---

## Usage

1. **Select ISO**: Tap the image card → choose *Download ISO* (Linux) or *Choose from device* (Windows/Linux local files).
2. **Download (Linux)**: Pick distribution/variant → choose SAF destination → download starts with SHA-256 verification.
3. **Select USB**: Connect USB drive via OTG → grant permission → drive appears in target card.
4. **Optional — Persistence** (Linux live ISOs only): Enable persistence toggle, choose size, confirm.
5. **Flash**: Tap **Erase and Flash** → confirm → progress sheet shows real-time speed, ETA, progress.
6. **Verify** (optional): After flash or anytime, use *Verify USB* to SHA-256 compare the drive against the source ISO.
7. **Backup** (optional): Use *Backup USB* to create a full drive image (`.img`) with SHA-256.

---

## Supported Distributions (In-App Catalog)

| Family | Distributions |
|--------|---------------|
| Debian-based | Debian, MX Linux, Kali Linux, antiX, Deepin, Parrot OS |
| Ubuntu-based | Ubuntu, Linux Mint, Pop!_OS, Zorin OS, Linux Lite |
| Arch-based | Arch Linux, Manjaro, Garuda Linux, CachyOS |
| Fedora-based | Fedora, Nobara, Qubes OS |
| Other | openSUSE Tumbleweed, NixOS |

*20 distributions, 52 variants total. Persistence supported for casper (Ubuntu/Mint/Pop!_OS) and Debian Live images.*

---

## Limitations

- ARM64 Android devices only (API 33+ / Android 13+)
- Windows ISOs: flash from local storage only (in-app download not supported)
- Linux persistence: unencrypted; casper (Ubuntu/Mint/Pop!_OS) & Debian Live only; no Fedora, Arch, openSUSE, Kali, Tails, Parrot, Ventoy, 4Kn drives
- No root, no PC required

---

## Building from Source

### Prerequisites
- Android NDK r28+
- Rust 1.85+ (`rustup target add aarch64-linux-android`)
- `uniffi-bindgen` 0.32.2: `cargo install uniffi --version 0.32.2 --features cli`
- Android Studio / Gradle 9.x

```bash
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

### Rust-only build
```bash
cd androidusbflasher/rust-lib
cargo build --release --target aarch64-linux-android -p hyntix-usb-flasher-jni
```

---

## License

GPL-3.0. See [LICENSE](LICENSE).

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).

---

## Security

See [SECURITY.md](SECURITY.md) for vulnerability reporting.

---

*Built with ❤️ by the Hyntix team.*
