//! FAT32 file and directory writer.
//!
//! Writes files and directories to the FAT32 filesystem with proper
//! cluster allocation and FAT updates.

use super::format::{FAT32_EOC, Fat32Formatter};
use hyntix_common::{Error, Result};
use std::io::{Read, Seek, SeekFrom, Write};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use unicode_normalization::UnicodeNormalization;

/// Maximum filename length for LFN.
const _MAX_LFN_LENGTH: usize = 255;

/// Copy buffer used while streaming a file into the filesystem.
const FILE_COPY_BUFFER_SIZE: usize = 2 * 1024 * 1024;

/// Maximum FAT run (in 512-byte sectors) read/modified/written as one batch.
/// 8192 sectors = 4MB, matching the stable SCSI transfer size.
const MAX_FAT_FLUSH_RUN_SECTORS: u64 = 8192;

/// Shared zero block used to initialise directory clusters without allocating
/// a fresh `bytes_per_cluster` vector for every directory.
const ZERO_BLOCK: [u8; 16 * 1024] = [0u8; 16 * 1024];

fn write_zeros<W: Write>(writer: &mut W, mut len: usize) -> std::io::Result<()> {
    while len > 0 {
        let n = len.min(ZERO_BLOCK.len());
        writer.write_all(&ZERO_BLOCK[..n])?;
        len -= n;
    }
    Ok(())
}

/// Attribute flags for directory entries.
#[derive(Debug, Clone, Copy)]
pub struct FileAttributes(u8);

impl FileAttributes {
    pub const READ_ONLY: FileAttributes = FileAttributes(0x01);
    pub const HIDDEN: FileAttributes = FileAttributes(0x02);
    pub const SYSTEM: FileAttributes = FileAttributes(0x04);
    pub const VOLUME_ID: FileAttributes = FileAttributes(0x08);
    pub const DIRECTORY: FileAttributes = FileAttributes(0x10);
    pub const ARCHIVE: FileAttributes = FileAttributes(0x20);
    pub const LFN: FileAttributes = FileAttributes(0x0F);

    pub fn bits(&self) -> u8 {
        self.0
    }
}

/// FAT32 file writer.
pub struct Fat32Writer<'a, W: Write + Seek + Read + hyntix_usb::PhysicalProgress> {
    writer: &'a mut W,
    formatter: Fat32Formatter,
    /// Current directory cluster.
    _current_dir_cluster: u32,
    /// FAT cache (cluster -> next cluster).
    fat_cache: Vec<(u32, u32)>,
    /// Cancellation handle.
    cancel_handle: Option<Arc<AtomicBool>>,
    /// Directory entry offset tracker (cluster -> next free entry index).
    /// For root dir (cluster 2), index starts at 0.
    /// For other dirs, index starts at 2 (after . and ..).
    dir_entry_tracker: std::collections::HashMap<u32, usize>,
    /// Pending directory entries to write in batch.
    pending_dir_entries: Vec<PendingDirEntry>,
}

/// A pending directory entry write.
struct PendingDirEntry {
    parent_cluster: u32,
    entries: Vec<[u8; 32]>,
}

impl<'a, W: Write + Seek + Read + hyntix_usb::PhysicalProgress> Fat32Writer<'a, W> {
    /// Create a new FAT32 writer.
    pub fn new(writer: &'a mut W, formatter: Fat32Formatter) -> Self {
        Self {
            writer,
            formatter,
            _current_dir_cluster: 2, // Root directory
            fat_cache: Vec::new(),
            cancel_handle: None,
            dir_entry_tracker: std::collections::HashMap::new(),
            pending_dir_entries: Vec::new(),
        }
    }

    /// Set cancellation handle.
    pub fn set_cancel_handle(&mut self, handle: Arc<AtomicBool>) {
        self.cancel_handle = Some(handle);
    }

    /// Format the partition.
    pub fn format(&mut self) -> Result<()> {
        self.formatter.format(self.writer)
    }

