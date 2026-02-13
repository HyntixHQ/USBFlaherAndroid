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
pub use mass_storage::{UsbMassStorage, UsbMassStorageWriter};
pub use native::NativeUsbBackend;

/// Trait for types that can report their physical I/O progress.
pub trait PhysicalProgress {
    /// Get the actual number of bytes written to the physical device.
    fn physical_position(&self) -> u64;
}

impl<T: PhysicalProgress + ?Sized> PhysicalProgress for &mut T {
    fn physical_position(&self) -> u64 {
        (**self).physical_position()
    }
}
impl<T: PhysicalProgress + ?Sized> PhysicalProgress for Box<T> {
    fn physical_position(&self) -> u64 {
        (**self).physical_position()
    }
}
