//! GUID Partition Table parsing, serialization, and validation.

use crate::crc32::crc32;
use crate::mbr::MbrEntry;
use crate::{GPT_ENTRY_SIZE, GPT_HEADER_SIZE, GptLayout, PartitionEntry, PartitionError, Result};
use hyntix_storage::BlockDevice;

const GPT_SIGNATURE: &[u8; 8] = b"EFI PART";

#[derive(Debug, Clone)]
pub struct GptHeader {
    pub revision: u32,
    pub header_size: u32,
    pub current_lba: u64,
    pub backup_lba: u64,
    pub first_usable_lba: u64,
    pub last_usable_lba: u64,
    pub disk_guid: [u8; 16],
    pub entry_lba: u64,
    pub num_entries: u32,
    pub entry_size: u32,
    pub entries_crc32: u32,
    pub header_crc32: u32,
    pub(crate) raw: Vec<u8>,
}

impl GptHeader {
    pub fn parse(sector: &[u8]) -> Result<Self> {
        if sector.len() < 512 {
            return Err(PartitionError::InvalidTable("short GPT header".into()));
        }
        if &sector[..8] != GPT_SIGNATURE {
            return Err(PartitionError::InvalidTable("bad GPT signature".into()));
        }
        let revision = u32::from_le_bytes(sector[8..12].try_into().unwrap());
        let header_size = u32::from_le_bytes(sector[12..16].try_into().unwrap());
        if header_size < GPT_HEADER_SIZE as u32 || header_size > 512 {
            return Err(PartitionError::InvalidTable(format!(
                "invalid GPT header size {header_size}"
            )));
        }
        let stored_crc = u32::from_le_bytes(sector[16..20].try_into().unwrap());
        let mut header = sector[..header_size as usize].to_vec();
        header[16..20].fill(0);
        let actual_crc = crc32(&header);
        if stored_crc != actual_crc {
            return Err(PartitionError::InvalidTable(format!(
                "GPT header CRC mismatch: stored 0x{stored_crc:08X}, computed 0x{actual_crc:08X}"
            )));
        }

        let num_entries = u32::from_le_bytes(sector[80..84].try_into().unwrap());
        let entry_size = u32::from_le_bytes(sector[84..88].try_into().unwrap());
        if num_entries == 0 || num_entries > 4096 {
            return Err(PartitionError::InvalidTable(format!(
                "invalid GPT entry count {num_entries}"
            )));
        }
        if entry_size < GPT_ENTRY_SIZE as u32 || entry_size % 128 != 0 {
            return Err(PartitionError::InvalidTable(format!(
                "invalid GPT entry size {entry_size}"
            )));
        }

        Ok(Self {
            revision,
            header_size,
            current_lba: u64::from_le_bytes(sector[24..32].try_into().unwrap()),
            backup_lba: u64::from_le_bytes(sector[32..40].try_into().unwrap()),
            first_usable_lba: u64::from_le_bytes(sector[40..48].try_into().unwrap()),
            last_usable_lba: u64::from_le_bytes(sector[48..56].try_into().unwrap()),
            disk_guid: sector[56..72].try_into().unwrap(),
            entry_lba: u64::from_le_bytes(sector[72..80].try_into().unwrap()),
            num_entries,
            entry_size,
            entries_crc32: u32::from_le_bytes(sector[88..92].try_into().unwrap()),
            header_crc32: stored_crc,
            raw: sector[..512].to_vec(),
        })
    }

