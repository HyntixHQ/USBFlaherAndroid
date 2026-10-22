//! Persistence planning, apply orchestration, and public result types.

use crate::activation::{self, BootPatch};
use crate::ext2::{self, FormatOptions};
use crate::iso::probe_iso;
use crate::profile::{PersistenceActivation, PersistenceProfile, PersistenceSupport};
use crate::verify;
use crate::{PersistenceError, Result};
use hyntix_partition::{
    self as partition, ALIGNMENT_1_MIB, AppendPlan, GPT_LINUX_FILESYSTEM_GUID, PartitionEntry,
    PartitionError, TableKind,
};
use hyntix_storage::BlockDevice;
use std::io::{Read, Seek};

pub const SECTOR_SIZE: u64 = 512;
const MIB: u64 = 1024 * 1024;

/// Progress phases reported to the UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PersistencePhase {
    Planning,
    Formatting,
    Configuring,
    ActivatingBoot,
    Partitioning,
    Verifying,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PersistenceProgress {
    pub phase: PersistencePhase,
    pub current: u64,
    pub total: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PersistenceAction {
    /// Append a new partition.
    Create,
    /// Reformat an existing partition after the ISO.
    Reuse,
}

#[derive(Debug, Clone)]
pub struct PersistencePlan {
    pub partition: AppendPlan,
    pub profile: PersistenceProfile,
    pub activation: PersistenceActivation,
    pub label: String,
    pub has_persistence_conf: bool,
    pub patches: Vec<BootPatch>,
    pub action: PersistenceAction,
    pub requested_bytes: u64,
    pub available_bytes: u64,
}

#[derive(Debug, Clone)]
pub struct PersistenceResult {
    pub profile: PersistenceProfile,
    pub activation: PersistenceActivation,
    pub label: String,
    pub partition_start_lba: u64,
    pub partition_sector_count: u64,
    pub partition_index: u32,
    pub has_persistence_conf: bool,
    pub patches_applied: bool,
    pub action: PersistenceAction,
}

/// Clamp a requested size to the supported range and the free tail.
///
/// Returns `None` when fewer than `min_bytes` are available.
pub fn choose_persistence_size(
    requested_bytes: u64,
    min_bytes: u64,
    recommended_bytes: u64,
    available_bytes: u64,
) -> Option<u64> {
    if available_bytes < min_bytes || min_bytes == 0 {
        return None;
    }
    let requested = if requested_bytes == 0 {
        recommended_bytes
    } else {
        requested_bytes
    };
    let mut size = requested.clamp(min_bytes, available_bytes);
    let rounded = (size / MIB) * MIB;
    if rounded >= min_bytes {
        size = rounded;
    }
    Some(size)
}

/// Inspect the partition table and produce a concrete plan.
pub fn plan(
    dev: &mut dyn BlockDevice,
    iso_size_bytes: u64,
    support: &PersistenceSupport,
    requested_bytes: u64,
) -> Result<PersistencePlan> {
    hyntix_storage::require_512_byte_sectors(dev)?;
    let layout = partition::read_layout(dev)?;
    if layout.kind == TableKind::Gpt && layout.hybrid && !layout.hybrid_consistent {
        return Err(PersistenceError::Partition(
            PartitionError::HybridMbrUnsupported,
        ));
    }
    let iso_sectors = iso_size_bytes.div_ceil(SECTOR_SIZE);

    if let Some(existing) = find_existing_persistence(&layout, iso_sectors)? {
        let bytes = existing.sector_count * SECTOR_SIZE;
        if bytes < support.min_bytes {
            return Err(PersistenceError::Verify(format!(
                "existing persistence partition is only {} MiB; reformat the drive to use at least {} MiB",
                bytes / MIB,
                support.min_bytes / MIB
            )));
        }
        return Ok(PersistencePlan {
            partition: existing,
            profile: support.profile,
            activation: support.activation,
            label: support.label.clone(),
            has_persistence_conf: support.needs_persistence_conf(),
            patches: support.patches.clone(),
            action: PersistenceAction::Reuse,
            requested_bytes,
            available_bytes: bytes,
        });
    }

    let available = match partition::available_append_bytes(&layout) {
        Ok(bytes) => bytes,
        Err(PartitionError::InsufficientSpace { have, .. }) => have,
        Err(e) => return Err(e.into()),
    };
    let size = choose_persistence_size(
        requested_bytes,
        support.min_bytes,
        support.recommended_bytes,
        available,
    )
    .ok_or(PersistenceError::Partition(
        PartitionError::InsufficientSpace {
            need: support.min_bytes,
            have: available,
        },
    ))?;

    let append = partition::plan_append_layout(&layout, size, ALIGNMENT_1_MIB)?;
    Ok(PersistencePlan {
        partition: append,
        profile: support.profile,
        activation: support.activation,
        label: support.label.clone(),
        has_persistence_conf: support.needs_persistence_conf(),
        patches: support.patches.clone(),
        action: PersistenceAction::Create,
        requested_bytes,
        available_bytes: available,
    })
}

/// Reuse a partition that already starts after the ISO. Raw flashing leaves no
/// such partition, so one can only come from a previous persistence attempt.
fn find_existing_persistence(
    layout: &hyntix_partition::DiskLayout,
    iso_sectors: u64,
) -> Result<Option<AppendPlan>> {
    let mut candidates: Vec<&PartitionEntry> = layout
        .entries
        .iter()
        .filter(|entry| {
            entry.start_lba >= iso_sectors
                && entry.sector_count > 0
                && match layout.kind {
                    TableKind::Mbr => entry.type_byte == 0x83,
                    TableKind::Gpt => entry.type_guid == GPT_LINUX_FILESYSTEM_GUID,
                }
        })
        .collect();
    candidates.sort_by_key(|entry| entry.start_lba);
    let Some(entry) = candidates.last() else {
        return Ok(None);
    };
    Ok(Some(AppendPlan {
        kind: layout.kind,
        index: entry.index,
        start_lba: entry.start_lba,
        sector_count: entry.sector_count,
        mbr_type: 0x83,
        gpt_type_guid: GPT_LINUX_FILESYSTEM_GUID,
        name: "persistence".to_string(),
    }))
}

/// Format, activate, commit the table, and verify.
///
/// If anything fails after the table commit, the partition table and boot
/// patches are rolled back. A failure before the commit leaves the ISO exactly
/// as it was; ext2 data written into the free tail is harmless.
#[allow(clippy::too_many_arguments)]
pub fn apply<R: Read + Seek>(
    dev: &mut dyn BlockDevice,
    iso_reader: &mut R,
    iso_size_bytes: u64,
    expected_profile: PersistenceProfile,
    requested_bytes: u64,
    progress: &mut dyn FnMut(PersistenceProgress),
    cancelled: &dyn Fn() -> bool,
) -> Result<PersistenceResult> {
    check_cancelled(cancelled)?;
    let support = probe_iso(iso_reader, iso_size_bytes)?.ok_or_else(|| {
        PersistenceError::UnsupportedIso("the ISO does not support persistence".into())
    })?;
    if support.profile != expected_profile {
        return Err(PersistenceError::UnsupportedIso(
            "the ISO persistence profile changed since it was probed".into(),
        ));
    }

    report(progress, PersistencePhase::Planning, 0, 1);
    let plan = plan(dev, iso_size_bytes, &support, requested_bytes)?;
    check_cancelled(cancelled)?;

    let partition_start = plan.partition.start_lba;
    let partition_sectors = plan.partition.sector_count;

    report(progress, PersistencePhase::Formatting, 0, 1);
    let timestamp = unix_timestamp();
    let uuid = make_uuid(&plan.label, partition_sectors, timestamp);
    let options = FormatOptions {
        label: &plan.label,
        persistence_conf: plan.has_persistence_conf,
        timestamp,
        uuid,
    };
    ext2::format(
        dev,
        partition_start,
        partition_sectors,
        &options,
        &mut |current, total| {
            report(progress, PersistencePhase::Formatting, current, total);
        },
    )?;
    check_cancelled(cancelled)?;

    let mut patches_applied = false;
    if plan.activation == PersistenceActivation::Patchable && !plan.patches.is_empty() {
        report(
            progress,
            PersistencePhase::ActivatingBoot,
            0,
            plan.patches.len() as u64,
        );
        activation::apply_patches(dev, &plan.patches, &mut |current, total| {
            report(progress, PersistencePhase::ActivatingBoot, current, total);
        })?;
        patches_applied = true;
    }
    if cancelled() {
        // The table has not been committed yet, so the ISO must go back to
        // its original boot configuration rather than keep pointing at a
        // persistence partition that does not exist.
        restore_after_failure(
            dev,
            &partition::OriginalTable::default(),
            &plan,
            patches_applied,
        );
        return Err(PersistenceError::Cancelled);
    }

    let mut original = partition::OriginalTable::default();
    if plan.action == PersistenceAction::Create {
        report(progress, PersistencePhase::Partitioning, 0, 1);
        if let Err(e) = partition::commit_append_journaled(dev, &plan.partition, &mut original) {
            restore_after_failure(dev, &original, &plan, patches_applied);
            return Err(e.into());
        }
    }
    check_cancelled(cancelled)?;

    report(progress, PersistencePhase::Verifying, 0, 1);
    let verification = verify::verify_persistence(
        dev,
        &plan.partition,
        &plan.label,
        plan.has_persistence_conf,
        if patches_applied {
            plan.patches.as_slice()
        } else {
            &[]
        },
    );
    if let Err(e) = verification {
        restore_after_failure(dev, &original, &plan, patches_applied);
        return Err(e);
    }

    Ok(PersistenceResult {
        profile: plan.profile,
        activation: plan.activation,
        label: plan.label,
        partition_start_lba: partition_start,
        partition_sector_count: partition_sectors,
        partition_index: plan.partition.index as u32,
        has_persistence_conf: plan.has_persistence_conf,
        patches_applied,
        action: plan.action,
    })
}

fn restore_after_failure(
    dev: &mut dyn BlockDevice,
    original: &partition::OriginalTable,
    plan: &PersistencePlan,
    patches_applied: bool,
) {
    if plan.action == PersistenceAction::Create && !original.sectors.is_empty() {
        let _ = partition::rollback(dev, original);
    }
    if patches_applied {
        let patches: Vec<&BootPatch> = plan.patches.iter().collect();
        let _ = activation::restore_patches(dev, &patches);
    }
}

fn report(
    progress: &mut dyn FnMut(PersistenceProgress),
    phase: PersistencePhase,
    current: u64,
    total: u64,
) {
    progress(PersistenceProgress {
        phase,
        current,
        total,
    });
}

fn check_cancelled(cancelled: &dyn Fn() -> bool) -> Result<()> {
    if cancelled() {
        Err(PersistenceError::Cancelled)
    } else {
        Ok(())
    }
}

fn unix_timestamp() -> u32 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_secs() as u32)
        .unwrap_or(0)
}

