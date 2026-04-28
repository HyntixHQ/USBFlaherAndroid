//! Minimal read-only UDF (ECMA-167) parser tailored for Windows ISO extraction.
//!
//! Implements just enough of the UDF spec to traverse the directory tree inside
//! a Windows installation ISO and stream individual files out. Not a
//! general-purpose UDF library.

use std::io::{self, Read, Seek, SeekFrom};
use thiserror::Error;

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const SECTOR_SIZE: u64 = 2048;

// ECMA-167 Descriptor Tag Identifiers
const TAG_PARTITION_DESC: u16 = 5;
const TAG_LOGICAL_VOLUME_DESC: u16 = 6;
const TAG_TERMINATING_DESC: u16 = 8;
const TAG_FILE_SET_DESC: u16 = 256;
const TAG_FILE_IDENT_DESC: u16 = 257;
const TAG_FILE_ENTRY: u16 = 261;         // ECMA-167 §14.9
const TAG_EXT_FILE_ENTRY: u16 = 266;     // ECMA-167 §14.17

// File Identifier Descriptor characteristics (ECMA-167 §14.4.3)
const FID_CHAR_DIRECTORY: u8 = 0x02;
const FID_CHAR_DELETED: u8 = 0x04;
const FID_CHAR_PARENT: u8 = 0x08;

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

