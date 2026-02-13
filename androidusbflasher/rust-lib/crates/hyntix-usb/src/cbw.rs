//! Command Block Wrapper (CBW) for USB Mass Storage Bulk-Only Transport.
//!
//! The CBW is a 31-byte structure used to wrap SCSI commands for transmission
//! over USB bulk endpoints.

/// CBW signature: "USBC" in little-endian (0x43425355).
pub const CBW_SIGNATURE: u32 = 0x43425355;

/// CBW size in bytes.
pub const CBW_SIZE: usize = 31;

/// Direction flags for CBW.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Direction {
    /// Data transfer from host to device.
    Out = 0x00,
    /// Data transfer from device to host.
    In = 0x80,
}

/// Command Block Wrapper structure.
#[derive(Debug, Clone)]
pub struct CommandBlockWrapper {
    /// Signature (must be CBW_SIGNATURE).
    pub signature: u32,
    /// Tag to associate with CSW response.
    pub tag: u32,
    /// Number of bytes to transfer in data phase.
    pub data_transfer_length: u32,
    /// Transfer direction flags.
    pub flags: u8,
    /// Logical Unit Number (usually 0).
    pub lun: u8,
    /// Length of the command block (1-16 bytes).
    pub cb_length: u8,
    /// SCSI command block (16 bytes, padded with zeros).
    pub command_block: [u8; 16],
}

impl CommandBlockWrapper {
    /// Create a new CBW with the given parameters.
    pub fn new(
        tag: u32,
        data_transfer_length: u32,
        direction: Direction,
        lun: u8,
        command: &[u8],
    ) -> Self {
        let mut command_block = [0u8; 16];
        let len = command.len().min(16);
        command_block[..len].copy_from_slice(&command[..len]);

        Self {
            signature: CBW_SIGNATURE,
            tag,
            data_transfer_length,
            flags: direction as u8,
            lun,
            cb_length: len as u8,
            command_block,
        }
    }

    /// Serialize CBW to a 31-byte buffer.
    pub fn serialize(&self) -> [u8; CBW_SIZE] {
        let mut buffer = [0u8; CBW_SIZE];

        // Signature (little-endian)
        buffer[0..4].copy_from_slice(&self.signature.to_le_bytes());
        // Tag (little-endian)
        buffer[4..8].copy_from_slice(&self.tag.to_le_bytes());
        // Data Transfer Length (little-endian)
        buffer[8..12].copy_from_slice(&self.data_transfer_length.to_le_bytes());
        // Flags
        buffer[12] = self.flags;
        // LUN
        buffer[13] = self.lun;
        // CB Length
        buffer[14] = self.cb_length;
        // Command Block (16 bytes)
        buffer[15..31].copy_from_slice(&self.command_block);

        buffer
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_cbw_serialize() {
        let cbw = CommandBlockWrapper::new(
            1,
            512,
            Direction::In,
            0,
            &[0x28, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00], // READ(10)
        );

        let data = cbw.serialize();

        // Check signature
        assert_eq!(&data[0..4], &[0x55, 0x53, 0x42, 0x43]); // "USBC"
                                                            // Check tag
        assert_eq!(&data[4..8], &[0x01, 0x00, 0x00, 0x00]);
        // Check data transfer length
        assert_eq!(&data[8..12], &[0x00, 0x02, 0x00, 0x00]); // 512
                                                             // Check flags (IN = 0x80)
        assert_eq!(data[12], 0x80);
    }
}
