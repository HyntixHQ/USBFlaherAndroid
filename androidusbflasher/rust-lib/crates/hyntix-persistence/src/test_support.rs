//! Deterministic ISO9660 images for tests.

pub enum Node {
    Dir {
        name: &'static str,
        children: Vec<Node>,
    },
    File {
        name: &'static str,
        data: Vec<u8>,
    },
}

struct BuiltNode {
    name: String,
    is_dir: bool,
    extent: u32,
    data: Vec<u8>,
    children: Vec<BuiltNode>,
}

/// Build a minimal ISO9660 image containing `children` under the root.
pub fn build_iso(children: Vec<Node>) -> Vec<u8> {
    let mut root = BuiltNode {
        name: String::new(),
        is_dir: true,
        extent: 0,
        data: Vec::new(),
        children: children.into_iter().map(build_node).collect(),
    };

    let mut next_sector = 20u32;
    assign(&mut root, &mut next_sector);
    let total_sectors = next_sector as usize;
    let mut image = vec![0u8; total_sectors * 2048];

    write_pvd(&mut image, &root, total_sectors as u32);
    write_node(&root, root.extent, &mut image);

    image
}

pub fn build_casper_iso(grub_cfg: Option<&str>) -> Vec<u8> {
    let grub = grub_cfg.unwrap_or(
        "menuentry \"Try or Install Ubuntu\" {\n linux /casper/vmlinuz quiet splash ---\n initrd /casper/initrd\n}\n",
    );
    build_iso(vec![
        Node::Dir {
            name: "casper",
            children: vec![
                Node::File {
                    name: "vmlinuz",
                    data: vec![0u8; 4096],
                },
                Node::File {
                    name: "initrd",
                    data: vec![0u8; 4096],
                },
            ],
        },
        Node::Dir {
            name: "boot",
            children: vec![Node::Dir {
                name: "grub",
                children: vec![Node::File {
                    name: "grub.cfg",
                    data: grub.as_bytes().to_vec(),
                }],
            }],
        },
    ])
}

pub fn build_debian_iso(isolinux_cfg: Option<&str>) -> Vec<u8> {
    let cfg = isolinux_cfg.unwrap_or(
        "label live-persistence\n menu label Live persistence\n kernel /live/vmlinuz\n append initrd=/live/initrd.img boot=live persistence\n",
    );
    build_iso(vec![
        Node::Dir {
            name: "live",
            children: vec![
                Node::File {
                    name: "vmlinuz",
                    data: vec![0u8; 4096],
                },
                Node::File {
                    name: "initrd.img",
                    data: vec![0u8; 4096],
                },
                Node::File {
                    name: "filesystem.squashfs",
                    data: vec![0u8; 4096],
                },
            ],
        },
        Node::Dir {
            name: "isolinux",
            children: vec![Node::File {
                name: "isolinux.cfg",
                data: cfg.as_bytes().to_vec(),
            }],
        },
    ])
}

fn build_node(node: Node) -> BuiltNode {
    match node {
        Node::Dir { name, children } => BuiltNode {
            name: name.to_string(),
            is_dir: true,
            extent: 0,
            data: Vec::new(),
            children: children.into_iter().map(build_node).collect(),
        },
        Node::File { name, data } => BuiltNode {
            name: name.to_string(),
            is_dir: false,
            extent: 0,
            data,
            children: Vec::new(),
        },
    }
}

fn assign(node: &mut BuiltNode, next_sector: &mut u32) {
    node.extent = *next_sector;
    if node.is_dir {
        *next_sector += 1;
        for child in &mut node.children {
            assign(child, next_sector);
        }
    } else {
        *next_sector += ((node.data.len() as u32).div_ceil(2048)).max(1);
    }
}

fn write_pvd(image: &mut [u8], root: &BuiltNode, total_sectors: u32) {
    let mut pvd = vec![0u8; 2048];
    pvd[0] = 1;
    pvd[1..6].copy_from_slice(b"CD001");
    pvd[6] = 1;
    pvd[80..84].copy_from_slice(&total_sectors.to_le_bytes());
    pvd[84..88].copy_from_slice(&total_sectors.to_be_bytes());
    pvd[128..130].copy_from_slice(&2048u16.to_le_bytes());
    pvd[130..132].copy_from_slice(&2048u16.to_be_bytes());
    write_record(&mut pvd, 156, root.extent, 2048, 2, &[0], 34);
    pvd[881] = 1;
    image[16 * 2048..17 * 2048].copy_from_slice(&pvd);

    let mut terminator = vec![0u8; 2048];
    terminator[0] = 255;
    terminator[1..6].copy_from_slice(b"CD001");
    terminator[6] = 1;
    image[17 * 2048..18 * 2048].copy_from_slice(&terminator);
}

fn record_length(name_len: usize) -> usize {
    33 + name_len + usize::from(name_len.is_multiple_of(2))
}

fn write_node(node: &BuiltNode, parent_extent: u32, image: &mut [u8]) {
    if !node.is_dir {
        let offset = node.extent as usize * 2048;
        image[offset..offset + node.data.len()].copy_from_slice(&node.data);
        return;
    }

    let base = node.extent as usize * 2048;
    let mut position = 0usize;

    let self_len = record_length(1);
    write_record(image, base + position, node.extent, 2048, 2, &[0], self_len);
    position += self_len;

    let parent_len = record_length(1);
    write_record(
        image,
        base + position,
        parent_extent,
        2048,
        2,
        &[1],
        parent_len,
    );
    position += parent_len;

    for (index, child) in node.children.iter().enumerate() {
        let record_name = if child.is_dir {
            child.name.as_bytes().to_vec()
        } else {
            format!("{};1", child.name).into_bytes()
        };
        let length = record_length(record_name.len());
        assert!(
            length <= 255,
            "test directory record is too large for a one-byte length"
        );
        let size = if child.is_dir {
            2048
        } else {
            child.data.len() as u32
        };
        let flags = if child.is_dir { 2 } else { 0 };
        write_record(
            image,
            base + position,
            child.extent,
            size,
            flags,
            &record_name,
            length,
        );
        position += length;
        let _ = index;
    }
    assert!(position <= 2048, "test directory does not fit one sector");

    for child in &node.children {
        write_node(child, node.extent, image);
    }
}

fn write_record(
    image: &mut [u8],
    offset: usize,
    extent: u32,
    size: u32,
    flags: u8,
    name: &[u8],
    rec_len: usize,
) {
    image[offset] = rec_len as u8;
    image[offset + 1] = 0;
    image[offset + 2..offset + 6].copy_from_slice(&extent.to_le_bytes());
    image[offset + 6..offset + 10].copy_from_slice(&extent.to_be_bytes());
    image[offset + 10..offset + 14].copy_from_slice(&size.to_le_bytes());
    image[offset + 14..offset + 18].copy_from_slice(&size.to_be_bytes());
    image[offset + 18..offset + 25].copy_from_slice(&[126, 1, 1, 0, 0, 0, 0]);
    image[offset + 25] = flags;
    image[offset + 28..offset + 30].copy_from_slice(&1u16.to_le_bytes());
    image[offset + 30..offset + 32].copy_from_slice(&1u16.to_be_bytes());
    image[offset + 32] = name.len() as u8;
    image[offset + 33..offset + 33 + name.len()].copy_from_slice(name);
}
