use hyntix_usb::{NativeUsbBackend, UsbMassStorage};
use hyntix_usb_flasher::{FlashPhase as CoreFlashPhase, Flasher as CoreFlasher};
use log::{info, LevelFilter};
use std::fs::File;
use std::io::{Seek, SeekFrom};
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use thiserror::Error;

uniffi::setup_scaffolding!();

fn init_logging() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        let _ = android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(LevelFilter::Debug)
                .with_tag("UsbFlasherRust"),
        );
    });
}

#[derive(Debug, Error, uniffi::Error)]
pub enum FlasherError {
    #[error("IO Error: {msg}")]
    IoError { msg: String },
    #[error("USB Error: {msg}")]
    UsbError { msg: String },
    #[error("Cancelled")]
    Cancelled,
    #[error("Invalid ISO: {msg}")]
    InvalidIso { msg: String },
    #[error("Unknown error: {msg}")]
    Unknown { msg: String },
}

impl From<hyntix_common::Error> for FlasherError {
    fn from(e: hyntix_common::Error) -> Self {
        match e {
            hyntix_common::Error::Io(e) => FlasherError::IoError { msg: e.to_string() },
            hyntix_common::Error::UsbError(msg) => FlasherError::UsbError { msg },
            hyntix_common::Error::Cancelled => FlasherError::Cancelled,
            _ => FlasherError::Unknown {
                msg: format!("{:?}", e),
            },
        }
    }
}

#[derive(uniffi::Enum)]
pub enum FlashPhase {
    Validating,
    Formatting,
    Flashing,
    Verifying,
    Finalizing,
}

impl From<CoreFlashPhase> for FlashPhase {
    fn from(p: CoreFlashPhase) -> Self {
        match p {
            CoreFlashPhase::Validating => FlashPhase::Validating,
            CoreFlashPhase::Formatting => FlashPhase::Formatting,
            CoreFlashPhase::Flashing => FlashPhase::Flashing,
            CoreFlashPhase::Verifying => FlashPhase::Verifying,
            CoreFlashPhase::Finalizing => FlashPhase::Finalizing,
        }
    }
}

#[uniffi::export(callback_interface)]
pub trait FlashCallback: Send + Sync {
    fn on_progress(&self, phase: FlashPhase, current: u64, total: u64);
}

#[derive(uniffi::Object)]
pub struct UsbFlasher {
    cancel_token: Arc<AtomicBool>,
}

