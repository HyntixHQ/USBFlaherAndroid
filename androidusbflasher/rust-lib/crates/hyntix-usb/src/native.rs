use std::collections::VecDeque;
use std::os::fd::RawFd;
use std::sync::atomic::{AtomicUsize, Ordering};

use log::{debug, error, info};

use crate::config::{INITIAL_URB_CHUNK_SIZE, MIN_URB_CHUNK_SIZE};

// ── URB Pipeline Constants ───────────────────────────────────────────────────
// All URB sizing constants are now imported from crate::config to ensure
// consistency across the entire codebase.

/// Default timeout for bulk transfers in milliseconds.
const DEFAULT_TIMEOUT_MS: u32 = 5000;

/// URB type for bulk transfers (`USBDEVFS_URB_TYPE_BULK`).
const URB_TYPE_BULK: u8 = 3;

// ── USBDEVFS ioctl numbers (architecture-dependent) ──────────────────────────

/// `USBDEVFS_SUBMITURB` — submit a URB asynchronously (non-blocking).
fn ioctl_submiturb() -> u64 {
    // _IOR('U', 10, struct usbdevfs_urb) — size differs by pointer width
    if std::mem::size_of::<*mut u8>() == 8 {
        0x8038550A // 64-bit: sizeof(usbdevfs_urb) = 56
    } else {
        0x802C550A // 32-bit: sizeof(usbdevfs_urb) = 44
    }
}

/// `USBDEVFS_REAPURB` — reap a completed URB (blocking).
fn ioctl_reapurb() -> u64 {
    // _IOW('U', 12, void*)
    if std::mem::size_of::<*mut u8>() == 8 {
        0x4008550C
    } else {
        0x4004550C
    }
}

/// `USBDEVFS_REAPURBNDELAY` — reap a completed URB (non-blocking).
/// Returns EAGAIN if no URBs are ready.
fn ioctl_reapurbndelay() -> u64 {
    // _IOW('U', 13, void*)
    if std::mem::size_of::<*mut u8>() == 8 {
        0x4008550D
    } else {
        0x4004550D
    }
}

/// `USBDEVFS_DISCARDURB` — cancel a submitted URB.
fn ioctl_discardurb() -> u64 {
    0x550B // _IO('U', 11)
}

/// `USBDEVFS_CLEAR_HALT` — clear a stalled endpoint.
const USBDEVFS_CLEAR_HALT: u64 = 0x80045515;

// ── URB Structure ────────────────────────────────────────────────────────────

/// Linux kernel `usbdevfs_urb` structure for async USB transfers.
///
/// Must match the kernel's layout exactly (including padding/alignment).
#[repr(C)]
struct UsbDevFsUrb {
    urb_type: u8,
    endpoint: u8,
    status: i32,
    flags: u32,
    buffer: *mut u8,
    buffer_length: i32,
    actual_length: i32,
    start_frame: i32,
    number_of_packets: i32,
    error_count: i32,
    signr: u32,
    usercontext: *mut libc::c_void,
}

impl UsbDevFsUrb {
    /// Create a new bulk URB pointing at the given buffer region.
    fn new_bulk(endpoint: u8, buffer: *mut u8, length: usize) -> Self {
        Self {
            urb_type: URB_TYPE_BULK,
            endpoint,
            status: 0,
            flags: 0,
            buffer,
            buffer_length: length as i32,
            actual_length: 0,
            start_frame: 0,
            number_of_packets: 0,
            error_count: 0,
            signr: 0,
            usercontext: std::ptr::null_mut(),
        }
    }
}

// ── NativeUsbBackend ─────────────────────────────────────────────────────────

pub struct NativeUsbBackend {
    fd: RawFd,
    _interface: u8,
    in_ep: u8,
    out_ep: u8,
    /// Adaptive chunk size for OUT (write) URBs. Starts at 2MB, halves on ENOMEM.
    adaptive_out_chunk: AtomicUsize,
    /// Adaptive chunk size for IN (read) URBs.
    adaptive_in_chunk: AtomicUsize,
}

impl NativeUsbBackend {
    pub fn new(fd: RawFd, interface: u8, in_ep: u8, out_ep: u8) -> Self {
        Self {
            fd,
            _interface: interface,
            in_ep,
            out_ep,
            adaptive_out_chunk: AtomicUsize::new(INITIAL_URB_CHUNK_SIZE),
            adaptive_in_chunk: AtomicUsize::new(INITIAL_URB_CHUNK_SIZE),
        }
    }

