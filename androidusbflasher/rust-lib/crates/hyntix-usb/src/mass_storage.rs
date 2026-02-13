//! USB Mass Storage device abstraction.
//!
//! Provides high-level read/write/seek operations using SCSI commands
//! over USB Bulk-Only Transport.

use super::cbw::{CommandBlockWrapper, CBW_SIZE};
use super::csw::{CommandStatusWrapper, CswStatus, CSW_SIZE};
use super::scsi::{
    ScsiInquiry, ScsiRead10, ScsiReadCapacity, ScsiStartStopUnit, ScsiSynchronizeCache,
    ScsiTestUnitReady, ScsiWrite10,
};
use hyntix_common::{Error, Result};

/// Maximum retry attempts for failed commands.
const MAX_RETRIES: u32 = 20;

/// Callback type for USB bulk OUT transfer (host to device).
pub type BulkOutCallback = Box<dyn Fn(&[u8]) -> Result<usize> + Send>;

/// Callback type for USB bulk IN transfer (device to host).
pub type BulkInCallback = Box<dyn Fn(&mut [u8]) -> Result<usize> + Send>;

/// USB Backend implementation.
pub enum UsbBackend {
    Callbacks {
        bulk_out: BulkOutCallback,
        bulk_in: BulkInCallback,
    },
    Native(super::native::NativeUsbBackend),
}

impl UsbBackend {
    pub fn bulk_out(&self, data: &[u8]) -> Result<usize> {
        match self {
            UsbBackend::Callbacks { bulk_out, .. } => bulk_out(data),
            UsbBackend::Native(native) => native.bulk_out(data).map_err(Error::from),
        }
    }

    pub fn bulk_in(&self, data: &mut [u8]) -> Result<usize> {
        match self {
            UsbBackend::Callbacks { bulk_in, .. } => bulk_in(data),
            UsbBackend::Native(native) => native.bulk_in(data).map_err(Error::from),
        }
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
    /// Create a new USB mass storage device with the given bulk transfer callbacks.
    pub fn new(
        bulk_out: BulkOutCallback,
        bulk_in: BulkInCallback,
        lun: u8,
        max_transfer_size: usize,
    ) -> Result<Self> {
        let mut device = Self {
            backend: UsbBackend::Callbacks { bulk_out, bulk_in },
            lun,
            block_size: 512, // Default, will be updated by init
            block_count: 0,
            tag_counter: 1,
            max_transfer_size,
        };

        device.init()?;
        Ok(device)
    }

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
        log::debug!("INQUIRY response received");

        // TEST UNIT READY (may need retries)
        for attempt in 0..MAX_RETRIES {
            let tag = self.next_tag();
            match self.transfer_command(ScsiTestUnitReady::cbw(tag, lun), None, None) {
                Ok(_) => break,
                Err(e) if attempt < MAX_RETRIES - 1 => {
                    log::warn!("TEST UNIT READY failed (attempt {}): {}", attempt + 1, e);
                    std::thread::sleep(std::time::Duration::from_millis(100));
                }
                Err(e) => return Err(e),
            }
        }
        log::debug!("Device is ready");

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
            log::info!(
                "UsbMassStorage: Capacity detected: {} blocks * {} bytes = {} bytes",
                self.block_count,
                block_size,
                self.capacity()
            );
        } else {
            log::error!("UsbMassStorage: Failed to parse READ CAPACITY response: {:?}", capacity_data);
            return Err(Error::UsbError(
                "Failed to parse READ CAPACITY response".into(),
            ));
        }

