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
pub const TARGET_IN_FLIGHT_BYTES: usize = 16 * 1024 * 1024;

/// Number of buffers in the async pool.
pub const BUFFER_COUNT: usize = 16;

/// Size of each buffer in the async pool.
/// 4MB is optimal for filling the 32-URB pipeline.
pub const ASYNC_BUFFER_SIZE: usize = 4 * 1024 * 1024;

/// Maximum transfer size for a single SCSI WRITE(10)/READ(10) command.
/// 1MB is a safe value that works on almost all USB controllers while
/// maintaining high throughput (minimizing CBW/CSW overhead).
pub const SCSI_MAX_TRANSFER_SIZE: usize = 1024 * 1024;

/// Initial chunk size per URB.
pub const INITIAL_URB_CHUNK_SIZE: usize = 32 * 1024;

/// Minimum URB chunk size (16KB).
pub const MIN_URB_CHUNK_SIZE: usize = 16 * 1024;

/// File read chunk size for the flasher.
pub const READ_CHUNK_SIZE: usize = ASYNC_BUFFER_SIZE;
