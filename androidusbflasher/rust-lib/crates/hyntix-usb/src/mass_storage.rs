//! USB Mass Storage device abstraction.
//!
//! Provides high-level read/write/seek operations using SCSI commands
//! over USB Bulk-Only Transport.

use super::cbw::{CommandBlockWrapper, CBW_SIZE};
use super::config;
use super::csw::{CommandStatusWrapper, CswStatus, CSW_SIZE};
use super::scsi::{ScsiInquiry, ScsiRead10, ScsiReadCapacity, ScsiTestUnitReady, ScsiWrite10};
use hyntix_common::{Error, Result};

use std::sync::atomic::{AtomicU64, Ordering};

/// Maximum retry attempts for failed commands.
const MAX_RETRIES: u32 = 20;

/// USB Backend implementation.
pub enum UsbBackend {
    Native(super::native::NativeUsbBackend),
}

impl UsbBackend {
    pub fn bulk_out(&self, data: &[u8]) -> Result<usize> {
        let UsbBackend::Native(native) = self;
        native.bulk_out(data).map_err(Error::from)
    }

    pub fn bulk_in(&self, data: &mut [u8]) -> Result<usize> {
        let UsbBackend::Native(native) = self;
        native.bulk_in(data).map_err(Error::from)
    }
}

/// USB Mass Storage device.
pub struct UsbMassStorage {
    /// The underlying USB communication backend.
    backend: UsbBackend,
    /// Logical Unit Number.
    lun: u8,
    /// Block size in bytes.
    block_size: u32,
    /// Total number of blocks.
    block_count: u64,
    /// Current tag counter for CBW/CSW matching.
    tag_counter: u32,
    /// Maximum transfer size (configurable for adaptive sizing).
    max_transfer_size: usize,
}

impl UsbMassStorage {
    /// Create a new USB mass storage device using the native Linux backend.
    pub fn new_native(
        backend: super::native::NativeUsbBackend,
        lun: u8,
        max_transfer_size: usize,
    ) -> Result<Self> {
        let mut device = Self {
            backend: UsbBackend::Native(backend),
            lun,
            block_size: 512,
            block_count: 0,
            tag_counter: 1,
            max_transfer_size,
        };

        device.init()?;
        Ok(device)
    }

