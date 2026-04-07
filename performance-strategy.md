# USB Flash Throughput Strategy

## Current picture
- The Android path obtains endpoint descriptors, opens a `UsbDeviceConnection`, and then calls `AndroidUsbFlasher.flashRaw` to hand control to the Rust engine ([.../FlashRepository.kt#L116-L229](app/src/main/java/com/hyntix/android/usbflasher/domain/FlashRepository.kt#L116)).
- The Rust `UsbFlasher` bridge duplicates fds and constructs `NativeUsbBackend`/`UsbMassStorage` with a single `AsyncUsbWriter` worker thread ([.../hyntix-usb-flasher-jni/src/lib.rs#L115-L186](androidusbflasher/rust-lib/crates/hyntix-usb-flasher-jni/src/lib.rs#L115)).
- `AsyncUsbWriter` keeps a pool of eight 64 MB buffers and streams into `UsbMassStorage::write_blocks` which itself drives low-level URB submission ([.../hyntix-usb/src/async_writer.rs#L14-L307](androidusbflasher/rust-lib/crates/hyntix-usb/src/async_writer.rs#L14)).

## Capacity target
We need to raise sustained throughput from ~15 MB/s to 30 MB/s+ by attacking three areas:
1. Keep the USB pipeline saturated with more aggressive chunk sizing and fewer stalls.
2. Reduce any CPU/locking or memory-copy overhead that slows the async writer.
3. Tighten verification so it doesn’t become the rate-limiting step.

## Bottlenecks & levers
- **Adaptive chunk throttling:** `NativeUsbBackend` halves chunk size when ENOMEM occurs and never upsizes again ([.../hyntix-usb/src/native.rs#L94-L210](androidusbflasher/rust-lib/crates/hyntix-usb/src/native.rs#L94)). That’s conservative and can leave the pipeline under-utilized after a temporary ENOMEM.
- **Single worker thread:** `AsyncUsbWriter` has one worker thread handling both writes and verification reads; heavy flash load might benefit from splitting the read/write responsibilities or adding a second worker specifically for verification phase ([.../hyntix-usb/src/async_writer.rs#L81-L160](androidusbflasher/rust-lib/crates/hyntix-usb/src/async_writer.rs#L81)).
- **Buffer reuse pattern:** buffer recycling tries to `try_send`, but on saturation it simply drops buffers and the next chunk allocates new Vecs, adding GC pressure and data-copy overhead ([.../hyntix-usb/src/async_writer.rs#L149-L153](androidusbflasher/rust-lib/crates/hyntix-usb/src/async_writer.rs#L149)).
- **Verification path:** `flash_raw()` re-reads device data through the async writer’s `Read` impl, which flushes pending writes and goes back over `UsbMassStorage::read_blocks`. A faster verify could either read via a dedicated pipeline or compare hashes in flight ([.../hyntix-usb-flasher/src/lib.rs#L134-L188](androidusbflasher/rust-lib/crates/hyntix-usb-flasher/src/lib.rs#L134)).

## Strategy
1. **Make pipeline sizing proactive.** Track consecutive successful URB submits without ENOMEM and gradually increase chunk size back toward `INITIAL_URB_CHUNK_SIZE` (64 MB) whenever the driver accepts full-size URBs for several iterations. This keeps the 32-URB depth fed without waiting for a manual reset.
2. **Add compressed/lock-free buffer reuse.** Convert the buffer pool to a `crossbeam_channel::Sender<Option<Vec<u8>>>` where workers requeue buffers instead of dropping them when the channel is full. If the pool is empty, block rather than allocate, so work waits on existing buffers instead of hitting the allocator.
3. **Split verification off the write pipeline.** During verification, spawn a second worker (or reuse another `AsyncUsbWriter` instance) that only reads back sectors from the device via the `UsbMassStorage::read_blocks` path. That keeps verification from competing with writes for the single worker’s mutex and can also prefetch larger ranges for chunked comparisons.
4. **Increase parallel read/write fan-out.** When the flash pipeline is filling the 32 URBs, prefetch the next `READ_CHUNK_SIZE` (64 MB) while the writer drains the previous chunk. A small dedicated read buffer thread that submits `read_exact` requests through a buffered channel to the writer would enable overlapping disk I/O and USB writes.
5. **Move final verification into a streaming hash check.** Instead of byte-for-byte comparison after the fact, maintain a rolling SHA-256 checksum during write (Rust `sha2` is already available) and re-read only to compare chunk digests. This cuts the amount of USB bandwidth reserved for verification.

## Action plan
1. Prototype chunk resizing logic and buffer-handback handling in `NativeUsbBackend::bulk_out_with_timeout`/`bulk_in_with_timeout` ([.../hyntix-usb/src/native.rs#L84-L210](androidusbflasher/rust-lib/crates/hyntix-usb/src/native.rs#L84)).
2. Refactor `AsyncUsbWriter` so `Write`/`Read` paths reuse buffers from a blocking channel and make the worker aware of multiple job queues ([.../hyntix-usb/src/async_writer.rs#L14-L274](androidusbflasher/rust-lib/crates/hyntix-usb/src/async_writer.rs#L14)).
3. Introduce a `VerificationWriter` that uses a second worker or direct `UsbMassStorage::read_blocks` calls so streaming verification doesn’t back-pressure writes ([.../hyntix-usb-flasher/src/lib.rs#L134-L188](androidusbflasher/rust-lib/crates/hyntix-usb-flasher/src/lib.rs#L134)).
4. Measure the effect by instrumenting `FlashProgress` updates to report `physical_position()` vs disk bytes read, ensuring the USB bus remains saturated once chunk size increases.[FlashState.kt#L1-L60](app/src/main/java/com/hyntix/android/usbflasher/data/FlashState.kt#L1)

## Next steps
1. Implement the chunk resizer and buffer recycling changes and re-run `cargo check --locked` from `androidusbflasher/rust-lib` to ensure no FFI or borrow issues.
2. Add logging (behind a feature flag) to the native backend to trace chunk-size adjustments and URB queue occupancy.
3. Benchmark flashing large ISOs once the new verification writer is in place; if bandwidth still caps at ~30 MB/s, investigate whether the USB connection (e.g., USB 2.0 vs 3.0) or device throttling is the limiter and adjust pipeline depth accordingly.
