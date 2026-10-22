//! Boot-configuration analysis and safe in-place activation patches.

use crate::profile::PersistenceProfile;
use crate::{PersistenceError, Result};
use hyntix_storage::BlockDevice;

/// A same-length patch inside an ISO file extent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BootPatch {
    pub iso_offset: u64,
    pub original: Vec<u8>,
    pub replacement: Vec<u8>,
    pub source: String,
}

#[derive(Debug, Clone, Default)]
pub struct Analysis {
    pub has_persistence: bool,
    pub patches: Vec<BootPatch>,
    pub live_entries: usize,
}

/// Analyze one plain-text boot config.
///
/// `base_offset` is the file's absolute byte offset in the ISO. Only live
/// entries are considered, and only exact same-length substitutions are
/// emitted.
pub fn analyze(
    source: &str,
    bytes: &[u8],
    base_offset: u64,
    profile: PersistenceProfile,
) -> Analysis {
    let mut result = Analysis::default();
    let Ok(text) = std::str::from_utf8(bytes) else {
        return result;
    };

    if source.ends_with("grub.cfg") || source.ends_with("loopback.cfg") {
        analyze_grub(source, text, base_offset, profile, &mut result);
    } else {
        analyze_isolinux(source, text, base_offset, profile, &mut result);
    }

    // De-duplicate patches by offset, keeping the first.
    result.patches.sort_by_key(|p| p.iso_offset);
    result.patches.dedup_by_key(|p| p.iso_offset);
    result
}

fn analyze_grub(
    source: &str,
    text: &str,
    base_offset: u64,
    profile: PersistenceProfile,
    result: &mut Analysis,
) {
    let mut in_entry = false;
    let mut brace_depth = 0i32;
    let mut entry_live = false;

    for (line_start, line) in line_offsets(text) {
        let trimmed = line.trim_start();
        if trimmed.starts_with('#') {
            continue;
        }

        if !in_entry && trimmed.starts_with("menuentry") {
            in_entry = true;
            brace_depth = trimmed.matches('{').count() as i32 - trimmed.matches('}').count() as i32;
            let title = grub_title(trimmed);
            entry_live = is_live_title(&title);
            if entry_live {
                result.live_entries += 1;
            }
            if brace_depth <= 0 {
                in_entry = false;
            }
            continue;
        }

        if !in_entry {
            continue;
        }

        if entry_live
            && let Some(command) = trimmed.split_whitespace().next()
            && matches!(
                command,
                "linux" | "linuxefi" | "linux16" | "linux32" | "kernel"
            )
        {
            inspect_command_line(source, line, line_start, base_offset, profile, true, result);
        }

        brace_depth += trimmed.matches('{').count() as i32;
        brace_depth -= trimmed.matches('}').count() as i32;
        if brace_depth <= 0 {
            in_entry = false;
        }
    }
}

fn analyze_isolinux(
    source: &str,
    text: &str,
    base_offset: u64,
    profile: PersistenceProfile,
    result: &mut Analysis,
) {
    let mut entry_live = false;
    let mut in_entry = false;
    let mut kernel_matches = false;

    for (line_start, line) in line_offsets(text) {
        let trimmed = line.trim_start();
        if trimmed.starts_with('#') {
            continue;
        }
        let command = trimmed.split_whitespace().next().unwrap_or("");
        match command {
            "label" => {
                in_entry = true;
                let title = trimmed["label".len()..].trim();
                entry_live = is_live_title(title);
                kernel_matches = false;
                if entry_live {
                    result.live_entries += 1;
                }
            }
            "menu" if trimmed["menu".len()..].trim_start().starts_with("label") => {
                let title = trimmed["menu".len()..]
                    .trim_start()
                    .trim_start_matches("label")
                    .trim();
                if is_live_title(title) {
                    entry_live = true;
                }
            }
            "kernel" => {
                kernel_matches = kernel_path_matches(trimmed, profile);
            }
            "append"
                if in_entry
                    && entry_live
                    && (kernel_matches || kernel_path_matches(trimmed, profile)) =>
            {
                inspect_command_line(
                    source,
                    line,
                    line_start,
                    base_offset,
                    profile,
                    false,
                    result,
                );
            }
            _ => {}
        }
    }
}

