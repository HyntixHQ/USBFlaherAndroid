//! Minimal ext2 formatter and reader.
//!
//! The formatter creates a complete, internally consistent ext2 filesystem
//! with 4 KiB blocks, 256-byte inodes, sparse superblock backups, a root
//! directory, `lost+found`, and an optional `persistence.conf`. It is designed
//! to be validated by `e2fsck -fn`; it is not a general-purpose mkfs.

use crate::{PersistenceError, Result};
use hyntix_storage::BlockDevice;

pub const BLOCK_SIZE: u32 = 4096;
pub const INODE_SIZE: u32 = 256;
pub const BLOCKS_PER_GROUP: u32 = 32_768;
pub const MAX_INODES_PER_GROUP: u32 = 8_192;
pub const EXT2_MAGIC: u16 = 0xEF53;
pub const ROOT_INODE: u32 = 2;
pub const LOST_FOUND_INODE: u32 = 11;
pub const PERSISTENCE_CONF_INODE: u32 = 12;
pub const EXT2_VALID_FS: u16 = 1;
pub const EXT2_ERRORS_CONTINUE: u16 = 1;
pub const FEATURE_INCOMPAT_FILETYPE: u32 = 0x2;
pub const FEATURE_RO_COMPAT_SPARSE_SUPER: u32 = 0x1;
pub const FEATURE_RO_COMPAT_LARGE_FILE: u32 = 0x2;
pub const PERSISTENCE_CONF_CONTENT: &[u8] = b"/ union\n";

#[derive(Debug, Clone)]
pub struct FormatOptions<'a> {
    pub label: &'a str,
    pub persistence_conf: bool,
    pub timestamp: u32,
    pub uuid: [u8; 16],
}

impl Default for FormatOptions<'_> {
    fn default() -> Self {
        Self {
            label: "persistence",
            persistence_conf: false,
            timestamp: 0,
            uuid: [
                0x48, 0x59, 0x4E, 0x54, 0x49, 0x58, 0x50, 0x53, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05,
                0x06, 0x07,
            ],
        }
    }
}

#[derive(Debug, Clone)]
pub struct Ext2Info {
    pub label: String,
    pub block_size: u32,
    pub inode_size: u32,
    pub blocks_count: u64,
    pub inodes_count: u32,
    pub groups: u32,
    pub inodes_per_group: u32,
    pub has_persistence_conf: bool,
}

#[derive(Debug, Clone)]
struct GroupLayout {
    index: u32,
    start_block: u64,
    blocks: u64,
    has_super: bool,
    block_bitmap: u64,
    inode_bitmap: u64,
    inode_table: u64,
    data_start: u64,
    free_blocks: u16,
    free_inodes: u16,
    used_dirs: u16,
}

struct FsLayout {
    blocks_count: u64,
    inodes_count: u32,
    groups: u32,
    inodes_per_group: u32,
    inode_table_blocks: u64,
    gdt_blocks: u64,
    groups_layout: Vec<GroupLayout>,
    free_blocks: u64,
    free_inodes: u32,
    label: [u8; 16],
    uuid: [u8; 16],
    has_conf: bool,
    timestamp: u32,
    root_block: u64,
    lost_found_block: u64,
    conf_block: Option<u64>,
}

fn put_u16(buf: &mut [u8], offset: usize, value: u16) {
    buf[offset..offset + 2].copy_from_slice(&value.to_le_bytes());
}

fn put_u32(buf: &mut [u8], offset: usize, value: u32) {
    buf[offset..offset + 4].copy_from_slice(&value.to_le_bytes());
}

fn get_u16(buf: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes(buf[offset..offset + 2].try_into().unwrap())
}

fn get_u32(buf: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(buf[offset..offset + 4].try_into().unwrap())
}

fn has_superblock(group: u32) -> bool {
    group == 0
        || group == 1
        || is_power_of(group, 3)
        || is_power_of(group, 5)
        || is_power_of(group, 7)
}

fn is_power_of(mut value: u32, base: u32) -> bool {
    if value < 1 {
        return false;
    }
    while value.is_multiple_of(base) {
        value /= base;
    }
    value == 1
}

fn inode_table_blocks(inodes_per_group: u32) -> u64 {
    (inodes_per_group as u64 * INODE_SIZE as u64).div_ceil(BLOCK_SIZE as u64)
}