    /// Create a directory.
    pub fn create_directory(&mut self, name: &str, parent_cluster: u32) -> Result<u32> {
        let dir_cluster = self.formatter.allocate_cluster();

        // Mark cluster as end-of-chain in FAT
        self.fat_cache.push((dir_cluster, FAT32_EOC));

        // Initialize directory with . and .. entries
        let offset = self
            .formatter
            .boot_sector
            .cluster_offset(self.formatter.partition_offset, dir_cluster);
        self.writer.seek(SeekFrom::Start(offset))?;

        // "." entry
        let dot_entry = self.create_dir_entry(".", dir_cluster, FileAttributes::DIRECTORY, 0)?;
        self.writer.write_all(&dot_entry)?;

        // ".." entry
        let dotdot_cluster = if parent_cluster == 2 {
            0
        } else {
            parent_cluster
        };
        let dotdot_entry =
            self.create_dir_entry("..", dotdot_cluster, FileAttributes::DIRECTORY, 0)?;
        self.writer.write_all(&dotdot_entry)?;

        // Zero out rest of cluster
        let remaining = self.formatter.boot_sector.bytes_per_cluster() as usize - 64;
        write_zeros(self.writer, remaining)?;

        // Track that this new directory has 2 entries (. and ..) already written
        self.dir_entry_tracker.insert(dir_cluster, 2);

        // Add entry in parent directory
        self.add_directory_entry(
            parent_cluster,
            name,
            dir_cluster,
            FileAttributes::DIRECTORY,
            0,
        )?;

        Ok(dir_cluster)
    }

    /// Write a file to the filesystem.
    pub fn write_file<R: Read>(
        &mut self,
        name: &str,
        parent_cluster: u32,
        reader: &mut R,
        size: u64,
        mut progress: impl FnMut(u64, u64, u64),
    ) -> Result<u32> {
        if size == 0 {
            // Empty file, no clusters needed
            self.add_directory_entry(parent_cluster, name, 0, FileAttributes::ARCHIVE, 0)?;
            return Ok(0);
        }

        // Use optimized Fat32FileWriter for batching and large writes
        let first_cluster = {
            let mut writer = self.open_file_writer(name, parent_cluster, size)?;

            // Size the copy buffer to the file instead of allocating a full
            // 2MB zeroed vector for every (often tiny) file in the ISO.
            let buffer_size = (size as usize).min(FILE_COPY_BUFFER_SIZE).max(512);
            let mut buffer = vec![0u8; buffer_size];
            let mut limited_reader = reader.take(size);

            loop {
                // Check cancellation via the writer which has its own handle
                if let Some(ref handle) = writer.cancel_handle {
                    if handle.load(Ordering::SeqCst) {
                        return Err(Error::Cancelled);
                    }
                }

                let n = limited_reader.read(&mut buffer)?;
                if n == 0 {
                    break;
                }

                writer.write_all(&buffer[..n])?;
                progress(writer.bytes_written, size, writer.physical_position());
            }

            // Finalize the directory entry. The data stays in the async
            // pipeline; the caller drains it once before finalising the FAT.
            writer.flush()?;
            writer.first_cluster
        };

        Ok(first_cluster)
    }

    /// Add a directory entry (with LFN support).
    /// This version caches the entry for batch writing to avoid sync I/O.
    fn add_directory_entry(
        &mut self,
        parent_cluster: u32,
        name: &str,
        first_cluster: u32,
        attrs: FileAttributes,
        size: u32,
    ) -> Result<()> {
        let entries = self.create_lfn_entries(name, first_cluster, attrs, size)?;

        // Cache the entry for batch writing during flush_fat()
        self.pending_dir_entries.push(PendingDirEntry {
            parent_cluster,
            entries,
        });

        Ok(())
    }

