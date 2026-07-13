use std::os::fd::RawFd;
use std::sync::atomic::{AtomicUsize, Ordering};

use tracing::{error, info};

use crate::config::{INITIAL_URB_CHUNK_SIZE, MIN_URB_CHUNK_SIZE};

// ── USBDEVFS Constants ────────────────────────────────────────────────────────
// These ioctl numbers match the Linux kernel's usbdevice_fs.h for ARM64.
// On 32-bit ARM the struct sizes and ioctl numbers differ.

/// Default timeout for bulk transfers in milliseconds.
const DEFAULT_TIMEOUT_MS: u32 = 5000;

// ── USBDEVFS ioctl numbers (architecture-dependent) ──────────────────────────

/// `USBDEVFS_BULK` — synchronous bulk transfer (_IOWR('U', 2, usbdevfs_bulktransfer))
/// sizeof(usbdevfs_bulktransfer) = 24 on 64-bit (u32×3 + void*), 16 on 32-bit (u32×4)
fn ioctl_bulk() -> u64 {
    if std::mem::size_of::<*mut u8>() == 8 {
        0xC0185502 // 64-bit: sizeof = 24
    } else {
        0x80085502 // 32-bit: sizeof = 16
    }
}

/// `USBDEVFS_CLEAR_HALT` — clear a stalled endpoint.
const USBDEVFS_CLEAR_HALT: u64 = 0x80045515;

// ── Bulk Transfer Structure ──────────────────────────────────────────────────

/// Matches Linux kernel `struct usbdevfs_bulktransfer`.
#[repr(C)]
struct UsbDevFsBulkTransfer {
    ep: u32,
    len: u32,
    timeout_ms: u32,
    data: *mut std::ffi::c_void,
}

// ── NativeUsbBackend ─────────────────────────────────────────────────────────

pub struct NativeUsbBackend {
    fd: RawFd,
    _interface: u8,
    in_ep: u8,
    out_ep: u8,
    /// Adaptive chunk size for OUT (write) transfers. Starts at 256KB, halves on ENOMEM.
    adaptive_out_chunk: AtomicUsize,
    /// Adaptive chunk size for IN (read) transfers.
    adaptive_in_chunk: AtomicUsize,
    /// Last ENOMEM size for OUT — AIMD additive increase never exceeds floor/2.
    /// Initialized to INITIAL_URB_CHUNK_SIZE * 2 so the first recovery is unconstrained.
    out_enomem_floor: AtomicUsize,
    /// Last ENOMEM size for IN.
    in_enomem_floor: AtomicUsize,
}

impl NativeUsbBackend {
    pub fn new(fd: RawFd, interface: u8, in_ep: u8, out_ep: u8) -> Self {
        let sentinel = INITIAL_URB_CHUNK_SIZE * 2;
        Self {
            fd,
            _interface: interface,
            in_ep,
            out_ep,
            adaptive_out_chunk: AtomicUsize::new(INITIAL_URB_CHUNK_SIZE),
            adaptive_in_chunk: AtomicUsize::new(INITIAL_URB_CHUNK_SIZE),
            out_enomem_floor: AtomicUsize::new(sentinel),
            in_enomem_floor: AtomicUsize::new(sentinel),
        }
    }

    // ── Synchronous bulk operations (USBDEVFS_BULK) ────────────────────────
    //
    // Unlike the userspace URB pipeline (SUBMITURB/REAPURB), USBDEVFS_BULK
    // tells the kernel to manage the entire transfer internally — including
    // DMA buffer allocation from the kernel's own pool. This avoids the
    // usbfs_memory_mb constraint that limited our URB pipeline to 32KB URBs.