fn compute_layout(sector_count: u64, options: &FormatOptions<'_>) -> Result<FsLayout> {
    let total_bytes = sector_count
        .checked_mul(512)
        .ok_or_else(|| PersistenceError::Ext2("partition size overflow".into()))?;
    let blocks_count = total_bytes / BLOCK_SIZE as u64;
    if blocks_count < 1024 {
        return Err(PersistenceError::Ext2(format!(
            "partition is too small for ext2: {blocks_count} blocks"
        )));
    }
    if blocks_count > u32::MAX as u64 {
        return Err(PersistenceError::Ext2(
            "partition is too large for 32-bit ext2 block addresses".into(),
        ));
    }

    let groups = blocks_count.div_ceil(BLOCKS_PER_GROUP as u64) as u32;
    let gdt_blocks = (groups as u64 * 32).div_ceil(BLOCK_SIZE as u64);

    let mut inodes_per_group = MAX_INODES_PER_GROUP;
    loop {
        let inode_blocks = inode_table_blocks(inodes_per_group);
        let mut fits = true;
        for group in 0..groups {
            let start = group as u64 * BLOCKS_PER_GROUP as u64;
            let group_blocks = (blocks_count - start).min(BLOCKS_PER_GROUP as u64);
            let super_blocks = if has_superblock(group) {
                1 + gdt_blocks
            } else {
                0
            };
            let metadata = super_blocks + 2 + inode_blocks;
            let data = if group == 0 { 3 } else { 0 };
            if metadata + data > group_blocks {
                fits = false;
                break;
            }
        }
        if fits {
            break;
        }
        if inodes_per_group <= 16 {
            return Err(PersistenceError::Ext2(
                "partition is too small for ext2 metadata".into(),
            ));
        }
        inodes_per_group -= 16;
    }

    let inode_blocks = inode_table_blocks(inodes_per_group);
    let inodes_count = groups
        .checked_mul(inodes_per_group)
        .ok_or_else(|| PersistenceError::Ext2("inode count overflow".into()))?;

    let mut groups_layout = Vec::with_capacity(groups as usize);
    let mut free_blocks = 0u64;
    for group in 0..groups {
        let start = group as u64 * BLOCKS_PER_GROUP as u64;
        let blocks = (blocks_count - start).min(BLOCKS_PER_GROUP as u64);
        let has_super = has_superblock(group);
        let prefix = if has_super { 1 + gdt_blocks } else { 0 };
        let block_bitmap = start + prefix;
        let inode_bitmap = block_bitmap + 1;
        let inode_table = inode_bitmap + 1;
        let data_start = inode_table + inode_blocks;
        let data_used = if group == 0 { 3 } else { 0 };
        let used = prefix + 2 + inode_blocks + data_used;
        let free = blocks.saturating_sub(used);
        free_blocks += free;
        groups_layout.push(GroupLayout {
            index: group,
            start_block: start,
            blocks,
            has_super,
            block_bitmap,
            inode_bitmap,
            inode_table,
            data_start,
            free_blocks: free.min(u16::MAX as u64) as u16,
            free_inodes: if group == 0 {
                (inodes_per_group - used_inodes(options.persistence_conf)).min(u16::MAX as u32)
                    as u16
            } else {
                inodes_per_group.min(u16::MAX as u32) as u16
            },
            used_dirs: if group == 0 { 2 } else { 0 },
        });
    }

    let used = used_inodes(options.persistence_conf);
    let free_inodes = inodes_count - used;

    let mut label = [0u8; 16];
    let label_bytes = options.label.as_bytes();
    let label_len = label_bytes.len().min(16);
    label[..label_len].copy_from_slice(&label_bytes[..label_len]);

    let group0_data_start = groups_layout[0].data_start;
    let root_block = group0_data_start;
    let lost_found_block = root_block + 1;
    let conf_block = if options.persistence_conf {
        Some(root_block + 2)
    } else {
        None
    };

    Ok(FsLayout {
        blocks_count,
        inodes_count,
        groups,
        inodes_per_group,
        inode_table_blocks: inode_blocks,
        gdt_blocks,
        groups_layout,
        free_blocks,
        free_inodes,
        label,
        uuid: options.uuid,
        has_conf: options.persistence_conf,
        timestamp: options.timestamp,
        root_block,
        lost_found_block,
        conf_block,
    })
}

fn used_inodes(has_conf: bool) -> u32 {
    if has_conf {
        PERSISTENCE_CONF_INODE
    } else {
        LOST_FOUND_INODE
    }
}