    /// Flush all pending directory entries to disk.
    /// Called before flush_fat() to write all cached entries.
    fn flush_pending_dir_entries(&mut self) -> Result<()> {
        if self.pending_dir_entries.is_empty() {
            return Ok(());
        }

        let entries_per_cluster = self.formatter.boot_sector.bytes_per_cluster() / 32;

        // Group entries by parent cluster for efficient writing. A BTreeMap
        // keeps directories in cluster order, which turns the subsequent
        // writes into a mostly-forward seek pattern.
        let mut by_cluster: std::collections::BTreeMap<u32, Vec<Vec<[u8; 32]>>> =
            std::collections::BTreeMap::new();

        for pending in self.pending_dir_entries.drain(..) {
            by_cluster
                .entry(pending.parent_cluster)
                .or_default()
                .push(pending.entries);
        }

        // Write each cluster's entries
        for (parent_cluster, entry_groups) in by_cluster {
            // Get or initialize the entry tracker for this directory
            let start_index = *self.dir_entry_tracker.get(&parent_cluster).unwrap_or(&{
                // For root dir, start at 0; for subdirs, start at 2 (after . and ..)
                if parent_cluster == 2 { 0 } else { 2 }
            });

            let mut current_index = start_index;
            let mut current_cluster = parent_cluster;

            for entries in entry_groups {
                // Check if we need a new cluster
                if current_index + entries.len() > entries_per_cluster as usize {
                    // Allocate new cluster
                    let new_cluster = self.formatter.allocate_cluster();
                    self.fat_cache.push((current_cluster, new_cluster));
                    self.fat_cache.push((new_cluster, FAT32_EOC));

                    // Initialize new cluster with zeros
                    let new_offset = self
                        .formatter
                        .boot_sector
                        .cluster_offset(self.formatter.partition_offset, new_cluster);
                    self.writer.seek(SeekFrom::Start(new_offset))?;
                    write_zeros(
                        self.writer,
                        self.formatter.boot_sector.bytes_per_cluster() as usize,
                    )?;

                    current_cluster = new_cluster;
                    current_index = 0;
                }

                // Calculate offset and write entries
                let cluster_offset = self
                    .formatter
                    .boot_sector
                    .cluster_offset(self.formatter.partition_offset, current_cluster);
                let entry_offset = cluster_offset + (current_index * 32) as u64;

                self.writer.seek(SeekFrom::Start(entry_offset))?;
                for entry in &entries {
                    self.writer.write_all(entry)?;
                }

                current_index += entries.len();
            }

            // Update tracker
            self.dir_entry_tracker.insert(parent_cluster, current_index);
        }

        Ok(())
    }

    /// Create LFN + SFN entries for a filename.
    fn create_lfn_entries(
        &self,
        name: &str,
        first_cluster: u32,
        attrs: FileAttributes,
        size: u32,
    ) -> Result<Vec<[u8; 32]>> {
        let (sfn, lfn_needed) = self.generate_short_name(name);

        if !lfn_needed {
            // Just SFN entry
            let entry = self.create_dir_entry_raw(&sfn, first_cluster, attrs, size);
            return Ok(vec![entry]);
        }

        // Create LFN entries + SFN entry
        let name_utf16: Vec<u16> = name
            .nfc()
            .flat_map(|c| c.encode_utf16(&mut [0; 2]).to_vec())
            .collect();
        let lfn_entry_count = (name_utf16.len() + 12) / 13; // 13 chars per LFN entry

        let checksum = self.sfn_checksum(&sfn);

        let mut entries = Vec::with_capacity(lfn_entry_count + 1);

        // LFN entries (reverse order)
        for i in (0..lfn_entry_count).rev() {
            let mut entry = [0u8; 32];
            let sequence = if i == lfn_entry_count - 1 {
                0x40 | (i as u8 + 1) // Last entry marker
            } else {
                i as u8 + 1
            };

            entry[0] = sequence;
            entry[11] = FileAttributes::LFN.bits();
            entry[12] = 0; // Reserved
            entry[13] = checksum;
            entry[26] = 0; // First cluster (always 0 for LFN)
            entry[27] = 0;

            // Fill name characters (13 per entry)
            let start = i * 13;
            let chars: Vec<u16> = (0..13)
                .map(|j| {
                    let idx = start + j;
                    if idx < name_utf16.len() {
                        name_utf16[idx]
                    } else if idx == name_utf16.len() {
                        0x0000 // Null terminator
                    } else {
                        0xFFFF // Padding
                    }
                })
                .collect();

            // Characters 1-5 (bytes 1-10)
            for j in 0..5 {
                let offset = 1 + j * 2;
                entry[offset..offset + 2].copy_from_slice(&chars[j].to_le_bytes());
            }

            // Characters 6-11 (bytes 14-25)
            for j in 0..6 {
                let offset = 14 + j * 2;
                entry[offset..offset + 2].copy_from_slice(&chars[5 + j].to_le_bytes());
            }

            // Characters 12-13 (bytes 28-31)
            for j in 0..2 {
                let offset = 28 + j * 2;
                entry[offset..offset + 2].copy_from_slice(&chars[11 + j].to_le_bytes());
            }

            entries.push(entry);
        }

        // SFN entry
        entries.push(self.create_dir_entry_raw(&sfn, first_cluster, attrs, size));

        Ok(entries)
    }