fn inspect_command_line(
    source: &str,
    line: &str,
    line_start: usize,
    base_offset: u64,
    profile: PersistenceProfile,
    grub: bool,
    result: &mut Analysis,
) {
    if grub && !kernel_path_matches(line, profile) {
        return;
    }

    let keyword = match profile {
        PersistenceProfile::Casper => "persistent",
        PersistenceProfile::DebianLive => "persistence",
    };
    if contains_token(line, keyword) {
        result.has_persistence = true;
        return;
    }

    // Debian Live ships its own persistence entry; do not invent one.
    if !grub || profile != PersistenceProfile::Casper {
        return;
    }

    for phrase in ["quiet splash", "splash quiet"] {
        if let Some(index) = line.find(phrase) {
            let before_ok = index == 0
                || line[..index]
                    .chars()
                    .next_back()
                    .map(|c| c.is_whitespace())
                    .unwrap_or(false);
            let after = index + phrase.len();
            let after_ok = after >= line.len()
                || line[after..]
                    .chars()
                    .next()
                    .map(|c| c.is_whitespace())
                    .unwrap_or(false);
            if before_ok && after_ok {
                result.patches.push(BootPatch {
                    iso_offset: base_offset + line_start as u64 + index as u64,
                    original: phrase.as_bytes().to_vec(),
                    replacement: b"persistent  ".to_vec(),
                    source: source.to_string(),
                });
            }
            break;
        }
    }
}

fn kernel_path_matches(line: &str, profile: PersistenceProfile) -> bool {
    let lower = line.to_ascii_lowercase();
    match profile {
        PersistenceProfile::Casper => lower.contains("/casper/vmlinuz"),
        PersistenceProfile::DebianLive => lower.contains("/live/vmlinuz"),
    }
}

fn contains_token(line: &str, token: &str) -> bool {
    line.split_whitespace().any(|word| {
        word.trim_matches(|c: char| !c.is_ascii_alphanumeric() && c != '-' && c != '_') == token
    })
}

fn is_live_title(title: &str) -> bool {
    let lower = title.to_ascii_lowercase();
    lower.contains("try") || lower.contains("live") || lower.contains("persistent")
}

fn grub_title(line: &str) -> String {
    let rest = line["menuentry".len()..].trim_start();
    if let Some(start) = rest.find(['"', '\'']) {
        let quote = rest[start..].chars().next().unwrap();
        let after = &rest[start + 1..];
        if let Some(end) = after.find(quote) {
            return after[..end].to_string();
        }
    }
    rest.split_whitespace().next().unwrap_or("").to_string()
}

fn line_offsets(text: &str) -> Vec<(usize, &str)> {
    let mut lines = Vec::new();
    let mut offset = 0usize;
    for line in text.split_inclusive('\n') {
        lines.push((offset, line));
        offset += line.len();
    }
    if offset < text.len() {
        lines.push((offset, &text[offset..]));
    }
    lines
}

