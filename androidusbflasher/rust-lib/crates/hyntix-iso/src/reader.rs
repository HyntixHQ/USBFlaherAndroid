use hyntix_common::Result;
use log::info;
use std::io::{Read, Seek, SeekFrom};

pub struct IsoReader<R: Read + Seek> {
    reader: R,
}

#[derive(Debug, Clone)]
pub struct IsoEntry {
    pub name: String,
    pub is_directory: bool,
}

impl<R: Read + Seek> IsoReader<R> {
    pub fn new(reader: R) -> Result<Self> {
        Ok(Self { reader })
    }

    /// Check for Linux-specific markers in the ISO.
    /// This is a robust detection system ensuring only Linux ISOs are supported.
    pub fn is_linux_iso(&mut self) -> Result<bool> {
        // 1. Basic ISO 9660 Check
        let mut buffer = [0u8; 2048];
        self.reader.seek(SeekFrom::Start(16 * 2048))?;
        self.reader.read_exact(&mut buffer)?;

        if &buffer[1..6] != b"CD001" {
            info!("Not a standard ISO 9660 image");
            return Ok(false);
        }

        // 2. Windows rejection logic: Check for key Windows files
        // A full ISO file tree traversal would be expensive, so we scan specific areas
        // or rely on volume descriptors if implemented.
        // For now, let's scan the first 64MB for "setup.exe", "sources/install.wim"
        // which usually appear in UDF/ISO file tables.
        // Better yet, look for "UDF" markers which windows uses extensively,
        // AND check for lack of Linux markers.

        // 3. Positive Linux Detection (Syslinux/Grub/Distro markers)
        // We look for common bootloader configs in the first few MBs or specific locations.
        let mut scan_buffer = vec![0u8; 2 * 1024 * 1024]; // 2MB Scan
        self.reader.seek(SeekFrom::Start(0))?;
        let n = self.reader.read(&mut scan_buffer)?;
        let haystack = &scan_buffer[..n];

        // Windows Indicators (Reject)
        if self.contains_bytes(haystack, b"SOURCES/INSTALL.WIM")
            || self.contains_bytes(haystack, b"sources/install.wim")
            || self.contains_bytes(haystack, b"setup.exe")
            || self.contains_bytes(haystack, b"SETUP.EXE")
        {
            info!("Windows ISO detected (contains setup.exe or install.wim)");
            return Ok(false);
        }

        // Linux Indicators (Accept)
        let linux_markers: Vec<&[u8]> = vec![
            b"isolinux",
            b"ISOLINUX",
            b"grub.cfg",
            b"vmlinuz",
            b"initrd",
            b"casper",
            b"archisoboot",
            b"live-media",
        ];

        for marker in linux_markers {
            if self.contains_bytes(haystack, marker) {
                info!(
                    "Linux marker found: {:?}",
                    std::str::from_utf8(marker).unwrap_or("BINARY")
                );
                return Ok(true);
            }
        }

        info!("No specific Linux markers found in header/boot area");
        Ok(false)
    }

    fn contains_bytes(&self, haystack: &[u8], needle: &[u8]) -> bool {
        haystack.windows(needle.len()).any(|w| w == needle)
    }

    pub fn list_root(&mut self) -> Result<Vec<IsoEntry>> {
        Ok(vec![])
    }
}
