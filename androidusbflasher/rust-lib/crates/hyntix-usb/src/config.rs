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
/// 8MB fits within typical Android kernel DMA pool (usbfs_memory_mb >= 12MB),
/// preventing ENOMEM oscillation seen at 16MB.
pub const TARGET_IN_FLIGHT_BYTES: usize = 8 * 1024 * 1024;

/// Number of buffers in the async pool.
pub const BUFFER_COUNT: usize = 16;

/// Size of each buffer in the async pool.
/// 4MB is optimal for filling the 32-URB pipeline.
pub const ASYNC_BUFFER_SIZE: usize = 4 * 1024 * 1024;

/// Maximum transfer size for a single SCSI WRITE(10)/READ(10) command.
/// 4MB matches the async buffer size, so each buffer = 1 SCSI command.
/// Falls back dynamically on CSW failure (see mass_storage.rs).
pub const SCSI_MAX_TRANSFER_SIZE: usize = 4 * 1024 * 1024;

/// Minimum transfer size floor for SCSI adaptive fallback.
pub const SCSI_MIN_TRANSFER_SIZE: usize = 512 * 1024;

/// Initial chunk size per URB.
/// 32KB is the stable default for Android DMA pools (~8MB).
/// AIMD additively increases to 64KB+ on devices with larger pools.
pub const INITIAL_URB_CHUNK_SIZE: usize = 32 * 1024;

/// Minimum URB chunk size (16KB).
/// Slightly smaller than the stable 32KB working size gives AIMD room
/// to fall back if transient DMA pressure occurs.
pub const MIN_URB_CHUNK_SIZE: usize = 16 * 1024;

/// File read chunk size for the flasher.
pub const READ_CHUNK_SIZE: usize = ASYNC_BUFFER_SIZE;
