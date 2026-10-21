use hyntix_fat32::{Fat32Formatter, Fat32Writer, GptTable};
use hyntix_udf::{UdfEntry, UdfReader};
use hyntix_usb::PhysicalProgress;
use hyntix_wim::{SwmSplitter, WimHeader, WimLookupEntry};
use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};
use thiserror::Error;

/// Phases reported while flashing a Windows ISO.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WindowsPhase {
    Formatting,
    Flashing,
    Finalizing,
}

#[derive(Error, Debug)]
pub enum WindowsError {
    #[error("UDF error: {0}")]
    Udf(#[from] hyntix_udf::UdfError),
    #[error("WIM error: {0}")]
    Wim(#[from] hyntix_wim::WimError),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("flash error: {0}")]
    Common(#[from] hyntix_common::Error),
    #[error("FAT32 error: {0}")]
    Fat32(String),
    #[error("progress error: {0}")]
    Progress(String),
}

pub struct WindowsFlasher<W: Write + Seek + Read + PhysicalProgress> {
    writer: W,
}

/// Canonical progress callback used by the publisher and the final
/// completion report.
type ProgressFn = dyn Fn(WindowsPhase, u64, u64) + Send + Sync + 'static;

/// How often the publisher samples the real device position. 16 ms keeps the
/// UI within one 60 Hz frame of the latest completed SCSI command without
/// adding meaningful CPU load.
const PROGRESS_POLL_INTERVAL: Duration = Duration::from_millis(16);

/// Emit a heartbeat even when the device position has not changed, so the UI
/// can decay its speed/ETA readout during a genuinely stalled transfer.
const PROGRESS_HEARTBEAT_INTERVAL: Duration = Duration::from_millis(500);

#[derive(Clone, Copy)]
struct ProgressSnapshot {
    phase: WindowsPhase,
    current: u64,
    total: u64,
}

/// Shared progress model. The flash thread only updates structural state
/// (phase, completed format, queued FAT bytes); the publisher thread samples
/// the physical position and turns it into monotonic UI progress.
struct ProgressState {
    total: u64,
    format_work: u64,
    data_start: u64,
    data_end: u64,
    max_data: u64,
    fat_written: u64,
    last: u64,
    format_done: bool,
    phase: WindowsPhase,
}

impl ProgressState {
    fn new(total: u64, format_work: u64, data_start: u64, data_end: u64) -> Self {
        Self {
            total: total.max(1),
            format_work,
            data_start,
            data_end,
            max_data: 0,
            fat_written: 0,
            last: 0,
            format_done: false,
            phase: WindowsPhase::Formatting,
        }
    }

    fn mark_format_done(&mut self) {
        self.format_done = true;
        self.phase = WindowsPhase::Flashing;
    }

    fn add_fat_bytes(&mut self, bytes: u64) {
        self.fat_written = self.fat_written.saturating_add(bytes);
    }

    fn set_phase(&mut self, phase: WindowsPhase) {
        self.phase = phase;
    }

    fn snapshot(&mut self, physical: u64) -> ProgressSnapshot {
        let current = if !self.format_done {
            0
        } else {
            // Only positions inside the file-data window count. The backup GPT
            // lives at the very end of the device and would otherwise be
            // mistaken for data progress if sampled before the format writes
            // have drained.
            let phys_data = if physical >= self.data_start && physical <= self.data_end {
                physical - self.data_start
            } else {
                0
            };
            if phys_data > self.max_data {
                self.max_data = phys_data;
            }

            let raw = self
                .format_work
                .saturating_add(self.max_data)
                .saturating_add(self.fat_written);
            // Keep 100% reserved until the final drain completes.
            raw.min(self.total.saturating_sub(1)).max(self.last)
        };
        self.last = current;
        ProgressSnapshot {
            phase: self.phase,
            current,
            total: self.total,
        }
    }

    fn finish(&mut self) -> ProgressSnapshot {
        self.last = self.total;
        self.phase = WindowsPhase::Finalizing;
        ProgressSnapshot {
            phase: self.phase,
            current: self.total,
            total: self.total,
        }
    }
}

fn lock_state(state: &Mutex<ProgressState>) -> MutexGuard<'_, ProgressState> {
    state
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// Dedicated progress publisher.
///
/// This thread exists so UI progress never depends on the flash thread being
/// schedulable. The flash thread can block for a full 32 MB pipeline buffer
/// recycle; the publisher keeps sampling the atomic hardware position and
/// emitting updates throughout that window.
struct ProgressPublisher {
    stop: Arc<(Mutex<bool>, Condvar)>,
    handle: Option<JoinHandle<()>>,
}

impl ProgressPublisher {
    fn start(
        state: Arc<Mutex<ProgressState>>,
        physical: Arc<AtomicU64>,
        progress: Arc<ProgressFn>,
    ) -> std::io::Result<Self> {
        let stop = Arc::new((Mutex::new(false), Condvar::new()));
        let stop_for_thread = Arc::clone(&stop);

        let handle = thread::Builder::new()
            .name("win-progress".to_string())
            .spawn(move || {
                let mut last_snapshot: Option<ProgressSnapshot> = None;
                let mut last_emit = Instant::now();

                loop {
                    let snapshot = {
                        let mut state = lock_state(&state);
                        state.snapshot(physical.load(Ordering::Relaxed))
                    };

                    let changed = last_snapshot.map_or(true, |last| {
                        last.current != snapshot.current || last.phase != snapshot.phase
                    });
                    if changed || last_emit.elapsed() >= PROGRESS_HEARTBEAT_INTERVAL {
                        progress(snapshot.phase, snapshot.current, snapshot.total);
                        last_snapshot = Some(snapshot);
                        last_emit = Instant::now();
                    }

                    let (lock, cvar) = &*stop_for_thread;
                    let mut stopped = lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
                    if *stopped {
                        break;
                    }
                    let (guard, _) = cvar
                        .wait_timeout(stopped, PROGRESS_POLL_INTERVAL)
                        .unwrap_or_else(|poisoned| poisoned.into_inner());
                    stopped = guard;
                    if *stopped {
                        break;
                    }
                }
            })?;

        Ok(Self {
            stop,
            handle: Some(handle),
        })
    }

    fn stop(&mut self) {
        let (lock, cvar) = &*self.stop;
        {
            let mut stopped = lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
            *stopped = true;
            cvar.notify_all();
        }
        if let Some(handle) = self.handle.take() {
            if handle.join().is_err() {
                tracing::warn!("Windows progress publisher panicked");
            }
        }
    }
}

impl Drop for ProgressPublisher {
    fn drop(&mut self) {
        self.stop();
    }
}

impl<W: Write + Seek + Read + PhysicalProgress> WindowsFlasher<W> {
    pub fn new(writer: W) -> Self {
        Self { writer }
    }

    pub fn flash<R: Read + Seek>(
        &mut self,
        mut iso_reader: R,
        progress: impl Fn(WindowsPhase, u64, u64) + Send + Sync + 'static,
        file_progress: impl Fn(&str) + Send + Sync,
    ) -> Result<(), WindowsError> {
        println!("Initializing UDF reader...");
        let mut udf = UdfReader::new(&mut iso_reader)?;
        let mut tree = udf.walk()?;
        tree.sort_by(|a, b| a.0.cmp(&b.0));

        println!("Calculating disk geometry...");
        let disk_size = self.writer.total_capacity();

        println!("Writing GPT Partition Table...");
        let gpt =
            GptTable::new_with_esp(disk_size).map_err(|e| WindowsError::Fat32(e.to_string()))?;
        gpt.write(&mut self.writer)
            .map_err(|e| WindowsError::Fat32(e.to_string()))?;

        let partition = gpt.partitions[0].clone();

        println!("Formatting FAT32 Partition...");
        let volume_label = udf.volume_id();
        let formatter = Fat32Formatter::new(
            partition.start_lba * 512,
            (partition.end_lba - partition.start_lba + 1) * 512,
            volume_label,
        )
        .map_err(|e| WindowsError::Fat32(e.to_string()))?;

        let cluster_size = formatter.boot_sector.bytes_per_cluster() as u64;
        let fat_count = formatter.boot_sector.fat_count as u64;
        let fat_copy_bytes = formatter.boot_sector.sectors_per_fat as u64 * 512;
        let fat_area_bytes = fat_copy_bytes.saturating_mul(fat_count);
        let data_area_offset = formatter
            .boot_sector
            .data_area_offset(formatter.partition_offset);
        // Cluster 2 is the root directory written by format(); user data starts
        // at cluster 3.
        let data_start = data_area_offset.saturating_add(cluster_size);

        // Estimate all physical work up-front. The estimates include a little
        // slack for WIM split headers/lookup tables and directories that grow
        // beyond one cluster; 100% is only reported after the final drain.
        let dir_count = tree.iter().filter(|(_, e)| e.is_dir).count() as u64;
        let file_clusters: u64 = tree
            .iter()
            .filter(|(_, e)| !e.is_dir)
            .map(|(_, e)| {
                if e.size == 0 {
                    0
                } else {
                    e.size.saturating_add(cluster_size - 1) / cluster_size
                }
            })
            .fold(0u64, |acc, clusters| acc.saturating_add(clusters));
        let file_bytes = file_clusters.saturating_mul(cluster_size);
        let dir_clusters = dir_count.saturating_add(8);
        let dir_bytes = dir_clusters.saturating_mul(cluster_size);
        let overhead = (file_bytes / 200).max(2 * 1024 * 1024);
        let data_limit = file_bytes
            .saturating_add(dir_bytes)
            .saturating_add(overhead);
        let format_work = fat_area_bytes
            .saturating_add(cluster_size)
            .saturating_add(4096);

        let fat_entries = file_clusters.saturating_add(dir_clusters).saturating_add(4);
        let fat_work = fat_entries
            .saturating_mul(4)
            .saturating_mul(fat_count)
            .saturating_add(512 * 1024);

        let total_work = format_work
            .saturating_add(data_limit)
            .saturating_add(fat_work)
            .max(1);
        // The backup GPT starts exactly at the first byte after the partition.
        // Use the whole partition as the data window; the size estimate is only
        // the percentage denominator. If the estimate is slightly low, real
        // writes can still approach 100% instead of freezing at the estimate.
        let partition_end = partition.end_lba.saturating_add(1).saturating_mul(512);
        let data_end = partition_end;
        let state = Arc::new(Mutex::new(ProgressState::new(
            total_work,
            format_work,
            data_start,
            data_end,
        )));
        let physical = self.writer.physical_progress_handle().ok_or_else(|| {
            WindowsError::Progress("writer does not expose a physical progress handle".to_string())
        })?;
        let progress: Arc<ProgressFn> = Arc::new(progress);

        let mut fat_writer = Fat32Writer::new(&mut self.writer, formatter);
        let mut publisher =
            ProgressPublisher::start(Arc::clone(&state), physical, Arc::clone(&progress))?;

        fat_writer
            .format()
            .map_err(|e| WindowsError::Fat32(e.to_string()))?;
        // Wait for the format writes to reach the device before entering the
        // data phase. This makes the first Flashing report a completed
        // hardware event instead of queued logical work.
        fat_writer.drain_with_progress(&mut |_| {})?;
        lock_state(&state).mark_format_done();

        println!("Copying files...");
        let root_cluster = fat_writer.root_cluster();

        let mut dir_clusters = std::collections::HashMap::new();
        dir_clusters.insert("".to_string(), root_cluster);

        for (path, entry) in &tree {
            if entry.is_dir {
                let parent_path = if let Some(idx) = path.rfind('/') {
                    &path[..idx]
                } else {
                    ""
                };
                let name = if let Some(idx) = path.rfind('/') {
                    &path[idx + 1..]
                } else {
                    path
                };

                let parent_cluster = *dir_clusters.get(parent_path).unwrap_or(&root_cluster);
                let cluster = fat_writer
                    .create_directory(name, parent_cluster)
                    .map_err(|e| WindowsError::Fat32(e.to_string()))?;
                dir_clusters.insert(path.clone(), cluster);
            }
        }

        for (path, entry) in &tree {
            if entry.is_dir {
                continue;
            }

            let parent_path = if let Some(idx) = path.rfind('/') {
                &path[..idx]
            } else {
                ""
            };
            let name = if let Some(idx) = path.rfind('/') {
                &path[idx + 1..]
            } else {
                path
            };
            let parent_cluster = *dir_clusters.get(parent_path).unwrap_or(&root_cluster);

            tracing::info!(
                "WindowsFlasher: Starting file {} ({} bytes)",
                path,
                entry.size
            );
            // Inform the UI which file is being copied. This is a separate
            // channel from the physical progress publisher: it fires at file
            // boundaries only and never touches the hot write path.
            file_progress(path);

            if path.eq_ignore_ascii_case("sources/install.wim")
                || path.eq_ignore_ascii_case("sources/install.esd")
            {
                tracing::info!("WindowsFlasher: Detected main image, using split-writer...");
                // The publisher samples the physical position directly, so the
                // flash thread never calls into the UI from this hot path.
                write_split_wim(&mut udf, entry, parent_cluster, &mut fat_writer, |_| {})?;
            } else {
                let _file_size = udf.open_file(entry)?;
                fat_writer
                    .write_file(
                        name,
                        parent_cluster,
                        udf.reader(),
                        entry.size,
                        |_written, _total, _physical| {},
                    )
                    .map_err(|e| WindowsError::Fat32(e.to_string()))?;
            }
            tracing::info!("WindowsFlasher: Finished file {}", path);
        }

        // Drain every queued file write. The publisher keeps sampling the real
        // device position, so the bar keeps moving during the last buffered
        // megabytes instead of freezing at 99%.
        println!("Draining write pipeline...");
        fat_writer.drain_with_progress(&mut |_| {})?;
        lock_state(&state).set_phase(WindowsPhase::Finalizing);

        println!("Finalizing FAT...");
        fat_writer
            .flush_fat_with_progress(|bytes| {
                lock_state(&state).add_fat_bytes(bytes);
            })
            .map_err(|e| WindowsError::Fat32(e.to_string()))?;
        fat_writer.drain_with_progress(&mut |_| {})?;

        // Stop the publisher before reporting completion so the final 100%
        // callback can never race with a sampled snapshot.
        publisher.stop();
        let final_snapshot = lock_state(&state).finish();
        progress(
            final_snapshot.phase,
            final_snapshot.current,
            final_snapshot.total,
        );
        Ok(())
    }
}

fn write_split_wim<U, W>(
    udf: &mut UdfReader<U>,
    entry: &UdfEntry,
    parent_cluster: u32,
    fat_writer: &mut Fat32Writer<W>,
    mut progress: impl FnMut(u64),
) -> Result<(), WindowsError>
where
    U: Read + Seek,
    W: Write + Seek + Read + PhysicalProgress,
{
    let _size = udf.open_file(entry)?;
    let wim_data_offset = udf.reader().stream_position()?;

    let mut header_buf = [0u8; 208];
    udf.reader().read_exact(&mut header_buf)?;
    let header = WimHeader::read(std::io::Cursor::new(&header_buf))?;

    udf.reader().seek(SeekFrom::Start(
        wim_data_offset + header.lookup_table_resource.offset,
    ))?;
    let mut entries = Vec::new();
    let num_entries = header.lookup_table_resource.original_size / 50;
    for _ in 0..num_entries {
        entries.push(WimLookupEntry::read(udf.reader())?);
    }

    let splitter = SwmSplitter::new(header.clone(), entries);
    let parts = splitter.split(4_000_000_000);

    for (i, part) in parts.iter().enumerate() {
        let swm_name = if i == 0 {
            "install.swm".to_string()
        } else {
            format!("install{}.swm", i + 1)
        };

        let mut part_total_size: u64 = 208; // Header
        for e in &part.blobs_to_write {
            part_total_size += e.res_hdr.size;
        }
        part_total_size += (part.lookup_table.len() * 50) as u64; // Lookup Table
        if part.part_number == 1 {
            part_total_size += header.xml_data_resource.size;
        }

        let mut swm_writer = fat_writer
            .open_file_writer(&swm_name, parent_cluster, part_total_size)
            .map_err(|e| WindowsError::Fat32(e.to_string()))?;

        // 1. Write Header
        let mut h_buf = Vec::new();
        part.write_header(&mut h_buf, &header)?;
        swm_writer.write_all(&h_buf)?;

        // 2. Write Blobs. 4MB matches the stable SCSI transfer size and cuts
        // the number of read/write round-trips compared with 2MB.
        let mut buffer = vec![0u8; 4 * 1024 * 1024];
        for e in &part.blobs_to_write {
            udf.reader()
                .seek(SeekFrom::Start(wim_data_offset + e.res_hdr.offset))?;
            let mut blob_reader = udf.reader().take(e.res_hdr.size);

            loop {
                let n = blob_reader.read(&mut buffer)?;
                if n == 0 {
                    break;
                }
                swm_writer.write_all(&buffer[..n])?;
                progress(swm_writer.physical_position());
            }
        }

        // 3. Write Lookup Table (The master table calculated during split)
        part.write_lookup_table(&mut swm_writer)?;

        // 4. Write XML (Part 1 only)
        if part.part_number == 1 && header.xml_data_resource.size > 0 {
            udf.reader().seek(SeekFrom::Start(
                wim_data_offset + header.xml_data_resource.offset,
            ))?;
            let mut xml_reader = udf.reader().take(header.xml_data_resource.size);
            let mut xml_buffer = vec![0u8; 1024 * 1024];
            loop {
                let n = xml_reader.read(&mut xml_buffer)?;
                if n == 0 {
                    break;
                }
                swm_writer.write_all(&xml_buffer[..n])?;
                progress(swm_writer.physical_position());
            }
        }

        swm_writer.flush()?;
        progress(swm_writer.physical_position());
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state() -> ProgressState {
        // 1_000 format bytes, a 6_000-byte data window at [2_000, 8_000),
        // and 10_000 total estimated bytes.
        ProgressState::new(10_000, 1_000, 2_000, 8_000)
    }

    #[test]
    fn formatting_reports_zero_until_format_completes() {
        let mut state = state();
        let snapshot = state.snapshot(7_000);
        assert_eq!(snapshot.current, 0);
        assert_eq!(snapshot.phase, WindowsPhase::Formatting);

        state.mark_format_done();
        let snapshot = state.snapshot(2_000);
        assert_eq!(snapshot.current, 1_000);
        assert_eq!(snapshot.phase, WindowsPhase::Flashing);
    }

    #[test]
    fn ignores_positions_outside_the_data_window() {
        let mut state = state();
        state.mark_format_done();

        // The backup GPT lives at the end of the device; it must never be
        // mistaken for file data.
        assert_eq!(state.snapshot(9_500).current, 1_000);

        // FAT/boot-area writes are below the data window.
        assert_eq!(state.snapshot(1_500).current, 1_000);

        // A real data write advances progress.
        assert_eq!(state.snapshot(2_500).current, 1_500);

        // A seek back into the data window must not move the bar backwards.
        assert_eq!(state.snapshot(2_100).current, 1_500);
    }

    #[test]
    fn fat_bytes_and_finish_are_reported() {
        let mut state = state();
        state.mark_format_done();
        assert_eq!(state.snapshot(8_000).current, 7_000);

        state.set_phase(WindowsPhase::Finalizing);
        state.add_fat_bytes(500);
        let snapshot = state.snapshot(1_000);
        assert_eq!(snapshot.phase, WindowsPhase::Finalizing);
        assert_eq!(snapshot.current, 7_500);

        let final_snapshot = state.finish();
        assert_eq!(final_snapshot.current, 10_000);
        assert_eq!(final_snapshot.total, 10_000);
    }

    #[test]
    fn progress_is_capped_below_100_until_finish() {
        let mut state = ProgressState::new(1_000, 100, 0, 10_000);
        state.mark_format_done();

        let snapshot = state.snapshot(10_000);
        assert_eq!(snapshot.current, 999);
        assert_eq!(snapshot.total, 1_000);

        assert_eq!(state.finish().current, 1_000);
    }

    #[test]
    fn data_beyond_the_estimate_counts_until_the_partition_end() {
        // The estimate is 10_000 bytes, but the partition data window extends
        // to 12_000. A real write above the estimate must not freeze the bar.
        let mut state = ProgressState::new(10_000, 1_000, 2_000, 12_000);
        state.mark_format_done();

        assert_eq!(state.snapshot(11_500).current, 9_999);
    }
}
