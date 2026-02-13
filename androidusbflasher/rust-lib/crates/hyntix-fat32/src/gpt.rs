//! GPT (GUID Partition Table) creation.
//!
//! Creates a GPT partition table with a single EFI System Partition
//! for UEFI boot support.

use byteorder::{LittleEndian, WriteBytesExt};
use hyntix_common::{Error, Result};
use std::io::{Seek, SeekFrom, Write};
use uuid::Uuid;

/// Sector size in bytes.
pub const SECTOR_SIZE: u64 = 512;

/// GPT signature "EFI PART".
const GPT_SIGNATURE: u64 = 0x5452415020494645;

/// GPT header size.
const GPT_HEADER_SIZE: u32 = 92;

/// GPT revision 1.0.
const GPT_REVISION: u32 = 0x00010000;

/// Number of partition entries.
const PARTITION_ENTRIES: u32 = 128;

/// Size of each partition entry.
const PARTITION_ENTRY_SIZE: u32 = 128;

/// Microsoft Basic Data Partition GUID.
const BASIC_DATA_TYPE_GUID: Uuid = Uuid::from_bytes([
    0xa2, 0xa0, 0xd0, 0xeb, 0xe5, 0xb9, 0x33, 0x44, 0x87, 0xc0, 0x68, 0xb6, 0xb7, 0x26, 0x99, 0xc7,
]);

/// Protective MBR partition type for GPT.
const GPT_PROTECTIVE_MBR: u8 = 0xEE;

/// GPT partition table.
#[derive(Debug)]
pub struct GptTable {
    /// Disk GUID.
    pub disk_guid: Uuid,
    /// Total device size in bytes.
    pub device_size: u64,
    /// Partitions.
    pub partitions: Vec<GptPartition>,
}

/// A GPT partition entry.
#[derive(Debug, Clone)]
pub struct GptPartition {
    /// Partition type GUID.
    pub type_guid: Uuid,
    /// Unique partition GUID.
    pub partition_guid: Uuid,
    /// Starting LBA.
    pub start_lba: u64,
    /// Ending LBA (inclusive).
    pub end_lba: u64,
    /// Partition attributes.
    pub attributes: u64,
    /// Partition name (UTF-16LE, max 36 chars).
    pub name: String,
}

impl GptTable {
    /// Create a new GPT with a single EFI System Partition.
    ///
    /// The ESP will use all available space for maximum compatibility
    /// with large Windows ISOs.
    pub fn new_with_esp(device_size: u64) -> Result<Self> {
        if device_size < 512 * 1024 * 1024 {
            return Err(Error::DeviceTooSmall {
                required: 512 * 1024 * 1024,
                available: device_size,
            });
        }

        let disk_guid = Uuid::new_v4();
        let partition_guid = Uuid::new_v4();

        // Calculate partition boundaries
        // LBA 0: Protective MBR
        // LBA 1: GPT Header
        // LBA 2-33: Partition entries (128 entries * 128 bytes = 16KB = 32 sectors)
        // LBA 34+: Data partitions
        // Last 33 sectors: Backup GPT

        let total_sectors = device_size / SECTOR_SIZE;
        let _first_usable_lba = 34;
        let last_usable_lba = total_sectors - 34;

        // Align partition start to 2048 sectors (1MB boundary) for performance
        let partition_start = 2048;
        let partition_end = last_usable_lba;

        let esp = GptPartition {
            type_guid: BASIC_DATA_TYPE_GUID,
            partition_guid,
            start_lba: partition_start,
            end_lba: partition_end,
            attributes: 0, // No special attributes
            name: "Microsoft Basic Data".to_string(),
        };

        Ok(Self {
            disk_guid,
            device_size,
            partitions: vec![esp],
        })
    }

    /// Write the GPT to a device.
    pub fn write<W: Write + Seek>(&self, writer: &mut W) -> Result<()> {
        let total_sectors = self.device_size / SECTOR_SIZE;

        // Write Protective MBR
        self.write_protective_mbr(writer, total_sectors)?;

        // Write Primary GPT Header (LBA 1)
        self.write_gpt_header(writer, 1, total_sectors - 1, 2)?;

        // Write Primary Partition Entries (LBA 2-33)
        let entries_crc = self.write_partition_entries(writer, 2)?;

        // Update header CRC and rewrite
        self.write_gpt_header_with_crc(writer, 1, total_sectors - 1, 2, entries_crc)?;

        // Write Backup Partition Entries
        let backup_entries_lba = total_sectors - 33;
        self.write_partition_entries(writer, backup_entries_lba)?;

        // Write Backup GPT Header (last sector)
        let backup_header_lba = total_sectors - 1;
        self.write_gpt_header_with_crc(
            writer,
            backup_header_lba,
            1, // Backup points to primary
            backup_entries_lba,
            entries_crc,
        )?;

        Ok(())
    }

    /// Write a protective MBR.
    fn write_protective_mbr<W: Write + Seek>(
        &self,
        writer: &mut W,
        total_sectors: u64,
    ) -> Result<()> {
        writer.seek(SeekFrom::Start(0))?;

        let mut mbr = [0u8; 512];

        // Boot code area (0-445) - leave as zeros

        // Partition entry 1 (offset 446, 16 bytes)
        let partition_offset = 446;

        // Boot indicator (not bootable)
        mbr[partition_offset] = 0x00;

        // Starting CHS (not used for GPT)
        mbr[partition_offset + 1] = 0x00;
        mbr[partition_offset + 2] = 0x02;
        mbr[partition_offset + 3] = 0x00;

        // Partition type (GPT Protective)
        mbr[partition_offset + 4] = GPT_PROTECTIVE_MBR;

        // Ending CHS (maximum value)
        mbr[partition_offset + 5] = 0xFF;
        mbr[partition_offset + 6] = 0xFF;
        mbr[partition_offset + 7] = 0xFF;

        // Starting LBA (1)
        mbr[partition_offset + 8] = 0x01;
        mbr[partition_offset + 9] = 0x00;
        mbr[partition_offset + 10] = 0x00;
        mbr[partition_offset + 11] = 0x00;

        // Size in sectors (entire disk or max 32-bit)
        let size = std::cmp::min(total_sectors - 1, 0xFFFFFFFF) as u32;
        mbr[partition_offset + 12..partition_offset + 16].copy_from_slice(&size.to_le_bytes());

        // MBR signature
        mbr[510] = 0x55;
        mbr[511] = 0xAA;

        writer.write_all(&mbr)?;
        Ok(())
    }