/// Apply patches to the device. All original bytes must match exactly; any
/// mismatch aborts the operation and restores what was already written.
pub fn apply_patches(
    dev: &mut dyn BlockDevice,
    patches: &[BootPatch],
    progress: &mut dyn FnMut(u64, u64),
) -> Result<()> {
    let total = patches.len() as u64;
    let mut applied: Vec<&BootPatch> = Vec::new();

    for patch in patches {
        let mut current = vec![0u8; patch.original.len()];
        if let Err(e) = dev.read_at(patch.iso_offset, &mut current) {
            restore_patches(dev, &applied)?;
            return Err(e.into());
        }
        if current == patch.replacement {
            // A previous attempt already applied this patch; keep it so a
            // later failure still restores the original bytes.
            applied.push(patch);
            progress(applied.len() as u64, total);
            continue;
        }
        if current != patch.original {
            restore_patches(dev, &applied)?;
            return Err(PersistenceError::Activation(format!(
                "boot config changed at offset {} in {}",
                patch.iso_offset, patch.source
            )));
        }
        if let Err(e) = dev.write_at(patch.iso_offset, &patch.replacement) {
            restore_patches(dev, &applied)?;
            return Err(e.into());
        }

        let mut check = vec![0u8; patch.replacement.len()];
        if let Err(e) = dev.read_at(patch.iso_offset, &mut check) {
            let _ = dev.write_at(patch.iso_offset, &patch.original);
            restore_patches(dev, &applied)?;
            return Err(e.into());
        }
        if check != patch.replacement {
            let _ = dev.write_at(patch.iso_offset, &patch.original);
            restore_patches(dev, &applied)?;
            return Err(PersistenceError::Activation(format!(
                "boot config write verification failed at offset {} in {}",
                patch.iso_offset, patch.source
            )));
        }
        applied.push(patch);
        progress(applied.len() as u64, total);
    }
    Ok(())
}

/// Restore original bytes for patches that were applied.
pub fn restore_patches(dev: &mut dyn BlockDevice, patches: &[&BootPatch]) -> Result<()> {
    let mut first_error = None;
    for patch in patches.iter().rev() {
        if let Err(e) = dev.write_at(patch.iso_offset, &patch.original)
            && first_error.is_none()
        {
            first_error = Some(e);
        }
    }
    match first_error {
        Some(e) => Err(e.into()),
        None => Ok(()),
    }
}

