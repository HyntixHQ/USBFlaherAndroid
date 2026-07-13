use blake3;
use hyntix_common::Result;
use hyntix_iso::reader::IsoReader;
use hyntix_usb::async_writer::AsyncUsbWriter;
use hyntix_usb::UsbMassStorage;
use std::io::{Read, Seek, SeekFrom};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tracing::info;

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

    /// Check if the provided ISO is a valid Windows installer.
    pub fn is_windows_iso<R: Read + Seek>(&self, reader: R) -> Result<bool> {
        let mut udf = hyntix_udf::UdfReader::new(reader).map_err(|e| {
            hyntix_common::Error::Io(std::io::Error::new(
                std::io::ErrorKind::Other,
                e.to_string(),
            ))
        })?;
        let tree = udf.walk().map_err(|e| {
            hyntix_common::Error::Io(std::io::Error::new(
                std::io::ErrorKind::Other,
                e.to_string(),
            ))
        })?;

        for (path, _) in &tree {
            if path == "sources/install.wim" || path == "sources/boot.wim" {
                return Ok(true);
            }
        }
        Ok(false)
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
        // Acquire the first buffer from the pool to avoid an extra allocation + memcpy.
        let mut buf = writer
            .acquire_buffer()
            .map_err(|e| hyntix_common::Error::Io(e))?;
        let mut total_written: u64 = 0;
        let mut source_hasher = blake3::Hasher::new();

        loop {
            if self.cancelled.load(Ordering::Relaxed) {
                return Err(hyntix_common::Error::Cancelled);
            }

            // Read a chunk from source directly into the pool buffer
            let mut bytes_in_buf = 0;
            let mut last_poll_pos: u64 = 0;
            while bytes_in_buf < hyntix_usb::config::READ_CHUNK_SIZE {
                let remaining = total_size - total_written - bytes_in_buf as u64;
                if remaining == 0 {
                    break;
                }
                let to_read =
                    (hyntix_usb::config::READ_CHUNK_SIZE - bytes_in_buf).min(remaining as usize);
                match source.read(&mut buf[bytes_in_buf..bytes_in_buf + to_read]) {
                    Ok(0) => break,
                    Ok(n) => {
                        source_hasher.update(&buf[bytes_in_buf..bytes_in_buf + n]);
                        bytes_in_buf += n;
                        // Poll physical progress every 4MB read to keep UI smooth.
                        // Worker may have advanced physical_position since last check.
                        let pos = writer.physical_position();
                        if pos - last_poll_pos >= 4 * 1024 * 1024 {
                            last_poll_pos = pos;
                            progress(FlashPhase::Flashing, pos, total_size);
                        }
                    }
                    Err(e) => return Err(hyntix_common::Error::Io(e)),
                }
            }

            if bytes_in_buf == 0 {
                break;
            }

            buf.truncate(bytes_in_buf);

            // Write the pool buffer directly to the async USB pipeline (zero-copy:
            // no extend_from_slice, the buffer is sent as-is to the worker thread)
            writer
                .write_buffer(buf)
                .map_err(|e| hyntix_common::Error::Io(e))?;

            total_written += bytes_in_buf as u64;

            // Acquire the next buffer — poll physical progress every 100ms while waiting.
            // This captures per-SCSI-position updates from the worker in realtime,
            // giving smooth UI feedback that accurately tracks the hardware state.
            buf = loop {
                match writer.try_acquire_buffer() {
                    Ok(Some(b)) => break b,
                    Ok(None) => {
                        let phys = writer.physical_position();
                        progress(FlashPhase::Flashing, phys, total_size);
                        if self.cancelled.load(Ordering::Relaxed) {
                            return Err(hyntix_common::Error::Cancelled);
                        }
                        std::thread::sleep(Duration::from_millis(100));
                        continue;
                    }
                    Err(e) => return Err(hyntix_common::Error::Io(e)),
                }
            };

            // Report progress after acquiring the buffer too (last known position)
            progress(FlashPhase::Flashing, writer.physical_position(), total_size);
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

        // ── Verification Phase (Triple-Pipelined: Read-Ahead + Main-Hash) ─────
        if verify {
            info!("Verifying integrity with read pre-fetching (1MB chunks)...");
            progress(FlashPhase::Verifying, 0, total_size);

            writer
                .seek(SeekFrom::Start(0))
                .map_err(|e| hyntix_common::Error::Io(e))?;

            let expected_hash = source_hasher.finalize();
            // Using 1MB chunks for verification to match safe SCSI limits and reduce memory pressure
            let chunk_len = 1024 * 1024;

            // Triple-buffering: Read Thread -> [Buffer 1] -> [Buffer 2] -> [Buffer 3] -> Main Thread
            let (read_tx, read_rx) = crossbeam_channel::bounded::<(Vec<u8>, usize)>(4);
            let (recycle_tx, recycle_rx) = crossbeam_channel::bounded::<Vec<u8>>(4);

            // Pre-allocate buffers (4 x 1MB = 4MB)
            for _ in 0..4 {
                recycle_tx.send(vec![0u8; chunk_len]).unwrap();
            }

            let cancel_clone = self.cancelled.clone();
            let mut reader = writer;

            let read_handle = std::thread::spawn(move || -> Result<AsyncUsbWriter> {
                let mut verified_bytes: u64 = 0;
                while verified_bytes < total_size {
                    if cancel_clone.load(Ordering::Relaxed) {
                        return Err(hyntix_common::Error::Cancelled);
                    }

                    let remaining = (total_size - verified_bytes) as usize;
                    let this_chunk = remaining.min(chunk_len);

                    let mut buf = recycle_rx.recv().map_err(|_| {
                        hyntix_common::Error::Io(std::io::Error::new(
                            std::io::ErrorKind::Other,
                            "Recycle channel closed",
                        ))
                    })?;

                    // Important: use storage.read_blocks directly or through reader.read
                    // reader.read is fine as it handles alignment and mutex locking.
                    let n = reader
                        .read(&mut buf[..this_chunk])
                        .map_err(|e| hyntix_common::Error::Io(e))?;

                    if n != this_chunk {
                        return Err(hyntix_common::Error::SizeMismatch {
                            operation: "verify read",
                            expected: this_chunk,
                            actual: n,
                        });
                    }

                    if read_tx.send((buf, n)).is_err() {
                        break;
                    }
                    verified_bytes += n as u64;
                }
                Ok(reader)
            });

            let mut dest_hasher = blake3::Hasher::new();
            let mut verified_bytes: u64 = 0;

            while verified_bytes < total_size {
                let (buf, n) = match read_rx.recv() {
                    Ok(data) => data,
                    Err(_) => break,
                };

                dest_hasher.update(&buf[..n]);
                verified_bytes += n as u64;

                // Report progress every 4MB to reduce JNI/UI overhead
                if verified_bytes % (4 * 1024 * 1024) == 0 || verified_bytes == total_size {
                    progress(FlashPhase::Verifying, verified_bytes, total_size);
                }

                let _ = recycle_tx.send(buf);
            }

            let _writer = read_handle
                .join()
                .map_err(|_| {
                    hyntix_common::Error::Io(std::io::Error::new(
                        std::io::ErrorKind::Other,
                        "Read thread panicked",
                    ))
                })?
                .map_err(|e| e)?;

            let final_dest_hash = dest_hasher.finalize();

            if expected_hash != final_dest_hash {
                return Err(hyntix_common::Error::Io(std::io::Error::new(
                    std::io::ErrorKind::InvalidData,
                    "Verification Failed: BLAKE3 data mismatch",
                )));
            }
        }

        info!("Flashing complete, finalizing...");
        progress(FlashPhase::Finalizing, total_size, total_size);

        info!("Flash successful");
        Ok(())
    }

    /// Flash a Windows ISO to the USB device.
    pub fn flash_windows<R>(
        &self,
        source: R,
        dest: UsbMassStorage,
        _total_size: u64,
        progress: impl Fn(FlashPhase, u64, u64),
    ) -> Result<()>
    where
        R: Read + Seek + Send,
    {
        // Wrap the destination in AsyncUsbWriter for high-performance SCSI commands
        let writer = AsyncUsbWriter::new(dest, self.cancelled.clone());
        let mut flasher = hyntix_windows::WindowsFlasher::new(writer);

        flasher
            .flash(source, |current, total| {
                progress(FlashPhase::Flashing, current, total);
            })
            .map_err(|e| {
                hyntix_common::Error::Io(std::io::Error::new(
                    std::io::ErrorKind::Other,
                    e.to_string(),
                ))
            })?;

        Ok(())
    }
}