        Ok(())
    }

    /// Access the underlying native backend.
    /// Panics if the backend is not Native.
    pub fn backend(&self) -> &super::native::NativeUsbBackend {
        match &self.backend {
            UsbBackend::Native(backend) => backend,
            _ => panic!("Backend is not native, cannot access native features"),
        }
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

    /// Get the maximum transfer size for the current backend.
    fn max_transfer_size(&self) -> usize {
        self.max_transfer_size
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
            log::error!("UsbMassStorage: CSW read incomplete: {} != {}", read, CSW_SIZE);
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
                // Could issue REQUEST SENSE here for more details
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
        let max_blocks_per_transfer = (self.max_transfer_size() / self.block_size as usize).min(u16::MAX as usize);

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

        let max_blocks_per_transfer = (self.max_transfer_size() / self.block_size as usize).min(u16::MAX as usize);

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

    /// Get the current byte position.
    pub fn position(&self) -> u64 {
        // We need to track position for std::io trait implementations
        0 // Will be replaced by actual tracking
    }

    /// Eject the media.
    pub fn eject(&mut self) -> Result<()> {
        let tag = self.next_tag();
        let lun = self.lun;
        let cbw = ScsiStartStopUnit::cbw(tag, lun, false, true);
        self.transfer_command(cbw, None, None)
    }

    /// Synchronize the device's cache (SCSI SYNCHRONIZE CACHE).
    pub fn synchronize_cache(&mut self) -> Result<()> {
        let tag = self.next_tag();
        let lun = self.lun;
        let cbw = ScsiSynchronizeCache::cbw(tag, lun);
        match self.transfer_command(cbw, None, None) {
            Ok(_) => Ok(()),
            Err(e) => {
                log::warn!(
                    "SYNCHRONIZE CACHE failed (might not be supported by device): {}",
                    e
                );
                // Some devices don't support it, so we don't treat it as a fatal error
                Ok(())
            }
        }
    }
}

impl UsbMassStorage {
    /// Seek to a byte position.
    pub fn seek_to(&mut self, position: u64) -> Result<u64> {
        let device_size = self.block_count * self.block_size as u64;
        if position > device_size {
            return Err(Error::UsbError(format!(
                "Seek position {} exceeds device size {}",
                position, device_size
            )));
        }
        Ok(position)
    }
}

/// Wrapper around UsbMassStorage that implements std::io traits.
/// This is needed because UsbMassStorage needs to track position state.
pub struct UsbMassStorageWriter {
    /// The underlying USB mass storage device.
    inner: UsbMassStorage,
    /// Current byte position.
    position: u64,
    /// Write buffer for accumulating partial sector writes.
    write_buffer: Vec<u8>,
    /// Start position of write buffer.
    write_buffer_start: u64,
}

impl UsbMassStorageWriter {
    /// Create a new writer wrapping a UsbMassStorage device.
    pub fn new(storage: UsbMassStorage) -> Self {
        Self {
            inner: storage,
            position: 0,
            write_buffer: Vec::new(),
            write_buffer_start: 0,
        }
    }

    /// Get the block size.
    pub fn block_size(&self) -> u32 {
        self.inner.block_size()
    }

    /// Get the total device capacity in bytes.
    pub fn capacity(&self) -> u64 {
        self.inner.block_count() * self.inner.block_size() as u64
    }

    /// Flush any pending buffered data to the device.
    fn flush_buffer(&mut self) -> std::io::Result<()> {
        if self.write_buffer.is_empty() {
            return Ok(());
        }

        let block_size = self.inner.block_size() as usize;

        // Pad buffer to block boundary if needed
        while self.write_buffer.len() % block_size != 0 {
            self.write_buffer.push(0);
        }

        let start_block = self.write_buffer_start / block_size as u64;

        self.inner
            .write_blocks(start_block, &self.write_buffer)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

        self.write_buffer.clear();
        Ok(())
    }
}

impl crate::PhysicalProgress for UsbMassStorageWriter {
    fn physical_position(&self) -> u64 {
        self.position
    }
}

impl std::io::Read for UsbMassStorageWriter {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }

        let block_size = self.inner.block_size() as usize;
        let device_capacity = self.capacity();

        // Check for EOF
        if self.position >= device_capacity {
            return Ok(0);
        }

        // Calculate aligned read
        let start_block = self.position / block_size as u64;
        let offset_in_block = (self.position % block_size as u64) as usize;

        // Calculate how many bytes we can read
        let bytes_available = (device_capacity - self.position) as usize;
        let bytes_to_read = buf.len().min(bytes_available);

        // Calculate blocks needed (round up)
        let bytes_needed_total = offset_in_block + bytes_to_read;
        let blocks_needed = (bytes_needed_total + block_size - 1) / block_size;

        // Read aligned blocks
        let mut block_buffer = vec![0u8; blocks_needed * block_size];
        self.inner
            .read_blocks(start_block, &mut block_buffer)
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

        // Copy requested portion
        buf[..bytes_to_read]
            .copy_from_slice(&block_buffer[offset_in_block..offset_in_block + bytes_to_read]);

        self.position += bytes_to_read as u64;
        Ok(bytes_to_read)
    }
}