    /// Synchronous bulk OUT: send data to the endpoint.
    /// Blocks until the transfer completes or times out.
    /// Auto-clears endpoint halt on EPIPE and retries once.
    fn bulk_out_sync(&self, data: &[u8], timeout_ms: u32) -> std::io::Result<usize> {
        let mut transfer = UsbDevFsBulkTransfer {
            ep: self.out_ep as u32,
            len: data.len() as u32,
            timeout_ms,
            data: data.as_ptr() as *mut std::ffi::c_void,
        };
        let ret = unsafe { libc::ioctl(self.fd, ioctl_bulk() as _, &mut transfer) };
        if ret >= 0 {
            return Ok(ret as usize);
        }
        let err = std::io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EPIPE) {
            info!(
                "Bulk OUT stall on ep {}, clearing halt and retrying",
                self.out_ep
            );
            self.clear_halt(self.out_ep);
            let ret = unsafe { libc::ioctl(self.fd, ioctl_bulk() as _, &mut transfer) };
            if ret >= 0 {
                return Ok(ret as usize);
            }
            return Err(std::io::Error::last_os_error());
        }
        Err(err)
    }

    /// Synchronous bulk IN: read data from the endpoint.
    /// Blocks until the transfer completes or times out.
    /// Auto-clears endpoint halt on EPIPE and retries once.
    fn bulk_in_sync(&self, data: &mut [u8], timeout_ms: u32) -> std::io::Result<usize> {
        let mut transfer = UsbDevFsBulkTransfer {
            ep: self.in_ep as u32,
            len: data.len() as u32,
            timeout_ms,
            data: data.as_mut_ptr() as *mut std::ffi::c_void,
        };
        let ret = unsafe { libc::ioctl(self.fd, ioctl_bulk() as _, &mut transfer) };
        if ret >= 0 {
            return Ok(ret as usize);
        }
        let err = std::io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EPIPE) {
            info!(
                "Bulk IN stall on ep {}, clearing halt and retrying",
                self.in_ep
            );
            self.clear_halt(self.in_ep);
            let ret = unsafe { libc::ioctl(self.fd, ioctl_bulk() as _, &mut transfer) };
            if ret >= 0 {
                return Ok(ret as usize);
            }
            return Err(std::io::Error::last_os_error());
        }
        Err(err)
    }

    // ── Endpoint control ──────────────────────────────────────────────────

    /// Clear a stalled endpoint (EPIPE recovery).
    fn clear_halt(&self, endpoint: u8) {
        let mut ep = endpoint as u32;
        let ret = unsafe { libc::ioctl(self.fd, USBDEVFS_CLEAR_HALT as _, &mut ep) };
        if ret < 0 {
            error!(
                "Failed to clear halt on ep {}: {}",
                endpoint,
                std::io::Error::last_os_error()
            );
        }
    }

    // ── Bulk OUT (Write) ──────────────────────────────────────────────────

    /// Send data to the OUT endpoint using synchronous USBDEVFS_BULK.
    ///
    /// The chunk size adapts on ENOMEM. Unlike the old URB pipeline, the
    /// kernel handles DMA allocation internally — potentially via a larger
    /// DMA pool than usbfs_memory_mb.
    pub fn bulk_out_with_timeout(&self, data: &[u8], timeout_ms: u32) -> std::io::Result<usize> {
        if data.is_empty() {
            return Ok(0);
        }

        let mut chunk_size = self.adaptive_out_chunk.load(Ordering::Relaxed);
        let mut total_sent = 0usize;
        let mut offset = 0usize;
        let mut successful_since_enomem = 0u32;

        while offset < data.len() {
            let this_chunk = chunk_size.min(data.len() - offset);
            let chunk = &data[offset..offset + this_chunk];

            match self.bulk_out_sync(chunk, timeout_ms) {
                Ok(n) => {
                    offset += n;
                    total_sent += n;
                    successful_since_enomem += 1;

                    // AIMD: Additive Increase — recover chunk size after sustained success
                    // Never exceeds floor/2 where floor is the last ENOMEM size.
                    if successful_since_enomem >= 200 {
                        let floor = self.out_enomem_floor.load(Ordering::Relaxed);
                        let cap = floor / 2;
                        let new_size = (chunk_size * 2).min(cap).min(INITIAL_URB_CHUNK_SIZE);
                        if new_size > chunk_size {
                            info!(
                                "AIMD: Increasing OUT chunk to {}KB after 200 clean calls (floor={}KB)",
                                new_size / 1024,
                                floor / 1024,
                            );
                            chunk_size = new_size;
                            self.adaptive_out_chunk.store(new_size, Ordering::Relaxed);
                        }
                        successful_since_enomem = 0;
                    }
                }
                Err(e) if e.raw_os_error() == Some(libc::ENOMEM) => {
                    successful_since_enomem = 0;
                    // Record this failing size so additive increase never exceeds floor/2
                    self.out_enomem_floor.store(chunk_size, Ordering::Relaxed);
                    // AIMD: Multiplicative Decrease — halve chunk size
                    let new_size = (chunk_size / 2).max(MIN_URB_CHUNK_SIZE);
                    if new_size < chunk_size {
                        info!(
                            "AIMD: ENOMEM at {}KB, reducing OUT chunk to {}KB",
                            chunk_size / 1024,
                            new_size / 1024,
                        );
                        chunk_size = new_size;
                        self.adaptive_out_chunk.store(new_size, Ordering::Relaxed);
                        // Retry the failed offset with the smaller chunk
                        continue;
                    }
                    error!(
                        "ENOMEM at minimum chunk size ({}KB)",
                        MIN_URB_CHUNK_SIZE / 1024
                    );
                    return Err(e);
                }
                Err(e) => {
                    error!("Bulk OUT failed: {}", e);
                    return Err(e);
                }
            }
        }

        Ok(total_sent)
    }

    // ── Bulk IN (Read) ────────────────────────────────────────────────────

    /// Read data from the IN endpoint using synchronous USBDEVFS_BULK.
    pub fn bulk_in_with_timeout(&self, data: &mut [u8], timeout_ms: u32) -> std::io::Result<usize> {
        if data.is_empty() {
            return Ok(0);
        }

        let mut chunk_size = self.adaptive_in_chunk.load(Ordering::Relaxed);
        let mut total_read = 0usize;
        let mut offset = 0usize;
        let mut successful_since_enomem = 0u32;

        while offset < data.len() {
            let this_chunk = chunk_size.min(data.len() - offset);
            let chunk = &mut data[offset..offset + this_chunk];

            match self.bulk_in_sync(chunk, timeout_ms) {
                Ok(n) => {
                    offset += n;
                    total_read += n;

                    // Short read: device has no more data
                    if n < this_chunk {
                        return Ok(total_read);
                    }

                    successful_since_enomem += 1;

                    // AIMD: Additive Increase — capped at floor/2
                    if successful_since_enomem >= 200 {
                        let floor = self.in_enomem_floor.load(Ordering::Relaxed);
                        let cap = floor / 2;
                        let new_size = (chunk_size * 2).min(cap).min(INITIAL_URB_CHUNK_SIZE);
                        if new_size > chunk_size {
                            info!(
                                "AIMD: Increasing IN chunk to {}KB after 200 clean calls (floor={}KB)",
                                new_size / 1024,
                                floor / 1024,
                            );
                            chunk_size = new_size;
                            self.adaptive_in_chunk.store(new_size, Ordering::Relaxed);
                        }
                        successful_since_enomem = 0;
                    }
                }
                Err(e) if e.raw_os_error() == Some(libc::ENOMEM) => {
                    successful_since_enomem = 0;
                    self.in_enomem_floor.store(chunk_size, Ordering::Relaxed);
                    let new_size = (chunk_size / 2).max(MIN_URB_CHUNK_SIZE);
                    if new_size < chunk_size {
                        info!(
                            "AIMD: ENOMEM at {}KB, reducing IN chunk to {}KB",
                            chunk_size / 1024,
                            new_size / 1024,
                        );
                        chunk_size = new_size;
                        self.adaptive_in_chunk.store(new_size, Ordering::Relaxed);
                        continue;
                    }
                    error!(
                        "ENOMEM at minimum IN chunk size ({}KB)",
                        MIN_URB_CHUNK_SIZE / 1024
                    );
                    return Err(e);
                }
                Err(e) => {
                    error!("Bulk IN failed: {}", e);
                    return Err(e);
                }
            }
        }

        Ok(total_read)
    }

    // ── Convenience wrappers ──────────────────────────────────────────────

    /// Bulk OUT with default timeout.
    pub fn bulk_out(&self, data: &[u8]) -> std::io::Result<usize> {
        self.bulk_out_with_timeout(data, DEFAULT_TIMEOUT_MS)
    }

    /// Bulk IN with default timeout.
    pub fn bulk_in(&self, data: &mut [u8]) -> std::io::Result<usize> {
        self.bulk_in_with_timeout(data, DEFAULT_TIMEOUT_MS)
    }
}
