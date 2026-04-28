use hyntix_windows::WindowsFlasher;
use std::fs::File;
use std::io::{Read, Seek, BufReader, Write};

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
        // Dummy implementation for testing
        0
    }
    fn total_capacity(&self) -> u64 {
        self.file.metadata().map(|m| m.len()).unwrap_or(0)
    }
}

fn main() {
    let iso_path = "/home/raja/OS/Win11_25H2_English_x64_v2.iso";
    let usb_path = "/home/raja/Desktop/test_usb.img";
    
    println!("=== hyntix-windows-cli: Full Flasher Test ===");
    println!("Source ISO: {}", iso_path);
    println!("Target Disk: {}", usb_path);

    let iso_file = File::open(iso_path).expect("Failed to open ISO");
    let iso_reader = BufReader::new(iso_file);

    let usb_file = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(usb_path)
        .expect("Failed to open test USB image");
    
    let writer = TestDiskWriter { file: usb_file };
    let mut flasher = WindowsFlasher::new(writer);

    println!("\nStarting Flashing Process...");
    flasher.flash(iso_reader, |p, total| {
        let pct = (p as f64 / total as f64) * 100.0;
        print!("\rProgress: {:.2}% ({}/{})", pct, p, total);
        let _ = std::io::stdout().flush();
    }).expect("Flashing failed");

    println!("\n\nFlashing completed successfully!");
}
