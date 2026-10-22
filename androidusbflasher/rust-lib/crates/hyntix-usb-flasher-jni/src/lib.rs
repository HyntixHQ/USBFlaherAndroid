use hyntix_partition::PartitionError;
use hyntix_persistence::{
    self as persistence, PersistenceActivation as CorePersistenceActivation,
    PersistenceError as CorePersistenceError, PersistencePhase as CorePersistencePhase,
    PersistenceProfile as CorePersistenceProfile,
};
use hyntix_storage::{BlockDevice, StorageError};
use hyntix_usb::{NativeUsbBackend, UsbMassStorage};
use hyntix_usb_flasher::{FlashPhase as CoreFlashPhase, Flasher as CoreFlasher};
use std::fs::File;
use std::io::{self, Seek, SeekFrom};
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use thiserror::Error;
use tracing::info;

uniffi::setup_scaffolding!();

#[cfg(target_os = "android")]
fn init_logging() {
    use tracing_subscriber::prelude::*;
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        if let Ok(layer) = tracing_android::layer("UsbFlasherRust") {
            tracing_subscriber::registry().with(layer).init();
        }
        log_diagnostics();
    });
}

#[cfg(not(target_os = "android"))]
fn init_logging() {
    static ONCE: std::sync::Once = std::sync::Once::new();
    ONCE.call_once(|| {
        tracing_subscriber::fmt::init();
        log_diagnostics();
    });
}

