# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.4] - Unreleased

### Added
- **Splash Screen**: Native Android 12+ SplashScreen API with app logo. White logo on dark
  background in dark mode, colored logo (#2B2C30) on white in light mode.
- **Separator Lines**: Subtle 0.5dp divider below TopAppBar in both MainScreen and LogViewerScreen.
- **String Resource Extraction**: All 45 user-visible strings extracted from code into
  `strings.xml` for future localization support.

### Changed
- App icons updated.
- License changed from AGPL-3.0 to GPL-3.0 (more appropriate for a client-side Android app).
- JNA version catalog entry removed (used via raw `@aar` string, not `libs.jna`).

### Internal
- `androidx.core:core-splashscreen:1.2.0` dependency added.

## [1.0.3] - 2026-07-13

### Added
- **ISO File Validation**: Files are now probed via Rust `isLinuxIso()`/`isWindowsIso()` at selection time.
  Non-ISO files are rejected with a Snackbar feedback message instead of being silently accepted.
- **USBDEVFS_BULK Transfer Engine**: Replaced the `SUBMITURB`/`REAPURB` userspace URB pipeline with
  synchronous `USBDEVFS_BULK` ioctl. Kernel manages DMA allocation from its own pool, bypassing the
  `usbfs_memory_mb` constraint that limited URB sizes to 32KB. Now achieves 64KB-128KB chunks
  even on devices with tight DMA pools.
- **AIMD Floor Locking**: Tracks the last ENOMEM size in `enomem_floor`; additive increase never
  exceeds `floor / 2`. Eliminates the 64KB↔128KB oscillation that plagued byte-based targeting.
- **Per-SCSI Progress Updates**: `write_blocks_with_progress()` updates `physical_position` after
  each SCSI WRITE(10) command. Main thread polls at 10Hz during both source reads and buffer
  acquire-wait, providing smooth real-time UI feedback.
- **Non-Blocking Buffer Acquire**: `try_acquire_buffer()` enables the main thread to poll progress
  while waiting for the worker to recycle buffers.
- **Inline BLAKE3 Hashing**: Hash computed per source read chunk instead of as a post-read batch,
  eliminating a ~32ms latency bubble per 32MB buffer.

### Improved
- **Verification Speed**: Read verification improved from ~14 MB/s to ~20 MB/s (+40%) due to
  `USBDEVFS_BULK` enabling larger IN chunks (64KB vs stuck at 32KB previously).
- **Progress Bar Fluid Motion**: Spring animation tuned to `stiffness=4000`, `dampingRatio=0.7`
  for natural water-flow feel. `strokeCap` changed to `Round` (matches design language).
- **Source Read Efficiency**: `READ_CHUNK_SIZE` aligned to 32MB async buffer size; buffer pool
  reduced to 4 × 32MB = 128MB (was 16 × 4MB = 64MB, then 8 × 32MB = 256MB).
- **Zero Rust Warnings**: All 13 crates compile with zero warnings.

### Changed
- `SCSI_MAX_TRANSFER_SIZE`: 4MB (reduced from 32MB — larger triggered CSW fallback retries).
- `BUFFER_COUNT`: 4 (reduced from 8 — 128MB pool adequate at ~18 MB/s throughput).
- `URB_PIPELINE_DEPTH`: retained as 32 (legacy constant, no longer the active pipeline cap).
- USB diagnostics: logs active config constants at startup; reads `usbfs_memory_mb` (SELinux permitting).

### Fixed
- **Buffer Pool Crash**: Recycled buffers had `len=0` from `clear()`, causing `source.read(&mut buf[0..n])`
  to abort with "range end index 4194304 out of range for slice of length 0". Fixed with `resize(BUFFER_SIZE, 0)`.
- **AIMD Oscillation**: Byte-based targeting (`TARGET_IN_FLIGHT_BYTES`) caused continuous 64KB↔128KB
  chunk size oscillation. Hard 32-URB depth cap + floor locking eliminates this.
- **Stuttering Progress Bar**: Per-buffer progress (32MB jumps every ~2s) replaced with per-SCSI
  progress (4MB every ~250ms) + 10Hz polling loop.

### Dependencies
- zerocopy: 0.8.48 → 0.8.52
- uniffi: 0.31.0 → 0.31.2 (system `uniffi-bindgen` updated to match)
- anyhow: 1.0.102 → 1.0.103
- uuid: 1.23.0 → 1.23.4

## [1.0.2] - 2026-05-12

### Added
- **Windows ISO Flashing Support**: Comprehensive implementation for flashing Windows 10/11 ISOs to FAT32 media with full UEFI boot compatibility.
- **Intelligent SWM Splitting**: Custom engine that automatically splits large `install.wim` files (>4GB) into spanned `.swm` parts.
- **Triple-Pipelined Verification**: High-performance verification engine utilizing a background read-ahead thread to saturate the USB bus while hashing.
- **Safety Confirmation Dialog**: Mandatory warning dialog before flashing to prevent accidental data erasure.
- **Dynamic Volume Labeling**: Automatic extraction of the Logical Volume Identifier from UDF metadata to apply native labels to bootable media.
- **Post-Flash Cleanup**: Automated drive ejection (SCSI START STOP UNIT) and state resetting (clearing ISO/Device) upon completion.

### Improved
- **Verification Throughput**: Optimized SCSI transfer sizes (1MB) and URB chunk sizes (128KB) to maximize speed while respecting Android DMA memory limits.
- **UI Streamlining**: Removed redundant "Done" text from the success screen for a more polished experience.
- **Progress Telemetry**: Throttled progress reporting during high-speed verification to reduce JNI/UI overhead.
- **USB Hardware Compatibility**: Decoupled async buffer sizing from SCSI transfer limits to ensure broad compatibility with generic controllers.

### Fixed
- **Verification Hangs**: Resolved a critical issue where large SCSI transfers (4MB) caused silent kernel hangs on certain Android devices.
- **The "License Terms" Bug**: Resolved the critical issue where Windows Setup failed to find the EULA due to absolute offset mismatches in split WIMs.
- **Infinite Overwrite Bug**: Fixed a cluster advancement logic error in the FAT32 writer that caused stalls at partition boundaries.

## [1.0.1] - 2026-04-22

### Added
- **In-App Log Viewer**: Tap the terminal icon in the app bar to view live debug logs from both Kotlin and Rust layers. Includes share, clear, and color-coded log levels.
- **Auto-Eject**: Drive is automatically ejected via SCSI START STOP UNIT when the user taps Done after a successful flash.
- **User-Friendly Errors**: All error messages are now concise and non-technical. Raw details are logged to the in-app logger for debugging.

### Improved
- **UI Polish**: Left-aligned app bar title, properly aligned drive name/capacity text, and relocated flash button to the fixed bottom bar.
- **USB Pipeline**: Added REAPURBNDELAY batch-drain for URB reaping, optimized SCSI transfer size to 4MB, and confirmed 32KB as the optimal URB chunk size.
- **Device Detection**: Reduced scan polling interval from 10s to 3s. USB device names are now trimmed of extra whitespace.
- **Bottom Sheet**: Removed redundant subtitle, fixed Success stage title to "Complete".

### Fixed
- Removed stale SCSI timing instrumentation that added log noise.
- Removed failing usbfs_memory_mb diagnostic read that caused permission errors in logs.

## [1.0.0] - 2026-02-13

### Added
- **High-Saturation Engine**: Initial public release with a custom Rust-based parallel I/O pipeline.
- **32-URB Pipelining**: Achieves sustained high throughput by eliminating inter-transfer latency.
- **Parallel Buffering**: Implementation of a 256MB pre-fetch pool for non-blocking Disk-to-USB hand-off.
- **Rootless OTG Support**: Direct writing to USB block devices via Android USB Host API.
- **Data Integrity**: Automatic SHA-256 verification phase after flashing.
- **High-Fidelity UI**: 100ms refresh rate for smooth progress visualization and real-time speed/ETA tracking.
- **Secure Key Management**: Release signing with high-entropy localized keystore.

### Fixed (Pre-Release)
- Resolved I/O serialization bottlenecks that previously capped speeds at 10MB/s.
- Fixed speedometer overflow/negative value issues during verification phase changes.
- Eliminated UI "jumps" during high-speed operations.
