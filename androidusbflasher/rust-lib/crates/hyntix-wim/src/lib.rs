use std::io::{self, Read, Seek, SeekFrom};
use thiserror::Error;

pub const WIM_HEADER_SIZE: usize = 208;
pub const WIM_MAGIC: &[u8; 8] = b"MSWIM\0\0\0";

#[derive(Error, Debug)]
pub enum WimError {
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("Invalid WIM signature")]
    InvalidSignature,
    #[error("Unsupported WIM version: {0}")]
    UnsupportedVersion(u32),
    #[error("Parse error: {0}")]
    Parse(String),
}

#[derive(Debug, Clone, Copy, Default)]
pub struct WimResHdr {
    pub size: u64,
    pub flags: u8,
    pub offset: u64,
    pub original_size: u64,
}

impl WimResHdr {
    pub fn from_bytes(buf: &[u8]) -> Self {
        let size = u64::from_le_bytes([buf[0], buf[1], buf[2], buf[3], buf[4], buf[5], buf[6], 0]);
        let flags = buf[7];
        let offset = u64::from_le_bytes([buf[8], buf[9], buf[10], buf[11], buf[12], buf[13], buf[14], buf[15]]);
        let original_size = u64::from_le_bytes([buf[16], buf[17], buf[18], buf[19], buf[20], buf[21], buf[22], buf[23]]);
        
        Self {
            size,
            flags,
            offset,
            original_size,
        }
    }
}

#[derive(Debug, Clone)]
pub struct WimLookupEntry {
    pub res_hdr: WimResHdr,
    pub part_number: u16,
    pub ref_count: u32,
    pub hash: [u8; 20],
}

impl WimLookupEntry {
    pub fn read<R: Read>(mut reader: R) -> io::Result<Self> {
        let mut buf = [0u8; 50];
        reader.read_exact(&mut buf)?;
        
        let res_hdr = WimResHdr::from_bytes(&buf[0..24]);
        let part_number = u16::from_le_bytes([buf[24], buf[25]]);
        let ref_count = u32::from_le_bytes([buf[26], buf[27], buf[28], buf[29]]);
        let mut hash = [0u8; 20];
        hash.copy_from_slice(&buf[30..50]);
        
        Ok(Self {
            res_hdr,
            part_number,
            ref_count,
            hash,
        })
    }
}

#[derive(Debug, Clone)]
pub struct WimHeader {
    pub flags: u32,
    pub compression_size: u32,
    pub guid: [u8; 16],
    pub part_number: u16,
    pub total_parts: u16,
    pub image_count: u32,
    pub lookup_table_resource: WimResHdr,
    pub xml_data_resource: WimResHdr,
    pub boot_index: u32,
    pub integrity_resource: WimResHdr,
}

impl WimHeader {
    pub fn read<R: Read>(mut reader: R) -> Result<Self, WimError> {
        let mut buf = [0u8; 208];
        reader.read_exact(&mut buf)?;
        
        if &buf[0..8] != WIM_MAGIC {
            return Err(WimError::InvalidSignature);
        }

        let flags = u32::from_le_bytes([buf[16], buf[17], buf[18], buf[19]]);
        let compression_size = u32::from_le_bytes([buf[20], buf[21], buf[22], buf[23]]);
        
        let mut guid = [0u8; 16];
        guid.copy_from_slice(&buf[24..40]);
        
        let part_number = u16::from_le_bytes([buf[40], buf[41]]);
        let total_parts = u16::from_le_bytes([buf[42], buf[43]]);
        let image_count = u32::from_le_bytes([buf[44], buf[45], buf[46], buf[47]]);
        
        let lookup_table_resource = WimResHdr::from_bytes(&buf[48..72]);
        let xml_data_resource = WimResHdr::from_bytes(&buf[72..96]);
        
        let boot_index = u32::from_le_bytes([buf[96], buf[97], buf[98], buf[99]]);
        let integrity_resource = WimResHdr::from_bytes(&buf[100..124]);

        Ok(Self {
            flags,
            compression_size,
            guid,
            part_number,
            total_parts,
            image_count,
            lookup_table_resource,
            xml_data_resource,
            boot_index,
            integrity_resource,
        })
    }

    pub fn read_lookup_table<R: Read + Seek>(&self, mut reader: R) -> Result<Vec<WimLookupEntry>, WimError> {
        let mut entries = Vec::new();
        let num_entries = self.lookup_table_resource.original_size / 50;
        
        reader.seek(SeekFrom::Start(self.lookup_table_resource.offset))?;
        for _ in 0..num_entries {
            entries.push(WimLookupEntry::read(&mut reader)?);
        }
        
        Ok(entries)
    }
}

pub struct SwmPart {
    pub part_number: u16,
    pub total_parts: u16,
    pub blobs_to_write: Vec<WimLookupEntry>,
    pub lookup_table: Vec<WimLookupEntry>,
}