    // ── Low-level URB operations ─────────────────────────────────────────

    /// Submit a URB to the kernel. Returns immediately (non-blocking).
    fn submit_urb(&self, urb: &mut UsbDevFsUrb) -> std::io::Result<()> {
        let ret = unsafe { libc::ioctl(self.fd, ioctl_submiturb() as _, urb as *mut UsbDevFsUrb) };
        if ret < 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    /// Reap (wait for) one completed URB. Blocks until a URB finishes.
    fn reap_urb(&self) -> std::io::Result<*mut UsbDevFsUrb> {
        let mut reaped: *mut UsbDevFsUrb = std::ptr::null_mut();
        let ret = unsafe { libc::ioctl(self.fd, ioctl_reapurb() as _, &mut reaped) };
        if ret < 0 {
            Err(std::io::Error::last_os_error())
        } else {
            Ok(reaped)
        }
    }

    /// Try to reap a completed URB without blocking.
    /// Returns Ok(Some(ptr)) if a URB was ready, Ok(None) if no URBs are ready.
    fn reap_urb_nonblocking(&self) -> std::io::Result<Option<*mut UsbDevFsUrb>> {
        let mut reaped: *mut UsbDevFsUrb = std::ptr::null_mut();
        let ret = unsafe { libc::ioctl(self.fd, ioctl_reapurbndelay() as _, &mut reaped) };
        if ret < 0 {
            let err = std::io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EAGAIN) {
                Ok(None) // No URBs ready
            } else {
                Err(err)
            }
        } else {
            Ok(Some(reaped))
        }
    }

    /// Cancel a submitted URB.
    fn discard_urb(&self, urb: &UsbDevFsUrb) {
        unsafe {
            libc::ioctl(self.fd, ioctl_discardurb() as _, urb as *const UsbDevFsUrb);
        }
    }

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

    /// Discard all in-flight URBs and reap them. Used for error cleanup.
    fn drain_urbs(&self, urbs: &mut VecDeque<Box<UsbDevFsUrb>>) {
        for urb in urbs.iter() {
            self.discard_urb(urb);
        }
        // Reap each discarded URB (kernel requires this)
        for _ in 0..urbs.len() {
            let _ = self.reap_urb();
        }
        urbs.clear();
    }

    // ── URB Pipeline: OUT (Write) ────────────────────────────────────────