#[derive(Error, Debug)]
pub enum UdfError {
    #[error("I/O error: {0}")]
    Io(#[from] io::Error),
    #[error("Not a valid UDF image (AVDP not found at sector 256)")]
    InvalidAvdp,
    #[error("Missing required descriptor: {0}")]
    MissingDescriptor(&'static str),
    #[error("Parse error: {0}")]
    Parse(String),
}

// ---------------------------------------------------------------------------
// Public data types
// ---------------------------------------------------------------------------

/// A file or directory entry discovered inside the ISO.
#[derive(Debug, Clone)]
pub struct UdfEntry {
    pub name: String,
    pub is_dir: bool,
    /// Byte-size of the file (0 for directories).
    pub size: u64,
    /// Sector of the File Entry ICB (partition-relative).
    icb_location: u32,
}

// ---------------------------------------------------------------------------
// UdfReader – the main public API
// ---------------------------------------------------------------------------

pub struct UdfReader<R: Read + Seek> {
    reader: R,
    /// Absolute sector where partition 0 data begins.
    partition_start: u32,
    /// Sector of the root directory File Entry (partition-relative).
    root_icb_loc: u32,
}

impl<R: Read + Seek> UdfReader<R> {
    /// Open a UDF image (typically a Windows ISO) and parse enough metadata
    /// to be ready for directory traversal.
    pub fn new(mut reader: R) -> Result<Self, UdfError> {
        // ── Step 1: Anchor Volume Descriptor Pointer at sector 256 ───────
        reader.seek(SeekFrom::Start(256 * SECTOR_SIZE))?;
        let mut buf = [0u8; SECTOR_SIZE as usize];
        reader.read_exact(&mut buf)?;

        if u16_at(&buf, 0) != 2 {
            return Err(UdfError::InvalidAvdp);
        }

        // Main VDS Extent (extent_ad: u32 length_bytes, u32 location_sector)
        let vds_len = u32_at(&buf, 16);
        let vds_loc = u32_at(&buf, 20);

        // ── Step 2: Walk Volume Descriptor Sequence ──────────────────────
        let mut partition_start: Option<u32> = None;
        let mut fsd_lbn: Option<u32> = None;

        let num_sectors = vds_len / SECTOR_SIZE as u32;
        for i in 0..num_sectors {
            reader.seek(SeekFrom::Start((vds_loc + i) as u64 * SECTOR_SIZE))?;
            reader.read_exact(&mut buf)?;

            match u16_at(&buf, 0) {
                TAG_PARTITION_DESC => {
                    // ECMA-167 3/10.5 – Partition Descriptor
                    //   16-19: Volume Descriptor Sequence Number
                    //   20-21: Partition Flags
                    //   22-23: Partition Number
                    //   24-55: Partition Contents (EntityIdentifier)
                    //   ...
                    //   184-187: Access Type
                    //   188-191: Partition Starting Location (sectors)
                    //   192-195: Partition Length (sectors)
                    let start_loc = u32_at(&buf, 188);
                    // Windows ISOs have exactly one partition – just take it.
                    if partition_start.is_none() {
                        partition_start = Some(start_loc);
                    }
                }
                TAG_LOGICAL_VOLUME_DESC => {
                    // ECMA-167 3/10.6 – Logical Volume Descriptor
                    //   0-15:   Tag
                    //   16-19:  Volume Descriptor Sequence Number
                    //   20-83:  Descriptor Character Set (charspec, 64 bytes)
                    //   84-211: Logical Volume Identifier (dstring, 128 bytes)
                    //   212-215: Logical Block Size (u32)
                    //   216-247: Domain Identifier (EntityIdentifier, 32 bytes)
                    //   248-263: Logical Volume Contents Use (long_ad, 16 bytes)
                    //     248-251: Extent Length
                    //     252-255: Extent LBN (partition-relative)
                    //     256-257: Partition Reference Number
                    fsd_lbn = Some(u32_at(&buf, 252));
                }
                TAG_TERMINATING_DESC => break,
                _ => {}
            }
        }

        let partition_start =
            partition_start.ok_or(UdfError::MissingDescriptor("Partition Descriptor"))?;
        let fsd_lbn =
            fsd_lbn.ok_or(UdfError::MissingDescriptor("Logical Volume Descriptor"))?;

        // ── Step 3: Read File Set Descriptor ─────────────────────────────
        let fsd_sector = partition_start + fsd_lbn;
        reader.seek(SeekFrom::Start(fsd_sector as u64 * SECTOR_SIZE))?;
        reader.read_exact(&mut buf)?;

        if u16_at(&buf, 0) != TAG_FILE_SET_DESC {
            return Err(UdfError::Parse(format!(
                "Expected FSD (tag 256) at sector {}, got tag {}",
                fsd_sector,
                u16_at(&buf, 0)
            )));
        }

        // Root Directory ICB (long_ad at offset 400)
        //   400-403: Extent Length
        //   404-407: Extent LBN (partition-relative)
        let root_icb_loc = u32_at(&buf, 404);

        Ok(Self {
            reader,
            partition_start,
            root_icb_loc,
        })
    }

    // ── Public API ───────────────────────────────────────────────────────

    /// List all entries in the root directory.
    pub fn read_root_dir(&mut self) -> Result<Vec<UdfEntry>, UdfError> {
        self.read_dir_at(self.root_icb_loc)
    }

    /// Recursively list the entire file tree. Returns `(path, entry)` pairs.
    pub fn walk(&mut self) -> Result<Vec<(String, UdfEntry)>, UdfError> {
        let mut out = Vec::new();
        self.walk_recursive(self.root_icb_loc, String::new(), &mut out)?;
        Ok(out)
    }

    /// Open a file for streaming – seeks the reader to the start of the
    /// file's data extent and returns its byte length.
    pub fn open_file(&mut self, entry: &UdfEntry) -> Result<u64, UdfError> {
        if entry.is_dir {
            return Err(UdfError::Parse("Cannot open a directory as a file".into()));
        }
        let (data_offset, data_len) = self.read_file_entry_data(entry.icb_location)?;
        self.reader.seek(SeekFrom::Start(data_offset))?;
        Ok(data_len)
    }

    /// Read a small file entirely into memory.
    pub fn read_file(&mut self, entry: &UdfEntry) -> Result<Vec<u8>, UdfError> {
        let size = self.open_file(entry)?;
        let mut data = vec![0u8; size as usize];
        self.reader.read_exact(&mut data)?;
        Ok(data)
    }

    /// Read a chunk from the current position (after `open_file`).
    pub fn read_chunk(&mut self, buf: &mut [u8]) -> Result<usize, UdfError> {
        Ok(self.reader.read(buf)?)
    }

    /// Access the underlying reader for streaming.
    pub fn reader(&mut self) -> &mut R {
        &mut self.reader
    }

    // ── Internal: File Entry parsing ─────────────────────────────────────

    /// Read the File Entry (tag 261) or Extended File Entry (tag 266) at
    /// `icb_lbn` and return `(absolute_byte_offset, data_byte_length)` for
    /// its first allocation extent.
    fn read_file_entry_data(&mut self, icb_lbn: u32) -> Result<(u64, u64), UdfError> {
        let sector = self.partition_start as u64 + icb_lbn as u64;
        self.reader.seek(SeekFrom::Start(sector * SECTOR_SIZE))?;
        let mut buf = [0u8; SECTOR_SIZE as usize];
        self.reader.read_exact(&mut buf)?;

        let tag = u16_at(&buf, 0);

        // Both File Entry (261) and Extended File Entry (266) share the
        // same initial layout but differ in where the allocation descriptors
        // start. We need to support both since Windows ISOs use Extended.
        //
        // File Entry (ECMA-167 §14.9):
        //   0-15:   Descriptor Tag
        //   16-35:  ICB Tag (20 bytes)
        //   36-39:  Uid
        //   40-43:  Gid
        //   44-47:  Permissions
        //   48-49:  File Link Count
        //   50:     Record Format
        //   51:     Record Display Attributes
        //   52-55:  Record Length
        //   56-63:  Information Length (u64)
        //   64-71:  Logical Blocks Recorded (u64)
        //   72-83:  Access Date and Time (12 bytes)
        //   84-95:  Modification Date and Time (12 bytes)
        //   96-107: Attribute Date and Time (12 bytes)
        //   108-111: Checkpoint (u32)
        //   112-127: Extended Attribute ICB (long_ad, 16 bytes)
        //   128-159: Implementation Identifier (32 bytes)
        //   160-167: Unique ID (u64)
        //   168-171: Length of Extended Attributes (u32)  ← L_EA
        //   172-175: Length of Allocation Descriptors (u32) ← L_AD
        //   176+:    Extended Attributes (L_EA bytes), then Allocation Descriptors
        //
        // Extended File Entry (ECMA-167 §14.17):
        //   Same as above through offset 167, then:
        //   168-171: Length of Extended Attributes (u32)  ← L_EA
        //   172-175: Length of Allocation Descriptors (u32) ← L_AD
        //   176-183: Object Size (u64)
        //   184-195: Creation Date and Time (12 bytes)
        //   196-211: ...additional fields...
        //   216+:    Extended Attributes (L_EA bytes), then Allocation Descriptors

        let (info_length, l_ea, _l_ad, ad_base) = match tag {
            TAG_FILE_ENTRY => {
                let info_len = u64_at(&buf, 56);
                let l_ea = u32_at(&buf, 168);
                let l_ad = u32_at(&buf, 172);
                (info_len, l_ea, l_ad, 176u32)
            }
            TAG_EXT_FILE_ENTRY => {
                let info_len = u64_at(&buf, 56);
                let l_ea = u32_at(&buf, 168);
                let l_ad = u32_at(&buf, 172);
                (info_len, l_ea, l_ad, 216u32)
            }
            _ => {
                return Err(UdfError::Parse(format!(
                    "Expected File Entry (261/266) at sector {}, got tag {}",
                    sector, tag
                )));
            }
        };

        // ICB Tag at offset 16, strategy type at offset 16+18 = 34 (Uint16)
        // Allocation type is in the ICB Tag flags (offset 16+18 = 34, lower 3 bits)
        let icb_flags = u16_at(&buf, 34);
        let alloc_type = icb_flags & 0x07;

        let ad_offset = (ad_base + l_ea) as usize;

        match alloc_type {
            0 => {
                // Short Allocation Descriptors (8 bytes each)
                // u32: extent length (upper 2 bits = type), u32: extent position (partition-relative LBN)
                if ad_offset + 8 > buf.len() {
                    return Err(UdfError::Parse("Short AD out of bounds".into()));
                }
                let ext_pos = u32_at(&buf, ad_offset + 4);
                let abs_offset = (self.partition_start as u64 + ext_pos as u64) * SECTOR_SIZE;
                Ok((abs_offset, info_length))
            }
            1 => {
                // Long Allocation Descriptors (16 bytes each)
                // u32: extent length, u32: extent LBN, u16: partition ref, 6 bytes impl use
                if ad_offset + 16 > buf.len() {
                    return Err(UdfError::Parse("Long AD out of bounds".into()));
                }
                let ext_pos = u32_at(&buf, ad_offset + 4);
                let abs_offset = (self.partition_start as u64 + ext_pos as u64) * SECTOR_SIZE;
                Ok((abs_offset, info_length))
            }
            3 => {
                // Immediate / embedded data – the file data is stored inline
                // within the allocation descriptor area of the File Entry itself.
                let abs_offset = sector * SECTOR_SIZE + ad_offset as u64;
                Ok((abs_offset, info_length))
            }
            _ => Err(UdfError::Parse(format!(
                "Unsupported allocation type {} at sector {}",
                alloc_type, sector
            ))),
        }
    }

    // ── Internal: Directory parsing ──────────────────────────────────────

    /// Read a directory at the given ICB location and return its entries.
    fn read_dir_at(&mut self, icb_lbn: u32) -> Result<Vec<UdfEntry>, UdfError> {
        let (data_offset, data_len) = self.read_file_entry_data(icb_lbn)?;

        self.reader.seek(SeekFrom::Start(data_offset))?;
        let mut dir_data = vec![0u8; data_len as usize];
        self.reader.read_exact(&mut dir_data)?;

        let mut entries = Vec::new();
        let mut pos = 0usize;

        while pos + 38 <= dir_data.len() {
            let fid_tag = u16_le(&dir_data[pos..pos + 2]);
            if fid_tag != TAG_FILE_IDENT_DESC {
                break;
            }

            // ECMA-167 §14.4 File Identifier Descriptor
            //   0-15:  Descriptor Tag (16 bytes)
            //   16-17: File Version Number (u16)
            //   18:    File Characteristics (u8)
            //   19:    Length of File Identifier (u8) ← L_FI
            //   20-35: ICB (long_ad, 16 bytes)
            //     20-23: Extent Length
            //     24-27: Extent LBN (partition-relative)
            //     28-29: Partition Reference Number
            //     30-35: Implementation Use (6 bytes)
            //   36-37: Length of Implementation Use (u16) ← L_IU
            //   38+:   Implementation Use (L_IU bytes), then File Identifier (L_FI bytes)

            let characteristics = dir_data[pos + 18];
            let l_fi = dir_data[pos + 19] as usize;
            let icb_loc = u32_le(&dir_data[pos + 24..pos + 28]);
            let l_iu = u16_le(&dir_data[pos + 36..pos + 38]) as usize;

            let is_parent = (characteristics & FID_CHAR_PARENT) != 0;
            let is_deleted = (characteristics & FID_CHAR_DELETED) != 0;
            let is_dir = (characteristics & FID_CHAR_DIRECTORY) != 0;

            let name_offset = pos + 38 + l_iu;

            if !is_parent && !is_deleted && name_offset + l_fi <= dir_data.len() {
                let name_bytes = &dir_data[name_offset..name_offset + l_fi];
                let name = decode_dstring(name_bytes);

                if !name.is_empty() {
                    let size = if !is_dir {
                        self.read_file_entry_data(icb_loc)
                            .map(|(_, len)| len)
                            .unwrap_or(0)
                    } else {
                        0
                    };

                    entries.push(UdfEntry {
                        name,
                        is_dir,
                        size,
                        icb_location: icb_loc,
                    });
                }
            }

            // FID total length = 38 + L_IU + L_FI, padded to 4-byte boundary
            let fid_len = 38 + l_iu + l_fi;
            let padded = (fid_len + 3) & !3;
            pos += padded;
        }

        Ok(entries)
    }

    fn walk_recursive(
        &mut self,
        icb_lbn: u32,
        prefix: String,
        out: &mut Vec<(String, UdfEntry)>,
    ) -> Result<(), UdfError> {
        let entries = self.read_dir_at(icb_lbn)?;
        for entry in entries {
            let path = if prefix.is_empty() {
                entry.name.clone()
            } else {
                format!("{}/{}", prefix, entry.name)
            };
            let is_dir = entry.is_dir;
            let icb = entry.icb_location;
            out.push((path.clone(), entry));
            if is_dir {
                self.walk_recursive(icb, path, out)?;
            }
        }
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Byte-reading helpers
// ---------------------------------------------------------------------------

#[inline(always)]
fn u16_at(buf: &[u8; SECTOR_SIZE as usize], off: usize) -> u16 {
    u16::from_le_bytes([buf[off], buf[off + 1]])
}

#[inline(always)]
fn u32_at(buf: &[u8; SECTOR_SIZE as usize], off: usize) -> u32 {
    u32::from_le_bytes([buf[off], buf[off + 1], buf[off + 2], buf[off + 3]])
}

#[inline(always)]
fn u64_at(buf: &[u8; SECTOR_SIZE as usize], off: usize) -> u64 {
    u64::from_le_bytes([
        buf[off], buf[off + 1], buf[off + 2], buf[off + 3],
        buf[off + 4], buf[off + 5], buf[off + 6], buf[off + 7],
    ])
}

#[inline(always)]
fn u16_le(b: &[u8]) -> u16 {
    u16::from_le_bytes([b[0], b[1]])
}

#[inline(always)]
fn u32_le(b: &[u8]) -> u32 {
    u32::from_le_bytes([b[0], b[1], b[2], b[3]])
}

/// Decode a UDF CS0 d-string. Byte 0 = compression ID:
///   8 → one byte per char (Latin-1), 16 → UTF-16 BE.
fn decode_dstring(data: &[u8]) -> String {
    if data.is_empty() {
        return String::new();
    }
    match data[0] {
        8 => String::from_utf8_lossy(&data[1..])
            .trim_end_matches('\0')
            .to_string(),
        16 => {
            let chars: Vec<u16> = data[1..]
                .chunks_exact(2)
                .map(|c| u16::from_be_bytes([c[0], c[1]]))
                .collect();
            String::from_utf16_lossy(&chars)
                .trim_end_matches('\0')
                .to_string()
        }
        _ => String::from_utf8_lossy(data)
            .trim_end_matches('\0')
            .to_string(),
    }
}