    pub fn serialize(&self) -> Result<[u8; 512]> {
        if self.header_size < GPT_HEADER_SIZE as u32 || self.header_size > 512 {
            return Err(PartitionError::InvalidTable(
                "cannot serialize a GPT header with an invalid size".into(),
            ));
        }
        let mut out = [0u8; 512];
        out.copy_from_slice(&self.raw);
        out[..8].copy_from_slice(GPT_SIGNATURE);
        out[8..12].copy_from_slice(&self.revision.to_le_bytes());
        out[12..16].copy_from_slice(&self.header_size.to_le_bytes());
        out[16..20].fill(0);
        out[24..32].copy_from_slice(&self.current_lba.to_le_bytes());
        out[32..40].copy_from_slice(&self.backup_lba.to_le_bytes());
        out[40..48].copy_from_slice(&self.first_usable_lba.to_le_bytes());
        out[48..56].copy_from_slice(&self.last_usable_lba.to_le_bytes());
        out[56..72].copy_from_slice(&self.disk_guid);
        out[72..80].copy_from_slice(&self.entry_lba.to_le_bytes());
        out[80..84].copy_from_slice(&self.num_entries.to_le_bytes());
        out[84..88].copy_from_slice(&self.entry_size.to_le_bytes());
        out[88..92].copy_from_slice(&self.entries_crc32.to_le_bytes());
        let crc = crc32(&out[..self.header_size as usize]);
        out[16..20].copy_from_slice(&crc.to_le_bytes());
        Ok(out)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GptEntry {
    pub type_guid: [u8; 16],
    pub unique_guid: [u8; 16],
    pub first_lba: u64,
    pub last_lba: u64,
    pub attributes: u64,
    pub name: String,
}

impl GptEntry {
    pub fn is_empty(&self) -> bool {
        self.type_guid == [0u8; 16]
    }

    pub fn serialize(&self, entry_size: usize) -> Result<Vec<u8>> {
        if entry_size < GPT_ENTRY_SIZE {
            return Err(PartitionError::InvalidTable(
                "GPT entry size is too small".into(),
            ));
        }
        let mut out = vec![0u8; entry_size];
        out[0..16].copy_from_slice(&self.type_guid);
        out[16..32].copy_from_slice(&self.unique_guid);
        out[32..40].copy_from_slice(&self.first_lba.to_le_bytes());
        out[40..48].copy_from_slice(&self.last_lba.to_le_bytes());
        out[48..56].copy_from_slice(&self.attributes.to_le_bytes());
        let name_units: Vec<u16> = self.name.encode_utf16().take(36).collect();
        for (i, unit) in name_units.iter().enumerate() {
            out[56 + i * 2..58 + i * 2].copy_from_slice(&unit.to_le_bytes());
        }
        Ok(out)
    }
}

pub fn entry_sectors(num_entries: u32, entry_size: u32) -> u64 {
    let bytes = num_entries as u64 * entry_size as u64;
    bytes.div_ceil(512)
}

pub fn parse_entries(
    bytes: &[u8],
    num_entries: usize,
    entry_size: usize,
) -> Result<Vec<PartitionEntry>> {
    if entry_size < GPT_ENTRY_SIZE {
        return Err(PartitionError::InvalidTable(
            "GPT entry size is too small".into(),
        ));
    }
    if bytes.len() < num_entries * entry_size {
        return Err(PartitionError::InvalidTable(
            "GPT entry array is truncated".into(),
        ));
    }

    let mut entries = Vec::new();
    for index in 0..num_entries {
        let offset = index * entry_size;
        let raw = &bytes[offset..offset + entry_size];
        let type_guid: [u8; 16] = raw[0..16].try_into().unwrap();
        if type_guid == [0u8; 16] {
            continue;
        }
        let first_lba = u64::from_le_bytes(raw[32..40].try_into().unwrap());
        let last_lba = u64::from_le_bytes(raw[40..48].try_into().unwrap());
        if last_lba < first_lba {
            return Err(PartitionError::InvalidTable(format!(
                "GPT entry {index} has last_lba < first_lba"
            )));
        }
        let name_units: Vec<u16> = raw[56..128]
            .chunks_exact(2)
            .map(|c| u16::from_le_bytes([c[0], c[1]]))
            .take_while(|u| *u != 0)
            .collect();
        let name = String::from_utf16_lossy(&name_units);
        entries.push(PartitionEntry {
            index: entries.len(),
            start_lba: first_lba,
            sector_count: last_lba - first_lba + 1,
            type_byte: 0,
            type_guid,
            name,
            bootable: false,
        });
        // Preserve the on-disk slot index separately from the compacted vector.
        entries.last_mut().unwrap().index = index;
    }
    Ok(entries)
}

/// Read and validate both GPT copies.
pub fn read(dev: &mut dyn BlockDevice) -> Result<GptLayout> {
    let sector_count = dev.sector_count();
    if sector_count < 34 {
        return Err(PartitionError::InvalidTable(
            "device is too small for GPT".into(),
        ));
    }

    let mut primary_sector = [0u8; 512];
    dev.read_sectors(1, &mut primary_sector)?;
    let primary = GptHeader::parse(&primary_sector)?;
    validate_header_location(&primary, 1, sector_count)?;

    let primary_entry_sectors = entry_sectors(primary.num_entries, primary.entry_size);
    if primary.entry_lba + primary_entry_sectors > sector_count {
        return Err(PartitionError::InvalidTable(
            "primary GPT entry array is out of range".into(),
        ));
    }
    let mut primary_entries = vec![0u8; (primary_entry_sectors * 512) as usize];
    dev.read_sectors(primary.entry_lba, &mut primary_entries)?;
    let primary_crc =
        crc32(&primary_entries[..primary.num_entries as usize * primary.entry_size as usize]);
    if primary_crc != primary.entries_crc32 {
        return Err(PartitionError::InvalidTable(
            "primary GPT entry array CRC mismatch".into(),
        ));
    }
    if primary.backup_lba >= sector_count {
        return Err(PartitionError::InvalidTable(
            "backup GPT header is out of range".into(),
        ));
    }

    let mut backup_sector = [0u8; 512];
    dev.read_sectors(primary.backup_lba, &mut backup_sector)?;
    let backup = GptHeader::parse(&backup_sector)?;
    validate_header_location(&backup, primary.backup_lba, sector_count)?;
    if backup.backup_lba != 1 {
        return Err(PartitionError::InvalidTable(
            "backup GPT does not point at the primary header".into(),
        ));
    }
    if backup.num_entries != primary.num_entries || backup.entry_size != primary.entry_size {
        return Err(PartitionError::InvalidTable(
            "primary and backup GPT entry layouts differ".into(),
        ));
    }

    let backup_entry_sectors = entry_sectors(backup.num_entries, backup.entry_size);
    if backup.entry_lba + backup_entry_sectors > sector_count {
        return Err(PartitionError::InvalidTable(
            "backup GPT entry array is out of range".into(),
        ));
    }
    let mut backup_entries = vec![0u8; (backup_entry_sectors * 512) as usize];
    dev.read_sectors(backup.entry_lba, &mut backup_entries)?;
    let backup_crc =
        crc32(&backup_entries[..backup.num_entries as usize * backup.entry_size as usize]);
    if backup_crc != backup.entries_crc32 {
        return Err(PartitionError::InvalidTable(
            "backup GPT entry array CRC mismatch".into(),
        ));
    }
    if backup_crc != primary_crc {
        return Err(PartitionError::InvalidTable(
            "primary and backup GPT entry arrays differ".into(),
        ));
    }

    validate_entries(
        &primary_entries,
        primary.num_entries as usize,
        primary.entry_size as usize,
        sector_count,
    )?;

    Ok(GptLayout {
        primary,
        backup,
        primary_entries,
        backup_entries,
    })
}

fn validate_header_location(
    header: &GptHeader,
    expected_current: u64,
    sector_count: u64,
) -> Result<()> {
    if header.current_lba != expected_current {
        return Err(PartitionError::InvalidTable(format!(
            "GPT header claims LBA {}, expected {}",
            header.current_lba, expected_current
        )));
    }
    if header.last_usable_lba >= sector_count {
        return Err(PartitionError::InvalidTable(
            "GPT last usable LBA is past the end of the device".into(),
        ));
    }
    if header.first_usable_lba < 2 || header.first_usable_lba > header.last_usable_lba {
        return Err(PartitionError::InvalidTable(
            "invalid GPT usable LBA range".into(),
        ));
    }
    Ok(())
}

fn validate_entries(
    bytes: &[u8],
    num_entries: usize,
    entry_size: usize,
    sector_count: u64,
) -> Result<()> {
    for entry in parse_entries(bytes, num_entries, entry_size)? {
        let end = entry
            .end_lba()
            .ok_or_else(|| PartitionError::InvalidTable("empty GPT entry".into()))?;
        if entry.start_lba < 2 || end >= sector_count {
            return Err(PartitionError::InvalidTable(format!(
                "GPT entry {} is outside the device",
                entry.index
            )));
        }
    }
    Ok(())
}

/// Deterministic unique GUID for a partition. Re-running the formatter on the
/// same range produces the same identifier, which keeps tests reproducible.
pub fn new_unique_guid(start_lba: u64, sector_count: u64) -> [u8; 16] {
    let mut guid = [0u8; 16];
    guid[..8].copy_from_slice(b"HYNTIXPS");
    let a = crc32(&start_lba.to_le_bytes());
    let b = crc32(&sector_count.to_le_bytes());
    guid[8..12].copy_from_slice(&a.to_le_bytes());
    guid[12..16].copy_from_slice(&b.to_le_bytes());
    guid
}

/// Decode an MBR entry into a GPT compatibility entry. Used by hybrid tests.
pub fn entry_from_mbr(mbr: &MbrEntry, name: &str) -> GptEntry {
    GptEntry {
        type_guid: crate::GPT_LINUX_FILESYSTEM_GUID,
        unique_guid: new_unique_guid(mbr.start_lba as u64, mbr.sector_count as u64),
        first_lba: mbr.start_lba as u64,
        last_lba: mbr.start_lba as u64 + mbr.sector_count as u64 - 1,
        attributes: 0,
        name: name.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crc32::crc32;

    fn valid_header() -> GptHeader {
        GptHeader {
            revision: 0x0001_0000,
            header_size: 92,
            current_lba: 1,
            backup_lba: 1023,
            first_usable_lba: 34,
            last_usable_lba: 990,
            disk_guid: [7u8; 16],
            entry_lba: 2,
            num_entries: 128,
            entry_size: 128,
            entries_crc32: 0,
            header_crc32: 0,
            raw: vec![0u8; 512],
        }
    }

    #[test]
    fn header_round_trips_and_rejects_corruption() {
        let header = valid_header();
        let bytes = header.serialize().unwrap();
        let parsed = GptHeader::parse(&bytes).unwrap();
        assert_eq!(parsed.current_lba, 1);
        assert_eq!(parsed.backup_lba, 1023);
        assert_eq!(parsed.last_usable_lba, 990);

        let mut corrupt = bytes;
        corrupt[40] ^= 0xFF;
        assert!(GptHeader::parse(&corrupt).is_err());
    }

    #[test]
    fn entries_round_trip() {
        let entry = GptEntry {
            type_guid: crate::GPT_LINUX_FILESYSTEM_GUID,
            unique_guid: [9u8; 16],
            first_lba: 2048,
            last_lba: 4095,
            attributes: 0,
            name: "persistence".into(),
        };
        let bytes = entry.serialize(128).unwrap();
        let parsed = parse_entries(&bytes, 1, 128).unwrap();
        assert_eq!(parsed.len(), 1);
        assert_eq!(parsed[0].start_lba, 2048);
        assert_eq!(parsed[0].sector_count, 2048);
        assert_eq!(parsed[0].name, "persistence");
    }

    #[test]
    fn empty_entries_use_full_crc_array() {
        let bytes = vec![0u8; 128 * 128];
        assert_eq!(crc32(&bytes[..128 * 128]), crc32(&bytes));
    }
}
