//! Rollback support re-exports.
//!
//! The implementation lives next to the table operations so it can share the
//! saved-sector representation.

pub use crate::{OriginalTable, SavedSector, rollback};
