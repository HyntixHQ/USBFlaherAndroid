//! Linux-specific ISO flashing logic.

use hyntix_common::{Error, Result};
use std::io::{Read, Seek, Write};
use std::sync::atomic::AtomicBool;
use std::sync::Arc;

pub struct LinuxFlashConfig {
    pub volume_label: String,
    pub persistence_size: Option<u64>,
}

impl Default for LinuxFlashConfig {
    fn default() -> Self {
        Self {
            volume_label: "LINUX_USB".to_string(),
            persistence_size: None,
        }
    }
}

pub struct LinuxFlasher {
    _cancelled: Arc<AtomicBool>,
}

impl LinuxFlasher {
    pub fn new(_cancelled: Arc<AtomicBool>) -> Self {
        Self { _cancelled }
    }

    pub fn flash<ISO, USB>(
        &mut self,
        _iso: ISO,
        _usb: USB,
        _progress: impl FnMut(String, u64, u64),
    ) -> Result<()>
    where
        ISO: Read + Seek,
        USB: Read + Write + Seek,
    {
        Err(Error::Io(std::io::Error::new(
            std::io::ErrorKind::Unsupported,
            "Linux ISO flashing not yet implemented",
        )))
    }
}
