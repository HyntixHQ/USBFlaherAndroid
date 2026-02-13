//! Asynchronous USB writer with double buffering.

use crate::mass_storage::UsbMassStorage;
use crossbeam_channel::{bounded, Receiver, Sender};
use hyntix_common::{Error, Result};
use log::error;
use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;

use crate::config::{BUFFER_COUNT, MAX_TRANSFER_SIZE as BUFFER_SIZE};

enum Job {
    Write(WriteJob),
    Sync(crossbeam_channel::Sender<()>),
}

struct WriteJob {
    buffer: Vec<u8>,
    position: u64,
}

/// A high-performance asynchronous USB writer that implements std::io traits.
pub struct AsyncUsbWriter {
    storage: Arc<std::sync::Mutex<UsbMassStorage>>,
    job_tx: Option<Sender<Job>>,
    buffer_rx: Receiver<Vec<u8>>,
    worker_handle: Option<thread::JoinHandle<()>>,
    current_pos: u64,
    last_error: Arc<std::sync::Mutex<Option<Error>>>,
    /// Local buffer to accumulate small writes into larger chunks
    pending_buffer: Vec<u8>,
    pending_start_pos: u64,
    /// Actual bytes written to the physical device
    physical_pos: Arc<std::sync::atomic::AtomicU64>,
}

impl AsyncUsbWriter {
    pub fn new(storage: UsbMassStorage, cancel_handle: Arc<AtomicBool>) -> Self {
        let (job_tx, job_rx) = bounded::<Job>(BUFFER_COUNT);
        let (buffer_tx, buffer_rx) = bounded::<Vec<u8>>(BUFFER_COUNT);

        // Pre-fill buffer pool with fixed-size allocations
        for _ in 0..BUFFER_COUNT {
            let _ = buffer_tx.send(vec![0u8; BUFFER_SIZE]);
        }

        let storage = Arc::new(std::sync::Mutex::new(storage));
        let storage_clone = Arc::clone(&storage);
        let cancel_clone = Arc::clone(&cancel_handle);
        let last_error = Arc::new(std::sync::Mutex::new(None));
        let error_clone = Arc::clone(&last_error);
        let physical_pos = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let physical_pos_clone = Arc::clone(&physical_pos);

        let worker_handle = thread::spawn(move || {
            Self::worker_loop(
                storage_clone,
                job_rx,
                buffer_tx,
                cancel_clone,
                error_clone,
                physical_pos_clone,
            )
        });

        Self {
            storage,
            job_tx: Some(job_tx),
            buffer_rx,
            worker_handle: Some(worker_handle),
            current_pos: 0,
            last_error,
            pending_buffer: Vec::with_capacity(BUFFER_SIZE),
            pending_start_pos: 0,
            physical_pos,
        }
    }