    /// Send data to the OUT endpoint using 32-URB pipelining.
    ///
    /// Queues up to 32 URBs simultaneously, keeping the USB host controller's
    /// DMA engine saturated. The chunk size adapts on ENOMEM:
    /// 2MB → 1MB → 512KB → 256KB → 128KB → 64KB → 32KB → 16KB (floor).
    pub fn bulk_out_with_timeout(&self, data: &[u8], _timeout_ms: u32) -> std::io::Result<usize> {
        if data.is_empty() {
            return Ok(0);
        }

        let mut chunk_size = self.adaptive_out_chunk.load(Ordering::Relaxed);
        let mut total_sent = 0usize;
        let mut submit_offset = 0usize;
        let mut in_flight: VecDeque<Box<UsbDevFsUrb>> = VecDeque::with_capacity(256);

        while submit_offset < data.len() || !in_flight.is_empty() {
            // Calculate dynamic depth: Ensure we always have TARGET_IN_FLIGHT_BYTES queued
            // A minimum of 4 URBs is enough to keep the hardware pipelined if chunks are huge (e.g. 1MB).
            let target_depth = (crate::config::TARGET_IN_FLIGHT_BYTES / chunk_size).clamp(4, 256);

            // ── Submit phase: fill pipeline up to dynamic depth ──────────────
            while submit_offset < data.len() && in_flight.len() < target_depth {
                let this_chunk = chunk_size.min(data.len() - submit_offset);
                let buf_ptr = data[submit_offset..].as_ptr() as *mut u8;

                let mut urb = Box::new(UsbDevFsUrb::new_bulk(self.out_ep, buf_ptr, this_chunk));

                match self.submit_urb(&mut urb) {
                    Ok(()) => {
                        submit_offset += this_chunk;
                        in_flight.push_back(urb);
                    }
                    Err(e) if e.raw_os_error() == Some(libc::ENOMEM) => {
                        // AIMD: Multiplicative Decrease
                        let new_size = (chunk_size / 2).max(MIN_URB_CHUNK_SIZE);
                        let shrunk = new_size < chunk_size;
                        
                        if shrunk {
                            info!(
                                "AIMD: ENOMEM at {}KB, reducing OUT chunk to {}KB. Pipeline expanding to {} URBs.",
                                chunk_size / 1024,
                                new_size / 1024,
                                (crate::config::TARGET_IN_FLIGHT_BYTES / new_size).clamp(4, 256)
                            );
                            chunk_size = new_size;
                            self.adaptive_out_chunk.store(new_size, Ordering::Relaxed);
                        }
                        
                        if !in_flight.is_empty() {
                            // Host DMA pool is exhausted by our pipeline.
                            // Break submit loop and enter reap phase to wait for memory to free.
                            break;
                        } else if shrunk {
                            // Nothing in flight, but we successfully shrank the chunk size.
                            // The host DMA is highly fragmented or limited. Retry with the smaller chunk.
                            continue;
                        } else {
                            // At minimum floor and nothing in flight — real OOM
                            error!("ENOMEM at minimum chunk size ({}KB) with empty pipeline", MIN_URB_CHUNK_SIZE / 1024);
                            self.drain_urbs(&mut in_flight);
                            return Err(e);
                        }
                    }
                    Err(e) => {
                        error!("URB submit failed: {}", e);
                        self.drain_urbs(&mut in_flight);
                        return Err(e);
                    }
                }
            }

            // ── Reap phase: batch-drain all completed URBs ────────────────
            if !in_flight.is_empty() {
                // First reap is blocking — wait for at least one URB
                let first_ptr = match self.reap_urb() {
                    Ok(ptr) => ptr,
                    Err(e) => {
                        error!("URB reap failed: {}", e);
                        self.drain_urbs(&mut in_flight);
                        return Err(e);
                    }
                };

                // Collect all reaped pointers: the blocking one + any non-blocking ones
                let mut reaped_ptrs = vec![first_ptr];
                loop {
                    match self.reap_urb_nonblocking() {
                        Ok(Some(ptr)) => reaped_ptrs.push(ptr),
                        Ok(None) => break, // No more ready URBs
                        Err(e) => {
                            error!("Non-blocking reap failed: {}", e);
                            self.drain_urbs(&mut in_flight);
                            return Err(e);
                        }
                    }
                }

                // Process all reaped URBs
                for reaped_ptr in reaped_ptrs {
                    let mut found = false;
                    for i in 0..in_flight.len() {
                        let urb_ptr = &*in_flight[i] as *const UsbDevFsUrb;
                        if urb_ptr == reaped_ptr as *const UsbDevFsUrb {
                            let urb = in_flight.remove(i).unwrap();

                            if urb.status != 0 {
                                let status = urb.status;
                                if status == -(libc::EPIPE as i32) {
                                    debug!("URB EPIPE on ep {}, clearing halt", self.out_ep);
                                    self.clear_halt(self.out_ep);
                                }
                                self.drain_urbs(&mut in_flight);
                                return Err(std::io::Error::from_raw_os_error(-status));
                            }

                            total_sent += urb.actual_length as usize;
                            found = true;
                            break;
                        }
                    }

                    if !found {
                        error!("Reaped unknown URB pointer — draining pipeline");
                        self.drain_urbs(&mut in_flight);
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Other,
                            "Reaped unknown URB",
                        ));
                    }
                }
            }
        }

        Ok(total_sent)
    }

    /// Read data from the IN endpoint using 32-URB pipelining.
    pub fn bulk_in_with_timeout(
        &self,
        data: &mut [u8],
        _timeout_ms: u32,
    ) -> std::io::Result<usize> {
        if data.is_empty() {
            return Ok(0);
        }

        let mut chunk_size = self.adaptive_in_chunk.load(Ordering::Relaxed);
        let mut total_read = 0usize;
        let mut submit_offset = 0usize;
        let mut in_flight: VecDeque<Box<UsbDevFsUrb>> = VecDeque::with_capacity(256);

        while submit_offset < data.len() || !in_flight.is_empty() {
            // Calculate dynamic depth: Ensure we always have TARGET_IN_FLIGHT_BYTES queued
            // A minimum of 4 URBs is enough to keep the hardware pipelined if chunks are huge (e.g. 1MB).
            let target_depth = (crate::config::TARGET_IN_FLIGHT_BYTES / chunk_size).clamp(4, 256);

            // ── Submit phase ─────────────────────────────────────────────
            while submit_offset < data.len() && in_flight.len() < target_depth {
                let this_chunk = chunk_size.min(data.len() - submit_offset);
                let buf_ptr = data[submit_offset..].as_mut_ptr();

                let mut urb = Box::new(UsbDevFsUrb::new_bulk(self.in_ep, buf_ptr, this_chunk));

                match self.submit_urb(&mut urb) {
                    Ok(()) => {
                        submit_offset += this_chunk;
                        in_flight.push_back(urb);
                    }
                    Err(e) if e.raw_os_error() == Some(libc::ENOMEM) => {
                        // AIMD: Multiplicative Decrease
                        let new_size = (chunk_size / 2).max(MIN_URB_CHUNK_SIZE);
                        let shrunk = new_size < chunk_size;
                        
                        if shrunk {
                            info!(
                                "AIMD: ENOMEM at {}KB, reducing IN chunk to {}KB. Pipeline expanding to {} URBs.",
                                chunk_size / 1024,
                                new_size / 1024,
                                (crate::config::TARGET_IN_FLIGHT_BYTES / new_size).clamp(4, 256)
                            );
                            chunk_size = new_size;
                            self.adaptive_in_chunk.store(new_size, Ordering::Relaxed);
                        }
                        
                        if !in_flight.is_empty() {
                            // DMA pool exhausted. Break submit loop to reap.
                            break;
                        } else if shrunk {
                            // Nothing in flight, but we successfully shrank the chunk size.
                            // The host DMA is highly fragmented or limited. Retry with the smaller chunk.
                            continue;
                        } else {
                            error!("ENOMEM at minimum IN chunk size ({}KB)", MIN_URB_CHUNK_SIZE / 1024);
                            self.drain_urbs(&mut in_flight);
                            return Err(e);
                        }
                    }
                    Err(e) => {
                        self.drain_urbs(&mut in_flight);
                        return Err(e);
                    }
                }
            }

            // ── Reap phase: batch-drain all completed URBs ────────────────
            if !in_flight.is_empty() {
                // First reap is blocking — wait for at least one URB
                let first_ptr = match self.reap_urb() {
                    Ok(ptr) => ptr,
                    Err(e) => {
                        self.drain_urbs(&mut in_flight);
                        return Err(e);
                    }
                };

                // Batch-drain: collect additional completed URBs without blocking
                let mut reaped_ptrs = vec![first_ptr];
                loop {
                    match self.reap_urb_nonblocking() {
                        Ok(Some(ptr)) => reaped_ptrs.push(ptr),
                        Ok(None) => break,
                        Err(e) => {
                            self.drain_urbs(&mut in_flight);
                            return Err(e);
                        }
                    }
                }

                // Process all reaped URBs
                for reaped_ptr in reaped_ptrs {
                    let mut found = false;
                    for i in 0..in_flight.len() {
                        let urb_ptr = &*in_flight[i] as *const UsbDevFsUrb;
                        if urb_ptr == reaped_ptr as *const UsbDevFsUrb {
                            let urb = in_flight.remove(i).unwrap();

                            if urb.status != 0 {
                                let status = urb.status;
                                if status == -(libc::EPIPE as i32) {
                                    self.clear_halt(self.in_ep);
                                }
                                self.drain_urbs(&mut in_flight);
                                return Err(std::io::Error::from_raw_os_error(-status));
                            }

                            total_read += urb.actual_length as usize;

                            // Short read: device has no more data for this request
                            if (urb.actual_length as usize) < (urb.buffer_length as usize) {
                                self.drain_urbs(&mut in_flight);
                                return Ok(total_read);
                            }

                            found = true;
                            break;
                        }
                    }

                    if !found {
                        self.drain_urbs(&mut in_flight);
                        return Err(std::io::Error::new(
                            std::io::ErrorKind::Other,
                            "Reaped unknown URB",
                        ));
                    }
                }
            }
        }

        Ok(total_read)
    }

    /// Convenience: bulk OUT with default timeout.
    pub fn bulk_out(&self, data: &[u8]) -> std::io::Result<usize> {
        self.bulk_out_with_timeout(data, DEFAULT_TIMEOUT_MS)
    }

    /// Convenience: bulk IN with default timeout.
    pub fn bulk_in(&self, data: &mut [u8]) -> std::io::Result<usize> {
        self.bulk_in_with_timeout(data, DEFAULT_TIMEOUT_MS)
    }
}