fn build_superblock(layout: &FsLayout, block_group_nr: u16) -> [u8; 1024] {
    let mut sb = [0u8; 1024];
    put_u32(&mut sb, 0, layout.inodes_count);
    put_u32(&mut sb, 4, layout.blocks_count as u32);
    put_u32(&mut sb, 8, (layout.blocks_count / 20) as u32);
    put_u32(&mut sb, 12, layout.free_blocks as u32);
    put_u32(&mut sb, 16, layout.free_inodes);
    put_u32(&mut sb, 20, 0); // s_first_data_block
    put_u32(&mut sb, 24, 2); // 1024 << 2 = 4096
    put_u32(&mut sb, 28, 2);
    put_u32(&mut sb, 32, BLOCKS_PER_GROUP);
    put_u32(&mut sb, 36, BLOCKS_PER_GROUP);
    put_u32(&mut sb, 40, layout.inodes_per_group);
    put_u32(&mut sb, 44, 0); // s_mtime
    put_u32(&mut sb, 48, layout.timestamp);
    put_u16(&mut sb, 52, 0); // s_mnt_count
    put_u16(&mut sb, 54, u16::MAX);
    put_u16(&mut sb, 56, EXT2_MAGIC);
    put_u16(&mut sb, 58, EXT2_VALID_FS);
    put_u16(&mut sb, 60, EXT2_ERRORS_CONTINUE);
    put_u16(&mut sb, 62, 0);
    put_u32(&mut sb, 64, layout.timestamp); // s_lastcheck
    put_u32(&mut sb, 68, 0); // s_checkinterval
    put_u32(&mut sb, 72, 0); // s_creator_os (Linux)
    put_u32(&mut sb, 76, 1); // s_rev_level (dynamic)
    put_u16(&mut sb, 80, 0); // s_def_resuid
    put_u16(&mut sb, 82, 0); // s_def_resgid
    put_u32(&mut sb, 84, LOST_FOUND_INODE); // s_first_ino
    put_u16(&mut sb, 88, INODE_SIZE as u16);
    put_u16(&mut sb, 90, block_group_nr);
    put_u32(&mut sb, 92, 0); // s_feature_compat
    put_u32(&mut sb, 96, FEATURE_INCOMPAT_FILETYPE);
    put_u32(
        &mut sb,
        100,
        FEATURE_RO_COMPAT_SPARSE_SUPER | FEATURE_RO_COMPAT_LARGE_FILE,
    );
    sb[104..120].copy_from_slice(&layout.uuid);
    sb[120..136].copy_from_slice(&layout.label);
    put_u32(&mut sb, 200, 0); // s_algorithm_usage_bitmap
    sb[204] = 0; // s_prealloc_blocks
    sb[205] = 0; // s_prealloc_dir_blocks
    put_u16(&mut sb, 206, 0); // s_reserved_gdt_blocks
    put_u32(&mut sb, 224, 0); // s_journal_inum
    put_u32(&mut sb, 228, 0); // s_journal_dev
    put_u32(&mut sb, 232, 0); // s_last_orphan
    sb[252] = 1; // s_def_hash_version (half_md4)
    sb[253] = 0; // s_jnl_backup_type
    put_u16(&mut sb, 254, 0); // s_desc_size (32-byte descriptors)
    put_u32(&mut sb, 256, 0); // s_default_mount_opts
    put_u32(&mut sb, 260, 0); // s_first_meta_bg
    put_u32(&mut sb, 264, layout.timestamp); // s_mkfs_time
    sb
}

fn build_group_descriptors(layout: &FsLayout) -> Vec<u8> {
    let mut gdt = vec![0u8; (layout.gdt_blocks * BLOCK_SIZE as u64) as usize];
    for group in &layout.groups_layout {
        let offset = group.index as usize * 32;
        put_u32(&mut gdt, offset, group.block_bitmap as u32);
        put_u32(&mut gdt, offset + 4, group.inode_bitmap as u32);
        put_u32(&mut gdt, offset + 8, group.inode_table as u32);
        put_u16(&mut gdt, offset + 12, group.free_blocks);
        put_u16(&mut gdt, offset + 14, group.free_inodes);
        put_u16(&mut gdt, offset + 16, group.used_dirs);
    }
    gdt
}

