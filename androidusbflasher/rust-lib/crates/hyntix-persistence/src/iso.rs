//! Minimal ISO9660 directory walker used for persistence probing.
//!
//! Only the primary volume descriptor is read. Rock Ridge and Joliet names are
//! ignored; the live markers and boot configs we need are plain ISO9660 names.

use crate::activation;
use crate::profile::{PersistenceActivation, PersistenceProfile, PersistenceSupport};
use crate::{PersistenceError, Result};
use std::collections::{HashMap, HashSet};
use std::io::{Read, Seek, SeekFrom};

const SECTOR: u64 = 2048;
const MAX_DIR_BYTES: u64 = 16 * 1024 * 1024;
const MAX_FILES: usize = 100_000;
const MAX_DEPTH: u32 = 16;
const MAX_CONFIG_BYTES: u64 = 1024 * 1024;

/// Debian Live derivatives that share the `/live/...` layout but are outside
/// the supported profile. They are identified by the ISO9660 volume ID, the
/// only distro-identifying field available without opening the squashfs.
const UNSUPPORTED_DEBIAN_LIVE_VENDORS: [&str; 3] = ["kali", "tails", "parrot"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IsoFile {
    pub path: String,
    pub offset: u64,
    pub length: u64,
}

#[derive(Debug, Default)]
pub struct IsoImage {
    files: HashMap<String, IsoFile>,
    volume_id: String,
}

impl IsoImage {
    pub fn files(&self) -> &HashMap<String, IsoFile> {
        &self.files
    }

    pub fn volume_id(&self) -> &str {
        &self.volume_id
    }

    pub fn get(&self, path: &str) -> Option<&IsoFile> {
        self.files.get(&normalize_path(path))
    }

    pub fn contains(&self, path: &str) -> bool {
        self.get(path).is_some()
    }

    pub fn read<R: Read + Seek>(&self, reader: &mut R, path: &str) -> Result<Vec<u8>> {
        self.read_limited(reader, path, u64::MAX)
    }

    pub fn read_limited<R: Read + Seek>(
        &self,
        reader: &mut R,
        path: &str,
        max_len: u64,
    ) -> Result<Vec<u8>> {
        let file = self
            .get(path)
            .ok_or_else(|| PersistenceError::UnsupportedIso(format!("{path} is missing")))?;
        if file.length > max_len {
            return Err(PersistenceError::UnsupportedIso(format!(
                "{path} is too large ({} bytes)",
                file.length
            )));
        }
        read_at(reader, file.offset, file.length as usize)
    }
}

#[derive(Debug, Clone)]
struct IsoRecord {
    extent: u64,
    ext_attr_blocks: u64,
    data_len: u64,
    flags: u8,
    name: Vec<u8>,
}

/// Walk the ISO9660 directory tree and return every regular file.
pub fn walk<R: Read + Seek>(reader: &mut R, image_len: u64) -> Result<IsoImage> {
    if image_len < 17 * SECTOR {
        return Err(PersistenceError::UnsupportedIso(
            "image is too small to be an ISO9660 filesystem".into(),
        ));
    }
    let pvd = read_at(reader, 16 * SECTOR, SECTOR as usize)?;
    if &pvd[1..6] != b"CD001" {
        return Err(PersistenceError::UnsupportedIso(
            "ISO9660 primary volume descriptor was not found".into(),
        ));
    }
    if pvd[0] != 1 {
        return Err(PersistenceError::UnsupportedIso(
            "first volume descriptor is not a primary volume descriptor".into(),
        ));
    }
    let logical_block = u16::from_le_bytes([pvd[128], pvd[129]]) as u64;
    if logical_block != SECTOR {
        return Err(PersistenceError::UnsupportedIso(format!(
            "unsupported ISO logical block size {logical_block}"
        )));
    }

    let root = parse_record(&pvd[156..190])?;
    let volume_id = String::from_utf8_lossy(&pvd[40..72])
        .trim_end_matches([' ', '\0'])
        .to_string();
    let mut image = IsoImage {
        files: HashMap::new(),
        volume_id,
    };
    let mut visited = HashSet::new();
    walk_directory(
        reader,
        &root,
        "",
        0,
        image_len,
        &mut image.files,
        &mut visited,
    )?;
    Ok(image)
}

fn walk_directory<R: Read + Seek>(
    reader: &mut R,
    record: &IsoRecord,
    parent_path: &str,
    depth: u32,
    image_len: u64,
    files: &mut HashMap<String, IsoFile>,
    visited: &mut HashSet<u64>,
) -> Result<()> {
    if depth > MAX_DEPTH {
        return Err(PersistenceError::UnsupportedIso(
            "ISO9660 directory tree is too deep".into(),
        ));
    }
    if record.data_len > MAX_DIR_BYTES {
        return Err(PersistenceError::UnsupportedIso(format!(
            "directory {parent_path} is unreasonably large"
        )));
    }
    let data_offset = record
        .extent
        .checked_mul(SECTOR)
        .and_then(|v| v.checked_add(record.ext_attr_blocks * SECTOR))
        .ok_or_else(|| PersistenceError::UnsupportedIso("directory offset overflow".into()))?;
    if data_offset
        .checked_add(record.data_len)
        .map(|end| end > image_len)
        .unwrap_or(true)
    {
        return Err(PersistenceError::UnsupportedIso(format!(
            "directory {parent_path} lies outside the image"
        )));
    }
    if !visited.insert(record.extent) {
        return Err(PersistenceError::UnsupportedIso(format!(
            "directory {parent_path} is cyclic"
        )));
    }

    let data = read_at(reader, data_offset, record.data_len as usize)?;
    let mut position = 0usize;
    while position < data.len() {
        let rec_len = data[position] as usize;
        if rec_len == 0 {
            position = ((position / SECTOR as usize) + 1) * SECTOR as usize;
            continue;
        }
        if rec_len < 34 || position + rec_len > data.len() {
            return Err(PersistenceError::UnsupportedIso(format!(
                "malformed directory record in {parent_path}"
            )));
        }
        let entry = parse_record(&data[position..position + rec_len])?;
        let name = record_name(&entry.name);
        if name != "." && name != ".." {
            let path = format!("{parent_path}/{name}");
            if entry.flags & 0x80 != 0 {
                // Multi-extent file. None of the files we need use this, so
                // skip it rather than joining extents incorrectly.
            } else if entry.flags & 0x02 != 0 {
                walk_directory(reader, &entry, &path, depth + 1, image_len, files, visited)?;
            } else {
                if files.len() >= MAX_FILES {
                    return Err(PersistenceError::UnsupportedIso(
                        "ISO contains too many files".into(),
                    ));
                }
                let offset = (entry.extent + entry.ext_attr_blocks) * SECTOR;
                if offset
                    .checked_add(entry.data_len)
                    .map(|end| end > image_len)
                    .unwrap_or(true)
                {
                    return Err(PersistenceError::UnsupportedIso(format!(
                        "file {path} lies outside the image"
                    )));
                }
                files.insert(
                    path.clone(),
                    IsoFile {
                        path,
                        offset,
                        length: entry.data_len,
                    },
                );
            }
        }
        position += rec_len;
    }
    Ok(())
}

fn parse_record(bytes: &[u8]) -> Result<IsoRecord> {
    if bytes.len() < 34 {
        return Err(PersistenceError::UnsupportedIso(
            "ISO directory record is too short".into(),
        ));
    }
    let length = bytes[0] as usize;
    if length < 34 || length > bytes.len() {
        return Err(PersistenceError::UnsupportedIso(
            "ISO directory record has an invalid length".into(),
        ));
    }
    let name_len = bytes[32] as usize;
    if 33 + name_len > length {
        return Err(PersistenceError::UnsupportedIso(
            "ISO directory record name is truncated".into(),
        ));
    }
    Ok(IsoRecord {
        ext_attr_blocks: bytes[1] as u64,
        extent: u32::from_le_bytes(bytes[2..6].try_into().unwrap()) as u64,
        data_len: u32::from_le_bytes(bytes[10..14].try_into().unwrap()) as u64,
        flags: bytes[25],
        name: bytes[33..33 + name_len].to_vec(),
    })
}

fn record_name(raw: &[u8]) -> String {
    if raw.len() == 1 {
        if raw[0] == 0 {
            return ".".to_string();
        }
        if raw[0] == 1 {
            return "..".to_string();
        }
    }
    let text = String::from_utf8_lossy(raw);
    let text = text.split(';').next().unwrap_or(&text);
    text.trim_end_matches('.').to_ascii_lowercase()
}

fn normalize_path(path: &str) -> String {
    let path = path.trim();
    if path.is_empty() || path == "/" {
        return "/".to_string();
    }
    let mut normalized = String::with_capacity(path.len() + 1);
    normalized.push('/');
    normalized.push_str(path.trim_start_matches('/').trim_end_matches('/'));
    normalized.to_ascii_lowercase()
}

fn read_at<R: Read + Seek>(reader: &mut R, offset: u64, length: usize) -> Result<Vec<u8>> {
    reader
        .seek(SeekFrom::Start(offset))
        .map_err(|e| PersistenceError::Io(format!("seek to {offset} failed: {e}")))?;
    let mut buffer = vec![0u8; length];
    reader
        .read_exact(&mut buffer)
        .map_err(|e| PersistenceError::Io(format!("read at {offset} failed: {e}")))?;
    Ok(buffer)
}

fn is_unsupported_debian_live_vendor(volume_id: &str) -> bool {
    let normalized = volume_id.to_ascii_lowercase();
    UNSUPPORTED_DEBIAN_LIVE_VENDORS
        .iter()
        .any(|vendor| normalized.contains(vendor))
}

/// Probe an ISO for casper or Debian Live persistence support.
///
/// Returns `Ok(None)` when the image is a valid ISO but not a supported live
/// layout. Malformed images and ambiguous layouts return an error.
pub fn probe_iso<R: Read + Seek>(
    reader: &mut R,
    image_len: u64,
) -> Result<Option<PersistenceSupport>> {
    let image = walk(reader, image_len)?;

    let casper = image.contains("/casper/vmlinuz")
        && (image.contains("/casper/initrd")
            || image.contains("/casper/initrd.lz")
            || image.contains("/casper/initrd.gz"));
    let debian = image.contains("/live/vmlinuz")
        && image.contains("/live/initrd.img")
        && image.contains("/live/filesystem.squashfs");

    let profile = match (casper, debian) {
        (true, true) => {
            return Err(PersistenceError::UnsupportedIso(
                "ISO contains both casper and Debian Live layouts".into(),
            ));
        }
        (true, false) => PersistenceProfile::Casper,
        (false, true) => PersistenceProfile::DebianLive,
        (false, false) => return Ok(None),
    };

    // Kali, Tails, and Parrot use the same `/live` layout as Debian Live but
    // are outside the supported profile. Reject them before offering the
    // toggle; their persistence conventions and encryption differ.
    if profile == PersistenceProfile::DebianLive
        && is_unsupported_debian_live_vendor(image.volume_id())
    {
        return Ok(None);
    }

    let config_paths = [
        "/boot/grub/grub.cfg",
        "/isolinux/isolinux.cfg",
        "/boot/isolinux/isolinux.cfg",
        "/boot/grub/loopback.cfg",
    ];

    let mut has_persistence = false;
    let mut patches = Vec::new();
    let mut live_entries = 0usize;

    for path in config_paths {
        let Some(file) = image.get(path) else {
            continue;
        };
        if file.length > MAX_CONFIG_BYTES {
            continue;
        }
        let bytes = image.read_limited(reader, path, MAX_CONFIG_BYTES)?;
        let analysis = activation::analyze(path, &bytes, file.offset, profile);
        has_persistence |= analysis.has_persistence;
        live_entries += analysis.live_entries;
        patches.extend(analysis.patches);
    }

    patches.sort_by_key(|patch| patch.iso_offset);
    patches.dedup_by_key(|patch| patch.iso_offset);

    let activation = if has_persistence {
        PersistenceActivation::BuiltIn
    } else if profile == PersistenceProfile::Casper && !patches.is_empty() {
        PersistenceActivation::Patchable
    } else {
        PersistenceActivation::Manual
    };

    let reason = match activation {
        PersistenceActivation::Manual if live_entries == 0 => Some(
            "No live boot entry was found in the ISO boot configuration.".to_string(),
        ),
        PersistenceActivation::Manual if profile == PersistenceProfile::Casper => Some(
            "The live boot entry has no safe same-length patch point. Add 'persistent' manually at boot.".to_string(),
        ),
        PersistenceActivation::Manual => Some(
            "The ISO does not provide a built-in persistence boot entry.".to_string(),
        ),
        _ => None,
    };

    let patches = if has_persistence { Vec::new() } else { patches };
    Ok(Some(PersistenceSupport::new(
        profile, activation, reason, patches,
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{Node, build_casper_iso, build_debian_iso, build_iso};

    #[test]
    fn walks_nested_directories_and_files() {
        let bytes = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz quiet splash ---\n}\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let image = walk(&mut cursor, bytes.len() as u64).unwrap();
        assert!(image.contains("/casper/vmlinuz"));
        assert!(image.contains("/casper/initrd"));
        assert!(image.contains("/boot/grub/grub.cfg"));
        let file = image.get("/CASPER/VMLINUZ").unwrap();
        assert_eq!(file.length, 4096);
    }

    #[test]
    fn rejects_non_iso_data() {
        let mut cursor = std::io::Cursor::new(vec![0u8; 64 * 1024]);
        let err = walk(&mut cursor, 64 * 1024).unwrap_err();
        assert!(matches!(err, PersistenceError::UnsupportedIso(_)));
    }

    #[test]
    fn detects_patchable_casper_image() {
        let bytes = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz quiet splash ---\n}\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert_eq!(support.profile, PersistenceProfile::Casper);
        assert_eq!(support.activation, PersistenceActivation::Patchable);
        assert_eq!(support.label, "casper-rw");
        assert_eq!(support.patches.len(), 1);
        assert_eq!(support.min_bytes, 1024 * 1024 * 1024);
        assert_eq!(support.recommended_bytes, 4 * 1024 * 1024 * 1024);
    }

    #[test]
    fn detects_casper_builtin_persistence() {
        let bytes = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz persistent quiet splash ---\n}\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert_eq!(support.activation, PersistenceActivation::BuiltIn);
        assert!(support.patches.is_empty());
    }

    #[test]
    fn reports_manual_when_no_patch_point() {
        let bytes = build_casper_iso(Some(
            "menuentry \"Try Ubuntu\" {\n linux /casper/vmlinuz maybe ---\n}\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert_eq!(support.activation, PersistenceActivation::Manual);
        assert!(support.reason.is_some());
    }

    #[test]
    fn detects_debian_builtin_persistence() {
        let bytes = build_debian_iso(Some(
            "label live-persistence\n menu label Live persistence\n kernel /live/vmlinuz\n append initrd=/live/initrd.img boot=live persistence\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert_eq!(support.profile, PersistenceProfile::DebianLive);
        assert_eq!(support.activation, PersistenceActivation::BuiltIn);
        assert_eq!(support.label, "persistence");
    }

    #[test]
    fn reports_debian_manual_without_entry() {
        let bytes = build_debian_iso(Some(
            "label live\n menu label Live\n kernel /live/vmlinuz\n append initrd=/live/initrd.img boot=live\n",
        ));
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert_eq!(support.profile, PersistenceProfile::DebianLive);
        assert_eq!(support.activation, PersistenceActivation::Manual);
    }

    fn set_volume_id(image: &mut [u8], volume_id: &str) {
        let offset = 16 * SECTOR as usize + 40;
        image[offset..offset + 32].fill(b' ');
        image[offset..offset + volume_id.len()].copy_from_slice(volume_id.as_bytes());
    }

    #[test]
    fn rejects_known_unsupported_debian_live_derivatives() {
        for volume_id in ["KALI LINUX LIVE", "TAILS", "Parrot Security"] {
            let mut bytes = build_debian_iso(None);
            set_volume_id(&mut bytes, volume_id);
            let mut cursor = std::io::Cursor::new(bytes.clone());
            assert!(
                probe_iso(&mut cursor, bytes.len() as u64)
                    .unwrap()
                    .is_none(),
                "{volume_id} must not be offered persistence"
            );
        }
    }

    #[test]
    fn accepts_official_debian_volume_id() {
        let mut bytes = build_debian_iso(None);
        set_volume_id(&mut bytes, "DEBIAN 12.5.0 AMD64 1");
        let mut cursor = std::io::Cursor::new(bytes.clone());
        let support = probe_iso(&mut cursor, bytes.len() as u64).unwrap().unwrap();
        assert_eq!(support.profile, PersistenceProfile::DebianLive);
        assert_eq!(support.activation, PersistenceActivation::BuiltIn);
    }

    #[test]
    fn returns_none_for_plain_iso() {
        let bytes = build_iso(vec![Node::File {
            name: "readme.txt",
            data: b"hello".to_vec(),
        }]);
        let mut cursor = std::io::Cursor::new(bytes.clone());
        assert!(
            probe_iso(&mut cursor, bytes.len() as u64)
                .unwrap()
                .is_none()
        );
    }
}
