//! Linux live-USB persistence support.
//!
//! This crate owns distro profiles, ISO9660 probing, the minimal ext2 writer,
//! boot-configuration activation, planning, and verification. It does not know
//! about USB transport or Android.

pub mod activation;
pub mod ext2;
pub mod iso;
pub mod plan;
pub mod profile;
pub mod verify;

#[cfg(test)]
mod test_support;

pub use plan::*;
pub use profile::*;

use hyntix_partition::PartitionError;
use hyntix_storage::StorageError;

/// Errors returned by persistence operations.
#[derive(Debug, thiserror::Error)]
pub enum PersistenceError {
    #[error(transparent)]
    Storage(#[from] StorageError),

    #[error(transparent)]
    Partition(#[from] PartitionError),

    #[error("unsupported ISO: {0}")]
    UnsupportedIso(String),

    #[error("unsupported logical sector size: {0}")]
    UnsupportedSectorSize(u32),

    #[error("ext2 formatting failed: {0}")]
    Ext2(String),

    #[error("boot activation failed: {0}")]
    Activation(String),

    #[error("verification failed: {0}")]
    Verify(String),

    #[error("cancelled")]
    Cancelled,

    #[error("I/O error: {0}")]
    Io(String),
}

pub type Result<T> = std::result::Result<T, PersistenceError>;