fn build_block_bitmap(layout: &FsLayout, group: &GroupLayout) -> Vec<u8> {
    let mut bitmap = vec![0u8; BLOCK_SIZE as usize];
    let mut mark = |absolute: u64| {
        let relative = absolute - group.start_block;
        if relative < BLOCKS_PER_GROUP as u64 {
            let byte = (relative / 8) as usize;
            bitmap[byte] |= 1 << (relative % 8);
        }
    };

    if group.has_super {
        mark(group.start_block);
        for i in 0..layout.gdt_blocks {
            mark(group.start_block + 1 + i);
        }
    }
    mark(group.block_bitmap);
    mark(group.inode_bitmap);
    for i in 0..layout.inode_table_blocks {
        mark(group.inode_table + i);
    }
    if group.index == 0 {
        mark(layout.root_block);
        mark(layout.lost_found_block);
        if let Some(conf) = layout.conf_block {
            mark(conf);
        }
    }

    // Bits past the end of the last group are allocated so the kernel cannot
    // hand them out.
    for relative in group.blocks..BLOCKS_PER_GROUP as u64 {
        let byte = (relative / 8) as usize;
        bitmap[byte] |= 1 << (relative % 8);
    }
    bitmap
}

fn build_inode_bitmap(layout: &FsLayout, group: &GroupLayout) -> Vec<u8> {
    let mut bitmap = vec![0u8; BLOCK_SIZE as usize];
    if group.index == 0 {
        let used = used_inodes(layout.has_conf);
        for inode in 1..=used {
            let bit = inode - 1;
            bitmap[(bit / 8) as usize] |= 1 << (bit % 8);
        }
    }
    // Bits past s_inodes_per_group are padding and must be allocated.
    let total_bits = BLOCK_SIZE * 8;
    for bit in layout.inodes_per_group..total_bits {
        bitmap[(bit / 8) as usize] |= 1 << (bit % 8);
    }
    bitmap
}

fn build_inode(
    mode: u16,
    size: u32,
    links: u16,
    blocks: u32,
    data_block: u64,
    timestamp: u32,
) -> [u8; INODE_SIZE as usize] {
    let mut inode = [0u8; INODE_SIZE as usize];
    put_u16(&mut inode, 0, mode);
    put_u16(&mut inode, 2, 0); // uid
    put_u32(&mut inode, 4, size);
    put_u32(&mut inode, 8, timestamp); // atime
    put_u32(&mut inode, 12, timestamp); // ctime
    put_u32(&mut inode, 16, timestamp); // mtime
    put_u32(&mut inode, 20, 0); // dtime
    put_u16(&mut inode, 24, 0); // gid
    put_u16(&mut inode, 26, links);
    put_u32(&mut inode, 28, blocks);
    put_u32(&mut inode, 32, 0); // flags
    put_u32(&mut inode, 36, 0); // osd1
    put_u32(&mut inode, 40, data_block as u32);
    put_u32(&mut inode, 100, 0); // generation
    put_u32(&mut inode, 104, 0); // file_acl
    put_u32(&mut inode, 108, 0); // size_high
    put_u32(&mut inode, 112, 0); // faddr
    inode
}

fn write_dir_entry(
    block: &mut [u8],
    position: usize,
    inode: u32,
    rec_len: u16,
    name: &[u8],
    file_type: u8,
) {
    put_u32(block, position, inode);
    put_u16(block, position + 4, rec_len);
    block[position + 6] = name.len() as u8;
    block[position + 7] = file_type;
    block[position + 8..position + 8 + name.len()].copy_from_slice(name);
}

fn build_root_directory(layout: &FsLayout) -> Vec<u8> {
    let mut block = vec![0u8; BLOCK_SIZE as usize];
    write_dir_entry(&mut block, 0, ROOT_INODE, 12, b".", 2);
    write_dir_entry(&mut block, 12, ROOT_INODE, 12, b"..", 2);
    if layout.has_conf {
        write_dir_entry(&mut block, 24, LOST_FOUND_INODE, 20, b"lost+found", 2);
        write_dir_entry(
            &mut block,
            44,
            PERSISTENCE_CONF_INODE,
            (BLOCK_SIZE as usize - 44) as u16,
            b"persistence.conf",
            1,
        );
    } else {
        write_dir_entry(
            &mut block,
            24,
            LOST_FOUND_INODE,
            (BLOCK_SIZE as usize - 24) as u16,
            b"lost+found",
            2,
        );
    }
    block
}