    /// Generate a valid 8.3 short filename.
    fn generate_short_name(&self, name: &str) -> ([u8; 11], bool) {
        let mut sfn = [0x20u8; 11]; // Space-padded

        // Check if name is already a valid SFN
        let name_upper = name.to_uppercase();
        let parts: Vec<&str> = name_upper.splitn(2, '.').collect();

        let base = parts[0];
        let ext = parts.get(1).copied().unwrap_or("");

        let needs_lfn = name.len() > 12
            || base.len() > 8
            || ext.len() > 3
            || name
                .chars()
                .any(|c| !c.is_ascii_alphanumeric() && c != '.' && c != '_' && c != '-');

        // Generate SFN
        let clean_base: String = base
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
            .take(8)
            .collect();

        let clean_ext: String = ext
            .chars()
            .filter(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-')
            .take(3)
            .collect();

        // Copy base (left-justified)
        for (i, c) in clean_base.bytes().take(8).enumerate() {
            sfn[i] = c;
        }

        // Copy extension
        for (i, c) in clean_ext.bytes().take(3).enumerate() {
            sfn[8 + i] = c;
        }

        (sfn, needs_lfn)
    }

    /// Calculate SFN checksum for LFN entries.
    fn sfn_checksum(&self, sfn: &[u8; 11]) -> u8 {
        let mut sum: u8 = 0;
        for &byte in sfn {
            sum = sum.rotate_right(1).wrapping_add(byte);
        }
        sum
    }

    /// Create a raw directory entry.
    fn create_dir_entry_raw(
        &self,
        sfn: &[u8; 11],
        first_cluster: u32,
        attrs: FileAttributes,
        size: u32,
    ) -> [u8; 32] {
        let mut entry = [0u8; 32];

        // Filename (0-10)
        entry[0..11].copy_from_slice(sfn);

        // Attributes (11)
        entry[11] = attrs.bits();

        // Reserved (12)
        entry[12] = 0;

        // Creation time (13-17) - simplified
        entry[13] = 0; // Tenths of seconds
        entry[14..16].copy_from_slice(&0u16.to_le_bytes()); // Creation time
        entry[16..18].copy_from_slice(&0u16.to_le_bytes()); // Creation date

        // Last access date (18-19)
        entry[18..20].copy_from_slice(&0u16.to_le_bytes());

        // High word of first cluster (20-21)
        entry[20..22].copy_from_slice(&((first_cluster >> 16) as u16).to_le_bytes());

        // Write time (22-23)
        entry[22..24].copy_from_slice(&0u16.to_le_bytes());

        // Write date (24-25)
        entry[24..26].copy_from_slice(&0u16.to_le_bytes());

        // Low word of first cluster (26-27)
        entry[26..28].copy_from_slice(&(first_cluster as u16).to_le_bytes());

        // File size (28-31)
        entry[28..32].copy_from_slice(&size.to_le_bytes());

        entry
    }

    /// Create a simplified directory entry.
    fn create_dir_entry(
        &self,
        name: &str,
        first_cluster: u32,
        attrs: FileAttributes,
        size: u32,
    ) -> Result<[u8; 32]> {
        let mut sfn = [0x20u8; 11];

        if name == "." {
            sfn[0] = b'.';
        } else if name == ".." {
            sfn[0] = b'.';
            sfn[1] = b'.';
        } else {
            let (generated, _) = self.generate_short_name(name);
            sfn = generated;
        }

        Ok(self.create_dir_entry_raw(&sfn, first_cluster, attrs, size))
    }

    /// Flush FAT cache to disk and drain the write pipeline.
    pub fn flush_fat(&mut self) -> Result<()> {
        self.flush_fat_with_progress(|_| {})?;
        let mut noop = |_| {};
        self.writer.drain_with_progress(&mut noop)?;
        Ok(())
    }

    /// Flush FAT cache to disk without draining the async write pipeline.
    ///
    /// Groups dirty FAT sectors into contiguous runs, loads each run from the
    /// first FAT copy in one read, patches all entries in memory, then writes
    /// the run to every FAT copy. This replaces the old one-read-and-write per
    /// 512-byte sector pattern, which was the main cause of the long stall near
    /// the end of a Windows flash.
    ///
    /// `on_written` receives the number of FAT bytes queued by each run.
    pub fn flush_fat_with_progress(&mut self, mut on_written: impl FnMut(u64)) -> Result<()> {
        // First, flush all pending directory entries
        self.flush_pending_dir_entries()?;

        if self.fat_cache.is_empty() {
            return Ok(());
        }

        // Stable sort by cluster so multiple updates to the same cluster keep
        // their insertion order (the last value wins when patching).
        self.fat_cache.sort_by_key(|(cluster, _)| *cluster);

        let fat_offset = self.formatter.partition_offset
            + self.formatter.boot_sector.reserved_sectors as u64 * 512;
        let fat_count = self.formatter.boot_sector.fat_count as u64;
        let fat_copy_bytes = self.formatter.boot_sector.sectors_per_fat as u64 * 512;
        let entry_count = self.fat_cache.len();
        let mut idx = 0usize;

        while idx < entry_count {
            let start_sector = (self.fat_cache[idx].0 as u64 * 4) / 512;
            let mut end_sector = start_sector;
            let mut end_idx = idx;

            // Build the largest contiguous run that does not exceed the cap.
            while end_idx < entry_count {
                let sector = (self.fat_cache[end_idx].0 as u64 * 4) / 512;
                if sector > end_sector + 1 {
                    break; // gap: start a new run
                }
                if sector >= start_sector + MAX_FAT_FLUSH_RUN_SECTORS {
                    break; // run cap reached
                }
                end_sector = end_sector.max(sector);
                end_idx += 1;
            }

            debug_assert!(end_idx > idx);
            let sector_count = (end_sector - start_sector + 1) as usize;
            let byte_count = sector_count * 512;
            let mut sector_data = vec![0u8; byte_count];

            // Load the run once from FAT #1 so entries allocated outside this
            // writer (reserved entries, pre-existing data) are preserved.
            let run_offset = fat_offset + start_sector * 512;
            self.writer.seek(SeekFrom::Start(run_offset))?;
            self.writer.read_exact(&mut sector_data)?;

            for &(cluster, next) in &self.fat_cache[idx..end_idx] {
                let entry_offset = cluster as usize * 4 - start_sector as usize * 512;
                sector_data[entry_offset..entry_offset + 4].copy_from_slice(&next.to_le_bytes());
            }

            for copy in 0..fat_count {
                let copy_offset = fat_offset + copy * fat_copy_bytes + start_sector * 512;
                self.writer.seek(SeekFrom::Start(copy_offset))?;
                self.writer.write_all(&sector_data)?;
            }

            // Report the delta for this run; callers accumulate it themselves.
            on_written(byte_count as u64 * fat_count);
            idx = end_idx;
        }

        self.fat_cache.clear();
        Ok(())
    }

    /// Drain buffered USB writes while reporting physical progress.
    pub fn drain_with_progress(&mut self, progress: &mut dyn FnMut(u64)) -> Result<()> {
        self.writer.drain_with_progress(progress)?;
        Ok(())
    }

    /// Physical device position (bytes written by the USB worker).
    pub fn physical_position(&self) -> u64 {
        self.writer.physical_position()
    }

    /// Get the partition offset.
    pub fn partition_offset(&self) -> u64 {
        self.formatter.partition_offset
    }

    /// Get the root cluster number.
    pub fn root_cluster(&self) -> u32 {
        2
    }

    /// Open a file for streaming write.
    pub fn open_file_writer<'b>(
        &'b mut self,
        name: &str,
        parent_cluster: u32,
        size: u64,
    ) -> Result<Fat32FileWriter<'b, 'a, W>> {
        let cancel_handle = self.cancel_handle.clone();

        if size == 0 {
            // Special case for zero byte files could be handled, but for now we assume size > 0
            // as WIM splits are always large.
        }

        let first_cluster = self.formatter.allocate_cluster();

        Ok(Fat32FileWriter {
            parent: self,
            name: name.to_string(),
            parent_cluster,
            first_cluster,
            current_cluster: first_cluster,
            bytes_written: 0,
            total_size: size,
            // Small files only need a small buffer. Large writes bypass the
            // buffer entirely, so there is no reason to reserve 2MB per file.
            buffer: Vec::with_capacity((size as usize).min(FILE_COPY_BUFFER_SIZE)),
            cancel_handle,
        })
    }
}