    fn worker_loop(
        storage: Arc<std::sync::Mutex<UsbMassStorage>>,
        job_rx: Receiver<Job>,
        buffer_tx: Sender<Vec<u8>>,
        cancel_handle: Arc<AtomicBool>,
        last_error: Arc<std::sync::Mutex<Option<Error>>>,
        physical_pos: Arc<std::sync::atomic::AtomicU64>,
    ) {
        while let Ok(job) = job_rx.recv() {
            if cancel_handle.load(Ordering::SeqCst) {
                return;
            }

            match job {
                Job::Write(write_job) => {
                    let res = {
                        let mut storage = storage.lock().unwrap();
                        let block_size = storage.block_size() as u64;

                        // Check if write is sector-aligned and a multiple of sector size
                        if write_job.position % block_size == 0
                            && write_job.buffer.len() % block_size as usize == 0
                        {
                            let lba = write_job.position / block_size;
                            storage.write_blocks(lba, &write_job.buffer)
                        } else {
                            // Read-Modify-Write (RMW) for unaligned or partial writes
                            let start_lba = write_job.position / block_size;
                            let offset_in_first_block = (write_job.position % block_size) as usize;

                            let end_pos = write_job.position + write_job.buffer.len() as u64;
                            let end_lba = (end_pos + block_size - 1) / block_size;
                            let total_blocks = (end_lba - start_lba) as usize;
                            let total_bytes_aligned = total_blocks * block_size as usize;

                            let mut full_buffer = vec![0u8; total_bytes_aligned];

                            // 1. Read existing sectors to preserve data
                            if let Err(e) = storage.read_blocks(start_lba, &mut full_buffer) {
                                Err(e)
                            } else {
                                // 2. Modify with new data
                                let copy_len = write_job.buffer.len();
                                full_buffer
                                    [offset_in_first_block..offset_in_first_block + copy_len]
                                    .copy_from_slice(&write_job.buffer);

                                // 3. Write back
                                let result = storage.write_blocks(start_lba, &full_buffer);
                                result
                            }
                        }
                    };

                    if let Err(e) = res {
                        error!("Async write error at pos {}: {}", write_job.position, e);
                        let mut err_guard = last_error.lock().unwrap();
                        if err_guard.is_none() {
                            *err_guard = Some(e);
                        }
                    } else {
                        // Update physical position after successful write
                        physical_pos.store(
                            write_job.position + write_job.buffer.len() as u64,
                            Ordering::SeqCst,
                        );
                    }

                    // Recycle buffer if it's our standard size
                    // Use try_send to avoid blocking - if pool is full, just drop the buffer
                    if write_job.buffer.len() == BUFFER_SIZE {
                        let _ = buffer_tx.try_send(write_job.buffer);
                    }
                }
                Job::Sync(done_tx) => {
                    // Note: SYNCHRONIZE CACHE removed as it causes USB errors on some drives.
                    // The write queue is already drained at this point.
                    let _ = done_tx.send(());
                }
            }
        }
    }

    fn check_error(&self) -> std::io::Result<()> {
        let mut err_guard = self.last_error.lock().unwrap();
        if let Some(e) = err_guard.take() {
            return Err(std::io::Error::new(
                std::io::ErrorKind::Other,
                e.to_string(),
            ));
        }
        Ok(())
    }

    /// Get the actual physical progress of the write operation.
    pub fn physical_position(&self) -> u64 {
        self.physical_pos.load(Ordering::SeqCst)
    }

    pub fn wait_idle(&mut self) -> Result<()> {
        let _ = self.flush();
        if let Some(ref handle) = self.worker_handle {
            if handle.is_finished() {
                let handle = self.worker_handle.take().unwrap();
                let _ = handle.join();
            }
        }
        let mut err_guard = self.last_error.lock().unwrap();
        if let Some(e) = err_guard.take() {
            return Err(e);
        }
        Ok(())
    }

    fn flush_pending(&mut self) -> std::io::Result<()> {
        if self.pending_buffer.is_empty() {
            return Ok(());
        }

        let mut next_buffer = if let Ok(mut buf) = self.buffer_rx.try_recv() {
            buf.clear();
            buf
        } else {
            Vec::with_capacity(BUFFER_SIZE)
        };

        // Swap pending buffer with the fresh one
        std::mem::swap(&mut self.pending_buffer, &mut next_buffer);
        let pos = self.pending_start_pos;
        self.pending_start_pos = self.current_pos;

        let job = WriteJob {
            buffer: next_buffer,
            position: pos,
        };

        self.job_tx
            .as_ref()
            .unwrap()
            .send(Job::Write(job))
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::Other, "Async worker died"))?;

        Ok(())
    }
}

