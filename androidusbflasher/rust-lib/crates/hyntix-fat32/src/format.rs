//! FAT32 filesystem formatter.
//!
//! Creates a FAT32 filesystem with proper boot sector, FAT tables,
//! and root directory structures.

use hyntix_common::Result;
use std::io::{Seek, SeekFrom, Write};

/// Bytes per sector (always 512 for USB drives).
pub const BYTES_PER_SECTOR: u32 = 512;

/// FAT32 media type for removable media.
const MEDIA_TYPE: u8 = 0xF8;

/// FAT32 end-of-chain marker.
pub const FAT32_EOC: u32 = 0x0FFFFFFF;

/// FAT32 Boot Sector and BPB.
#[derive(Debug, Clone)]
pub struct Fat32BootSector {
    /// OEM name (8 bytes).
    pub oem_name: [u8; 8],
    /// Bytes per sector.
    pub bytes_per_sector: u16,
    /// Sectors per cluster.
    pub sectors_per_cluster: u8,
    /// Reserved sector count (including boot sector).
    pub reserved_sectors: u16,
    /// Number of FAT copies.
    pub fat_count: u8,
    /// Total sectors (32-bit for FAT32).
    pub total_sectors: u32,
    /// Sectors per FAT.
    pub sectors_per_fat: u32,
    /// Root directory cluster.
    pub root_cluster: u32,
    /// FSInfo sector number.
    pub fsinfo_sector: u16,
    /// Backup boot sector location.
    pub backup_boot_sector: u16,
    /// Volume serial number.
    pub volume_serial: u32,
    /// Volume label (11 bytes).
    pub volume_label: [u8; 11],
}

impl Fat32BootSector {
    /// Create a new FAT32 boot sector for the given partition size.
    pub fn new(partition_size: u64, volume_label: &str) -> Result<Self> {
        let total_sectors = (partition_size / BYTES_PER_SECTOR as u64) as u32;

        // Determine sectors per cluster based on partition size
        // Microsoft recommendations for FAT32
        let sectors_per_cluster: u8 = match partition_size {
            0..=67108864 => 1,            // Up to 64MB: 512B clusters
            67108865..=134217728 => 2,    // 64MB-128MB: 1KB clusters
            134217729..=268435456 => 4,   // 128MB-256MB: 2KB clusters
            268435457..=8589934592 => 32, // 256MB-8GB: 16KB clusters
            _ => 64,                      // >8GB: Cap at 32KB clusters for UEFI compatibility
        };

        // Reserved sectors: 32 is standard for FAT32
        let reserved_sectors: u16 = 32;

        // Number of FAT copies (always 2 for reliability)
        let fat_count: u8 = 2;

        // Calculate sectors per FAT
        // Formula: sectors_per_fat = (total_sectors - reserved) / (cluster_size * 128 + 2)
        let cluster_size = sectors_per_cluster as u32;
        let data_sectors = total_sectors - reserved_sectors as u32;
        let cluster_count = data_sectors / cluster_size;

        // Each FAT entry is 4 bytes, 128 entries per sector
        let sectors_per_fat = (cluster_count + 127) / 128 + 1;

        // Generate volume serial number from timestamp-like value
        let volume_serial = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_secs() as u32)
            .unwrap_or(0x12345678);

        // Format volume label (11 bytes, space-padded)
        let mut label = [0x20u8; 11]; // Space-padded
        let label_bytes = volume_label.as_bytes();
        let copy_len = std::cmp::min(label_bytes.len(), 11);
        label[..copy_len].copy_from_slice(&label_bytes[..copy_len]);