    /// Write GPT header.
    fn write_gpt_header<W: Write + Seek>(
        &self,
        writer: &mut W,
        header_lba: u64,
        backup_lba: u64,
        entries_lba: u64,
    ) -> Result<()> {
        self.write_gpt_header_with_crc(writer, header_lba, backup_lba, entries_lba, 0)
    }

    /// Write GPT header with CRC values.
    fn write_gpt_header_with_crc<W: Write + Seek>(
        &self,
        writer: &mut W,
        header_lba: u64,
        backup_lba: u64,
        entries_lba: u64,
        entries_crc: u32,
    ) -> Result<()> {
        writer.seek(SeekFrom::Start(header_lba * SECTOR_SIZE))?;

        let total_sectors = self.device_size / SECTOR_SIZE;
        let first_usable = 34u64;
        let last_usable = total_sectors - 34;

        let mut header = Vec::with_capacity(512);

        // Signature
        header.write_u64::<LittleEndian>(GPT_SIGNATURE)?;

        // Revision
        header.write_u32::<LittleEndian>(GPT_REVISION)?;

        // Header size
        header.write_u32::<LittleEndian>(GPT_HEADER_SIZE)?;

        // Header CRC32 (placeholder, calculated later)
        header.write_u32::<LittleEndian>(0)?;

        // Reserved
        header.write_u32::<LittleEndian>(0)?;

        // Current LBA
        header.write_u64::<LittleEndian>(header_lba)?;

        // Backup LBA
        header.write_u64::<LittleEndian>(backup_lba)?;

        // First usable LBA
        header.write_u64::<LittleEndian>(first_usable)?;

        // Last usable LBA
        header.write_u64::<LittleEndian>(last_usable)?;

        // Disk GUID
        header.extend_from_slice(self.disk_guid.as_bytes());

        // Partition entries starting LBA
        header.write_u64::<LittleEndian>(entries_lba)?;

        // Number of partition entries
        header.write_u32::<LittleEndian>(PARTITION_ENTRIES)?;

        // Size of each partition entry
        header.write_u32::<LittleEndian>(PARTITION_ENTRY_SIZE)?;

        // Partition entries CRC32
        header.write_u32::<LittleEndian>(entries_crc)?;

        // Calculate header CRC (over first 92 bytes)
        let header_crc = crc32(&header[..GPT_HEADER_SIZE as usize]);
        header[16..20].copy_from_slice(&header_crc.to_le_bytes());

        // Pad to sector size
        header.resize(512, 0);

        writer.write_all(&header)?;
        Ok(())
    }

    /// Write partition entries and return CRC.
    fn write_partition_entries<W: Write + Seek>(
        &self,
        writer: &mut W,
        start_lba: u64,
    ) -> Result<u32> {
        writer.seek(SeekFrom::Start(start_lba * SECTOR_SIZE))?;

        let total_size = (PARTITION_ENTRIES * PARTITION_ENTRY_SIZE) as usize;
        let mut entries = vec![0u8; total_size];

        for (i, partition) in self.partitions.iter().enumerate() {
            let offset = i * PARTITION_ENTRY_SIZE as usize;

            // Type GUID (mixed-endian)
            entries[offset..offset + 16].copy_from_slice(partition.type_guid.as_bytes());

            // Partition GUID (mixed-endian)
            entries[offset + 16..offset + 32].copy_from_slice(partition.partition_guid.as_bytes());

            // Starting LBA
            entries[offset + 32..offset + 40].copy_from_slice(&partition.start_lba.to_le_bytes());

            // Ending LBA
            entries[offset + 40..offset + 48].copy_from_slice(&partition.end_lba.to_le_bytes());

            // Attributes
            entries[offset + 48..offset + 56].copy_from_slice(&partition.attributes.to_le_bytes());

            // Name (UTF-16LE, 72 bytes max)
            let name_utf16: Vec<u16> = partition.name.encode_utf16().take(36).collect();
            for (j, c) in name_utf16.iter().enumerate() {
                let name_offset = offset + 56 + j * 2;
                entries[name_offset..name_offset + 2].copy_from_slice(&c.to_le_bytes());
            }
        }

        let crc = crc32(&entries);
        writer.write_all(&entries)?;

        Ok(crc)
    }

    /// Get the starting offset of the ESP in bytes.
    pub fn esp_offset(&self) -> u64 {
        self.partitions[0].start_lba * SECTOR_SIZE
    }

    /// Get the size of the ESP in bytes.
    pub fn esp_size(&self) -> u64 {
        let esp = &self.partitions[0];
        (esp.end_lba - esp.start_lba + 1) * SECTOR_SIZE
    }
}

/// Calculate CRC32 using the standard polynomial.
fn crc32(data: &[u8]) -> u32 {
    use crc::{Crc, CRC_32_ISO_HDLC};
    const CRC: Crc<u32> = Crc::<u32>::new(&CRC_32_ISO_HDLC);
    CRC.checksum(data)
}