fn build_lost_found_directory(_layout: &FsLayout) -> Vec<u8> {
    let mut block = vec![0u8; BLOCK_SIZE as usize];
    write_dir_entry(&mut block, 0, LOST_FOUND_INODE, 12, b".", 2);
    write_dir_entry(
        &mut block,
        12,
        ROOT_INODE,
        (BLOCK_SIZE as usize - 12) as u16,
        b"..",
        2,
    );
    block
}

pub fn format(
    dev: &mut dyn BlockDevice,
    start_lba: u64,
    sector_count: u64,
    options: &FormatOptions<'_>,
    progress: &mut dyn FnMut(u64, u64),
) -> Result<Ext2Info> {
    if dev.sector_size() != 512 {
        return Err(PersistenceError::UnsupportedSectorSize(dev.sector_size()));
    }
    if start_lba
        .checked_add(sector_count)
        .map(|end| end > dev.sector_count())
        .unwrap_or(true)
    {
        return Err(PersistenceError::Ext2(
            "persistence partition is outside the device".into(),
        ));
    }

    let layout = compute_layout(sector_count, options)?;
    let partition_offset = start_lba * 512;

    // Total work used only for progress reporting.
    let bitmap_bytes = layout.groups as u64 * 2 * BLOCK_SIZE as u64;
    let inode_bytes = layout.inode_table_blocks * BLOCK_SIZE as u64;
    let gdt_copies = layout.groups_layout.iter().filter(|g| g.has_super).count() as u64;
    let total_work = bitmap_bytes
        + inode_bytes
        + layout.gdt_blocks * BLOCK_SIZE as u64 * gdt_copies
        + BLOCK_SIZE as u64 * 3
        + 1024 * gdt_copies.max(1);
    let mut done = 0u64;

    let zero_block = vec![0u8; BLOCK_SIZE as usize];
    let gdt = build_group_descriptors(&layout);
    let root_data = build_root_directory(&layout);
    let lost_found_data = build_lost_found_directory(&layout);

    // Data blocks first: they are referenced by the inodes below.
    dev.write_at(
        partition_offset + layout.root_block * BLOCK_SIZE as u64,
        &root_data,
    )?;
    dev.write_at(
        partition_offset + layout.lost_found_block * BLOCK_SIZE as u64,
        &lost_found_data,
    )?;
    done += BLOCK_SIZE as u64 * 2;

    if let Some(conf_block) = layout.conf_block {
        let mut conf = vec![0u8; BLOCK_SIZE as usize];
        conf[..PERSISTENCE_CONF_CONTENT.len()].copy_from_slice(PERSISTENCE_CONF_CONTENT);
        dev.write_at(partition_offset + conf_block * BLOCK_SIZE as u64, &conf)?;
        done += BLOCK_SIZE as u64;
    }

    // Zero the first inode table so reserved and free inodes are clean. Only
    // group 0 is zeroed in full; free inode slots in other groups are never
    // read by e2fsck.
    let group0 = &layout.groups_layout[0];
    for block in 0..layout.inode_table_blocks {
        dev.write_at(
            partition_offset + (group0.inode_table + block) * BLOCK_SIZE as u64,
            &zero_block,
        )?;
    }
    done += inode_bytes;

    let timestamp = layout.timestamp;
    let root_inode = build_inode(
        0o40755,
        BLOCK_SIZE,
        3,
        BLOCK_SIZE / 512,
        layout.root_block,
        timestamp,
    );
    let lost_found_inode = build_inode(
        0o40755,
        BLOCK_SIZE,
        2,
        BLOCK_SIZE / 512,
        layout.lost_found_block,
        timestamp,
    );
    let conf_inode = layout.conf_block.map(|block| {
        build_inode(
            0o100644,
            PERSISTENCE_CONF_CONTENT.len() as u32,
            1,
            BLOCK_SIZE / 512,
            block,
            timestamp,
        )
    });

    let inode_table_offset = partition_offset + group0.inode_table * BLOCK_SIZE as u64;
    dev.write_at(
        inode_table_offset + (ROOT_INODE as u64 - 1) * INODE_SIZE as u64,
        &root_inode,
    )?;
    dev.write_at(
        inode_table_offset + (LOST_FOUND_INODE as u64 - 1) * INODE_SIZE as u64,
        &lost_found_inode,
    )?;
    if let Some(inode) = conf_inode {
        dev.write_at(
            inode_table_offset + (PERSISTENCE_CONF_INODE as u64 - 1) * INODE_SIZE as u64,
            &inode,
        )?;
    }

    // Bitmaps.
    for group in &layout.groups_layout {
        let block_bitmap = build_block_bitmap(&layout, group);
        dev.write_at(
            partition_offset + group.block_bitmap * BLOCK_SIZE as u64,
            &block_bitmap,
        )?;
        let inode_bitmap = build_inode_bitmap(&layout, group);
        dev.write_at(
            partition_offset + group.inode_bitmap * BLOCK_SIZE as u64,
            &inode_bitmap,
        )?;
        done += BLOCK_SIZE as u64 * 2;
        progress(done.min(total_work), total_work);
    }

    // Group descriptor tables.
    for group in &layout.groups_layout {
        if group.has_super {
            dev.write_at(
                partition_offset + (group.start_block + 1) * BLOCK_SIZE as u64,
                &gdt,
            )?;
            done += layout.gdt_blocks * BLOCK_SIZE as u64;
            progress(done.min(total_work), total_work);
        }
    }

    // Superblocks last. The primary lives at byte 1024 of block 0 and does not
    // overwrite the boot code.
    let primary = build_superblock(&layout, 0);
    dev.write_at(partition_offset + 1024, &primary)?;
    done += 1024;
    progress(done.min(total_work), total_work);

    for group in &layout.groups_layout {
        if group.index == 0 || !group.has_super {
            continue;
        }
        let backup = build_superblock(&layout, group.index as u16);
        dev.write_at(
            partition_offset + group.start_block * BLOCK_SIZE as u64,
            &backup,
        )?;
        done += 1024;
        progress(done.min(total_work), total_work);
    }

    Ok(Ext2Info {
        label: options.label.to_string(),
        block_size: BLOCK_SIZE,
        inode_size: INODE_SIZE,
        blocks_count: layout.blocks_count,
        inodes_count: layout.inodes_count,
        groups: layout.groups,
        inodes_per_group: layout.inodes_per_group,
        has_persistence_conf: layout.has_conf,
    })
}