/// A writer for a file within the FAT32 filesystem.
pub struct Fat32FileWriter<'b, 'a, W: Write + Seek + Read + hyntix_usb::PhysicalProgress> {
    parent: &'b mut Fat32Writer<'a, W>,
    name: String,
    parent_cluster: u32,
    first_cluster: u32,
    current_cluster: u32,
    bytes_written: u64,
    total_size: u64,
    buffer: Vec<u8>,
    cancel_handle: Option<Arc<AtomicBool>>,
}

impl<'b, 'a, W: Write + Seek + Read + hyntix_usb::PhysicalProgress> Write
    for Fat32FileWriter<'b, 'a, W>
{
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        let mut total_written = 0;
        const BATCH_SIZE: usize = FILE_COPY_BUFFER_SIZE;

        // Optimization: If buffer is empty and input is large, bypass internal buffering
        if self.buffer.is_empty() && buf.len() >= BATCH_SIZE {
            // We still need to flush any pending cluster allocations
            // but for simple high-speed sequential writes, we can write chunks directly.
            // However, we MUST handle the cluster allocation and FAT updates.
            // So we use a specialized direct flush.
            let chunks = buf.len() / BATCH_SIZE;
            for i in 0..chunks {
                let chunk = &buf[i * BATCH_SIZE..(i + 1) * BATCH_SIZE];
                self.write_chunk_direct(chunk)?;
                total_written += BATCH_SIZE;
            }

            let remaining = &buf[total_written..];
            if !remaining.is_empty() {
                self.buffer.extend_from_slice(remaining);
                total_written += remaining.len();
            }
            return Ok(total_written);
        }

        // Standard buffered path
        let mut current_data = &buf[..];
        while !current_data.is_empty() {
            let space_in_buffer = BATCH_SIZE - self.buffer.len();
            let to_copy = std::cmp::min(space_in_buffer, current_data.len());

            self.buffer.extend_from_slice(&current_data[..to_copy]);
            current_data = &current_data[to_copy..];
            total_written += to_copy;

            if self.buffer.len() >= BATCH_SIZE {
                self.flush_batch()?;
            }
        }

        Ok(total_written)
    }

    fn flush(&mut self) -> std::io::Result<()> {
        // If there's data in buffer, write it out
        if !self.buffer.is_empty() {
            self.flush_batch()?;
        }

        // Finalize FAT chain and directory entry
        self.parent
            .fat_cache
            .push((self.current_cluster, FAT32_EOC));
        self.parent
            .add_directory_entry(
                self.parent_cluster,
                &self.name,
                self.first_cluster,
                FileAttributes::ARCHIVE,
                self.total_size as u32,
            )
            .map_err(|e| std::io::Error::new(std::io::ErrorKind::Other, e.to_string()))?;

        // Do NOT drain the async pipeline here. File data is queued in order
        // and the final drain happens once, after all files have been written.
        // Draining per file serialised the entire USB pipeline and was a major
        // cause of slow Windows flashing.
        Ok(())
    }
}

