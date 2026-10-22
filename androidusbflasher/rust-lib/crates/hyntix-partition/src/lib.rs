//! Partition-table primitives for append-only persistence partitions.
//!
//! This crate knows how to read MBR and GPT tables, append one partition in the
//! free tail, verify the result, and roll the table back if verification fails.
//! It deliberately does not know anything about ext2 or Linux distributions.

pub mod crc32;
pub mod gpt;
pub mod mbr;
pub mod rollback;

use hyntix_storage::{BlockDevice, StorageError, require_512_byte_sectors};

/// Errors returned by partition operations.
#[derive(Debug, thiserror::Error)]
pub enum PartitionError {
    #[error(transparent)]
    Storage(#[from] StorageError),

    #[error("no MBR or GPT partition table found")]
    NoPartitionTable,

    #[error("no free MBR primary partition slot")]
    NoMbrSlot,

    #[error("no free GPT partition entry slot")]
    NoGptSlot,

    #[error("hybrid MBR layout is not safe to edit")]
    HybridMbrUnsupported,

    #[error("insufficient free space: need {need} bytes, have {have}")]
    InsufficientSpace { need: u64, have: u64 },

    #[error("invalid partition table: {0}")]
    InvalidTable(String),

    #[error("partition exceeds the MBR 32-bit LBA limit")]
    MbrTooLarge,

    #[error("requested partition size is zero")]
    ZeroSize,

    #[error("partition table verification failed: {0}")]
    Verify(String),
}

pub type Result<T> = std::result::Result<T, PartitionError>;

pub const SECTOR_SIZE: u32 = 512;
pub const ALIGNMENT_1_MIB: u64 = 1024 * 1024;
pub const GPT_ENTRY_SIZE: usize = 128;
pub const GPT_ENTRY_COUNT: usize = 128;
pub const GPT_HEADER_SIZE: usize = 92;
pub const GPT_LINUX_FILESYSTEM_GUID: [u8; 16] = [
    0xAF, 0x3D, 0xC6, 0x0F, 0x83, 0x84, 0x72, 0x47, 0x8E, 0x79, 0x3D, 0x69, 0xD8, 0x47, 0x7D, 0xE4,
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TableKind {
    Mbr,
    Gpt,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PartitionEntry {
    /// Zero-based slot index in the on-disk table.
    pub index: usize,
    pub start_lba: u64,
    pub sector_count: u64,
    pub type_byte: u8,
    pub type_guid: [u8; 16],
    pub name: String,
    pub bootable: bool,
}

impl PartitionEntry {
    pub fn end_lba(&self) -> Option<u64> {
        if self.sector_count == 0 {
            None
        } else {
            self.start_lba.checked_add(self.sector_count - 1)
        }
    }

    pub fn is_empty(&self) -> bool {
        self.sector_count == 0 && self.type_guid == [0u8; 16]
    }
}

#[derive(Debug, Clone)]
pub struct GptLayout {
    pub primary: gpt::GptHeader,
    pub backup: gpt::GptHeader,
    pub primary_entries: Vec<u8>,
    pub backup_entries: Vec<u8>,
}

#[derive(Debug, Clone)]
pub struct DiskLayout {
    pub sector_size: u32,
    pub sector_count: u64,
    pub kind: TableKind,
    pub entries: Vec<PartitionEntry>,
    pub gpt: Option<GptLayout>,
    pub mbr_boot_code: [u8; 446],
    pub mbr_disk_signature: [u8; 4],
    /// A hybrid table has a protective MBR plus one or more legacy entries.
    pub hybrid: bool,
    /// True when every legacy MBR entry matches a GPT entry.
    pub hybrid_consistent: bool,
}

impl DiskLayout {
    /// Last LBA used by an existing partition (inclusive).
    pub fn last_used_lba(&self) -> u64 {
        let entry_end = self
            .entries
            .iter()
            .filter_map(|e| e.end_lba())
            .max()
            .unwrap_or(0);
        match &self.gpt {
            Some(gpt) => entry_end.max(gpt.primary.first_usable_lba.saturating_sub(1)),
            None => entry_end,
        }
    }

    /// The old backup GPT location. Formatting must not touch it before the
    /// table commit has moved the backup to the end of the device.
    fn old_gpt_backup_lba(&self) -> Option<u64> {
        self.gpt.as_ref().map(|g| g.backup.current_lba)
    }
}

#[derive(Debug, Clone)]
pub struct AppendPlan {
    pub kind: TableKind,
    pub index: usize,
    pub start_lba: u64,
    pub sector_count: u64,
    pub mbr_type: u8,
    pub gpt_type_guid: [u8; 16],
    pub name: String,
}

impl AppendPlan {
    pub fn end_lba(&self) -> u64 {
        self.start_lba + self.sector_count - 1
    }

    pub fn byte_len(&self) -> u64 {
        self.sector_count * SECTOR_SIZE as u64
    }
}

#[derive(Debug, Clone)]
pub struct SavedSector {
    pub lba: u64,
    pub data: Vec<u8>,
}

#[derive(Debug, Clone, Default)]
pub struct OriginalTable {
    pub sectors: Vec<SavedSector>,
}

impl OriginalTable {
    fn save(&mut self, dev: &mut dyn BlockDevice, lba: u64, count: u64) -> Result<()> {
        for i in 0..count {
            let current = lba + i;
            if self.sectors.iter().any(|s| s.lba == current) {
                continue;
            }
            let mut data = vec![0u8; SECTOR_SIZE as usize];
            dev.read_sectors(current, &mut data)?;
            self.sectors.push(SavedSector { lba: current, data });
        }
        Ok(())
    }
}

/// Parse the on-disk partition table.
pub fn read_layout(dev: &mut dyn BlockDevice) -> Result<DiskLayout> {
    require_512_byte_sectors(dev)?;
    let sector_count = dev.sector_count();
    if sector_count < 2 {
        return Err(PartitionError::NoPartitionTable);
    }

    let mut sector0 = [0u8; SECTOR_SIZE as usize];
    dev.read_sectors(0, &mut sector0)?;
    if sector0[510] != 0x55 || sector0[511] != 0xAA {
        return Err(PartitionError::NoPartitionTable);
    }

    let mbr_entries = mbr::parse_entries(&sector0);
    let mut mbr_boot_code = [0u8; 446];
    mbr_boot_code.copy_from_slice(&sector0[..446]);
    let mut mbr_disk_signature = [0u8; 4];
    mbr_disk_signature.copy_from_slice(&sector0[440..444]);

    let protective = mbr_entries
        .iter()
        .any(|e| e.type_byte == 0xEE && e.sector_count > 0);

    if protective {
        let gpt = gpt::read(dev)?;
        let entries = gpt::parse_entries(
            &gpt.primary_entries,
            gpt.primary.num_entries as usize,
            gpt.primary.entry_size as usize,
        )?;

        let mut hybrid = false;
        let mut hybrid_consistent = true;
        for mbr_entry in &mbr_entries {
            if mbr_entry.type_byte == 0xEE || mbr_entry.sector_count == 0 {
                continue;
            }
            hybrid = true;
            let matches = entries.iter().any(|e| {
                e.start_lba == mbr_entry.start_lba as u64
                    && e.sector_count == mbr_entry.sector_count as u64
                    && !e.is_empty()
            });
            if !matches {
                hybrid_consistent = false;
            }
        }

        return Ok(DiskLayout {
            sector_size: SECTOR_SIZE,
            sector_count,
            kind: TableKind::Gpt,
            entries,
            gpt: Some(gpt),
            mbr_boot_code,
            mbr_disk_signature,
            hybrid,
            hybrid_consistent,
        });
    }

    let entries = mbr_entries
        .iter()
        .enumerate()
        .filter(|(_, e)| e.sector_count > 0)
        .map(|(index, e)| PartitionEntry {
            index,
            start_lba: e.start_lba as u64,
            sector_count: e.sector_count as u64,
            type_byte: e.type_byte,
            type_guid: [0u8; 16],
            name: String::new(),
            bootable: e.status == 0x80,
        })
        .collect();

    Ok(DiskLayout {
        sector_size: SECTOR_SIZE,
        sector_count,
        kind: TableKind::Mbr,
        entries,
        gpt: None,
        mbr_boot_code,
        mbr_disk_signature,
        hybrid: false,
        hybrid_consistent: true,
    })
}

/// Plan an append-only partition at the end of the device.
pub fn plan_append(
    dev: &mut dyn BlockDevice,
    request_bytes: u64,
    alignment: u64,
) -> Result<AppendPlan> {
    let layout = read_layout(dev)?;
    plan_append_layout(&layout, request_bytes, alignment)
}

/// Bytes available for an appended partition with 1 MiB alignment.
pub fn available_append_bytes(layout: &DiskLayout) -> Result<u64> {
    if layout.kind == TableKind::Gpt && layout.hybrid && !layout.hybrid_consistent {
        return Err(PartitionError::HybridMbrUnsupported);
    }
    if layout.entries.is_empty() && layout.gpt.is_none() {
        return Err(PartitionError::NoPartitionTable);
    }
    let (start_lba, max_end_lba) = append_bounds(layout, ALIGNMENT_1_MIB / SECTOR_SIZE as u64);
    if start_lba > max_end_lba {
        Ok(0)
    } else {
        Ok((max_end_lba - start_lba + 1) * SECTOR_SIZE as u64)
    }
}

fn append_bounds(layout: &DiskLayout, alignment_sectors: u64) -> (u64, u64) {
    let mut first_free_lba = layout.last_used_lba() + 1;

    // Never format over the old backup GPT before the table commit relocates it.
    // If the backup already sits at the end of the device there is nothing to
    // relocate, and the free tail before it is usable.
    if let Some(old_backup) = layout.old_gpt_backup_lba()
        && old_backup + 1 < layout.sector_count
    {
        first_free_lba = first_free_lba.max(old_backup + 1);
    }
    let start_lba = align_up(first_free_lba, alignment_sectors.max(1));

    // Keep the last MiB free. For GPT the new backup header/entries live in
    // the final 33 sectors, so the usable end is derived from them.
    let reserve = ALIGNMENT_1_MIB / SECTOR_SIZE as u64;
    let max_end_lba = match &layout.gpt {
        Some(gpt) => {
            let entry_sectors = gpt::entry_sectors(gpt.primary.num_entries, gpt.primary.entry_size);
            let new_backup_lba = layout.sector_count.saturating_sub(1);
            let new_backup_entries = new_backup_lba.saturating_sub(entry_sectors);
            new_backup_entries.saturating_sub(1).saturating_sub(reserve)
        }
        None => layout
            .sector_count
            .saturating_sub(1)
            .saturating_sub(reserve),
    };
    (start_lba, max_end_lba)
}

pub fn plan_append_layout(
    layout: &DiskLayout,
    request_bytes: u64,
    alignment: u64,
) -> Result<AppendPlan> {
    if request_bytes == 0 {
        return Err(PartitionError::ZeroSize);
    }
    if alignment == 0 || !alignment.is_multiple_of(SECTOR_SIZE as u64) {
        return Err(PartitionError::InvalidTable(format!(
            "alignment {alignment} is not a multiple of {SECTOR_SIZE}"
        )));
    }
    if layout.kind == TableKind::Gpt && layout.hybrid && !layout.hybrid_consistent {
        return Err(PartitionError::HybridMbrUnsupported);
    }
    if layout.entries.is_empty() && layout.gpt.is_none() {
        return Err(PartitionError::NoPartitionTable);
    }

    let alignment_sectors = alignment / SECTOR_SIZE as u64;
    let (start_lba, max_end_lba) = append_bounds(layout, alignment_sectors);

    if start_lba > max_end_lba {
        return Err(PartitionError::InsufficientSpace {
            need: request_bytes,
            have: 0,
        });
    }

    let available_sectors = max_end_lba - start_lba + 1;
    let available_bytes = available_sectors * SECTOR_SIZE as u64;
    let requested_sectors = request_bytes.div_ceil(SECTOR_SIZE as u64);
    if requested_sectors > available_sectors {
        return Err(PartitionError::InsufficientSpace {
            need: request_bytes,
            have: available_bytes,
        });
    }

    let index = match layout.kind {
        TableKind::Mbr => (0..4)
            .find(|i| {
                layout
                    .entries
                    .iter()
                    .all(|e| e.index != *i || e.sector_count == 0)
            })
            .ok_or(PartitionError::NoMbrSlot)?,
        TableKind::Gpt => {
            let gpt = layout
                .gpt
                .as_ref()
                .ok_or(PartitionError::NoPartitionTable)?;
            (0..gpt.primary.num_entries as usize)
                .find(|i| layout.entries.iter().all(|e| e.index != *i))
                .ok_or(PartitionError::NoGptSlot)?
        }
    };

    Ok(AppendPlan {
        kind: layout.kind,
        index,
        start_lba,
        sector_count: requested_sectors,
        mbr_type: 0x83,
        gpt_type_guid: GPT_LINUX_FILESYSTEM_GUID,
        name: "persistence".to_string(),
    })
}

/// Commit the planned partition. Returns the original sectors for rollback.
pub fn commit_append(dev: &mut dyn BlockDevice, plan: &AppendPlan) -> Result<OriginalTable> {
    let mut original = OriginalTable::default();
    commit_append_journaled(dev, plan, &mut original)?;
    Ok(original)
}

/// Commit the planned partition, filling `original` before each write so a
/// caller can roll back even when a write fails part-way through.
pub fn commit_append_journaled(
    dev: &mut dyn BlockDevice,
    plan: &AppendPlan,
    original: &mut OriginalTable,
) -> Result<()> {
    let layout = read_layout(dev)?;
    if layout.kind != plan.kind {
        return Err(PartitionError::InvalidTable(
            "partition table changed since planning".into(),
        ));
    }
    if plan.sector_count == 0 {
        return Err(PartitionError::ZeroSize);
    }
    if plan.end_lba() >= layout.sector_count {
        return Err(PartitionError::InvalidTable(
            "planned partition extends past the device".into(),
        ));
    }

    match plan.kind {
        TableKind::Mbr => commit_mbr(dev, &layout, plan, original),
        TableKind::Gpt => commit_gpt(dev, &layout, plan, original),
    }
}

fn commit_mbr(
    dev: &mut dyn BlockDevice,
    _layout: &DiskLayout,
    plan: &AppendPlan,
    original: &mut OriginalTable,
) -> Result<()> {
    if plan.start_lba > u32::MAX as u64 || plan.sector_count > u32::MAX as u64 {
        return Err(PartitionError::MbrTooLarge);
    }
    original.save(dev, 0, 1)?;

    let mut sector0 = [0u8; SECTOR_SIZE as usize];
    dev.read_sectors(0, &mut sector0)?;

    mbr::set_entry(
        &mut sector0,
        plan.index,
        &mbr::MbrEntry {
            status: 0,
            type_byte: plan.mbr_type,
            start_lba: plan.start_lba as u32,
            sector_count: plan.sector_count as u32,
        },
    );

    dev.write_sectors(0, &sector0)?;
    Ok(())
}

fn commit_gpt(
    dev: &mut dyn BlockDevice,
    layout: &DiskLayout,
    plan: &AppendPlan,
    original: &mut OriginalTable,
) -> Result<()> {
    let gpt = layout
        .gpt
        .as_ref()
        .ok_or(PartitionError::NoPartitionTable)?;
    if plan.index >= gpt.primary.num_entries as usize {
        return Err(PartitionError::NoGptSlot);
    }

    // Check every precondition that can reject the edit before touching the
    // device.
    let hybrid_slot = if layout.hybrid {
        if !layout.hybrid_consistent {
            return Err(PartitionError::HybridMbrUnsupported);
        }
        if plan.start_lba > u32::MAX as u64 || plan.sector_count > u32::MAX as u64 {
            return Err(PartitionError::MbrTooLarge);
        }
        let mut mbr_sector = [0u8; SECTOR_SIZE as usize];
        dev.read_sectors(0, &mut mbr_sector)?;
        Some(
            mbr::parse_entries(&mbr_sector)
                .iter()
                .position(|e| e.sector_count == 0)
                .ok_or(PartitionError::NoMbrSlot)?,
        )
    } else {
        None
    };

    let entry_size = gpt.primary.entry_size as usize;
    let num_entries = gpt.primary.num_entries as usize;
    let entry_sectors = gpt::entry_sectors(gpt.primary.num_entries, gpt.primary.entry_size);
    let new_backup_lba = layout.sector_count - 1;
    let new_backup_entries_lba = new_backup_lba - entry_sectors;
    let new_last_usable = new_backup_entries_lba - 1;

    // Save everything the commit may overwrite.
    original.save(dev, 0, 1)?;
    original.save(dev, 1, 1)?;
    original.save(dev, gpt.primary.entry_lba, entry_sectors)?;
    original.save(dev, gpt.backup.entry_lba, entry_sectors)?;
    original.save(dev, gpt.backup.current_lba, 1)?;
    original.save(dev, new_backup_entries_lba, entry_sectors)?;
    original.save(dev, new_backup_lba, 1)?;

    let mut entries = vec![0u8; (entry_sectors * SECTOR_SIZE as u64) as usize];
    let copy_len = (num_entries * entry_size).min(entries.len());
    entries[..copy_len].copy_from_slice(&gpt.primary_entries[..copy_len]);

    let entry = gpt::GptEntry {
        type_guid: plan.gpt_type_guid,
        unique_guid: gpt::new_unique_guid(plan.start_lba, plan.sector_count),
        first_lba: plan.start_lba,
        last_lba: plan.end_lba(),
        attributes: 0,
        name: plan.name.clone(),
    };
    let serialized = entry.serialize(entry_size)?;
    let offset = plan.index * entry_size;
    if offset + entry_size > entries.len() {
        return Err(PartitionError::NoGptSlot);
    }
    entries[offset..offset + entry_size].copy_from_slice(&serialized);

    let entries_crc = crc32::crc32(&entries[..num_entries * entry_size]);

    let primary = gpt::GptHeader {
        backup_lba: new_backup_lba,
        last_usable_lba: new_last_usable,
        entries_crc32: entries_crc,
        header_crc32: 0,
        ..gpt.primary.clone()
    };
    let primary_sector = primary.serialize()?;

    let backup = gpt::GptHeader {
        current_lba: new_backup_lba,
        backup_lba: 1,
        entry_lba: new_backup_entries_lba,
        last_usable_lba: new_last_usable,
        entries_crc32: entries_crc,
        header_crc32: 0,
        ..gpt.primary.clone()
    };
    let backup_sector = backup.serialize()?;

    // Backup first, then the primary header last.
    dev.write_sectors(new_backup_entries_lba, &entries)?;
    dev.write_sectors(new_backup_lba, &backup_sector)?;
    dev.write_sectors(gpt.primary.entry_lba, &entries)?;
    dev.write_sectors(1, &primary_sector)?;

    // A hybrid MBR must expose the new partition to BIOS too.
    if let Some(slot) = hybrid_slot {
        let mut mbr_sector = [0u8; SECTOR_SIZE as usize];
        dev.read_sectors(0, &mut mbr_sector)?;
        mbr::set_entry(
            &mut mbr_sector,
            slot,
            &mbr::MbrEntry {
                status: 0,
                type_byte: plan.mbr_type,
                start_lba: plan.start_lba as u32,
                sector_count: plan.sector_count as u32,
            },
        );
        dev.write_sectors(0, &mbr_sector)?;
    }

    Ok(())
}

/// Verify that the planned partition exists and both GPT copies are valid.
pub fn verify_append(dev: &mut dyn BlockDevice, plan: &AppendPlan) -> Result<()> {
    let layout = read_layout(dev)?;
    if layout.kind != plan.kind {
        return Err(PartitionError::Verify(
            "partition table kind changed".into(),
        ));
    }

    let entry = layout
        .entries
        .iter()
        .find(|e| e.index == plan.index)
        .ok_or_else(|| PartitionError::Verify("planned entry is missing".into()))?;

    if entry.start_lba != plan.start_lba
        || entry.sector_count != plan.sector_count
        || (layout.kind == TableKind::Mbr && entry.type_byte != plan.mbr_type)
    {
        return Err(PartitionError::Verify(format!(
            "entry mismatch: got start={} count={} type=0x{:02X}, expected start={} count={} type=0x{:02X}",
            entry.start_lba,
            entry.sector_count,
            entry.type_byte,
            plan.start_lba,
            plan.sector_count,
            plan.mbr_type
        )));
    }
    if layout.kind == TableKind::Gpt {
        if entry.type_guid != plan.gpt_type_guid {
            return Err(PartitionError::Verify("GPT type GUID mismatch".into()));
        }
        if entry.name != plan.name {
            return Err(PartitionError::Verify("GPT partition name mismatch".into()));
        }
        let gpt = layout.gpt.as_ref().unwrap();
        if gpt.primary.backup_lba != layout.sector_count - 1 {
            return Err(PartitionError::Verify(
                "backup GPT is not at the end of the device".into(),
            ));
        }
        let entry_sectors = gpt::entry_sectors(gpt.primary.num_entries, gpt.primary.entry_size);
        let expected_last_usable = layout.sector_count - 1 - entry_sectors - 1;
        if gpt.primary.last_usable_lba != expected_last_usable
            || gpt.backup.last_usable_lba != expected_last_usable
        {
            return Err(PartitionError::Verify(
                "GPT last usable LBA was not extended".into(),
            ));
        }
        if gpt.backup.entry_lba != layout.sector_count - 1 - entry_sectors {
            return Err(PartitionError::Verify(
                "backup GPT entries are in the wrong place".into(),
            ));
        }
    }

    // No overlap with any other entry.
    for other in &layout.entries {
        if other.index == entry.index || other.sector_count == 0 {
            continue;
        }
        let other_end = other.end_lba().unwrap_or(other.start_lba);
        if entry.start_lba <= other_end
            && other.start_lba <= entry.end_lba().unwrap_or(entry.start_lba)
        {
            return Err(PartitionError::Verify(format!(
                "partition {} overlaps partition {}",
                entry.index, other.index
            )));
        }
    }

    Ok(())
}

/// Roll the table back to the saved sectors.
pub fn rollback(dev: &mut dyn BlockDevice, saved: &OriginalTable) -> Result<()> {
    let mut sectors = saved.sectors.clone();
    // Restore high LBAs first, then the primary header, then sector 0.
    sectors.sort_by_key(|s| match s.lba {
        0 => u64::MAX,
        1 => u64::MAX - 1,
        other => u64::MAX - 2 - other,
    });
    for sector in &sectors {
        dev.write_sectors(sector.lba, &sector.data)?;
    }
    Ok(())
}

fn align_up(value: u64, alignment: u64) -> u64 {
    if alignment <= 1 {
        value
    } else {
        value.div_ceil(alignment) * alignment
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use hyntix_storage::MemBlockDevice;

    fn mbr_device() -> MemBlockDevice {
        let mut dev = MemBlockDevice::new(64 * 1024, 512).unwrap();
        let mut sector = [0u8; 512];
        sector[440] = 0xDE;
        sector[441] = 0xAD;
        sector[442] = 0xBE;
        sector[443] = 0xEF;
        sector[510] = 0x55;
        sector[511] = 0xAA;
        mbr::set_entry(
            &mut sector,
            0,
            &mbr::MbrEntry {
                status: 0x80,
                type_byte: 0x83,
                start_lba: 2048,
                sector_count: 2048,
            },
        );
        dev.write_sectors(0, &sector).unwrap();
        dev
    }

    #[test]
    fn mbr_read_preserves_boot_code_and_signature() {
        let mut dev = mbr_device();
        // A distinctive disk signature and boot code.
        let mut sector = [0u8; 512];
        dev.read_sectors(0, &mut sector).unwrap();
        sector[0] = 0xEB;
        sector[1] = 0x3C;
        sector[440] = 0xDE;
        sector[441] = 0xAD;
        sector[442] = 0xBE;
        sector[443] = 0xEF;
        dev.write_sectors(0, &sector).unwrap();

        let layout = read_layout(&mut dev).unwrap();
        assert_eq!(layout.kind, TableKind::Mbr);
        assert_eq!(layout.mbr_boot_code[0], 0xEB);
        assert_eq!(layout.mbr_disk_signature, [0xDE, 0xAD, 0xBE, 0xEF]);
    }

    #[test]
    fn mbr_append_uses_free_primary_slot() {
        let mut dev = mbr_device();
        let plan = plan_append(&mut dev, 8 * 1024 * 1024, ALIGNMENT_1_MIB).unwrap();
        assert_eq!(plan.kind, TableKind::Mbr);
        assert_eq!(plan.index, 1);
        assert_eq!(plan.start_lba % 2048, 0);
        commit_append(&mut dev, &plan).unwrap();
        verify_append(&mut dev, &plan).unwrap();

        let layout = read_layout(&mut dev).unwrap();
        assert_eq!(layout.entries.len(), 2);
        assert_eq!(layout.entries[0].start_lba, 2048);
        assert_eq!(layout.mbr_disk_signature, [0xDE, 0xAD, 0xBE, 0xEF]);
    }

    #[test]
    fn mbr_append_rejects_when_four_primaries() {
        let mut dev = MemBlockDevice::new(64 * 1024, 512).unwrap();
        let mut sector = [0u8; 512];
        sector[510] = 0x55;
        sector[511] = 0xAA;
        for i in 0..4 {
            mbr::set_entry(
                &mut sector,
                i,
                &mbr::MbrEntry {
                    status: 0,
                    type_byte: 0x83,
                    start_lba: 2048 + (i as u32 * 2048),
                    sector_count: 2048,
                },
            );
        }
        dev.write_sectors(0, &sector).unwrap();
        let err = plan_append(&mut dev, 8 * 1024 * 1024, ALIGNMENT_1_MIB).unwrap_err();
        assert!(matches!(err, PartitionError::NoMbrSlot));
    }

    #[test]
    fn append_is_1mib_aligned() {
        let mut dev = mbr_device();
        let plan = plan_append(&mut dev, 8 * 1024 * 1024, ALIGNMENT_1_MIB).unwrap();
        assert_eq!(plan.start_lba % 2048, 0);
    }

    #[test]
    fn rollback_restores_original_table() {
        let mut dev = mbr_device();
        let before = read_layout(&mut dev).unwrap();
        let plan = plan_append(&mut dev, 8 * 1024 * 1024, ALIGNMENT_1_MIB).unwrap();
        let original = commit_append(&mut dev, &plan).unwrap();
        assert_eq!(read_layout(&mut dev).unwrap().entries.len(), 2);
        rollback(&mut dev, &original).unwrap();
        let after = read_layout(&mut dev).unwrap();
        assert_eq!(after.entries, before.entries);
        assert_eq!(after.mbr_boot_code, before.mbr_boot_code);
    }

    #[test]
    fn plan_rejects_insufficient_space() {
        let mut dev = mbr_device();
        let err = plan_append(&mut dev, 100 * 1024 * 1024 * 1024, ALIGNMENT_1_MIB).unwrap_err();
        assert!(matches!(err, PartitionError::InsufficientSpace { .. }));
    }

    #[test]
    fn plan_rejects_empty_request() {
        let mut dev = mbr_device();
        let err = plan_append(&mut dev, 0, ALIGNMENT_1_MIB).unwrap_err();
        assert!(matches!(err, PartitionError::ZeroSize));
    }

    fn gpt_device() -> MemBlockDevice {
        let sector_count = 64 * 1024u64;
        let mut dev = MemBlockDevice::new(sector_count, 512).unwrap();

        let mut sector0 = [0u8; 512];
        sector0[510] = 0x55;
        sector0[511] = 0xAA;
        mbr::set_entry(
            &mut sector0,
            0,
            &mbr::MbrEntry {
                status: 0,
                type_byte: 0xEE,
                start_lba: 1,
                sector_count: (sector_count - 1) as u32,
            },
        );
        dev.write_sectors(0, &sector0).unwrap();

        let entry_sectors = gpt::entry_sectors(128, 128);
        let backup_lba = sector_count - 1;
        let backup_entries_lba = backup_lba - entry_sectors;
        let last_usable = backup_entries_lba - 1;

        let mut entries = vec![0u8; (entry_sectors * 512) as usize];
        let entry = gpt::GptEntry {
            type_guid: GPT_LINUX_FILESYSTEM_GUID,
            unique_guid: [1u8; 16],
            first_lba: 2048,
            last_lba: 4095,
            attributes: 0,
            name: "iso".into(),
        };
        entries[..128].copy_from_slice(&entry.serialize(128).unwrap());
        let entries_crc = crc32::crc32(&entries[..128 * 128]);
        dev.write_sectors(2, &entries).unwrap();
        dev.write_sectors(backup_entries_lba, &entries).unwrap();

        let primary = gpt::GptHeader {
            revision: 0x0001_0000,
            header_size: 92,
            current_lba: 1,
            backup_lba,
            first_usable_lba: 34,
            last_usable_lba: last_usable,
            disk_guid: [7u8; 16],
            entry_lba: 2,
            num_entries: 128,
            entry_size: 128,
            entries_crc32: entries_crc,
            header_crc32: 0,
            raw: vec![0u8; 512],
        };
        dev.write_sectors(1, &primary.serialize().unwrap()).unwrap();

        let backup = gpt::GptHeader {
            current_lba: backup_lba,
            backup_lba: 1,
            entry_lba: backup_entries_lba,
            ..primary.clone()
        };
        dev.write_sectors(backup_lba, &backup.serialize().unwrap())
            .unwrap();

        dev
    }

    #[test]
    fn gpt_read_preserves_existing_entries() {
        let mut dev = gpt_device();
        let layout = read_layout(&mut dev).unwrap();
        assert_eq!(layout.kind, TableKind::Gpt);
        assert_eq!(layout.entries.len(), 1);
        assert_eq!(layout.entries[0].start_lba, 2048);
        assert_eq!(layout.entries[0].sector_count, 2048);
        assert_eq!(layout.entries[0].name, "iso");
        assert!(!layout.hybrid);
    }

    #[test]
    fn gpt_append_moves_backup_and_recomputes_crc() {
        let mut dev = gpt_device();
        let plan = plan_append(&mut dev, 8 * 1024 * 1024, ALIGNMENT_1_MIB).unwrap();
        assert_eq!(plan.kind, TableKind::Gpt);
        assert_eq!(plan.index, 1);
        commit_append(&mut dev, &plan).unwrap();
        verify_append(&mut dev, &plan).unwrap();

        let layout = read_layout(&mut dev).unwrap();
        assert_eq!(layout.entries.len(), 2);
        let gpt = layout.gpt.unwrap();
        assert_eq!(gpt.backup.current_lba, dev.sector_count() - 1);
        assert_eq!(gpt.primary.backup_lba, dev.sector_count() - 1);
        assert_eq!(gpt.primary.entries_crc32, gpt.backup.entries_crc32);
    }

    #[test]
    fn gpt_rejects_corrupt_primary_header() {
        let mut dev = gpt_device();
        let mut header = [0u8; 512];
        dev.read_sectors(1, &mut header).unwrap();
        header[40] ^= 0xFF;
        dev.write_sectors(1, &header).unwrap();
        let err = read_layout(&mut dev).unwrap_err();
        assert!(matches!(err, PartitionError::InvalidTable(_)));
    }

    #[test]
    fn gpt_rollback_restores_both_copies() {
        let mut dev = gpt_device();
        let plan = plan_append(&mut dev, 8 * 1024 * 1024, ALIGNMENT_1_MIB).unwrap();
        let original = commit_append(&mut dev, &plan).unwrap();
        rollback(&mut dev, &original).unwrap();
        let layout = read_layout(&mut dev).unwrap();
        assert_eq!(layout.entries.len(), 1);
        assert_eq!(layout.entries[0].name, "iso");
    }
}