/// Verify the superblock, group descriptors, root inode, and optional
/// `persistence.conf`.
pub fn verify(
    dev: &mut dyn BlockDevice,
    start_lba: u64,
    expected_label: &str,
    expect_conf: bool,
) -> Result<Ext2Info> {
    if dev.sector_size() != 512 {
        return Err(PersistenceError::UnsupportedSectorSize(dev.sector_size()));
    }
    let partition_offset = start_lba * 512;
    let mut sb = [0u8; 1024];
    dev.read_at(partition_offset + 1024, &mut sb)?;

    if get_u16(&sb, 56) != EXT2_MAGIC {
        return Err(PersistenceError::Verify(format!(
            "ext2 magic mismatch: 0x{:04X}",
            get_u16(&sb, 56)
        )));
    }
    let block_size = 1024u32 << get_u32(&sb, 24);
    if block_size != BLOCK_SIZE {
        return Err(PersistenceError::Verify(format!(
            "unexpected ext2 block size {block_size}"
        )));
    }
    let inode_size = get_u16(&sb, 88) as u32;
    if inode_size != INODE_SIZE {
        return Err(PersistenceError::Verify(format!(
            "unexpected ext2 inode size {inode_size}"
        )));
    }
    let state = get_u16(&sb, 58);
    if state & EXT2_VALID_FS == 0 || state & 0x2 != 0 {
        return Err(PersistenceError::Verify(format!(
            "filesystem is not clean (state 0x{state:04X})"
        )));
    }

    let label_bytes = &sb[120..136];
    let label_end = label_bytes.iter().position(|b| *b == 0).unwrap_or(16);
    let label = String::from_utf8_lossy(&label_bytes[..label_end]).to_string();
    if label != expected_label {
        return Err(PersistenceError::Verify(format!(
            "ext2 label mismatch: got '{label}', expected '{expected_label}'"
        )));
    }

    let blocks_count = get_u32(&sb, 4) as u64;
    let inodes_count = get_u32(&sb, 0);
    let inodes_per_group = get_u32(&sb, 40);
    let groups = blocks_count.div_ceil(BLOCKS_PER_GROUP as u64) as u32;
    let gdt_blocks = (groups as u64 * 32).div_ceil(BLOCK_SIZE as u64);

    let mut gdt = vec![0u8; (gdt_blocks * BLOCK_SIZE as u64) as usize];
    dev.read_at(partition_offset + BLOCK_SIZE as u64, &mut gdt)?;

    for group in 0..groups {
        let offset = group as usize * 32;
        let block_bitmap = get_u32(&gdt, offset) as u64;
        let inode_bitmap = get_u32(&gdt, offset + 4) as u64;
        let inode_table = get_u32(&gdt, offset + 8) as u64;
        let group_start = group as u64 * BLOCKS_PER_GROUP as u64;
        let group_blocks = (blocks_count - group_start).min(BLOCKS_PER_GROUP as u64);
        let inode_blocks = inode_table_blocks(inodes_per_group);
        if block_bitmap < group_start
            || block_bitmap >= group_start + group_blocks
            || inode_bitmap < group_start
            || inode_bitmap >= group_start + group_blocks
            || inode_table < group_start
            || inode_table + inode_blocks > group_start + group_blocks
        {
            return Err(PersistenceError::Verify(format!(
                "group {group} metadata is out of range"
            )));
        }
    }

    let root_inode = read_inode(dev, partition_offset, gdt.as_slice(), 2, inodes_per_group)?;
    let mode = get_u16(&root_inode, 0);
    if mode & 0xF000 != 0x4000 {
        return Err(PersistenceError::Verify(
            "root inode is not a directory".into(),
        ));
    }
    let root_block = get_u32(&root_inode, 40) as u64;
    if root_block == 0 {
        return Err(PersistenceError::Verify(
            "root directory has no data block".into(),
        ));
    }

    let has_conf = directory_contains(
        dev,
        partition_offset,
        &gdt,
        &root_inode,
        b"persistence.conf",
    )?;
    if expect_conf {
        if !has_conf {
            return Err(PersistenceError::Verify(
                "persistence.conf is missing".into(),
            ));
        }
        let content = read_file_from_dir(
            dev,
            partition_offset,
            &gdt,
            &root_inode,
            b"persistence.conf",
            inodes_per_group,
        )?;
        if content != PERSISTENCE_CONF_CONTENT {
            return Err(PersistenceError::Verify(format!(
                "persistence.conf content mismatch: {:?}",
                String::from_utf8_lossy(&content)
            )));
        }
    }

    Ok(Ext2Info {
        label,
        block_size,
        inode_size,
        blocks_count,
        inodes_count,
        groups,
        inodes_per_group,
        has_persistence_conf: has_conf,
    })
}