/// Log USB system diagnostics at startup for performance tuning.
fn log_diagnostics() {
    // Read kernel DMA pool size
    let usbfs_path = "/sys/module/usbcore/parameters/usbfs_memory_mb";
    match std::fs::read_to_string(usbfs_path) {
        Ok(val) => info!("Diagnostics: {} = {} MB", usbfs_path, val.trim()),
        Err(_) => info!("Diagnostics: could not read {} (non-fatal)", usbfs_path),
    }

    info!(
        "Diagnostics: SCSI_MAX_TRANSFER_SIZE={}KB, INITIAL_URB_CHUNK_SIZE={}KB, URB_PIPELINE_DEPTH={}",
        hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE / 1024,
        hyntix_usb::config::INITIAL_URB_CHUNK_SIZE / 1024,
        hyntix_usb::config::URB_PIPELINE_DEPTH,
    );
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
    /// Called at file boundaries while copying a Windows ISO. This is a
    /// low-frequency hint for the UI, separate from physical progress.
    fn on_file(&self, name: String);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PersistenceProfile {
    Casper,
    DebianLive,
}

impl From<CorePersistenceProfile> for PersistenceProfile {
    fn from(profile: CorePersistenceProfile) -> Self {
        match profile {
            CorePersistenceProfile::Casper => PersistenceProfile::Casper,
            CorePersistenceProfile::DebianLive => PersistenceProfile::DebianLive,
        }
    }
}

impl From<PersistenceProfile> for CorePersistenceProfile {
    fn from(profile: PersistenceProfile) -> Self {
        match profile {
            PersistenceProfile::Casper => CorePersistenceProfile::Casper,
            PersistenceProfile::DebianLive => CorePersistenceProfile::DebianLive,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PersistenceActivation {
    BuiltIn,
    Patchable,
    Manual,
}

impl From<CorePersistenceActivation> for PersistenceActivation {
    fn from(activation: CorePersistenceActivation) -> Self {
        match activation {
            CorePersistenceActivation::BuiltIn => PersistenceActivation::BuiltIn,
            CorePersistenceActivation::Patchable => PersistenceActivation::Patchable,
            CorePersistenceActivation::Manual => PersistenceActivation::Manual,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, uniffi::Enum)]
pub enum PersistencePhase {
    Planning,
    Formatting,
    Configuring,
    ActivatingBoot,
    Partitioning,
    Verifying,
}

impl From<CorePersistencePhase> for PersistencePhase {
    fn from(phase: CorePersistencePhase) -> Self {
        match phase {
            CorePersistencePhase::Planning => PersistencePhase::Planning,
            CorePersistencePhase::Formatting => PersistencePhase::Formatting,
            CorePersistencePhase::Configuring => PersistencePhase::Configuring,
            CorePersistencePhase::ActivatingBoot => PersistencePhase::ActivatingBoot,
            CorePersistencePhase::Partitioning => PersistencePhase::Partitioning,
            CorePersistencePhase::Verifying => PersistencePhase::Verifying,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PersistenceSupport {
    pub profile: PersistenceProfile,
    pub activation: PersistenceActivation,
    pub label: String,
    pub min_bytes: u64,
    pub recommended_bytes: u64,
    pub reason: Option<String>,
}

impl From<persistence::PersistenceSupport> for PersistenceSupport {
    fn from(support: persistence::PersistenceSupport) -> Self {
        Self {
            profile: support.profile.into(),
            activation: support.activation.into(),
            label: support.label,
            min_bytes: support.min_bytes,
            recommended_bytes: support.recommended_bytes,
            reason: support.reason,
        }
    }
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct PersistenceResult {
    pub profile: PersistenceProfile,
    pub activation: PersistenceActivation,
    pub label: String,
    pub size_bytes: u64,
    pub partition_start_lba: u64,
    pub partition_index: u32,
    pub reused_existing: bool,
    pub patches_applied: bool,
}

impl From<persistence::PersistenceResult> for PersistenceResult {
    fn from(result: persistence::PersistenceResult) -> Self {
        Self {
            profile: result.profile.into(),
            activation: result.activation.into(),
            label: result.label,
            size_bytes: result.partition_sector_count * 512,
            partition_start_lba: result.partition_start_lba,
            partition_index: result.partition_index,
            reused_existing: result.action == persistence::PersistenceAction::Reuse,
            patches_applied: result.patches_applied,
        }
    }
}

#[derive(Debug, Error, uniffi::Error)]
pub enum PersistenceError {
    #[error("unsupported ISO: {reason}")]
    UnsupportedIso { reason: String },
    #[error("insufficient free space: need {need} bytes, have {have}")]
    InsufficientFreeSpace { need: u64, have: u64 },
    #[error("no free MBR primary partition slot")]
    NoMbrSlot,
    #[error("hybrid MBR layout is not safe to edit")]
    HybridMbrUnsupported,
    #[error("unsupported logical sector size: {0}")]
    UnsupportedSectorSize(u32),
    #[error("partition table verification failed: {0}")]
    PartitionVerify(String),
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

impl From<CorePersistenceError> for PersistenceError {
    fn from(error: CorePersistenceError) -> Self {
        match error {
            CorePersistenceError::Storage(error) => match error {
                StorageError::UnsupportedSectorSize(size) => {
                    PersistenceError::UnsupportedSectorSize(size)
                }
                other => PersistenceError::Io(other.to_string()),
            },
            CorePersistenceError::Partition(error) => match error {
                PartitionError::InsufficientSpace { need, have } => {
                    PersistenceError::InsufficientFreeSpace { need, have }
                }
                PartitionError::NoMbrSlot => PersistenceError::NoMbrSlot,
                PartitionError::HybridMbrUnsupported => PersistenceError::HybridMbrUnsupported,
                PartitionError::Storage(StorageError::UnsupportedSectorSize(size)) => {
                    PersistenceError::UnsupportedSectorSize(size)
                }
                PartitionError::Storage(other) => PersistenceError::Io(other.to_string()),
                other => PersistenceError::PartitionVerify(other.to_string()),
            },
            CorePersistenceError::UnsupportedIso(reason) => {
                PersistenceError::UnsupportedIso { reason }
            }
            CorePersistenceError::UnsupportedSectorSize(size) => {
                PersistenceError::UnsupportedSectorSize(size)
            }
            CorePersistenceError::Ext2(message) => PersistenceError::Ext2(message),
            CorePersistenceError::Activation(message) => PersistenceError::Activation(message),
            CorePersistenceError::Verify(message) => PersistenceError::Verify(message),
            CorePersistenceError::Cancelled => PersistenceError::Cancelled,
            CorePersistenceError::Io(message) => PersistenceError::Io(message),
        }
    }
}

#[uniffi::export(callback_interface)]
pub trait PersistenceCallback: Send + Sync {
    fn on_progress(&self, phase: PersistencePhase, current: u64, total: u64);
}

/// Adapter that exposes the USB mass-storage transport as a block device for
/// the persistence stack.
struct UsbBlock<'a> {
    storage: &'a mut UsbMassStorage,
}

impl BlockDevice for UsbBlock<'_> {
    fn sector_size(&self) -> u32 {
        self.storage.block_size()
    }

    fn sector_count(&self) -> u64 {
        self.storage.block_count()
    }

    fn read_sectors(&mut self, lba: u64, out: &mut [u8]) -> hyntix_storage::Result<()> {
        self.storage
            .read_blocks(lba, out)
            .map_err(|error| StorageError::Io(io::Error::other(error.to_string())))
    }

    fn write_sectors(&mut self, lba: u64, data: &[u8]) -> hyntix_storage::Result<()> {
        self.storage
            .write_blocks(lba, data)
            .map_err(|error| StorageError::Io(io::Error::other(error.to_string())))
    }
}

fn dup_file(fd: i32, label: &str) -> Result<File, PersistenceError> {
    let dup_fd = unsafe { libc::dup(fd) };
    if dup_fd < 0 {
        return Err(PersistenceError::Io(format!(
            "failed to dup {label} FD {fd}: {}",
            io::Error::last_os_error()
        )));
    }
    Ok(unsafe { File::from_raw_fd(dup_fd) })
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
                msg: format!(
                    "Failed to dup FD {}: {}",
                    fd,
                    std::io::Error::last_os_error()
                ),
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
                msg: format!(
                    "Failed to dup FD {}: {}",
                    fd,
                    std::io::Error::last_os_error()
                ),
            });
        }
        let file = unsafe { File::from_raw_fd(dup_fd) };

        let core_flasher = CoreFlasher::new(self.cancel_token.clone());
        core_flasher
            .is_windows_iso(file)
            .map_err(FlasherError::from)
    }

    pub fn get_device_capacity(
        &self,
        usb_fd: i32,
        interface: u8,
        in_ep: u8,
        out_ep: u8,
    ) -> Result<u64, FlasherError> {
        info!(
            "UsbFlasherJni: get_device_capacity(usb_fd={}, iface={}, in={}, out={})",
            usb_fd, interface, in_ep, out_ep
        );
        // Construct NativeUsbBackend
        let backend = NativeUsbBackend::new(usb_fd, interface, in_ep, out_ep);

        // Construct Mass Storage Wrapper and probe capacity (simulated by new_native init)
        let storage =
            UsbMassStorage::new_native(backend, 0, hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE)
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
        let storage =
            UsbMassStorage::new_native(backend, 0, hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE)
                .map_err(FlasherError::from)?;

        // Construct File from FD
        // Duplicate file descriptor to avoid fdsan issues on Android
        // (Rust File::from_raw_fd takes ownership and closes it, which we want to avoid for the original FD)
        let dup_image_fd = unsafe { libc::dup(image_fd) };
        if dup_image_fd < 0 {
            return Err(FlasherError::IoError {
                msg: format!(
                    "Failed to dup image FD {}: {}",
                    image_fd,
                    std::io::Error::last_os_error()
                ),
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
            .flash_raw(
                source,
                storage,
                total_size,
                verify,
                |phase, current, total| {
                    callback.on_progress(phase.into(), current, total);
                },
            )
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
        let storage =
            UsbMassStorage::new_native(backend, 0, hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE)
                .map_err(FlasherError::from)?;

        let dup_image_fd = unsafe { libc::dup(image_fd) };
        if dup_image_fd < 0 {
            return Err(FlasherError::IoError {
                msg: format!(
                    "Failed to dup image FD {}: {}",
                    image_fd,
                    std::io::Error::last_os_error()
                ),
            });
        }
        let source = unsafe { File::from_raw_fd(dup_image_fd) };

        let core_flasher = CoreFlasher::new(self.cancel_token.clone());

        // The callback is used from both the progress publisher thread and the
        // flash thread (file boundaries), so share it instead of moving it into
        // one closure.
        let callback = Arc::new(callback);
        let progress_callback = Arc::clone(&callback);
        let file_callback = Arc::clone(&callback);

        core_flasher
            .flash_windows(
                source,
                storage,
                0,
                move |phase, current, total| {
                    progress_callback.on_progress(phase.into(), current, total);
                },
                move |name| {
                    file_callback.on_file(name.to_string());
                },
            )
            .map_err(FlasherError::from)
    }

    /// Inspect an image without touching the USB device. Returns `None` when the
    /// image is a supported ISO but has no persistence profile.
    pub fn probe_persistence(
        &self,
        image_fd: i32,
    ) -> Result<Option<PersistenceSupport>, PersistenceError> {
        let mut source = dup_file(image_fd, "image")?;
        let image_len = source
            .seek(SeekFrom::End(0))
            .map_err(|error| PersistenceError::Io(error.to_string()))?;
        source
            .seek(SeekFrom::Start(0))
            .map_err(|error| PersistenceError::Io(error.to_string()))?;

        match persistence::iso::probe_iso(&mut source, image_len) {
            Ok(Some(support)) => Ok(Some(support.into())),
            Ok(None) => Ok(None),
            Err(CorePersistenceError::UnsupportedIso(reason)) => {
                Err(PersistenceError::UnsupportedIso { reason })
            }
            Err(error) => Err(error.into()),
        }
    }

    /// Append (or reuse) a persistence partition on an already-flashed Linux
    /// live USB. The image file descriptor is read-only from Rust's point of
    /// view; all writes go through the USB mass-storage handle.
    #[allow(clippy::too_many_arguments)]
    pub fn apply_persistence(
        &self,
        image_fd: i32,
        usb_fd: i32,
        interface: u8,
        in_ep: u8,
        out_ep: u8,
        profile: PersistenceProfile,
        size_bytes: u64,
        callback: Box<dyn PersistenceCallback>,
    ) -> Result<PersistenceResult, PersistenceError> {
        self.cancel_token.store(false, Ordering::SeqCst);

        info!(
            "UsbFlasherJni: apply_persistence(usb_fd={}, iface={}, in={}, out={}, profile={:?}, size={})",
            usb_fd, interface, in_ep, out_ep, profile, size_bytes
        );

        let backend = NativeUsbBackend::new(usb_fd, interface, in_ep, out_ep);
        let mut storage =
            UsbMassStorage::new_native(backend, 0, hyntix_usb::config::SCSI_MAX_TRANSFER_SIZE)
                .map_err(|error| PersistenceError::Io(error.to_string()))?;

        let mut source = dup_file(image_fd, "image")?;
        let image_len = source
            .seek(SeekFrom::End(0))
            .map_err(|error| PersistenceError::Io(error.to_string()))?;
        source
            .seek(SeekFrom::Start(0))
            .map_err(|error| PersistenceError::Io(error.to_string()))?;

        let mut block = UsbBlock {
            storage: &mut storage,
        };
        let cancel_token = Arc::clone(&self.cancel_token);
        let cancelled = move || cancel_token.load(Ordering::SeqCst);

        let result = persistence::apply(
            &mut block,
            &mut source,
            image_len,
            profile.into(),
            size_bytes,
            &mut |progress| {
                callback.on_progress(progress.phase.into(), progress.current, progress.total);
            },
            &cancelled,
        )?;

        Ok(result.into())
    }

    pub fn cancel(&self) {
        self.cancel_token.store(true, Ordering::SeqCst);
        info!("Cancel requested via UniFFI");
    }
}
