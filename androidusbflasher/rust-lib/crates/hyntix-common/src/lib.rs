//! Error types for the Windows ISO Flasher library.

use thiserror::Error;

/// Result type alias using the library's Error type.
pub type Result<T> = std::result::Result<T, Error>;

/// Errors that can occur during ISO flashing operations.
#[derive(Debug, Error)]
pub enum Error {
    // ─────────────────────────────────────────────────────────────────────────────
    // ISO Parsing Errors
    // ─────────────────────────────────────────────────────────────────────────────
    /// The file is not a valid ISO 9660 image.
    #[error("Invalid ISO 9660 format: {0}")]
    InvalidIso(String),

    /// The ISO does not contain expected Windows installation files.
    #[error("Not a Windows installation ISO: missing {0}")]
    NotWindowsIso(String),

    /// UDF filesystem parsing failed.
    #[error("UDF parsing error: {0}")]
    UdfError(String),

    /// Failed to read directory structure.
    #[error("Failed to read directory: {0}")]
    DirectoryReadError(String),

    /// File not found in ISO.
    #[error("File not found in ISO: {0}")]
    FileNotFound(String),

    // ─────────────────────────────────────────────────────────────────────────────
    // FAT32 Formatting Errors
    // ─────────────────────────────────────────────────────────────────────────────
    /// Device is too small for Windows installation.
    #[error("Device too small: need at least {required} bytes, have {available} bytes")]
    DeviceTooSmall { required: u64, available: u64 },

    /// Device is too large for FAT32.
    #[error("Device too large for FAT32: {0} bytes (max 2TB)")]
    DeviceTooLarge(u64),

    /// Failed to create partition table.
    #[error("Failed to create GPT: {0}")]
    GptError(String),

    /// Failed to format FAT32 filesystem.
    #[error("FAT32 format error: {0}")]
    Fat32FormatError(String),

    /// Cluster allocation failed.
    #[error("Failed to allocate cluster: {0}")]
    ClusterAllocationError(String),

    // ─────────────────────────────────────────────────────────────────────────────
    // WIM Handling Errors
    // ─────────────────────────────────────────────────────────────────────────────
    /// Invalid WIM file header.
    #[error("Invalid WIM header: {0}")]
    InvalidWimHeader(String),

    /// WIM splitting failed.
    #[error("WIM split error: {0}")]
    WimSplitError(String),

    // ─────────────────────────────────────────────────────────────────────────────
    // I/O Errors
    // ─────────────────────────────────────────────────────────────────────────────
    /// Standard I/O error.
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Failed to seek to position.
    #[error("Seek error at offset {offset}: {message}")]
    SeekError { offset: u64, message: String },

    /// Read/write size mismatch.
    #[error("Expected to {operation} {expected} bytes, but got {actual}")]
    SizeMismatch {
        operation: &'static str,
        expected: usize,
        actual: usize,
    },

    // ─────────────────────────────────────────────────────────────────────────────
    // Operation Errors
    // ─────────────────────────────────────────────────────────────────────────────
    /// Operation was cancelled by user.
    #[error("Operation cancelled")]
    Cancelled,

    /// Flash operation already in progress.
    #[error("Flash operation already in progress")]
    AlreadyInProgress,

    /// Device is busy or locked.
    #[error("Device is busy: {0}")]
    DeviceBusy(String),

    // ─────────────────────────────────────────────────────────────────────────────
    // USB Errors
    // ─────────────────────────────────────────────────────────────────────────────
    /// USB mass storage protocol error.
    #[error("USB error: {0}")]
    UsbError(String),
}