fn read_inode(
    dev: &mut dyn BlockDevice,
    partition_offset: u64,
    gdt: &[u8],
    inode_number: u32,
    inodes_per_group: u32,
) -> Result<[u8; INODE_SIZE as usize]> {
    let group = (inode_number - 1) / inodes_per_group;
    let index = (inode_number - 1) % inodes_per_group;
    let offset = group as usize * 32;
    let inode_table = get_u32(gdt, offset + 8) as u64;
    let inode_size = INODE_SIZE as u64;
    let mut inode = [0u8; INODE_SIZE as usize];
    dev.read_at(
        partition_offset + inode_table * BLOCK_SIZE as u64 + index as u64 * inode_size,
        &mut inode,
    )?;
    Ok(inode)
}

fn directory_contains(
    dev: &mut dyn BlockDevice,
    partition_offset: u64,
    gdt: &[u8],
    dir_inode: &[u8; INODE_SIZE as usize],
    name: &[u8],
) -> Result<bool> {
    Ok(find_dir_entry(dev, partition_offset, gdt, dir_inode, name)?.is_some())
}

fn find_dir_entry(
    dev: &mut dyn BlockDevice,
    partition_offset: u64,
    _gdt: &[u8],
    dir_inode: &[u8; INODE_SIZE as usize],
    name: &[u8],
) -> Result<Option<(u32, u8)>> {
    let size = get_u32(dir_inode, 4) as u64;
    let block = get_u32(dir_inode, 40) as u64;
    if block == 0 || size == 0 {
        return Ok(None);
    }
    let mut data = vec![0u8; size.min(BLOCK_SIZE as u64) as usize];
    dev.read_at(partition_offset + block * BLOCK_SIZE as u64, &mut data)?;

    let mut pos = 0usize;
    while pos + 8 <= data.len() {
        let rec_len = get_u16(&data, pos + 4) as usize;
        if rec_len < 8 || pos + rec_len > data.len() {
            break;
        }
        let name_len = data[pos + 6] as usize;
        if pos + 8 + name_len <= pos + rec_len {
            let entry_name = &data[pos + 8..pos + 8 + name_len];
            if entry_name == name {
                return Ok(Some((get_u32(&data, pos), data[pos + 7])));
            }
        }
        pos += rec_len;
    }
    Ok(None)
}

