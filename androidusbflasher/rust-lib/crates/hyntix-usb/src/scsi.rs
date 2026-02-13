//! SCSI commands for USB Mass Storage.
//!
//! Implements the SCSI commands needed for block device I/O:
//! - INQUIRY
//! - TEST UNIT READY
//! - READ CAPACITY
//! - READ(10)
//! - WRITE(10)

use super::cbw::{CommandBlockWrapper, Direction};

/// SCSI INQUIRY command (0x12).
/// Queries device identification and capabilities.
pub struct ScsiInquiry;

impl ScsiInquiry {
    /// Op code for INQUIRY.
    pub const OPCODE: u8 = 0x12;

    /// Create INQUIRY command bytes.
    pub fn command(allocation_length: u8) -> [u8; 6] {
        [
            Self::OPCODE,
            0x00,              // EVPD = 0
            0x00,              // Page code
            0x00,              // Reserved
            allocation_length, // Allocation length
            0x00,              // Control
        ]
    }

    /// Create CBW for INQUIRY.
    pub fn cbw(tag: u32, lun: u8, allocation_length: u8) -> CommandBlockWrapper {
        CommandBlockWrapper::new(
            tag,
            allocation_length as u32,
            Direction::In,
            lun,
            &Self::command(allocation_length),
        )
    }
}

/// SCSI TEST UNIT READY command (0x00).
/// Checks if device is ready for data transfer.
pub struct ScsiTestUnitReady;

impl ScsiTestUnitReady {
    /// Op code for TEST UNIT READY.
    pub const OPCODE: u8 = 0x00;

    /// Create TEST UNIT READY command bytes.
    pub fn command() -> [u8; 6] {
        [Self::OPCODE, 0, 0, 0, 0, 0]
    }

    /// Create CBW for TEST UNIT READY (no data phase).
    pub fn cbw(tag: u32, lun: u8) -> CommandBlockWrapper {
        CommandBlockWrapper::new(tag, 0, Direction::Out, lun, &Self::command())
    }
}

/// SCSI READ CAPACITY (10) command (0x25).
/// Gets block size and last block address.
pub struct ScsiReadCapacity;

impl ScsiReadCapacity {
    /// Op code for READ CAPACITY.
    pub const OPCODE: u8 = 0x25;

    /// Response size (8 bytes).
    pub const RESPONSE_SIZE: u32 = 8;

    /// Create READ CAPACITY command bytes.
    pub fn command() -> [u8; 10] {
        let mut cmd = [0u8; 10];
        cmd[0] = Self::OPCODE;
        cmd
    }

    /// Create CBW for READ CAPACITY.
    pub fn cbw(tag: u32, lun: u8) -> CommandBlockWrapper {
        CommandBlockWrapper::new(
            tag,
            Self::RESPONSE_SIZE,
            Direction::In,
            lun,
            &Self::command(),
        )
    }

    /// Parse READ CAPACITY response.
    /// Returns (last_block_address, block_size).
    pub fn parse_response(data: &[u8]) -> Option<(u32, u32)> {
        log::debug!("ScsiReadCapacity: Raw response (len={}): {:02X?}", data.len(), data);
        if data.len() < 8 {
            return None;
        }

        // Both values are big-endian
        let last_block_address = u32::from_be_bytes([data[0], data[1], data[2], data[3]]);
        let block_size = u32::from_be_bytes([data[4], data[5], data[6], data[7]]);

        log::debug!("ScsiReadCapacity: Parsed LBA={}, BlockSize={}", last_block_address, block_size);
        Some((last_block_address, block_size))
    }
}

/// SCSI READ(10) command (0x28).
/// Reads blocks from the device.
pub struct ScsiRead10;

impl ScsiRead10 {
    /// Op code for READ(10).
    pub const OPCODE: u8 = 0x28;

    /// Create READ(10) command bytes.
    pub fn command(lba: u32, transfer_blocks: u16) -> [u8; 10] {
        let lba_bytes = lba.to_be_bytes();
        let blocks_bytes = transfer_blocks.to_be_bytes();

        [
            Self::OPCODE,
            0x00,         // Flags
            lba_bytes[0], // LBA MSB
            lba_bytes[1],
            lba_bytes[2],
            lba_bytes[3],    // LBA LSB
            0x00,            // Reserved
            blocks_bytes[0], // Transfer length MSB
            blocks_bytes[1], // Transfer length LSB
            0x00,            // Control
        ]
    }

