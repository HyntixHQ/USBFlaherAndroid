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
/// Pipeline depth: Number of URBs that can be in-flight simultaneously.
pub const URB_PIPELINE_DEPTH: usize = 32;

/// Number of buffers in the async pool.
/// 8 buffers (8 * 32MB = 256MB) allows a massive read-ahead cushion.
pub const BUFFER_COUNT: usize = 8;

/// Initial chunk size per URB (1MB).
pub const INITIAL_URB_CHUNK_SIZE: usize = 1024 * 1024;

/// Minimum URB chunk size (16KB).
pub const MIN_URB_CHUNK_SIZE: usize = 16 * 1024;

/// Maximum transfer size for a single SCSI WRITE(10) command.
/// Aligned to exactly 65535 blocks (33,553,920 bytes) to avoid residue.
pub const MAX_TRANSFER_SIZE: usize = 65535 * 512;

/// File read chunk size for the flasher.
pub const READ_CHUNK_SIZE: usize = MAX_TRANSFER_SIZE;
