//! USB Mass Storage Class (Bulk-Only Transport) implementation.
//!
//! This module provides functionality to communicate with USB mass storage
//! devices using SCSI commands over the Bulk-Only Transport protocol.

pub mod async_writer;
pub mod cbw;
pub mod config;
pub mod csw;
pub mod mass_storage;
pub mod native;
pub mod scsi;

pub use async_writer::AsyncUsbWriter;
pub use cbw::CommandBlockWrapper;
pub use csw::CommandStatusWrapper;
pub use mass_storage::UsbMassStorage;
pub use native::NativeUsbBackend;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;

/// Trait for types that can report their physical I/O progress.
pub trait PhysicalProgress {
    /// Get the actual number of bytes written to the physical device.
    fn physical_position(&self) -> u64;
    /// Get the total capacity of the physical device.
    fn total_capacity(&self) -> u64;
    /// Handle to the atomic physical write position, when the writer performs
    /// asynchronous I/O.
    ///
    /// A dedicated progress publisher can poll this handle at a fixed cadence
    /// without borrowing the writer, so UI updates continue even while the
    /// flashing thread is blocked waiting for a free pipeline buffer.
    fn physical_progress_handle(&self) -> Option<Arc<AtomicU64>> {
        None
    }
    /// Drain queued writes, invoking `progress` with the physical position.
    ///
    /// Implementations that buffer writes should override this so callers can
    /// keep reporting real device progress while the final pipeline drains.
    fn drain_with_progress(&mut self, _progress: &mut dyn FnMut(u64)) -> std::io::Result<()> {
        Ok(())
    }
}

impl<T: PhysicalProgress + ?Sized> PhysicalProgress for &mut T {
    fn physical_position(&self) -> u64 {
        (**self).physical_position()
    }
    fn total_capacity(&self) -> u64 {
        (**self).total_capacity()
    }
    fn physical_progress_handle(&self) -> Option<Arc<AtomicU64>> {
        (**self).physical_progress_handle()
    }
    fn drain_with_progress(&mut self, progress: &mut dyn FnMut(u64)) -> std::io::Result<()> {
        (**self).drain_with_progress(progress)
    }
}
impl<T: PhysicalProgress + ?Sized> PhysicalProgress for Box<T> {
    fn physical_position(&self) -> u64 {
        (**self).physical_position()
    }
    fn total_capacity(&self) -> u64 {
        (**self).total_capacity()
    }
    fn physical_progress_handle(&self) -> Option<Arc<AtomicU64>> {
        (**self).physical_progress_handle()
    }
    fn drain_with_progress(&mut self, progress: &mut dyn FnMut(u64)) -> std::io::Result<()> {
        (**self).drain_with_progress(progress)
    }
}