    /// Create CBW for READ(10).
    pub fn cbw(
        tag: u32,
        lun: u8,
        lba: u32,
        transfer_blocks: u16,
        block_size: u32,
    ) -> CommandBlockWrapper {
        let transfer_length = transfer_blocks as u32 * block_size;
        CommandBlockWrapper::new(
            tag,
            transfer_length,
            Direction::In,
            lun,
            &Self::command(lba, transfer_blocks),
        )
    }
}

/// SCSI WRITE(10) command (0x2A).
/// Writes blocks to the device.
pub struct ScsiWrite10;

impl ScsiWrite10 {
    /// Op code for WRITE(10).
    pub const OPCODE: u8 = 0x2A;

    /// Create WRITE(10) command bytes.
    pub fn command(lba: u32, transfer_blocks: u16) -> [u8; 10] {
        let lba_bytes = lba.to_be_bytes();
        let blocks_bytes = transfer_blocks.to_be_bytes();

        [
            Self::OPCODE,
            0x00,         // Flags
            lba_bytes[0], // LBA MSB
            lba_bytes[1],
            lba_bytes[2],
            lba_bytes[3],    // LBA LSB
            0x00,            // Reserved
            blocks_bytes[0], // Transfer length MSB
            blocks_bytes[1], // Transfer length LSB
            0x00,            // Control
        ]
    }

    /// Create CBW for WRITE(10).
    pub fn cbw(
        tag: u32,
        lun: u8,
        lba: u32,
        transfer_blocks: u16,
        block_size: u32,
    ) -> CommandBlockWrapper {
        let transfer_length = transfer_blocks as u32 * block_size;
        CommandBlockWrapper::new(
            tag,
            transfer_length,
            Direction::Out,
            lun,
            &Self::command(lba, transfer_blocks),
        )
    }
}

/// SCSI REQUEST SENSE command (0x03).
/// Gets detailed error information after a failed command.
pub struct ScsiRequestSense;

impl ScsiRequestSense {
    /// Op code for REQUEST SENSE.
    pub const OPCODE: u8 = 0x03;

    /// Standard response size (18 bytes).
    pub const RESPONSE_SIZE: u8 = 18;

    /// Create REQUEST SENSE command bytes.
    pub fn command(allocation_length: u8) -> [u8; 6] {
        [
            Self::OPCODE,
            0x00,              // Reserved
            0x00,              // Reserved
            0x00,              // Reserved
            allocation_length, // Allocation length
            0x00,              // Control
        ]
    }

    /// Create CBW for REQUEST SENSE.
    pub fn cbw(tag: u32, lun: u8) -> CommandBlockWrapper {
        CommandBlockWrapper::new(
            tag,
            Self::RESPONSE_SIZE as u32,
            Direction::In,
            lun,
            &Self::command(Self::RESPONSE_SIZE),
        )
    }
}

/// SCSI START STOP UNIT command (0x1B).
/// Used to eject the media or stop the motor.
pub struct ScsiStartStopUnit;

impl ScsiStartStopUnit {
    /// Op code for START STOP UNIT.
    pub const OPCODE: u8 = 0x1B;

    /// Create START STOP UNIT command bytes.
    /// @param start If true, start the unit. If false, stop it.
    /// @param loej If true, load/eject the media.
    pub fn command(start: bool, loej: bool) -> [u8; 6] {
        let mut cmd = [0u8; 6];
        cmd[0] = Self::OPCODE;
        let mut byte4 = 0u8;
        if start {
            byte4 |= 0x01;
        }
        if loej {
            byte4 |= 0x02;
        }
        cmd[4] = byte4;
        cmd
    }

    /// Create CBW for START STOP UNIT.
    pub fn cbw(tag: u32, lun: u8, start: bool, loej: bool) -> CommandBlockWrapper {
        CommandBlockWrapper::new(tag, 0, Direction::Out, lun, &Self::command(start, loej))
    }
}

/// SCSI SYNCHRONIZE CACHE (10) command (0x35).
/// Flushes the device's internal write cache to physical storage.
pub struct ScsiSynchronizeCache;

impl ScsiSynchronizeCache {
    /// Op code for SYNCHRONIZE CACHE (10).
    pub const OPCODE: u8 = 0x35;

    /// Create SYNCHRONIZE CACHE (10) command bytes.
    pub fn command() -> [u8; 10] {
        let mut cmd = [0u8; 10];
        cmd[0] = Self::OPCODE;
        cmd
    }

    /// Create CBW for SYNCHRONIZE CACHE (10).
    pub fn cbw(tag: u32, lun: u8) -> CommandBlockWrapper {
        CommandBlockWrapper::new(tag, 0, Direction::Out, lun, &Self::command())
    }
}