pub fn verify_patches(dev: &mut dyn BlockDevice, patches: &[BootPatch]) -> Result<()> {
    for patch in patches {
        let mut current = vec![0u8; patch.replacement.len()];
        dev.read_at(patch.iso_offset, &mut current)?;
        if current != patch.replacement {
            return Err(PersistenceError::Verify(format!(
                "boot patch at offset {} was not applied",
                patch.iso_offset
            )));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use hyntix_storage::MemBlockDevice;

    const CASPER_GRUB: &str = r#"
set timeout=10
menuentry "Try or Install Ubuntu" {
    set gfxpayload=keep
    linux /casper/vmlinuz quiet splash ---
    initrd /casper/initrd
}
menuentry "Boot from next volume" {
    linux /casper/vmlinuz quiet splash ---
}
"#;

    const DEBIAN_ISOLINUX: &str = r#"
label live
  menu label ^Live (amd64)
  kernel /live/vmlinuz
  append initrd=/live/initrd.img boot=live components
label live-persistence
  menu label ^Live (amd64 persistence)
  kernel /live/vmlinuz
  append initrd=/live/initrd.img boot=live components persistence
"#;

    #[test]
    fn patches_only_live_casper_entry() {
        let analysis = analyze(
            "/boot/grub/grub.cfg",
            CASPER_GRUB.as_bytes(),
            10_000,
            PersistenceProfile::Casper,
        );
        assert_eq!(analysis.live_entries, 1);
        assert!(!analysis.has_persistence);
        assert_eq!(analysis.patches.len(), 1);
        let patch = &analysis.patches[0];
        assert_eq!(patch.original, b"quiet splash");
        assert_eq!(patch.replacement, b"persistent  ");
        assert_eq!(
            patch.iso_offset,
            10_000 + CASPER_GRUB.find("quiet splash").unwrap() as u64
        );
    }

    #[test]
    fn does_not_patch_non_live_entry() {
        let text = r#"
menuentry "Boot from next volume" {
    linux /casper/vmlinuz quiet splash ---
}
"#;
        let analysis = analyze(
            "/boot/grub/grub.cfg",
            text.as_bytes(),
            0,
            PersistenceProfile::Casper,
        );
        assert_eq!(analysis.live_entries, 0);
        assert!(analysis.patches.is_empty());
    }

    #[test]
    fn detects_existing_persistent_keyword() {
        let text = r#"
menuentry "Try Ubuntu" {
    linux /casper/vmlinuz persistent quiet splash ---
}
"#;
        let analysis = analyze(
            "/boot/grub/grub.cfg",
            text.as_bytes(),
            0,
            PersistenceProfile::Casper,
        );
        assert!(analysis.has_persistence);
        assert!(analysis.patches.is_empty());
    }

    #[test]
    fn rejects_length_changing_patch() {
        let text = r#"
menuentry "Try Ubuntu" {
    linux /casper/vmlinuz maybe-something-else ---
}
"#;
        let analysis = analyze(
            "/boot/grub/grub.cfg",
            text.as_bytes(),
            0,
            PersistenceProfile::Casper,
        );
        assert!(!analysis.has_persistence);
        assert!(analysis.patches.is_empty());
    }

    #[test]
    fn detects_debian_builtin_persistence() {
        let analysis = analyze(
            "/isolinux/isolinux.cfg",
            DEBIAN_ISOLINUX.as_bytes(),
            0,
            PersistenceProfile::DebianLive,
        );
        assert!(analysis.has_persistence);
        assert!(analysis.patches.is_empty());
    }

    #[test]
    fn applies_and_restores_patches() {
        let mut dev = MemBlockDevice::new(64, 512).unwrap();
        let original = b"quiet splash".to_vec();
        let replacement = b"persistent  ".to_vec();
        dev.write_at(1000, &original).unwrap();
        let patch = BootPatch {
            iso_offset: 1000,
            original: original.clone(),
            replacement: replacement.clone(),
            source: "test".into(),
        };

        apply_patches(&mut dev, std::slice::from_ref(&patch), &mut |_, _| {}).unwrap();
        verify_patches(&mut dev, std::slice::from_ref(&patch)).unwrap();
        let mut readback = vec![0u8; replacement.len()];
        dev.read_at(1000, &mut readback).unwrap();
        assert_eq!(readback, replacement);

        restore_patches(&mut dev, &[&patch]).unwrap();
        let mut readback = vec![0u8; original.len()];
        dev.read_at(1000, &mut readback).unwrap();
        assert_eq!(readback, original);
    }

    #[test]
    fn skips_an_already_applied_patch() {
        let mut dev = MemBlockDevice::new(64, 512).unwrap();
        let replacement = b"persistent  ".to_vec();
        dev.write_at(1000, &replacement).unwrap();
        let patch = BootPatch {
            iso_offset: 1000,
            original: b"quiet splash".to_vec(),
            replacement: replacement.clone(),
            source: "test".into(),
        };
        apply_patches(&mut dev, std::slice::from_ref(&patch), &mut |_, _| {}).unwrap();
        verify_patches(&mut dev, std::slice::from_ref(&patch)).unwrap();
        let mut readback = vec![0u8; replacement.len()];
        dev.read_at(1000, &mut readback).unwrap();
        assert_eq!(readback, replacement);
    }

    #[test]
    fn aborts_when_original_bytes_changed() {
        let mut dev = MemBlockDevice::new(64, 512).unwrap();
        dev.write_at(1000, b"different text").unwrap();
        let patch = BootPatch {
            iso_offset: 1000,
            original: b"quiet splash".to_vec(),
            replacement: b"persistent  ".to_vec(),
            source: "test".into(),
        };
        let err = apply_patches(&mut dev, &[patch], &mut |_, _| {}).unwrap_err();
        assert!(matches!(err, PersistenceError::Activation(_)));
        let mut readback = vec![0u8; 14];
        dev.read_at(1000, &mut readback).unwrap();
        assert_eq!(&readback, b"different text");
    }
}
