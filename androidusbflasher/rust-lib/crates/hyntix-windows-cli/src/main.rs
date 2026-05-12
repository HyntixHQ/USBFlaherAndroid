use hyntix_windows::WindowsFlasher;
use std::fs::File;
use std::io::{Read, Seek, BufReader, Write};
use clap::Parser;
use anyhow::Result;
use tracing::{info, Level};
use tracing_subscriber;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Path to the source ISO file
    #[arg(short, long)]
    iso: String,

    /// Path to the target USB image or device
    #[arg(short, long)]
    target: String,
}

struct TestDiskWriter {
    file: File,
}

impl Write for TestDiskWriter {
    fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
        self.file.write(buf)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.file.flush()
    }
}

impl Seek for TestDiskWriter {
    fn seek(&mut self, pos: std::io::SeekFrom) -> std::io::Result<u64> {
        self.file.seek(pos)
    }
}

impl Read for TestDiskWriter {
    fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
        self.file.read(buf)
    }
}

impl hyntix_usb::PhysicalProgress for TestDiskWriter {
    fn physical_position(&self) -> u64 {
        0
    }
    fn total_capacity(&self) -> u64 {
        self.file.metadata().map(|m| m.len()).unwrap_or(0)
    }
}

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_max_level(Level::INFO)
        .init();

    let args = Args::parse();
    
    info!("=== hyntix-windows-cli: Full Flasher Test ===");
    info!("Source ISO: {}", args.iso);
    info!("Target Disk: {}", args.target);

    let iso_file = File::open(&args.iso)?;
    let iso_reader = BufReader::new(iso_file);

    let usb_file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(&args.target)?;
    
    let writer = TestDiskWriter { file: usb_file };
    let mut flasher = WindowsFlasher::new(writer);

    info!("Starting Flashing Process...");
    flasher.flash(iso_reader, |p, total| {
        let pct = (p as f64 / total as f64) * 100.0;
        print!("\rProgress: {:.2}% ({}/{})", pct, p, total);
        let _ = std::io::stdout().flush();
    })?;

    info!("\nFlashing completed successfully!");
    Ok(())
}
