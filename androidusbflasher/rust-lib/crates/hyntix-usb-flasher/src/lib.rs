use hyntix_common::Result;
use hyntix_iso::reader::IsoReader;
use hyntix_usb::async_writer::AsyncUsbWriter;
use hyntix_usb::UsbMassStorage;
use log::info;
use sha2::Digest;
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
        let mut source_hasher = sha2::Sha256::new();

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

            source_hasher.update(&buf[..bytes_in_buf]);

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
            .flush_with_progress(|phys| progress(FlashPhase::Flashing, phys, total_size))
            .map_err(|e| hyntix_common::Error::Io(e))?;

        // Final flush progress
        progress(FlashPhase::Flashing, total_size, total_size);

        // Wait for worker thread to fully complete
        writer.wait_idle()?;

        // ── Verification Phase (Pipelined: Read overlaps with Hash) ─────
        if verify {
            info!("Verifying integrity...");
            progress(FlashPhase::Verifying, 0, total_size);

            // Seek the writer back to the beginning to read from LBA 0
            writer
                .seek(SeekFrom::Start(0))
                .map_err(|e| hyntix_common::Error::Io(e))?;

            let expected_hash = source_hasher.finalize();
            let chunk_len = hyntix_usb::config::READ_CHUNK_SIZE;

            // Double-buffer: main thread reads into one buffer while hash thread
            // processes the other. This hides SHA-256 latency behind USB I/O.
            let (hash_tx, hash_rx) = crossbeam_channel::bounded::<(Vec<u8>, usize)>(2);
            let (recycle_tx, recycle_rx) = crossbeam_channel::bounded::<Vec<u8>>(2);

            // Pre-allocate two buffers
            recycle_tx.send(vec![0u8; chunk_len]).unwrap();
            recycle_tx.send(vec![0u8; chunk_len]).unwrap();

            let cancel_clone = self.cancelled.clone();

            // Hash thread: receives filled buffers, updates SHA-256, recycles them
            let hash_handle = std::thread::spawn(move || -> Result<sha2::digest::Output<sha2::Sha256>> {
                let mut dest_hasher = sha2::Sha256::new();
                while let Ok((buf, len)) = hash_rx.recv() {
                    if cancel_clone.load(Ordering::Relaxed) {
                        return Err(hyntix_common::Error::Cancelled);
                    }
                    dest_hasher.update(&buf[..len]);
                    let _ = recycle_tx.send(buf); // Return buffer to pool
                }
                Ok(dest_hasher.finalize())
            });

            // Main thread: reads from USB device, sends filled buffers to hash thread
            let mut verified_bytes: u64 = 0;
            while verified_bytes < total_size {
                if self.cancelled.load(Ordering::Relaxed) {
                    drop(hash_tx); // Signal hash thread to stop
                    let _ = hash_handle.join();
                    return Err(hyntix_common::Error::Cancelled);
                }

                let remaining = (total_size - verified_bytes) as usize;
                let this_chunk = remaining.min(chunk_len);

                // Get a recycled buffer (blocks until hash thread returns one)
                let mut buf = recycle_rx.recv().map_err(|_| {
                    hyntix_common::Error::Io(std::io::Error::new(
                        std::io::ErrorKind::Other,
                        "Hash thread died",
                    ))
                })?;

                let n = writer
                    .read(&mut buf[..this_chunk])
                    .map_err(|e| hyntix_common::Error::Io(e))?;

                if n != this_chunk {
                    drop(hash_tx);
                    let _ = hash_handle.join();
                    return Err(hyntix_common::Error::SizeMismatch {
                        operation: "verify read",
                        expected: this_chunk,
                        actual: n,
                    });
                }

                // Send filled buffer to hash thread (non-blocking if channel has space)
                hash_tx.send((buf, n)).map_err(|_| {
                    hyntix_common::Error::Io(std::io::Error::new(
                        std::io::ErrorKind::Other,
                        "Hash thread died",
                    ))
                })?;

                verified_bytes += n as u64;
                progress(FlashPhase::Verifying, verified_bytes, total_size);
            }

            // Drop sender to signal hash thread that all data has been sent
            drop(hash_tx);

            // Wait for hash thread to finish and get the final digest
            let final_dest_hash = hash_handle
                .join()
                .map_err(|_| {
                    hyntix_common::Error::Io(std::io::Error::new(
                        std::io::ErrorKind::Other,
                        "Hash thread panicked",
                    ))
                })?
                .map_err(|e| e)?;

            if expected_hash != final_dest_hash {
                return Err(hyntix_common::Error::Io(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "Verification Failed: SHA-256 data mismatch",
                )));
            }
        }

        info!("Flashing complete, finalizing...");
        progress(FlashPhase::Finalizing, total_size, total_size);

        info!("Flash successful");
        Ok(())
    }
}