impl SwmPart {
    pub fn write_header<W: io::Write>(&self, mut writer: W, original_header: &WimHeader) -> io::Result<()> {
        let mut buf = [0u8; 208];
        buf[0..8].copy_from_slice(WIM_MAGIC);
        buf[8..12].copy_from_slice(&208u32.to_le_bytes()); // Header size
        buf[12..16].copy_from_slice(&0x00010d00u32.to_le_bytes()); // Version 1.13
        
        let mut flags = original_header.flags;
        flags |= 0x00000008; // WIM_HDR_FLAG_SPANNED
        buf[16..20].copy_from_slice(&flags.to_le_bytes());
        buf[20..24].copy_from_slice(&original_header.compression_size.to_le_bytes());
        buf[24..40].copy_from_slice(&original_header.guid);
        
        buf[40..42].copy_from_slice(&self.part_number.to_le_bytes());
        buf[42..44].copy_from_slice(&self.total_parts.to_le_bytes());
        buf[44..48].copy_from_slice(&original_header.image_count.to_le_bytes());
        buf[96..100].copy_from_slice(&original_header.boot_index.to_le_bytes());
        buf[100..124].fill(0);

        // Calculate Lookup Table and XML offsets
        let mut current_offset = 208u64;
        for entry in &self.blobs_to_write {
            current_offset += entry.res_hdr.size;
        }
        
        // Lookup Table Resource (Always present in all parts for safety)
        let lookup_table_size = (self.lookup_table.len() * 50) as u64;
        let lookup_res = WimResHdr {
            size: lookup_table_size,
            flags: 0x02, // Metadata/Table
            offset: current_offset,
            original_size: lookup_table_size,
        };
        Self::write_res_hdr(&mut buf[48..72], &lookup_res);
        current_offset += lookup_table_size;

        // XML Data Resource (Part 1 only)
        if self.part_number == 1 && original_header.xml_data_resource.size > 0 {
             let mut xml_res = original_header.xml_data_resource;
             xml_res.offset = current_offset;
             Self::write_res_hdr(&mut buf[72..96], &xml_res);
        } else {
             buf[72..96].fill(0);
        }

        writer.write_all(&buf)?;
        Ok(())
    }

    fn write_res_hdr(buf: &mut [u8], res: &WimResHdr) {
        let size_low = (res.size & 0xFFFFFFFFFFFFFF) as u64;
        buf[0..7].copy_from_slice(&size_low.to_le_bytes()[0..7]);
        buf[7] = res.flags;
        buf[8..16].copy_from_slice(&res.offset.to_le_bytes());
        buf[16..24].copy_from_slice(&res.original_size.to_le_bytes());
    }

    pub fn write_lookup_table<W: io::Write>(&self, mut writer: W) -> io::Result<()> {
        for entry in &self.lookup_table {
            let mut buf = [0u8; 50];
            Self::write_res_hdr(&mut buf[0..24], &entry.res_hdr);
            buf[24..26].copy_from_slice(&entry.part_number.to_le_bytes());
            buf[26..30].copy_from_slice(&entry.ref_count.to_le_bytes());
            buf[30..50].copy_from_slice(&entry.hash);
            writer.write_all(&buf)?;
        }
        Ok(())
    }
}

pub struct SwmSplitter {
    pub header: WimHeader,
    pub entries: Vec<WimLookupEntry>,
}

impl SwmSplitter {
    pub fn new(header: WimHeader, entries: Vec<WimLookupEntry>) -> Self {
        Self { header, entries }
    }

    pub fn split(&self, max_part_size: u64) -> Vec<SwmPart> {
        let mut parts_blobs = Vec::new();
        let mut current_part_blobs = Vec::new();
        let mut current_part_size: u64 = WIM_HEADER_SIZE as u64;

        // Group 1: Metadata resources always go into Part 1
        for entry in &self.entries {
            if (entry.res_hdr.flags & 0x02) != 0 {
                current_part_blobs.push(entry.clone());
                current_part_size += entry.res_hdr.size;
            }
        }
        // Account for XML data in Part 1
        current_part_size += self.header.xml_data_resource.size;

        // Group 2: Data resources
        for entry in &self.entries {
            if (entry.res_hdr.flags & 0x02) == 0 {
                if current_part_size + entry.res_hdr.size > max_part_size && !current_part_blobs.is_empty() {
                    parts_blobs.push(current_part_blobs);
                    current_part_blobs = Vec::new();
                    current_part_size = WIM_HEADER_SIZE as u64;
                }
                current_part_size += entry.res_hdr.size;
                current_part_blobs.push(entry.clone());
            }
        }
        if !current_part_blobs.is_empty() {
            parts_blobs.push(current_part_blobs);
        }

        // Generate the global master lookup table
        let mut master_lookup_table = Vec::new();
        for (i, blobs) in parts_blobs.iter().enumerate() {
            let part_num = (i + 1) as u16;
            let mut offset = WIM_HEADER_SIZE as u64;
            for blob in blobs {
                let mut master_entry = blob.clone();
                master_entry.part_number = part_num;
                master_entry.res_hdr.offset = offset;
                master_lookup_table.push(master_entry);
                offset += blob.res_hdr.size;
            }
        }

        let total_parts = parts_blobs.len() as u16;
        parts_blobs.into_iter().enumerate().map(|(i, blobs)| {
            SwmPart {
                part_number: (i + 1) as u16,
                total_parts,
                blobs_to_write: blobs,
                lookup_table: master_lookup_table.clone(),
            }
        }).collect()
    }
}

