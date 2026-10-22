//! Sector-oriented block device abstraction used by the persistence stack.
//!
//! The same code runs against a USB mass-storage device on Android and against
//! a sparse file on the host. All device access goes through this trait so the
//! filesystem and partition code stays testable without hardware.

use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

/// Result type for block-device operations.
pub type Result<T> = std::result::Result<T, StorageError>;

/// Errors returned by [`BlockDevice`] implementations.
#[derive(Debug, thiserror::Error)]
pub enum StorageError {
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("unsupported logical sector size: {0} bytes (only 512 is supported)")]
    UnsupportedSectorSize(u32),

    #[error("unaligned operation: lba={lba}, sectors={sectors}, sector_size={sector_size}")]
    Unaligned {
        lba: u64,
        sectors: u64,
        sector_size: u32,
    },

    #[error("out of range: lba={lba}, sectors={sectors}, device_sectors={device_sectors}")]
    OutOfRange {
        lba: u64,
        sectors: u64,
        device_sectors: u64,
    },

    #[error(
        "invalid length {len}: must be a non-zero multiple of the {sector_size}-byte sector size"
    )]
    InvalidLength { len: usize, sector_size: u32 },

    #[error("short write: expected {expected} bytes, wrote {actual}")]
    ShortWrite { expected: usize, actual: usize },
}

/// A device addressed in fixed-size logical sectors.
pub trait BlockDevice {
    /// Logical sector size in bytes.
    fn sector_size(&self) -> u32;

    /// Number of addressable logical sectors.
    fn sector_count(&self) -> u64;

    /// Read `out.len() / sector_size` sectors starting at `lba`.
    fn read_sectors(&mut self, lba: u64, out: &mut [u8]) -> Result<()>;

    /// Write `data.len() / sector_size` sectors starting at `lba`.
    fn write_sectors(&mut self, lba: u64, data: &[u8]) -> Result<()>;

    /// Total device capacity in bytes.
    fn byte_len(&self) -> u64 {
        self.sector_count() * self.sector_size() as u64
    }

    /// Read an arbitrary byte range, using read-modify-write for the edges.
    fn read_at(&mut self, offset: u64, out: &mut [u8]) -> Result<()> {
        if out.is_empty() {
            return Ok(());
        }
        let ss = self.sector_size() as u64;
        let end = offset
            .checked_add(out.len() as u64)
            .ok_or(StorageError::OutOfRange {
                lba: offset / ss,
                sectors: 1,
                device_sectors: self.sector_count(),
            })?;
        if end > self.byte_len() {
            return Err(StorageError::OutOfRange {
                lba: offset / ss,
                sectors: (end - offset).div_ceil(ss),
                device_sectors: self.sector_count(),
            });
        }

        let first_lba = offset / ss;
        let last_lba = (end - 1) / ss;
        let sector_count = last_lba - first_lba + 1;
        let mut buf = vec![0u8; (sector_count * ss) as usize];
        self.read_sectors(first_lba, &mut buf)?;
        let start = (offset - first_lba * ss) as usize;
        out.copy_from_slice(&buf[start..start + out.len()]);
        Ok(())
    }

    /// Write an arbitrary byte range, using read-modify-write for the edges.
    fn write_at(&mut self, offset: u64, data: &[u8]) -> Result<()> {
        if data.is_empty() {
            return Ok(());
        }
        let ss = self.sector_size() as u64;
        let end = offset
            .checked_add(data.len() as u64)
            .ok_or(StorageError::OutOfRange {
                lba: offset / ss,
                sectors: 1,
                device_sectors: self.sector_count(),
            })?;
        if end > self.byte_len() {
            return Err(StorageError::OutOfRange {
                lba: offset / ss,
                sectors: (end - offset).div_ceil(ss),
                device_sectors: self.sector_count(),
            });
        }

        let first_lba = offset / ss;
        let last_lba = (end - 1) / ss;
        let sector_count = last_lba - first_lba + 1;
        let mut buf = vec![0u8; (sector_count * ss) as usize];
        self.read_sectors(first_lba, &mut buf)?;
        let start = (offset - first_lba * ss) as usize;
        buf[start..start + data.len()].copy_from_slice(data);
        self.write_sectors(first_lba, &buf)
    }
}