#[uniffi::export]
impl UsbFlasher {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        init_logging();
        Arc::new(Self {
            cancel_token: Arc::new(AtomicBool::new(false)),
        })
    }

    pub fn is_linux_iso(&self, fd: i32) -> Result<bool, FlasherError> {
        // Safety: The caller must ensure fd is valid and owned by us (or dup'd).
        // Since we likely just read, borrowing it or duping is safer,
        // but FromRawFd consumes it. We should assume the Kotlin side passed ownership
        // or we clone it if possible. For simple checking, we should probably dup it
        // if we want to avoid closing the original if the method merely snoops.
        // However, standard UniFFI practice roughly assumes ownership transfer for such types usually?
        // Let's use `ManuallyDrop` or similar if we want to not close it?
        // Actually `File::from_raw_fd` WILL close on drop.
        // Ideally we should rely on the caller to manage lifecycle, but `File` consumes.
        // Let's assume ownership is transferred for this call.
        let dup_fd = unsafe { libc::dup(fd) };
        if dup_fd < 0 {
            return Err(FlasherError::IoError {
                msg: format!("Failed to dup FD {}: {}", fd, std::io::Error::last_os_error()),
            });
        }
        let file = unsafe { File::from_raw_fd(dup_fd) };

        let core_flasher = CoreFlasher::new(self.cancel_token.clone());
        core_flasher.is_linux_iso(file).map_err(FlasherError::from)
    }

    pub fn is_windows_iso(&self, fd: i32) -> Result<bool, FlasherError> {
        let dup_fd = unsafe { libc::dup(fd) };
        if dup_fd < 0 {
            return Err(FlasherError::IoError {
                msg: format!("Failed to dup FD {}: {}", fd, std::io::Error::last_os_error()),
            });
        }
        let file = unsafe { File::from_raw_fd(dup_fd) };

        let core_flasher = CoreFlasher::new(self.cancel_token.clone());
        core_flasher.is_windows_iso(file).map_err(FlasherError::from)
    }

    pub fn get_device_capacity(
        &self,
        usb_fd: i32,
        interface: u8,
        in_ep: u8,
        out_ep: u8,
    ) -> Result<u64, FlasherError> {
        info!("UsbFlasherJni: get_device_capacity(usb_fd={}, iface={}, in={}, out={})", usb_fd, interface, in_ep, out_ep);
        // Construct NativeUsbBackend
        let backend = NativeUsbBackend::new(usb_fd, interface, in_ep, out_ep);

        // Construct Mass Storage Wrapper and probe capacity (simulated by new_native init)
        let storage = UsbMassStorage::new_native(
            backend,
            0,
            hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE,
        )
        .map_err(FlasherError::from)?;
        
        let cap = storage.capacity();
        info!("UsbFlasherJni: Capacity result: {} bytes", cap);
        Ok(cap)
    }

    pub fn flash_device(
        &self,
        image_fd: i32,
        usb_fd: i32,
        interface: u8,
        in_ep: u8,
        out_ep: u8,
        verify: bool,
        callback: Box<dyn FlashCallback>,
    ) -> Result<(), FlasherError> {
        self.cancel_token.store(false, Ordering::SeqCst);

        // Construct NativeUsbBackend
        let backend = NativeUsbBackend::new(usb_fd, interface, in_ep, out_ep);

        // Construct Mass Storage Wrapper
        let storage = UsbMassStorage::new_native(
            backend,
            0,
            hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE,
        )
        .map_err(FlasherError::from)?;

        // Construct File from FD
        // Duplicate file descriptor to avoid fdsan issues on Android
        // (Rust File::from_raw_fd takes ownership and closes it, which we want to avoid for the original FD)
        let dup_image_fd = unsafe { libc::dup(image_fd) };
        if dup_image_fd < 0 {
            return Err(FlasherError::IoError {
                msg: format!("Failed to dup image FD {}: {}", image_fd, std::io::Error::last_os_error()),
            });
        }
        let mut source = unsafe { File::from_raw_fd(dup_image_fd) };
        let total_size = source
            .seek(SeekFrom::End(0))
            .map_err(|e| FlasherError::IoError { msg: e.to_string() })?;
        source
            .seek(SeekFrom::Start(0))
            .map_err(|e| FlasherError::IoError { msg: e.to_string() })?;

        let core_flasher = CoreFlasher::new(self.cancel_token.clone());

        core_flasher
            .flash_raw(source, storage, total_size, verify, |phase, current, total| {
                callback.on_progress(phase.into(), current, total);
            })
            .map_err(FlasherError::from)
    }

    pub fn flash_windows_device(
        &self,
        image_fd: i32,
        usb_fd: i32,
        interface: u8,
        in_ep: u8,
        out_ep: u8,
        callback: Box<dyn FlashCallback>,
    ) -> Result<(), FlasherError> {
        self.cancel_token.store(false, Ordering::SeqCst);

        let backend = NativeUsbBackend::new(usb_fd, interface, in_ep, out_ep);
        let storage = UsbMassStorage::new_native(
            backend,
            0,
            hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE,
        )
        .map_err(FlasherError::from)?;

        let dup_image_fd = unsafe { libc::dup(image_fd) };
        if dup_image_fd < 0 {
            return Err(FlasherError::IoError {
                msg: format!("Failed to dup image FD {}: {}", image_fd, std::io::Error::last_os_error()),
            });
        }
        let source = unsafe { File::from_raw_fd(dup_image_fd) };
        
        let core_flasher = CoreFlasher::new(self.cancel_token.clone());

        core_flasher
            .flash_windows(source, storage, 0, |phase, current, total| {
                callback.on_progress(phase.into(), current, total);
            })
            .map_err(FlasherError::from)
    }

    pub fn cancel(&self) {
        self.cancel_token.store(true, Ordering::SeqCst);
        info!("Cancel requested via UniFFI");
    }
}
