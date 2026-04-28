use hyntix_udf::{UdfReader, UdfEntry};
use hyntix_wim::{WimHeader, WimLookupEntry, SwmSplitter};
use hyntix_fat32::{Fat32Writer, Fat32Formatter, GptTable};
use hyntix_usb::PhysicalProgress;
use std::io::{Read, Seek, Write, SeekFrom};
use thiserror::Error;
use log;

#[derive(Error, Debug)]
pub enum WindowsError {
    #[error("UDF error: {0}")]
    Udf(#[from] hyntix_udf::UdfError),
    #[error("WIM error: {0}")]
    Wim(#[from] hyntix_wim::WimError),
    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),
    #[error("FAT32 error: {0}")]
    Fat32(String),
}

pub struct WindowsFlasher<W: Write + Seek + Read + PhysicalProgress> {
    writer: W,
}

impl<W: Write + Seek + Read + PhysicalProgress> WindowsFlasher<W> {
    pub fn new(writer: W) -> Self {
        Self { writer }
    }

    pub fn flash<R: Read + Seek>(
        &mut self,
        mut iso_reader: R,
        progress: impl Fn(u64, u64),
    ) -> Result<(), WindowsError> {
        println!("Initializing UDF reader...");
        let mut udf = UdfReader::new(&mut iso_reader)?;
        let tree = udf.walk()?; 

        println!("Calculating disk geometry...");
        let disk_size = self.writer.total_capacity();

        println!("Writing GPT Partition Table...");
        let gpt = GptTable::new_with_esp(disk_size)
            .map_err(|e| WindowsError::Fat32(e.to_string()))?;
        gpt.write(&mut self.writer).map_err(|e| WindowsError::Fat32(e.to_string()))?;
        
        let partition = gpt.partitions[0].clone();
        
        println!("Formatting FAT32 Partition...");
        let formatter = Fat32Formatter::new(
            partition.start_lba * 512, 
            (partition.end_lba - partition.start_lba + 1) * 512,
            "BOOTABLE"
        ).map_err(|e| WindowsError::Fat32(e.to_string()))?;

        let mut fat_writer = Fat32Writer::new(&mut self.writer, formatter);
        fat_writer.format().map_err(|e| WindowsError::Fat32(e.to_string()))?;

        println!("Copying files...");
        let mut total_size: u64 = 0;
        for (_, entry) in &tree {
            total_size += entry.size;
        }

        let root_cluster = fat_writer.root_cluster();

        let mut dir_clusters = std::collections::HashMap::new();
        dir_clusters.insert("".to_string(), root_cluster);

        let mut sorted_tree = tree.clone();
        sorted_tree.sort_by(|a, b| a.0.cmp(&b.0));

        for (path, entry) in &sorted_tree {
            if entry.is_dir {
                let parent_path = if let Some(idx) = path.rfind('/') {
                    &path[..idx]
                } else {
                    ""
                };
                let name = if let Some(idx) = path.rfind('/') {
                    &path[idx+1..]
                } else {
                    path
                };
                
                let parent_cluster = *dir_clusters.get(parent_path).unwrap_or(&root_cluster);
                let cluster = fat_writer.create_directory(name, parent_cluster)
                    .map_err(|e| WindowsError::Fat32(e.to_string()))?;
                dir_clusters.insert(path.clone(), cluster);
            }
        }

        let mut current_progress: u64 = 0;
        for (path, entry) in &sorted_tree {
            if entry.is_dir { continue; }

            let path_lower = path.to_lowercase();
            let parent_path = if let Some(idx) = path.rfind('/') {
                &path[..idx]
            } else {
                ""
            };
            let name = if let Some(idx) = path.rfind('/') {
                &path[idx+1..]
            } else {
                path
            };
            let parent_cluster = *dir_clusters.get(parent_path).unwrap_or(&root_cluster);

            log::info!("WindowsFlasher: Starting file {} ({} bytes)", path, entry.size);

            if path_lower == "sources/install.wim" || path_lower == "sources/install.esd" {
                log::info!("WindowsFlasher: Detected main image, using split-writer...");
                let mut accumulated_wim_progress = 0;
                write_split_wim(&mut udf, entry, parent_cluster, &mut fat_writer, |p| {
                     accumulated_wim_progress += p;
                     progress(current_progress + accumulated_wim_progress, total_size);
                })?;
                current_progress += entry.size;
            } else {
                let _file_size = udf.open_file(entry)?;
                fat_writer.write_file(name, parent_cluster, udf.reader(), entry.size, |p, _| {
                    progress(current_progress + p, total_size);
                }).map_err(|e| WindowsError::Fat32(e.to_string()))?;
                current_progress += entry.size;
            }
            log::info!("WindowsFlasher: Finished file {}", path);
        }

        println!("Finalizing FAT...");
        fat_writer.flush_fat().map_err(|e| WindowsError::Fat32(e.to_string()))?;
        
        Ok(())
    }
}

fn write_split_wim<U, W>(
    udf: &mut UdfReader<U>,
    entry: &UdfEntry,
    parent_cluster: u32,
    fat_writer: &mut Fat32Writer<W>,
    mut progress: impl FnMut(u64),
) -> Result<(), WindowsError> 
where 
    U: Read + Seek,
    W: Write + Seek + Read + PhysicalProgress
{
    let _size = udf.open_file(entry)?;
    let wim_data_offset = udf.reader().stream_position()?;
    
    let mut header_buf = [0u8; 208];
    udf.reader().read_exact(&mut header_buf)?;
    let header = WimHeader::read(std::io::Cursor::new(&header_buf))?;

    udf.reader().seek(SeekFrom::Start(wim_data_offset + header.lookup_table_resource.offset))?;
    let mut entries = Vec::new();
    let num_entries = header.lookup_table_resource.original_size / 50;
    for _ in 0..num_entries {
        entries.push(WimLookupEntry::read(udf.reader())?);
    }

    let splitter = SwmSplitter::new(header.clone(), entries);
    let parts = splitter.split(4_000_000_000);

    for (i, part) in parts.iter().enumerate() {
        let swm_name = if i == 0 {
            "install.swm".to_string()
        } else {
            format!("install{}.swm", i + 1)
        };

        let mut part_total_size: u64 = 208; // Header
        for e in &part.blobs_to_write {
            part_total_size += e.res_hdr.size;
        }
        part_total_size += (part.lookup_table.len() * 50) as u64; // Lookup Table
        if part.part_number == 1 {
            part_total_size += header.xml_data_resource.size;
        }

        let mut swm_writer = fat_writer.open_file_writer(&swm_name, parent_cluster, part_total_size)
            .map_err(|e| WindowsError::Fat32(e.to_string()))?;

        // 1. Write Header
        let mut h_buf = Vec::new();
        part.write_header(&mut h_buf, &header)?;
        swm_writer.write_all(&h_buf)?;

        // 2. Write Blobs
        for e in &part.blobs_to_write {
            udf.reader().seek(SeekFrom::Start(wim_data_offset + e.res_hdr.offset))?;
            let mut blob_reader = udf.reader().take(e.res_hdr.size);
            std::io::copy(&mut blob_reader, &mut swm_writer)?;
            progress(e.res_hdr.size);
        }

        // 3. Write Lookup Table (The master table calculated during split)
        part.write_lookup_table(&mut swm_writer)?;

        // 4. Write XML (Part 1 only)
        if part.part_number == 1 && header.xml_data_resource.size > 0 {
            udf.reader().seek(SeekFrom::Start(wim_data_offset + header.xml_data_resource.offset))?;
            let mut xml_reader = udf.reader().take(header.xml_data_resource.size);
            std::io::copy(&mut xml_reader, &mut swm_writer)?;
        }

        swm_writer.flush()?;
    }

    Ok(())
}
