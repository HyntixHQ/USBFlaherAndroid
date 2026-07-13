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
pub const URB_PIPELINE_DEPTH: usize = 32;

/// Number of buffers in the async pool.
/// 4 buffers × 32MB = 128MB pre-fetch cushion. Lower count reduces DMA
/// memory pressure while still providing ample read-ahead at 18 MB/s.
pub const BUFFER_COUNT: usize = 4;

/// Size of each buffer in the async pool.
/// 32MB matches the original High-Saturation Engine design.
pub const ASYNC_BUFFER_SIZE: usize = 32 * 1024 * 1024;

/// Maximum transfer size for a single SCSI WRITE(10)/READ(10) command.
/// 4MB is proven stable on this device. Larger sizes trigger CSW fallback
/// retries that degrade throughput. Falls back dynamically on CSW failure.
pub const SCSI_MAX_TRANSFER_SIZE: usize = 4 * 1024 * 1024;

/// Minimum transfer size floor for SCSI adaptive fallback.
pub const SCSI_MIN_TRANSFER_SIZE: usize = 512 * 1024;

/// Initial chunk size per URB.
/// AIMD halves on ENOMEM and additively recovers after 200 clean cycles.
pub const INITIAL_URB_CHUNK_SIZE: usize = 256 * 1024;

/// Minimum URB chunk size (16KB).
pub const MIN_URB_CHUNK_SIZE: usize = 16 * 1024;

/// File read chunk size for the flasher.
pub const READ_CHUNK_SIZE: usize = ASYNC_BUFFER_SIZE;
