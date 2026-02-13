# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