        Ok(Self {
            oem_name: *b"MSDOS5.0",
            bytes_per_sector: BYTES_PER_SECTOR as u16,
            sectors_per_cluster,
            reserved_sectors,
            fat_count,
            total_sectors,
            sectors_per_fat,
            root_cluster: 2, // First data cluster
            fsinfo_sector: 1,
            backup_boot_sector: 6,
            volume_serial,
            volume_label: label,
        })
    }

    /// Write the boot sector to a writer.
    pub fn write<W: Write + Seek>(&self, writer: &mut W, offset: u64) -> Result<()> {
        writer.seek(SeekFrom::Start(offset))?;

        let mut sector = [0u8; 512];

        // Jump instruction
        sector[0] = 0xEB;
        sector[1] = 0x58; // Jump to boot code
        sector[2] = 0x90; // NOP

        // OEM name (bytes 3-10)
        sector[3..11].copy_from_slice(&self.oem_name);

        // BPB (BIOS Parameter Block)
        // Bytes per sector (11-12)
        sector[11..13].copy_from_slice(&self.bytes_per_sector.to_le_bytes());

        // Sectors per cluster (13)
        sector[13] = self.sectors_per_cluster;

        // Reserved sectors (14-15)
        sector[14..16].copy_from_slice(&self.reserved_sectors.to_le_bytes());

        // Number of FATs (16)
        sector[16] = self.fat_count;

        // Root entry count (17-18) - 0 for FAT32
        sector[17] = 0;
        sector[18] = 0;

        // Total sectors 16-bit (19-20) - 0 for FAT32
        sector[19] = 0;
        sector[20] = 0;

        // Media type (21)
        sector[21] = MEDIA_TYPE;

        // FAT size 16-bit (22-23) - 0 for FAT32
        sector[22] = 0;
        sector[23] = 0;

        // Sectors per track (24-25) - not used for USB
        sector[24..26].copy_from_slice(&63u16.to_le_bytes());

        // Number of heads (26-27)
        sector[26..28].copy_from_slice(&255u16.to_le_bytes());

        // Hidden sectors (28-31)
        sector[28..32].copy_from_slice(&0u32.to_le_bytes());

        // Total sectors 32-bit (32-35)
        sector[32..36].copy_from_slice(&self.total_sectors.to_le_bytes());

        // FAT32 Extended BPB
        // Sectors per FAT (36-39)
        sector[36..40].copy_from_slice(&self.sectors_per_fat.to_le_bytes());

        // Extended flags (40-41)
        sector[40..42].copy_from_slice(&0u16.to_le_bytes());

        // FS version (42-43)
        sector[42..44].copy_from_slice(&0u16.to_le_bytes());

        // Root cluster (44-47)
        sector[44..48].copy_from_slice(&self.root_cluster.to_le_bytes());

        // FSInfo sector (48-49)
        sector[48..50].copy_from_slice(&self.fsinfo_sector.to_le_bytes());

        // Backup boot sector (50-51)
        sector[50..52].copy_from_slice(&self.backup_boot_sector.to_le_bytes());

        // Reserved (52-63)
        // Already zeros

        // Drive number (64)
        sector[64] = 0x80; // Fixed disk

        // Reserved (65)
        sector[65] = 0;

        // Extended boot signature (66)
        sector[66] = 0x29;

        // Volume serial number (67-70)
        sector[67..71].copy_from_slice(&self.volume_serial.to_le_bytes());

        // Volume label (71-81)
        sector[71..82].copy_from_slice(&self.volume_label);

        // Filesystem type (82-89)
        sector[82..90].copy_from_slice(b"FAT32   ");

        // Boot code (90-509)
        // Simple boot code that prints "Not bootable"
        let boot_message = b"This is not a bootable disk.\r\n";
        sector[90..90 + boot_message.len()].copy_from_slice(boot_message);

        // Boot signature (510-511)
        sector[510] = 0x55;
        sector[511] = 0xAA;

        writer.write_all(&sector)?;

        // Write FSInfo sector
        self.write_fsinfo(writer, offset)?;

        // Write backup boot sector
        writer.seek(SeekFrom::Start(
            offset + self.backup_boot_sector as u64 * 512,
        ))?;
        writer.write_all(&sector)?;

        Ok(())
    }

    /// Write FSInfo sector.
    fn write_fsinfo<W: Write + Seek>(&self, writer: &mut W, offset: u64) -> Result<()> {
        writer.seek(SeekFrom::Start(offset + self.fsinfo_sector as u64 * 512))?;

        let mut sector = [0u8; 512];

        // Lead signature
        sector[0..4].copy_from_slice(&0x41615252u32.to_le_bytes());

        // Reserved (all zeros, bytes 4-483)

        // Structure signature
        sector[484..488].copy_from_slice(&0x61417272u32.to_le_bytes());

        // Free cluster count (unknown = 0xFFFFFFFF)
        sector[488..492].copy_from_slice(&0xFFFFFFFFu32.to_le_bytes());

        // Next free cluster (hint)
        sector[492..496].copy_from_slice(&3u32.to_le_bytes());

        // Reserved (bytes 496-509)

        // Trail signature
        sector[508..512].copy_from_slice(&0xAA550000u32.to_le_bytes());

        writer.write_all(&sector)?;

        Ok(())
    }

    /// Write the FAT tables.
    pub fn write_fat<W: Write + Seek>(&self, writer: &mut W, offset: u64) -> Result<()> {
        let fat_offset = offset + self.reserved_sectors as u64 * 512;

        // Use a 128KB chunk for faster zeroing
        const CHUNK_SIZE: usize = 128 * 1024;
        let chunk_buffer = vec![0u8; CHUNK_SIZE];

        // Write both FAT copies
        for i in 0..self.fat_count {
            let fat_start = fat_offset + i as u64 * self.sectors_per_fat as u64 * 512;
            writer.seek(SeekFrom::Start(fat_start))?;

            // Initialize first sector of FAT with media type and root directory
            let mut first_sector = [0u8; 512];
            // FAT[0] = media type (bits 0-7) + bits 8-31 are all 1s
            first_sector[0..4].copy_from_slice(&(0x0FFFFF00 | MEDIA_TYPE as u32).to_le_bytes());
            // FAT[1] = end of chain marker
            first_sector[4..8].copy_from_slice(&0xFFFFFFFFu32.to_le_bytes());
            // FAT[2] = end of chain (root directory)
            first_sector[8..12].copy_from_slice(&FAT32_EOC.to_le_bytes());

            writer.write_all(&first_sector)?;

            // Zero out rest of FAT in large chunks
            let mut remaining_sectors = self.sectors_per_fat - 1;
            let total_sectors = remaining_sectors;
            while remaining_sectors > 0 {
                let sectors_to_write = std::cmp::min(remaining_sectors, (CHUNK_SIZE / 512) as u32);
                let bytes_to_write = sectors_to_write as usize * 512;

                writer.write_all(&chunk_buffer[..bytes_to_write])?;
                remaining_sectors -= sectors_to_write;
                
                if (total_sectors - remaining_sectors) % 1000 == 0 {
                    log::info!("FAT32: Zeroing FAT{}... {}/{} sectors", i + 1, total_sectors - remaining_sectors, total_sectors);
                }
            }
        }

        Ok(())
    }

    /// Get the offset of the data area.
    pub fn data_area_offset(&self, partition_offset: u64) -> u64 {
        partition_offset
            + self.reserved_sectors as u64 * 512
            + self.fat_count as u64 * self.sectors_per_fat as u64 * 512
    }

    /// Get the offset of a specific cluster.
    pub fn cluster_offset(&self, partition_offset: u64, cluster: u32) -> u64 {
        let data_offset = self.data_area_offset(partition_offset);
        data_offset + (cluster as u64 - 2) * self.sectors_per_cluster as u64 * 512
    }

    /// Get bytes per cluster.
    pub fn bytes_per_cluster(&self) -> u32 {
        self.sectors_per_cluster as u32 * BYTES_PER_SECTOR
    }

    /// Get total number of data clusters.
    pub fn total_clusters(&self) -> u32 {
        let data_sectors = self.total_sectors
            - self.reserved_sectors as u32
            - self.fat_count as u32 * self.sectors_per_fat;
        data_sectors / self.sectors_per_cluster as u32
    }
}

