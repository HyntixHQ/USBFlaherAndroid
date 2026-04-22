// URB Pipeline Configuration
//
// This module defines the core constants for the 32-URB pipeline architecture.
// All size calculations derive from these base constants to ensure consistency.

/// Pipeline depth: Number of URBs that can be in-flight simultaneously.
///
/// 32 URBs keeps the USB host controller's DMA engine fully saturated,
/// creating the characteristic burst pattern:
///   - Initial 100MB/s+ (pipeline filling)
///   - Settles to device sustained speed (~30MB/s USB 2.0, ~100MB/s USB 3.0)
/// Legacy constant, no longer used as a strict limit but as a baseline for calculations.
pub const URB_PIPELINE_DEPTH: usize = 32;

/// Target total bytes to keep in-flight simultaneously across the pipeline.
/// 8MB provides ~266ms of cushion at 30MB/s, hiding Android kernel DMA mapping latency.
pub const TARGET_IN_FLIGHT_BYTES: usize = 8 * 1024 * 1024;

/// Number of buffers in the async pool.
/// 16 buffers × 4MB = 64MB read-ahead cushion.
pub const BUFFER_COUNT: usize = 16;

/// Initial chunk size per URB.
/// 32KB is the maximum this device's DMA pool can handle (confirmed by
/// ENOMEM at 64KB). 32KB × 256 URBs = 8MB in-flight pipeline depth.
pub const INITIAL_URB_CHUNK_SIZE: usize = 32 * 1024;

/// Minimum URB chunk size (16KB).
pub const MIN_URB_CHUNK_SIZE: usize = 16 * 1024;

/// Maximum transfer size for a single SCSI WRITE(10)/READ(10) command.
/// 4MB = 8192 blocks × 512 bytes.
/// 4MB is optimal: 5 of every 6 commands stay within the drive's ~20MB SLC
/// cache (18.5 MB/s), with only 1 hitting TLC speed (10 MB/s).
/// Larger sizes (16MB+) bake the TLC stall into EVERY command, averaging lower.
pub const MAX_TRANSFER_SIZE: usize = 8192 * 512; // 4MB

/// File read chunk size for the flasher.
pub const READ_CHUNK_SIZE: usize = MAX_TRANSFER_SIZE;