fn make_uuid(label: &str, sector_count: u64, timestamp: u32) -> [u8; 16] {
    let mut uuid = [0u8; 16];
    uuid[..6].copy_from_slice(b"HYNTIX");
    let a = hyntix_partition::crc32::crc32(label.as_bytes());
    let b = hyntix_partition::crc32::crc32(&sector_count.to_le_bytes());
    let c = hyntix_partition::crc32::crc32(&timestamp.to_le_bytes());
    uuid[6..10].copy_from_slice(&a.to_le_bytes());
    uuid[10..14].copy_from_slice(&b.to_le_bytes());
    uuid[14..16].copy_from_slice(&c.to_le_bytes()[..2]);
    uuid
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::profile::{MIN_PERSISTENCE_BYTES, RECOMMENDED_PERSISTENCE_BYTES};
    use crate::test_support::{build_casper_iso, build_debian_iso};

    #[test]
    fn size_defaults_to_recommended() {
        assert_eq!(
            choose_persistence_size(
                0,
                MIN_PERSISTENCE_BYTES,
                RECOMMENDED_PERSISTENCE_BYTES,
                10 * 1024 * MIB
            ),
            Some(RECOMMENDED_PERSISTENCE_BYTES)
        );
    }

    #[test]
    fn size_is_clamped_to_available() {
        let available = 2 * 1024 * MIB;
        assert_eq!(
            choose_persistence_size(
                100 * 1024 * MIB,
                MIN_PERSISTENCE_BYTES,
                RECOMMENDED_PERSISTENCE_BYTES,
                available
            ),
            Some(available)
        );
    }

    #[test]
    fn size_is_rounded_down_to_mib() {
        let size = choose_persistence_size(
            3 * 1024 * MIB + 123,
            MIN_PERSISTENCE_BYTES,
            RECOMMENDED_PERSISTENCE_BYTES,
            10 * 1024 * MIB,
        )
        .unwrap();
        assert_eq!(size % MIB, 0);
    }

    #[test]
    fn rejects_too_small_free_space() {
        assert_eq!(
            choose_persistence_size(
                0,
                MIN_PERSISTENCE_BYTES,
                RECOMMENDED_PERSISTENCE_BYTES,
                512 * MIB
            ),
            None
        );
    }

    #[test]
    fn debian_support_uses_persistence_conf() {
        let bytes = build_debian_iso(Some(
            "label live-persistence\n menu label Live persistence\n kernel /live/vmlinuz\n append initrd=/live/initrd.img boot=live persistence\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert!(support.needs_persistence_conf());
        assert_eq!(support.label, "persistence");
    }

    #[test]
    fn casper_support_does_not_use_persistence_conf() {
        let bytes = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz quiet splash ---\n}\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert!(!support.needs_persistence_conf());
    }

    // ---------------------------------------------------------------
    // Apply integration tests
    // ---------------------------------------------------------------

    use hyntix_partition::GPT_LINUX_FILESYSTEM_GUID;
    use hyntix_partition::crc32;
    use hyntix_partition::gpt;
    use hyntix_partition::mbr;
    use hyntix_storage::{FileBlockDevice, StorageError};
    use std::io::{Read, Seek, SeekFrom};
    use std::sync::atomic::{AtomicU64, Ordering};

    const THREE_GIB_SECTORS: u64 = 3 * 1024 * 1024 * 2;
    const ONE_GIB_SECTORS: u64 = 1024 * 1024 * 2;
    const FAKE_ISO_BYTES: u64 = 2 * 1024 * 1024;

    struct SparseReader {
        data: Vec<u8>,
        len: u64,
        position: u64,
    }

    impl SparseReader {
        fn new(data: Vec<u8>, len: u64) -> Self {
            assert!(len >= data.len() as u64);
            Self {
                data,
                len,
                position: 0,
            }
        }
    }

    impl Read for SparseReader {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.position >= self.len {
                return Ok(0);
            }
            let available = (self.len - self.position) as usize;
            let count = buf.len().min(available);
            for (index, slot) in buf[..count].iter_mut().enumerate() {
                let position = self.position + index as u64;
                *slot = if position < self.data.len() as u64 {
                    self.data[position as usize]
                } else {
                    0
                };
            }
            self.position += count as u64;
            Ok(count)
        }
    }

    impl Seek for SparseReader {
        fn seek(&mut self, from: SeekFrom) -> std::io::Result<u64> {
            let next = match from {
                SeekFrom::Start(value) => value as i128,
                SeekFrom::Current(delta) => self.position as i128 + delta as i128,
                SeekFrom::End(delta) => self.len as i128 + delta as i128,
            };
            if next < 0 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "negative seek",
                ));
            }
            self.position = next as u64;
            Ok(self.position)
        }
    }

    struct FaultDevice {
        inner: FileBlockDevice,
        fail_lba: Option<u64>,
    }

    impl BlockDevice for FaultDevice {
        fn sector_size(&self) -> u32 {
            self.inner.sector_size()
        }

        fn sector_count(&self) -> u64 {
            self.inner.sector_count()
        }

        fn read_sectors(&mut self, lba: u64, out: &mut [u8]) -> hyntix_storage::Result<()> {
            self.inner.read_sectors(lba, out)
        }

        fn write_sectors(&mut self, lba: u64, data: &[u8]) -> hyntix_storage::Result<()> {
            if self.fail_lba == Some(lba) {
                self.fail_lba = None;
                return Err(StorageError::Io(std::io::Error::other(
                    "injected write failure",
                )));
            }
            self.inner.write_sectors(lba, data)
        }
    }

    fn temp_image(name: &str) -> (FileBlockDevice, std::path::PathBuf) {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let unique = NEXT.fetch_add(1, Ordering::Relaxed);
        let path = std::env::temp_dir().join(format!(
            "hyntix-persistence-{name}-{}-{unique}.img",
            std::process::id()
        ));
        let dev = FileBlockDevice::create(&path, THREE_GIB_SECTORS, 512).unwrap();
        (dev, path)
    }

    fn write_iso(dev: &mut dyn BlockDevice, iso: &[u8]) {
        let mut image = iso.to_vec();
        let padded = image.len().div_ceil(512) * 512;
        image.resize(padded, 0);
        dev.write_sectors(0, &image).unwrap();
    }

    fn init_mbr(dev: &mut dyn BlockDevice, iso_sectors: u64) {
        let mut sector0 = [0u8; 512];
        sector0[440..444].copy_from_slice(&[0xDE, 0xAD, 0xBE, 0xEF]);
        sector0[510] = 0x55;
        sector0[511] = 0xAA;
        mbr::set_entry(
            &mut sector0,
            0,
            &mbr::MbrEntry {
                status: 0x80,
                type_byte: 0x83,
                start_lba: 0,
                sector_count: iso_sectors as u32,
            },
        );
        dev.write_sectors(0, &sector0).unwrap();
    }

    fn build_gpt_header(
        current_lba: u64,
        backup_lba: u64,
        first_usable: u64,
        last_usable: u64,
        disk_guid: [u8; 16],
        entry_lba: u64,
        entries_crc: u32,
    ) -> [u8; 512] {
        let mut sector = [0u8; 512];
        sector[..8].copy_from_slice(b"EFI PART");
        sector[8..12].copy_from_slice(&0x0001_0000u32.to_le_bytes());
        sector[12..16].copy_from_slice(&92u32.to_le_bytes());
        sector[24..32].copy_from_slice(&current_lba.to_le_bytes());
        sector[32..40].copy_from_slice(&backup_lba.to_le_bytes());
        sector[40..48].copy_from_slice(&first_usable.to_le_bytes());
        sector[48..56].copy_from_slice(&last_usable.to_le_bytes());
        sector[56..72].copy_from_slice(&disk_guid);
        sector[72..80].copy_from_slice(&entry_lba.to_le_bytes());
        sector[80..84].copy_from_slice(&128u32.to_le_bytes());
        sector[84..88].copy_from_slice(&128u32.to_le_bytes());
        sector[88..92].copy_from_slice(&entries_crc.to_le_bytes());
        let crc = crc32::crc32(&sector[..92]);
        sector[16..20].copy_from_slice(&crc.to_le_bytes());
        sector
    }

    fn init_gpt(
        dev: &mut dyn BlockDevice,
        sector_count: u64,
        iso_start_lba: u64,
        iso_end_lba: u64,
        hybrid_extra: Option<(u64, u64)>,
    ) {
        let mut sector0 = [0u8; 512];
        sector0[440..444].copy_from_slice(&[0xDE, 0xAD, 0xBE, 0xEF]);
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
        if let Some((start, count)) = hybrid_extra {
            mbr::set_entry(
                &mut sector0,
                1,
                &mbr::MbrEntry {
                    status: 0,
                    type_byte: 0x83,
                    start_lba: start as u32,
                    sector_count: count as u32,
                },
            );
        }
        dev.write_sectors(0, &sector0).unwrap();

        let entry_sectors = gpt::entry_sectors(128, 128);
        let backup_lba = sector_count - 1;
        let backup_entries_lba = backup_lba - entry_sectors;
        let last_usable = backup_entries_lba - 1;
        let mut entries = vec![0u8; (entry_sectors * 512) as usize];
        let entry = gpt::GptEntry {
            type_guid: GPT_LINUX_FILESYSTEM_GUID,
            unique_guid: [1u8; 16],
            first_lba: iso_start_lba,
            last_lba: iso_end_lba,
            attributes: 0,
            name: "iso".into(),
        };
        entries[..128].copy_from_slice(&entry.serialize(128).unwrap());
        let entries_crc = crc32::crc32(&entries[..128 * 128]);
        dev.write_sectors(2, &entries).unwrap();
        dev.write_sectors(backup_entries_lba, &entries).unwrap();

        let primary = build_gpt_header(1, backup_lba, 34, last_usable, [7u8; 16], 2, entries_crc);
        let backup = build_gpt_header(
            backup_lba,
            1,
            34,
            last_usable,
            [7u8; 16],
            backup_entries_lba,
            entries_crc,
        );
        dev.write_sectors(1, &primary).unwrap();
        dev.write_sectors(backup_lba, &backup).unwrap();
    }

    fn read_ext2_label(dev: &mut dyn BlockDevice, start_lba: u64) -> [u8; 16] {
        let mut superblock = [0u8; 1024];
        dev.read_at(start_lba * SECTOR_SIZE + 1024, &mut superblock)
            .unwrap();
        assert_eq!(u16::from_le_bytes([superblock[56], superblock[57]]), 0xEF53);
        let mut label = [0u8; 16];
        label.copy_from_slice(&superblock[120..136]);
        label
    }

    #[test]
    fn applies_mbr_casper_persistence_and_patches_grub() {
        let (mut dev, path) = temp_image("mbr");
        let iso = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz quiet splash ---\n}\n",
        ));
        write_iso(&mut dev, &iso);
        init_mbr(&mut dev, ONE_GIB_SECTORS);

        let mut reader = SparseReader::new(iso.clone(), FAKE_ISO_BYTES);
        let mut phases = Vec::new();
        let result = apply(
            &mut dev,
            &mut reader,
            FAKE_ISO_BYTES,
            PersistenceProfile::Casper,
            MIN_PERSISTENCE_BYTES,
            &mut |progress| phases.push(progress.phase),
            &|| false,
        )
        .unwrap();

        assert_eq!(result.profile, PersistenceProfile::Casper);
        assert_eq!(result.action, PersistenceAction::Create);
        assert!(result.patches_applied);
        assert_eq!(result.label, "casper-rw");
        assert_eq!(result.partition_start_lba, ONE_GIB_SECTORS);
        assert_eq!(result.partition_sector_count, ONE_GIB_SECTORS);
        assert!(phases.contains(&PersistencePhase::Planning));
        assert!(phases.contains(&PersistencePhase::Formatting));
        assert!(phases.contains(&PersistencePhase::ActivatingBoot));
        assert!(phases.contains(&PersistencePhase::Partitioning));
        assert!(phases.contains(&PersistencePhase::Verifying));

        let layout = partition::read_layout(&mut dev).unwrap();
        assert_eq!(layout.entries.len(), 2);
        let persistence = layout
            .entries
            .iter()
            .find(|entry| entry.start_lba == ONE_GIB_SECTORS)
            .expect("persistence partition");
        assert_eq!(persistence.type_byte, 0x83);
        assert_eq!(persistence.sector_count, ONE_GIB_SECTORS);
        assert_eq!(
            &read_ext2_label(&mut dev, persistence.start_lba)[..9],
            b"casper-rw"
        );

        let patch_offset = iso
            .windows(12)
            .position(|window| window == b"quiet splash")
            .expect("quiet splash in grub.cfg");
        let mut patched = [0u8; 12];
        dev.read_at(patch_offset as u64, &mut patched).unwrap();
        assert_eq!(&patched, b"persistent  ");

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn applies_gpt_debian_persistence_and_reuses_existing_partition() {
        let (mut dev, path) = temp_image("gpt");
        let iso = build_debian_iso(None);
        write_iso(&mut dev, &iso);
        init_gpt(&mut dev, ONE_GIB_SECTORS, 2048, ONE_GIB_SECTORS - 1, None);

        let mut reader = SparseReader::new(iso.clone(), FAKE_ISO_BYTES);
        let result = apply(
            &mut dev,
            &mut reader,
            FAKE_ISO_BYTES,
            PersistenceProfile::DebianLive,
            0,
            &mut |_| {},
            &|| false,
        )
        .unwrap();

        assert_eq!(result.profile, PersistenceProfile::DebianLive);
        assert_eq!(result.activation, PersistenceActivation::BuiltIn);
        assert_eq!(result.action, PersistenceAction::Create);
        assert!(!result.patches_applied);
        assert_eq!(result.label, "persistence");

        let layout = partition::read_layout(&mut dev).unwrap();
        assert_eq!(layout.entries.len(), 2);
        let persistence = layout
            .entries
            .iter()
            .find(|entry| entry.name == "persistence")
            .expect("persistence partition");
        assert_eq!(persistence.type_guid, GPT_LINUX_FILESYSTEM_GUID);
        assert_eq!(persistence.start_lba, result.partition_start_lba);
        assert_eq!(
            &read_ext2_label(&mut dev, persistence.start_lba)[..11],
            b"persistence"
        );

        let first_start = persistence.start_lba;
        let first_count = persistence.sector_count;

        // A retry must reuse the existing partition rather than appending a
        // second one.
        let mut second_reader = SparseReader::new(iso.clone(), FAKE_ISO_BYTES);
        let second = apply(
            &mut dev,
            &mut second_reader,
            FAKE_ISO_BYTES,
            PersistenceProfile::DebianLive,
            4 * MIN_PERSISTENCE_BYTES,
            &mut |_| {},
            &|| false,
        )
        .unwrap();
        assert_eq!(second.action, PersistenceAction::Reuse);
        assert_eq!(second.partition_start_lba, first_start);
        assert_eq!(second.partition_sector_count, first_count);

        let layout = partition::read_layout(&mut dev).unwrap();
        assert_eq!(layout.entries.len(), 2);
        assert_eq!(
            layout
                .entries
                .iter()
                .filter(|entry| entry.name == "persistence")
                .count(),
            1
        );

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn rolls_back_table_and_patch_when_commit_fails() {
        let (mut dev, path) = temp_image("rollback");
        let iso = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz quiet splash ---\n}\n",
        ));
        write_iso(&mut dev, &iso);
        init_gpt(&mut dev, ONE_GIB_SECTORS, 2048, ONE_GIB_SECTORS - 1, None);
        let before = partition::read_layout(&mut dev).unwrap().entries;

        // Fail the primary GPT header write after the backup has been written.
        let mut faulty = FaultDevice {
            inner: dev,
            fail_lba: Some(1),
        };
        let mut reader = SparseReader::new(iso.clone(), FAKE_ISO_BYTES);
        let error = apply(
            &mut faulty,
            &mut reader,
            FAKE_ISO_BYTES,
            PersistenceProfile::Casper,
            MIN_PERSISTENCE_BYTES,
            &mut |_| {},
            &|| false,
        )
        .unwrap_err();
        assert!(matches!(error, PersistenceError::Partition(_)));

        let after = partition::read_layout(&mut faulty).unwrap().entries;
        assert_eq!(after, before);

        let patch_offset = iso
            .windows(12)
            .position(|window| window == b"quiet splash")
            .expect("quiet splash in grub.cfg");
        let mut restored = [0u8; 12];
        faulty.read_at(patch_offset as u64, &mut restored).unwrap();
        assert_eq!(&restored, b"quiet splash");

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn cancellation_after_boot_patch_restores_patch_before_commit() {
        let (mut dev, path) = temp_image("cancel-patch");
        let iso = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz quiet splash ---\n}\n",
        ));
        write_iso(&mut dev, &iso);
        init_mbr(&mut dev, ONE_GIB_SECTORS);
        let before = partition::read_layout(&mut dev).unwrap().entries;

        // The fourth cancellation check runs after the boot patch is applied
        // and before the partition table is committed.
        let checks = AtomicU64::new(0);
        let mut reader = SparseReader::new(iso.clone(), FAKE_ISO_BYTES);
        let error = apply(
            &mut dev,
            &mut reader,
            FAKE_ISO_BYTES,
            PersistenceProfile::Casper,
            MIN_PERSISTENCE_BYTES,
            &mut |_| {},
            &|| checks.fetch_add(1, Ordering::Relaxed) == 3,
        )
        .unwrap_err();
        assert!(matches!(error, PersistenceError::Cancelled));
        assert_eq!(checks.load(Ordering::Relaxed), 4);

        let after = partition::read_layout(&mut dev).unwrap().entries;
        assert_eq!(after, before);

        let patch_offset = iso
            .windows(12)
            .position(|window| window == b"quiet splash")
            .expect("quiet splash in grub.cfg");
        let mut restored = [0u8; 12];
        dev.read_at(patch_offset as u64, &mut restored).unwrap();
        assert_eq!(&restored, b"quiet splash");

        std::fs::remove_file(&path).ok();
    }

    #[test]
    fn rejects_inconsistent_hybrid_mbr_before_writing() {
        let (mut dev, path) = temp_image("hybrid");
        let iso = build_casper_iso(None);
        write_iso(&mut dev, &iso);
        init_gpt(
            &mut dev,
            ONE_GIB_SECTORS,
            2048,
            ONE_GIB_SECTORS - 1,
            Some((4096, 4096)),
        );

        let mut reader = SparseReader::new(iso, FAKE_ISO_BYTES);
        let error = apply(
            &mut dev,
            &mut reader,
            FAKE_ISO_BYTES,
            PersistenceProfile::Casper,
            MIN_PERSISTENCE_BYTES,
            &mut |_| {},
            &|| false,
        )
        .unwrap_err();
        assert!(matches!(
            error,
            PersistenceError::Partition(PartitionError::HybridMbrUnsupported)
        ));

        let mut magic = [0u8; 2];
        dev.read_at(ONE_GIB_SECTORS * SECTOR_SIZE + 1024 + 56, &mut magic)
            .unwrap();
        assert_ne!(u16::from_le_bytes(magic), 0xEF53);

        std::fs::remove_file(&path).ok();
    }
}