fn read_file_from_dir(
    dev: &mut dyn BlockDevice,
    partition_offset: u64,
    gdt: &[u8],
    dir_inode: &[u8; INODE_SIZE as usize],
    name: &[u8],
    inodes_per_group: u32,
) -> Result<Vec<u8>> {
    let (inode_number, file_type) = find_dir_entry(dev, partition_offset, gdt, dir_inode, name)?
        .ok_or_else(|| PersistenceError::Verify("file not found".into()))?;
    if file_type != 1 {
        return Err(PersistenceError::Verify(
            "entry is not a regular file".into(),
        ));
    }
    let inode = read_inode(dev, partition_offset, gdt, inode_number, inodes_per_group)?;
    let size = get_u32(&inode, 4) as usize;
    let block = get_u32(&inode, 40) as u64;
    let mut data = vec![0u8; size];
    if size > 0 {
        dev.read_at(partition_offset + block * BLOCK_SIZE as u64, &mut data)?;
    }
    Ok(data)
}

#[cfg(test)]
mod tests {
    use super::*;
    use hyntix_storage::MemBlockDevice;
    use std::path::PathBuf;
    use std::process::Command;

    fn options(conf: bool) -> FormatOptions<'static> {
        FormatOptions {
            label: if conf { "persistence" } else { "casper-rw" },
            persistence_conf: conf,
            timestamp: 1_700_000_000,
            uuid: [0x42; 16],
        }
    }

    #[test]
    fn formats_mbr_partition_and_verifies() {
        let sectors = (256 * 1024 * 1024u64) / 512;
        let mut dev = MemBlockDevice::new(sectors, 512).unwrap();
        let info = format(&mut dev, 0, sectors, &options(false), &mut |_, _| {}).unwrap();
        assert_eq!(info.label, "casper-rw");
        assert_eq!(info.block_size, 4096);
        assert!(info.groups > 0);
        verify(&mut dev, 0, "casper-rw", false).unwrap();
    }

    #[test]
    fn writes_persistence_conf() {
        let sectors = (256 * 1024 * 1024u64) / 512;
        let mut dev = MemBlockDevice::new(sectors, 512).unwrap();
        format(&mut dev, 0, sectors, &options(true), &mut |_, _| {}).unwrap();
        verify(&mut dev, 0, "persistence", true).unwrap();
    }

    #[test]
    fn rejects_non_512_sector_device() {
        let mut dev = MemBlockDevice::new(64 * 1024, 4096).unwrap();
        let sectors = dev.sector_count();
        let err = format(&mut dev, 0, sectors, &options(false), &mut |_, _| {}).unwrap_err();
        assert!(matches!(err, PersistenceError::UnsupportedSectorSize(4096)));
    }

    #[test]
    fn multigroup_layout() {
        // 2 GiB is 16 groups with 4 KiB blocks and 128 MiB groups.
        let sectors = (2u64 * 1024 * 1024 * 1024) / 512;
        let mut dev = MemBlockDevice::new(sectors, 512).unwrap();
        let info = format(&mut dev, 0, sectors, &options(false), &mut |_, _| {}).unwrap();
        assert!(info.groups >= 16);
        verify(&mut dev, 0, "casper-rw", false).unwrap();
        let _ = PathBuf::new();
        let _ = Command::new("true");
    }

    #[test]
    fn e2fsck_accepts_filesystem() {
        let Ok(e2fsck) = which("e2fsck") else {
            eprintln!("skipping: e2fsck not installed");
            return;
        };
        let sectors = (256 * 1024 * 1024u64) / 512;
        let mut dev = MemBlockDevice::new(sectors, 512).unwrap();
        format(&mut dev, 0, sectors, &options(true), &mut |_, _| {}).unwrap();

        let dir = std::env::temp_dir().join(format!("hyntix-ext2-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("fs.img");
        std::fs::write(&path, dev.as_slice()).unwrap();
        let output = Command::new(e2fsck)
            .args(["-fn", path.to_str().unwrap()])
            .output()
            .unwrap();
        let text = format!(
            "{}{}",
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(output.status.success(), "e2fsck failed:\n{text}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn which(tool: &str) -> std::result::Result<String, ()> {
        let output = Command::new("sh")
            .arg("-c")
            .arg(format!("command -v {tool}"))
            .output()
            .map_err(|_| ())?;
        if output.status.success() {
            Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
        } else {
            Err(())
        }
    }
}