impl Write for AsyncUsbWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.check_error()?;

        // If data is large and aligned, and we have no pending data, take the fast path
        if self.pending_buffer.is_empty()
            && buf.len() >= BUFFER_SIZE
            && self.current_pos % 512 == 0
            && buf.len() % 512 == 0
        {
            // Acquire a buffer from the pool. Try non-blocking first to keep disk I/O at max speed.
            let mut job_buffer = self.buffer_rx.try_recv().or_else(|_| self.buffer_rx.recv()).map_err(|_| {
                std::io::Error::new(std::io::ErrorKind::Other, "Buffer pool channel closed")
            })?;
            
            job_buffer.clear();
            job_buffer.extend_from_slice(buf);

            let job = WriteJob {
                buffer: job_buffer,
                position: self.current_pos,
            };

            self.job_tx
                .as_ref()
                .unwrap()
                .send(Job::Write(job))
                .map_err(|_| std::io::Error::new(std::io::ErrorKind::Other, "Async worker died"))?;

            self.current_pos += buf.len() as u64;
            self.pending_start_pos = self.current_pos;
            return Ok(buf.len());
        }

        // Otherwise, buffer it
        if self.pending_buffer.is_empty() {
            self.pending_start_pos = self.current_pos;
        }

        self.pending_buffer.extend_from_slice(buf);
        self.current_pos += buf.len() as u64;

        if self.pending_buffer.len() >= BUFFER_SIZE {
            self.flush_pending()?;
        }

        Ok(buf.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        self.check_error()?;
        self.flush_pending()?;

        let (done_tx, done_rx) = crossbeam_channel::bounded(1);
        if let Some(ref tx) = self.job_tx {
            tx.send(Job::Sync(done_tx)).map_err(|_| {
                std::io::Error::new(std::io::ErrorKind::Other, "Async worker died during flush")
            })?;

            // Wait with timeout to detect hangs and log progress
            loop {
                match done_rx.recv_timeout(std::time::Duration::from_secs(5)) {
                    Ok(_) => {
                        break;
                    }
                    Err(crossbeam_channel::RecvTimeoutError::Timeout) => {
                        // Check if worker died
                        self.check_error()?;
                    }
                    Err(crossbeam_channel::RecvTimeoutError::Disconnected) => {
                        error!("Async worker died unexpectedly");
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Other,
                            "Async worker died",
                        ));
                    }
                }
            }
        }

        self.check_error()
    }
}

impl Read for AsyncUsbWriter {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        // Only flush if there's pending data - during verification there's nothing to flush
        if !self.pending_buffer.is_empty() {
            self.flush()?;
        }

        let mut storage = self.storage.lock().unwrap();
        let block_size = storage.block_size() as u64;

        // Handle unaligned read by doing a blocking read through storage
        // This is fine as Read is rarely used during flashing, mostly for validation
        let bytes_to_read = buf.len();

        // If not aligned, we might need a temporary buffer to read more blocks
        let start_lba = self.current_pos / block_size;
        let offset = (self.current_pos % block_size) as usize;
        let total_bytes_needed = offset + bytes_to_read;
        let total_blocks = (total_bytes_needed + block_size as usize - 1) / block_size as usize;

        let mut temp_buf = vec![0u8; total_blocks * block_size as usize];
        storage
            .read_blocks(start_lba, &mut temp_buf)
            .map(|_| {
                buf.copy_from_slice(&temp_buf[offset..offset + bytes_to_read]);
                self.current_pos += bytes_to_read as u64;
                self.pending_start_pos = self.current_pos; // Reset pending start
                bytes_to_read
            })
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))
    }
}

impl Seek for AsyncUsbWriter {
    fn seek(&mut self, pos: SeekFrom) -> std::io::Result<u64> {
        let new_pos = match pos {
            SeekFrom::Start(n) => n,
            SeekFrom::Current(n) => (self.current_pos as i64 + n) as u64,
            SeekFrom::End(n) => {
                let size = {
                    let storage = self.storage.lock().unwrap();
                    storage.block_count() * storage.block_size() as u64
                };
                (size as i64 + n) as u64
            }
        };

        if new_pos != self.current_pos {
            self.flush()?; // Must flush old data before moving pointer
            self.current_pos = new_pos;
            self.pending_start_pos = self.current_pos;
        }
        Ok(self.current_pos)
    }
}

impl Drop for AsyncUsbWriter {
    fn drop(&mut self) {
        // Drop the sender to signal the worker thread to exit
        drop(self.job_tx.take());

        if let Some(handle) = self.worker_handle.take() {
            let _ = handle.join();
        }
    }
}
impl crate::PhysicalProgress for AsyncUsbWriter {
    fn physical_position(&self) -> u64 {
        self.physical_position()
    }
}