    /// Initialize the device: inquiry, test unit ready, read capacity.
    fn init(&mut self) -> Result<()> {
        let lun = self.lun;

        // INQUIRY
        let mut inquiry_data = [0u8; 36];
        let tag = self.next_tag();
        self.transfer_command(
            ScsiInquiry::cbw(tag, lun, 36),
            Some(&mut inquiry_data),
            None,
        )?;
        tracing::debug!("INQUIRY response received");

        // TEST UNIT READY (may need retries)
        for attempt in 0..MAX_RETRIES {
            let tag = self.next_tag();
            match self.transfer_command(ScsiTestUnitReady::cbw(tag, lun), None, None) {
                Ok(_) => break,
                Err(e) if attempt < MAX_RETRIES - 1 => {
                    tracing::warn!("TEST UNIT READY failed (attempt {}): {}", attempt + 1, e);
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
                Err(e) => return Err(e),
            }
        }
        tracing::debug!("Device is ready");

        // READ CAPACITY
        let mut capacity_data = [0u8; 8];
        let tag = self.next_tag();
        self.transfer_command(
            ScsiReadCapacity::cbw(tag, lun),
            Some(&mut capacity_data),
            None,
        )?;

        if let Some((last_block, block_size)) = ScsiReadCapacity::parse_response(&capacity_data) {
            self.block_size = block_size;
            self.block_count = last_block as u64 + 1;
            tracing::info!(
                "UsbMassStorage: Capacity detected: {} blocks * {} bytes = {} bytes",
                self.block_count,
                block_size,
                self.capacity()
            );
        } else {
            tracing::error!(
                "UsbMassStorage: Failed to parse READ CAPACITY response: {:?}",
                capacity_data
            );
            return Err(Error::UsbError(
                "Failed to parse READ CAPACITY response".into(),
            ));
        }

        Ok(())
    }

    /// Get the block size in bytes.
    pub fn block_size(&self) -> u32 {
        self.block_size
    }

    /// Get the total number of blocks.
    pub fn block_count(&self) -> u64 {
        self.block_count
    }

    /// Get the total capacity in bytes.
    pub fn capacity(&self) -> u64 {
        self.block_count * self.block_size as u64
    }

    /// Get next tag and increment counter.
    fn next_tag(&mut self) -> u32 {
        let tag = self.tag_counter;
        self.tag_counter = self.tag_counter.wrapping_add(1);
        if self.tag_counter == 0 {
            self.tag_counter = 1;
        }
        tag
    }

    /// Get the maximum transfer size for SCSI commands.
    fn max_transfer_size(&self) -> usize {
        self.max_transfer_size
    }

    /// Reduce max transfer size on SCSI command failure (adaptive fallback).
    /// Halves until the floor is reached, then stays at minimum.
    fn reduce_max_transfer_size(&mut self) {
        let new_size = (self.max_transfer_size / 2).max(config::SCSI_MIN_TRANSFER_SIZE);
        if new_size < self.max_transfer_size {
            tracing::warn!(
                "SCSI: Reducing max transfer size from {}KB to {}KB due to command failure",
                self.max_transfer_size / 1024,
                new_size / 1024
            );
            self.max_transfer_size = new_size;
        }
    }

    /// Transfer a SCSI command with optional data phase.
    fn transfer_command(
        &mut self,
        cbw: CommandBlockWrapper,
        read_buffer: Option<&mut [u8]>,
        write_buffer: Option<&[u8]>,
    ) -> Result<()> {
        let tag = cbw.tag;
        let data_length = cbw.data_transfer_length as usize;
        let is_read = cbw.flags == 0x80;

        // Send CBW
        let cbw_data = cbw.serialize();
        let written = self.backend.bulk_out(&cbw_data)?;
        if written != CBW_SIZE {
            return Err(Error::UsbError(format!(
                "CBW write incomplete: {} != {}",
                written, CBW_SIZE
            )));
        }

        // Data phase
        if data_length > 0 {
            if is_read {
                if let Some(buffer) = read_buffer {
                    let mut total_read = 0;
                    while total_read < data_length.min(buffer.len()) {
                        let read = self.backend.bulk_in(&mut buffer[total_read..])?;
                        if read == 0 {
                            break;
                        }
                        total_read += read;
                    }
                }
            } else {
                if let Some(buffer) = write_buffer {
                    let mut total_written = 0;
                    while total_written < data_length.min(buffer.len()) {
                        let written = self.backend.bulk_out(&buffer[total_written..])?;
                        if written == 0 {
                            break;
                        }
                        total_written += written;
                    }
                }
            }
        }

        // Receive CSW
        let mut csw_buffer = [0u8; CSW_SIZE];
        let read = self.backend.bulk_in(&mut csw_buffer)?;
        if read != CSW_SIZE {
            tracing::error!(
                "UsbMassStorage: CSW read incomplete: {} != {}",
                read,
                CSW_SIZE
            );
            return Err(Error::UsbError(format!(
                "CSW read incomplete: {} != {}",
                read, CSW_SIZE
            )));
        }

        let csw = CommandStatusWrapper::parse(&csw_buffer)?;

        // Verify tag matches
        if csw.tag != tag {
            return Err(Error::UsbError(format!(
                "CSW tag mismatch: {} != {}",
                csw.tag, tag
            )));
        }

        // Check status
        match csw.status {
            CswStatus::Passed => Ok(()),
            CswStatus::Failed => {
                // Adaptive fallback: halve max transfer size for next command.
                // Some drives have firmware limits on command size.
                self.reduce_max_transfer_size();
                Err(Error::UsbError("SCSI command failed".into()))
            }
            CswStatus::PhaseError => Err(Error::UsbError(
                "SCSI phase error - device reset required".into(),
            )),
        }
    }

    /// Read blocks from the device.
    pub fn read_blocks(&mut self, start_block: u64, buffer: &mut [u8]) -> Result<()> {
        if buffer.len() % self.block_size as usize != 0 {
            return Err(Error::UsbError(
                "Buffer size must be multiple of block size".into(),
            ));
        }

        let _total_blocks = buffer.len() / self.block_size as usize;
        let max_blocks_per_transfer =
            (self.max_transfer_size() / self.block_size as usize).min(u16::MAX as usize);

        let mut offset = 0;
        let mut current_block = start_block;

        while offset < buffer.len() {
            let blocks_remaining = (buffer.len() - offset) / self.block_size as usize;
            let blocks_to_read = blocks_remaining.min(max_blocks_per_transfer) as u16;
            let bytes_to_read = blocks_to_read as usize * self.block_size as usize;

            let cbw = ScsiRead10::cbw(
                self.next_tag(),
                self.lun,
                current_block as u32,
                blocks_to_read,
                self.block_size,
            );

            self.transfer_command(cbw, Some(&mut buffer[offset..offset + bytes_to_read]), None)?;

            offset += bytes_to_read;
            current_block += blocks_to_read as u64;
        }

        Ok(())
    }

    /// Write blocks to the device.
    pub fn write_blocks(&mut self, start_block: u64, buffer: &[u8]) -> Result<()> {
        if buffer.len() % self.block_size as usize != 0 {
            return Err(Error::UsbError(
                "Buffer size must be multiple of block size".into(),
            ));
        }

        let max_blocks_per_transfer =
            (self.max_transfer_size() / self.block_size as usize).min(u16::MAX as usize);

        let mut offset = 0;
        let mut current_block = start_block;

        while offset < buffer.len() {
            let blocks_remaining = (buffer.len() - offset) / self.block_size as usize;
            let blocks_to_write = blocks_remaining.min(max_blocks_per_transfer) as u16;
            let bytes_to_write = blocks_to_write as usize * self.block_size as usize;

            let cbw = ScsiWrite10::cbw(
                self.next_tag(),
                self.lun,
                current_block as u32,
                blocks_to_write,
                self.block_size,
            );

            self.transfer_command(cbw, None, Some(&buffer[offset..offset + bytes_to_write]))?;

            offset += bytes_to_write;
            current_block += blocks_to_write as u64;
        }

        Ok(())
    }

    /// Write blocks with per-SCSI progress updates.
    /// Updates `phys_progress` after each SCSI WRITE(10) command for smooth UI.
    pub fn write_blocks_with_progress(
        &mut self,
        start_block: u64,
        buffer: &[u8],
        phys_progress: &AtomicU64,
        base_pos: u64,
    ) -> Result<()> {
        if buffer.len() % self.block_size as usize != 0 {
            return Err(Error::UsbError(
                "Buffer size must be multiple of block size".into(),
            ));
        }

        let max_blocks_per_transfer =
            (self.max_transfer_size() / self.block_size as usize).min(u16::MAX as usize);

        let mut offset = 0;
        let mut current_block = start_block;
        let total_buffer = buffer.len();

        while offset < total_buffer {
            let blocks_remaining = (total_buffer - offset) / self.block_size as usize;
            let blocks_to_write = blocks_remaining.min(max_blocks_per_transfer) as u16;
            let bytes_to_write = blocks_to_write as usize * self.block_size as usize;

            let cbw = ScsiWrite10::cbw(
                self.next_tag(),
                self.lun,
                current_block as u32,
                blocks_to_write,
                self.block_size,
            );

            self.transfer_command(cbw, None, Some(&buffer[offset..offset + bytes_to_write]))?;

            offset += bytes_to_write;
            current_block += blocks_to_write as u64;

            // Update physical progress after each SCSI command
            phys_progress.store(base_pos + offset as u64, Ordering::Release);
        }

        Ok(())
    }
}
