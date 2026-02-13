//! Command Status Wrapper (CSW) for USB Mass Storage Bulk-Only Transport.
//!
//! The CSW is a 13-byte structure returned by the device after processing
//! a CBW command.

use hyntix_common::{Error, Result};

/// CSW signature: "USBS" in little-endian (0x53425355).
pub const CSW_SIGNATURE: u32 = 0x53425355;

/// CSW size in bytes.
pub const CSW_SIZE: usize = 13;

/// CSW status codes.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CswStatus {
    /// Command passed (good status).
    Passed = 0x00,
    /// Command failed.
    Failed = 0x01,
    /// Phase error (requires reset).
    PhaseError = 0x02,
}

impl TryFrom<u8> for CswStatus {
    type Error = Error;

    fn try_from(value: u8) -> Result<Self> {
        match value {
            0x00 => Ok(CswStatus::Passed),
            0x01 => Ok(CswStatus::Failed),
            0x02 => Ok(CswStatus::PhaseError),
            _ => Err(Error::UsbError(format!("Invalid CSW status: {}", value))),
        }
    }
}

/// Command Status Wrapper structure.
#[derive(Debug, Clone)]
pub struct CommandStatusWrapper {
    /// Signature (must be CSW_SIGNATURE).
    pub signature: u32,
    /// Tag from corresponding CBW.
    pub tag: u32,
    /// Number of bytes not transferred.
    pub data_residue: u32,
    /// Command status.
    pub status: CswStatus,
}

impl CommandStatusWrapper {
    /// Parse CSW from a 13-byte buffer.
    pub fn parse(buffer: &[u8]) -> Result<Self> {
        if buffer.len() < CSW_SIZE {
            return Err(Error::UsbError(format!(
                "CSW buffer too small: {} < {}",
                buffer.len(),
                CSW_SIZE
            )));
        }

        let signature = u32::from_le_bytes([buffer[0], buffer[1], buffer[2], buffer[3]]);

        if signature != CSW_SIGNATURE {
            return Err(Error::UsbError(format!(
                "Invalid CSW signature: 0x{:08X}",
                signature
            )));
        }

        let tag = u32::from_le_bytes([buffer[4], buffer[5], buffer[6], buffer[7]]);
        let data_residue = u32::from_le_bytes([buffer[8], buffer[9], buffer[10], buffer[11]]);
        let status = CswStatus::try_from(buffer[12])?;

        Ok(Self {
            signature,
            tag,
            data_residue,
            status,
        })
    }

    /// Check if the command was successful.
    pub fn is_success(&self) -> bool {
        self.status == CswStatus::Passed
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_csw_parse() {
        let buffer = [
            0x55, 0x53, 0x42, 0x53, // Signature "USBS"
            0x01, 0x00, 0x00, 0x00, // Tag = 1
            0x00, 0x00, 0x00, 0x00, // Data residue = 0
            0x00, // Status = Passed
        ];

        let csw = CommandStatusWrapper::parse(&buffer).unwrap();

        assert_eq!(csw.signature, CSW_SIGNATURE);
        assert_eq!(csw.tag, 1);
        assert_eq!(csw.data_residue, 0);
        assert_eq!(csw.status, CswStatus::Passed);
        assert!(csw.is_success());
    }
}
