use hyntix_common::Result;
use hyntix_iso::reader::IsoReader;
use hyntix_usb::async_writer::AsyncUsbWriter;
use hyntix_usb::UsbMassStorage;
use log::info;
use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

pub mod config;
pub use config::FlashConfig;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum FlashPhase {
    Validating,
    Formatting,
    Flashing,
    Verifying,
    Finalizing,
}

impl FlashPhase {
    pub fn as_str(&self) -> &'static str {
        match self {
            FlashPhase::Validating => "Validating",
            FlashPhase::Formatting => "Formatting",
            FlashPhase::Flashing => "Flashing",
            FlashPhase::Verifying => "Verifying",
            FlashPhase::Finalizing => "Finalizing",
        }
    }
}

pub struct Flasher {
    cancelled: Arc<AtomicBool>,
}

impl Flasher {
    pub fn new(cancelled: Arc<AtomicBool>) -> Self {
        Self { cancelled }
    }

    /// Check if the provided ISO is a valid Linux distribution.
    pub fn is_linux_iso<R: Read + Seek>(&self, reader: R) -> Result<bool> {
        let mut iso = IsoReader::new(reader)?;
        iso.is_linux_iso()
    }

    /// Flash a raw image or ISO to the USB device using the AsyncUsbWriter pipeline.
    ///
    /// Architecture:
    /// 1. Source file is read in 2MB chunks on the calling thread
    /// 2. Data is handed to AsyncUsbWriter which has a background worker thread
    /// 3. The worker thread uses UsbMassStorage::write_blocks() for proper
    ///    SCSI WRITE(10) commands (CBW → Data → CSW)
    /// 4. 8 pre-allocated 2MB buffers provide double-buffering to keep the USB
    ///    bus saturated while reading from filesystem
    ///
    /// This achieves 30MB/s+ sustained throughput.
    pub fn flash_raw<R>(
        &self,
        mut source: R,
        dest: UsbMassStorage,
        total_size: u64,
        verify: bool,
        progress: impl Fn(FlashPhase, u64, u64),
    ) -> Result<()>
    where
        R: Read + Seek + Send,
    {
        progress(FlashPhase::Flashing, 0, total_size);

        // Create the high-performance AsyncUsbWriter.
        // It wraps UsbMassStorage in a Mutex, spawns a worker thread,
        // pre-allocates 8 × 2MB buffers, and uses proper SCSI WRITE(10)
        // commands via the BOT protocol (CBW → Data → CSW).
        let mut writer = AsyncUsbWriter::new(dest, self.cancelled.clone());

        // Stream from source file to USB device via the async writer pipeline.
        // We use the standard READ_CHUNK_SIZE (64MB) to fill the 32-URB pipeline
        // (64MB / 2MB URB = 32 URBs per SCSI command).
        let mut buf = vec![0u8; hyntix_usb::config::READ_CHUNK_SIZE];
        let mut total_written: u64 = 0;

        loop {
            if self.cancelled.load(Ordering::Relaxed) {
                return Err(hyntix_common::Error::Cancelled);
            }

            // Read a chunk from source
            let mut bytes_in_buf = 0;
            while bytes_in_buf < hyntix_usb::config::READ_CHUNK_SIZE {
                let remaining = total_size - total_written - bytes_in_buf as u64;
                if remaining == 0 {
                    break;
                }
                let to_read = (hyntix_usb::config::READ_CHUNK_SIZE - bytes_in_buf).min(remaining as usize);
                match source.read(&mut buf[bytes_in_buf..bytes_in_buf + to_read]) {
                    Ok(0) => break,
                    Ok(n) => bytes_in_buf += n,
                    Err(e) => return Err(hyntix_common::Error::Io(e)),
                }
            }

            if bytes_in_buf == 0 {
                break;
            }

            // Write to the async USB pipeline (this enqueues the job and returns quickly
            // thanks to the double-buffered channel architecture)
            writer
                .write_all(&buf[..bytes_in_buf])
                .map_err(|e| hyntix_common::Error::Io(e))?;

            total_written += bytes_in_buf as u64;

            // Report progress using the physical position (actual bytes confirmed written)
            let phys = writer.physical_position();
            progress(FlashPhase::Flashing, phys, total_size);
        }

        // Flush: drain the pipeline and wait for all pending writes to complete
        info!("Flushing async writer pipeline...");
        writer
            .flush()
            .map_err(|e| hyntix_common::Error::Io(e))?;

        // Final flush progress
        progress(FlashPhase::Flashing, total_size, total_size);

        // Wait for worker thread to fully complete
        writer.wait_idle()?;

        // ── Verification Phase ──────────────────────────────────────────
        if verify {
            info!("Verifying integrity...");
            progress(FlashPhase::Verifying, 0, total_size);
            source
                .seek(SeekFrom::Start(0))
                .map_err(|e| hyntix_common::Error::Io(e))?;

            // Seek the writer back to the beginning to read from LBA 0
            writer
                .seek(SeekFrom::Start(0))
                .map_err(|e| hyntix_common::Error::Io(e))?;

            let mut verified_bytes: u64 = 0;
            let mut file_buf = vec![0u8; hyntix_usb::config::READ_CHUNK_SIZE];
            let mut device_buf = vec![0u8; hyntix_usb::config::READ_CHUNK_SIZE];

            while verified_bytes < total_size {
                if self.cancelled.load(Ordering::Relaxed) {
                    return Err(hyntix_common::Error::Cancelled);
                }

                let remaining = (total_size - verified_bytes) as usize;
                let chunk_size = remaining.min(hyntix_usb::config::READ_CHUNK_SIZE);

                // Read from source file
                source
                    .read_exact(&mut file_buf[..chunk_size])
                    .map_err(|e| hyntix_common::Error::Io(e))?;

                // Read from USB device via SCSI READ(10)
                let n = writer
                    .read(&mut device_buf[..chunk_size])
                    .map_err(|e| hyntix_common::Error::Io(e))?;

                if n != chunk_size {
                    return Err(hyntix_common::Error::SizeMismatch {
                        operation: "verify read",
                        expected: chunk_size,
                        actual: n,
                    });
                }

                // Compare
                if file_buf[..chunk_size] != device_buf[..chunk_size] {
                    return Err(hyntix_common::Error::Io(std::io::Error::new(
                        std::io::ErrorKind::InvalidData,
                        "Verification Failed: Data mismatch",
                    )));
                }

                verified_bytes += chunk_size as u64;
                progress(FlashPhase::Verifying, verified_bytes, total_size);
            }
        }

        info!("Flashing complete, finalizing...");
        progress(FlashPhase::Finalizing, total_size, total_size);

        info!("Flash successful");
        Ok(())
    }
}
