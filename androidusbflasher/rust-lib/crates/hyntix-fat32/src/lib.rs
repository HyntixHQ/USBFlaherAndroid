//! FAT32 filesystem creation and writing.
//!
//! This module provides functionality to create GPT partition tables
//! and format FAT32 filesystems directly to raw block devices.

mod format;
mod gpt;
mod writer;

pub use format::*;
pub use gpt::*;
pub use writer::*;
