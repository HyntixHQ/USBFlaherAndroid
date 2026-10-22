//! Legacy Master Boot Record parsing and editing.

use crate::{PartitionError, Result, SECTOR_SIZE};

pub const MBR_SIGNATURE_OFFSET: usize = 510;
pub const MBR_ENTRY_OFFSET: usize = 446;
pub const MBR_ENTRY_SIZE: usize = 16;
pub const PROTECTIVE_TYPE: u8 = 0xEE;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MbrEntry {
    pub status: u8,
    pub type_byte: u8,
    pub start_lba: u32,
    pub sector_count: u32,
}

/// Parse the four primary entries. The signature is validated by the caller.
pub fn parse_entries(sector0: &[u8; SECTOR_SIZE as usize]) -> [MbrEntry; 4] {
    let mut entries = [MbrEntry {
        status: 0,
        type_byte: 0,
        start_lba: 0,
        sector_count: 0,
    }; 4];
    for (i, entry) in entries.iter_mut().enumerate() {
        let offset = MBR_ENTRY_OFFSET + i * MBR_ENTRY_SIZE;
        entry.status = sector0[offset];
        entry.type_byte = sector0[offset + 4];
        entry.start_lba = u32::from_le_bytes([
            sector0[offset + 8],
            sector0[offset + 9],
            sector0[offset + 10],
            sector0[offset + 11],
        ]);
        entry.sector_count = u32::from_le_bytes([
            sector0[offset + 12],
            sector0[offset + 13],
            sector0[offset + 14],
            sector0[offset + 15],
        ]);
    }
    entries
}

/// Write one entry while preserving boot code, the disk signature, the other
/// entries, and the 0x55AA signature.
pub fn set_entry(sector0: &mut [u8; SECTOR_SIZE as usize], index: usize, entry: &MbrEntry) {
    assert!(index < 4);
    let offset = MBR_ENTRY_OFFSET + index * MBR_ENTRY_SIZE;
    sector0[offset] = entry.status;
    sector0[offset + 1] = 0;
    sector0[offset + 2] = 0;
    sector0[offset + 3] = 0;
    sector0[offset + 4] = entry.type_byte;
    sector0[offset + 5] = 0;
    sector0[offset + 6] = 0;
    sector0[offset + 7] = 0;
    sector0[offset + 8..offset + 12].copy_from_slice(&entry.start_lba.to_le_bytes());
    sector0[offset + 12..offset + 16].copy_from_slice(&entry.sector_count.to_le_bytes());
    sector0[MBR_SIGNATURE_OFFSET] = 0x55;
    sector0[MBR_SIGNATURE_OFFSET + 1] = 0xAA;
}

/// Reject a table that cannot be edited safely.
pub fn validate(entries: &[MbrEntry; 4]) -> Result<()> {
    for entry in entries {
        if entry.sector_count > 0 && entry.type_byte == 0 {
            return Err(PartitionError::InvalidTable(
                "non-empty MBR entry has type 0x00".into(),
            ));
        }
    }
    Ok(())
}
