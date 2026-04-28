# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.2] - 2026-04-28

### Added
- **Windows ISO Flashing Support**: Comprehensive implementation for flashing Windows 10/11 ISOs to FAT32 media with full UEFI boot compatibility.
- **Intelligent SWM Splitting**: Custom engine that automatically splits large `install.wim` files (>4GB) into spanned `.swm` parts.
- **Master Lookup Table**: Implemented a global WIM index architecture across all split parts, ensuring the Windows Setup engine can locate resources flawlessly.
- **Custom FAT32 Driver**: A specialized Rust-based FAT32 writer with cluster-aligned allocation and high-performance throughput.

### Improved
- **Progress Calculation**: Refactored progress tracking to use relative byte reporting, eliminating negative ETA values and cumulative percentage errors.
- **USB Hardware Compatibility**: Decoupled async buffer sizing from SCSI transfer limits (1MB) to ensure broad compatibility with generic USB mass storage controllers.
- **Logging Pipeline**: Targeted PID-based filtering and streamlined log tags for better diagnostic signal.

### Fixed
- **The "License Terms" Bug**: Resolved the critical issue where Windows Setup failed to find the EULA due to absolute offset mismatches in split WIMs.
- **Infinite Overwrite Bug**: Fixed a cluster advancement logic error in the FAT32 writer that caused stalls at partition boundaries.
- **SWM Header Compliance**: Corrected SWM headers to include required boot indices and GUIDs.

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