/// FAT32 filesystem formatter.
pub struct Fat32Formatter {
    /// Boot sector configuration.
    pub boot_sector: Fat32BootSector,
    /// Partition offset in bytes.
    pub partition_offset: u64,
    /// Next free cluster.
    next_cluster: u32,
}

impl Fat32Formatter {
    /// Create a new FAT32 formatter.
    pub fn new(partition_offset: u64, partition_size: u64, volume_label: &str) -> Result<Self> {
        let boot_sector = Fat32BootSector::new(partition_size, volume_label)?;

        Ok(Self {
            boot_sector,
            partition_offset,
            next_cluster: 3, // Cluster 2 is root directory
        })
    }

    /// Format the partition with FAT32.
    pub fn format<W: Write + Seek>(&mut self, writer: &mut W) -> Result<()> {
        // Write boot sector and backup
        self.boot_sector.write(writer, self.partition_offset)?;

        // Write FAT tables
        self.boot_sector.write_fat(writer, self.partition_offset)?;

        // Initialize root directory (cluster 2)
        let root_offset = self.boot_sector.cluster_offset(self.partition_offset, 2);
        writer.seek(SeekFrom::Start(root_offset))?;

        // Write volume label entry
        let mut volume_entry = [0u8; 32];
        volume_entry[0..11].copy_from_slice(&self.boot_sector.volume_label);
        volume_entry[11] = 0x08; // Volume label attribute
        writer.write_all(&volume_entry)?;

        // Zero out rest of root cluster
        let remaining = self.boot_sector.bytes_per_cluster() as usize - 32;
        let zeros = vec![0u8; remaining];
        writer.write_all(&zeros)?;

        log::info!("FAT32: Formatting completed.");

        Ok(())
    }

    /// Allocate a cluster and return its number.
    pub fn allocate_cluster(&mut self) -> u32 {
        let cluster = self.next_cluster;
        self.next_cluster += 1;
        cluster
    }
}