impl std::io::Write for UsbMassStorageWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        if buf.is_empty() {
            return Ok(0);
        }

        let block_size = self.inner.block_size() as usize;

        // Initialize buffer position if needed
        if self.write_buffer.is_empty() {
            self.write_buffer_start = (self.position / block_size as u64) * block_size as u64;
            // If not block-aligned, we need to read the first block
            let offset = (self.position % block_size as u64) as usize;
            if offset > 0 {
                let mut first_block = vec![0u8; block_size];
                self.inner
                    .read_blocks(self.position / block_size as u64, &mut first_block)
                    .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;
                self.write_buffer = first_block;
            }
        }

        // Calculate offset within buffer
        let buffer_offset = (self.position - self.write_buffer_start) as usize;

        // Grow buffer if needed
        if buffer_offset + buf.len() > self.write_buffer.len() {
            self.write_buffer.resize(buffer_offset + buf.len(), 0);
        }

        // Copy data to buffer
        self.write_buffer[buffer_offset..buffer_offset + buf.len()].copy_from_slice(buf);
        self.position += buf.len() as u64;

        let max_transfer = self.inner.max_transfer_size();

        // Flush if buffer is large enough
        if self.write_buffer.len() >= max_transfer {
            // Flush complete blocks only
            let complete_blocks = self.write_buffer.len() / block_size;
            let complete_bytes = complete_blocks * block_size;

            if complete_bytes > 0 {
                let start_block = self.write_buffer_start / block_size as u64;

                self.inner
                    .write_blocks(start_block, &self.write_buffer[..complete_bytes])
                    .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

                // Remove the written portion from the buffer
                self.write_buffer.drain(0..complete_bytes);
                self.write_buffer_start += complete_bytes as u64;
            }
        }

        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.flush_buffer()?;
        // Note: SYNCHRONIZE CACHE removed as it causes USB errors on some drives.
        Ok(())
    }
}

impl std::io::Seek for UsbMassStorageWriter {
    fn seek(&mut self, pos: std::io::SeekFrom) -> std::io::Result<u64> {
        let device_capacity = self.capacity() as i64;

        let new_pos = match pos {
            std::io::SeekFrom::Start(offset) => offset as i64,
            std::io::SeekFrom::End(offset) => device_capacity + offset,
            std::io::SeekFrom::Current(offset) => self.position as i64 + offset,
        };

        if new_pos < 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                "Seek to negative position",
            ));
        }

        if new_pos > device_capacity {
            return Err(std::io::Error::new(
                std::io::ErrorKind::InvalidInput,
                format!(
                    "Seek position {} exceeds device capacity {}",
                    new_pos, device_capacity
                ),
            ));
        }

        let new_pos_u64 = new_pos as u64;

        // Only flush if we are actually moving the position
        if new_pos_u64 != self.position {
            self.flush_buffer()?;
            self.position = new_pos_u64;
        }

        Ok(self.position)
    }

    fn stream_position(&mut self) -> std::io::Result<u64> {
        // Return current position without flushing
        Ok(self.position)
    }
}