/// Reject devices whose logical sector size is not supported by v1.
pub fn require_512_byte_sectors(dev: &dyn BlockDevice) -> Result<()> {
    if dev.sector_size() != 512 {
        return Err(StorageError::UnsupportedSectorSize(dev.sector_size()));
    }
    Ok(())
}

fn validate_range(lba: u64, len: usize, sector_size: u32, sector_count: u64) -> Result<u64> {
    if len == 0 || !len.is_multiple_of(sector_size as usize) {
        return Err(StorageError::InvalidLength { len, sector_size });
    }
    let sectors = (len / sector_size as usize) as u64;
    let end = lba.checked_add(sectors).ok_or(StorageError::OutOfRange {
        lba,
        sectors,
        device_sectors: sector_count,
    })?;
    if end > sector_count {
        return Err(StorageError::OutOfRange {
            lba,
            sectors,
            device_sectors: sector_count,
        });
    }
    Ok(sectors)
}

/// In-memory block device. Always available so tests and the JNI crate share
/// one implementation; large images should use [`FileBlockDevice`] instead.
pub struct MemBlockDevice {
    data: Vec<u8>,
    sector_size: u32,
    sector_count: u64,
}

impl MemBlockDevice {
    /// Create a zero-filled device.
    pub fn new(sector_count: u64, sector_size: u32) -> Result<Self> {
        if sector_size == 0 || !sector_size.is_power_of_two() {
            return Err(StorageError::UnsupportedSectorSize(sector_size));
        }
        let bytes = sector_count
            .checked_mul(sector_size as u64)
            .and_then(|v| usize::try_from(v).ok())
            .ok_or(StorageError::OutOfRange {
                lba: 0,
                sectors: sector_count,
                device_sectors: sector_count,
            })?;
        Ok(Self {
            data: vec![0u8; bytes],
            sector_size,
            sector_count,
        })
    }

    /// Create a device from existing bytes. The length must be sector-aligned.
    pub fn from_bytes(data: Vec<u8>, sector_size: u32) -> Result<Self> {
        if sector_size == 0 || !sector_size.is_power_of_two() {
            return Err(StorageError::UnsupportedSectorSize(sector_size));
        }
        if !data.len().is_multiple_of(sector_size as usize) {
            return Err(StorageError::InvalidLength {
                len: data.len(),
                sector_size,
            });
        }
        let sector_count = (data.len() / sector_size as usize) as u64;
        Ok(Self {
            data,
            sector_size,
            sector_count,
        })
    }

    pub fn as_slice(&self) -> &[u8] {
        &self.data
    }

    pub fn into_vec(self) -> Vec<u8> {
        self.data
    }
}

impl BlockDevice for MemBlockDevice {
    fn sector_size(&self) -> u32 {
        self.sector_size
    }

    fn sector_count(&self) -> u64 {
        self.sector_count
    }

    fn read_sectors(&mut self, lba: u64, out: &mut [u8]) -> Result<()> {
        validate_range(lba, out.len(), self.sector_size, self.sector_count)?;
        let start = (lba * self.sector_size as u64) as usize;
        out.copy_from_slice(&self.data[start..start + out.len()]);
        Ok(())
    }

    fn write_sectors(&mut self, lba: u64, data: &[u8]) -> Result<()> {
        validate_range(lba, data.len(), self.sector_size, self.sector_count)?;
        let start = (lba * self.sector_size as u64) as usize;
        self.data[start..start + data.len()].copy_from_slice(data);
        Ok(())
    }
}

/// Sparse file-backed block device for host tests and offline tooling.
pub struct FileBlockDevice {
    file: File,
    sector_size: u32,
    sector_count: u64,
}

impl FileBlockDevice {
    /// Create (or truncate) a sparse image file.
    pub fn create(path: impl AsRef<Path>, sector_count: u64, sector_size: u32) -> Result<Self> {
        if sector_size == 0 || !sector_size.is_power_of_two() {
            return Err(StorageError::UnsupportedSectorSize(sector_size));
        }
        let file = OpenOptions::new()
            .read(true)
            .write(true)
            .create(true)
            .truncate(true)
            .open(path)?;
        let bytes =
            sector_count
                .checked_mul(sector_size as u64)
                .ok_or(StorageError::OutOfRange {
                    lba: 0,
                    sectors: sector_count,
                    device_sectors: sector_count,
                })?;
        file.set_len(bytes)?;
        Ok(Self {
            file,
            sector_size,
            sector_count,
        })
    }