impl<'b, 'a, W: Write + Seek + Read + hyntix_usb::PhysicalProgress> Fat32FileWriter<'b, 'a, W> {
    fn flush_batch(&mut self) -> std::io::Result<()> {
        if self.buffer.is_empty() {
            return Ok(());
        }
        // Use mem::take to avoid borrowing &self.buffer while calling &mut self method
        let mut buffer = std::mem::take(&mut self.buffer);
        let res = self.write_data_internal(&buffer);
        buffer.clear();
        self.buffer = buffer; // Return allocation to pool
        res
    }

    fn write_chunk_direct(&mut self, chunk: &[u8]) -> std::io::Result<()> {
        self.write_data_internal(chunk)
    }

    fn write_data_internal(&mut self, data: &[u8]) -> std::io::Result<()> {
        if data.is_empty() {
            return Ok(());
        }

        let start_disk_offset = self
            .parent
            .formatter
            .boot_sector
            .cluster_offset(self.parent.formatter.partition_offset, self.current_cluster);

        // Ensure writer is at the correct position
        if self.parent.writer.stream_position()? != start_disk_offset {
            tracing::debug!(
                "Fat32FileWriter: Seeking to cluster offset {}",
                start_disk_offset
            );
            self.parent
                .writer
                .seek(SeekFrom::Start(start_disk_offset))?;
        }

        tracing::debug!(
            "Fat32FileWriter: Writing {} bytes to disk at {}",
            data.len(),
            start_disk_offset
        );
        self.parent.writer.write_all(data)?;

        let mut total_processed_bytes = 0;
        let bytes_per_cluster = self.parent.formatter.boot_sector.bytes_per_cluster() as usize;

        while total_processed_bytes < data.len() {
            let remaining_in_data = data.len() - total_processed_bytes;

            // Calculate how much we can write to the CURRENT cluster
            let offset_in_cluster = (self.bytes_written % bytes_per_cluster as u64) as usize;
            let space_in_cluster = bytes_per_cluster - offset_in_cluster;
            let to_process = std::cmp::min(space_in_cluster, remaining_in_data);

            self.bytes_written += to_process as u64;
            total_processed_bytes += to_process;

            // If we just finished a cluster, allocate the next one
            if self.bytes_written % bytes_per_cluster as u64 == 0 {
                let next_cluster = self.parent.formatter.allocate_cluster();
                tracing::debug!(
                    "Fat32FileWriter: Advancing from cluster {} to {}",
                    self.current_cluster,
                    next_cluster
                );
                self.parent
                    .fat_cache
                    .push((self.current_cluster, next_cluster));
                self.current_cluster = next_cluster;
            }
        }

        Ok(())
    }

    pub fn physical_position(&self) -> u64 {
        self.parent.writer.physical_position()
    }

    pub fn bytes_written(&self) -> u64 {
        self.bytes_written
    }
}

impl<'b, 'a, W: Write + Seek + Read + hyntix_usb::PhysicalProgress> Drop
    for Fat32FileWriter<'b, 'a, W>
{
    fn drop(&mut self) {
        // Automatically flush if not already done, but ONLY if not cancelled
        let cancelled = self
            .cancel_handle
            .as_ref()
            .map(|h| h.load(Ordering::SeqCst))
            .unwrap_or(false);

        if !cancelled && self.bytes_written < self.total_size && self.first_cluster != 0 {
            let _ = self.flush();
        }
    }
}