    /// Open an existing image file.
    pub fn open(path: impl AsRef<Path>, sector_size: u32) -> Result<Self> {
        if sector_size == 0 || !sector_size.is_power_of_two() {
            return Err(StorageError::UnsupportedSectorSize(sector_size));
        }
        let file = OpenOptions::new().read(true).write(true).open(path)?;
        let len = file.metadata()?.len();
        if len % sector_size as u64 != 0 {
            return Err(StorageError::InvalidLength {
                len: len as usize,
                sector_size,
            });
        }
        Ok(Self {
            file,
            sector_size,
            sector_count: len / sector_size as u64,
        })
    }

    pub fn file_mut(&mut self) -> &mut File {
        &mut self.file
    }
}

impl BlockDevice for FileBlockDevice {
    fn sector_size(&self) -> u32 {
        self.sector_size
    }

    fn sector_count(&self) -> u64 {
        self.sector_count
    }

    fn read_sectors(&mut self, lba: u64, out: &mut [u8]) -> Result<()> {
        validate_range(lba, out.len(), self.sector_size, self.sector_count)?;
        self.file
            .seek(SeekFrom::Start(lba * self.sector_size as u64))?;
        self.file.read_exact(out)?;
        Ok(())
    }

    fn write_sectors(&mut self, lba: u64, data: &[u8]) -> Result<()> {
        validate_range(lba, data.len(), self.sector_size, self.sector_count)?;
        self.file
            .seek(SeekFrom::Start(lba * self.sector_size as u64))?;
        self.file.write_all(data)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mem_device_round_trips_sectors() {
        let mut dev = MemBlockDevice::new(8, 512).unwrap();
        let mut sector = [0u8; 512];
        sector[0] = 0xAA;
        sector[511] = 0x55;
        dev.write_sectors(3, &sector).unwrap();

        let mut out = [0u8; 512];
        dev.read_sectors(3, &mut out).unwrap();
        assert_eq!(out, sector);
    }

    #[test]
    fn mem_device_rejects_unaligned_lengths() {
        let mut dev = MemBlockDevice::new(8, 512).unwrap();
        let mut buf = [0u8; 100];
        let err = dev.read_sectors(0, &mut buf).unwrap_err();
        assert!(matches!(err, StorageError::InvalidLength { .. }));
        let err = dev.write_sectors(0, &[0u8; 100]).unwrap_err();
        assert!(matches!(err, StorageError::InvalidLength { .. }));
    }

    #[test]
    fn mem_device_rejects_out_of_range() {
        let mut dev = MemBlockDevice::new(8, 512).unwrap();
        let mut buf = [0u8; 512];
        let err = dev.read_sectors(8, &mut buf).unwrap_err();
        assert!(matches!(err, StorageError::OutOfRange { .. }));
        let err = dev.write_sectors(7, &[0u8; 1024]).unwrap_err();
        assert!(matches!(err, StorageError::OutOfRange { .. }));
    }

    #[test]
    fn byte_range_io_handles_unaligned_edges() {
        let mut dev = MemBlockDevice::new(8, 512).unwrap();
        let data = [0x5Au8; 700];
        dev.write_at(100, &data).unwrap();

        let mut out = [0u8; 700];
        dev.read_at(100, &mut out).unwrap();
        assert_eq!(out, data);

        let mut first = [0u8; 1];
        dev.read_at(99, &mut first).unwrap();
        assert_eq!(first[0], 0);
        dev.read_at(800, &mut first).unwrap();
        assert_eq!(first[0], 0);
    }

    #[test]
    fn byte_range_io_rejects_past_end() {
        let mut dev = MemBlockDevice::new(2, 512).unwrap();
        let mut out = [0u8; 8];
        let err = dev.read_at(1020, &mut out).unwrap_err();
        assert!(matches!(err, StorageError::OutOfRange { .. }));
    }

    #[test]
    fn sector_size_must_be_supported() {
        let dev = MemBlockDevice::new(8, 4096).unwrap();
        let err = require_512_byte_sectors(&dev).unwrap_err();
        assert!(matches!(err, StorageError::UnsupportedSectorSize(4096)));
    }
}
