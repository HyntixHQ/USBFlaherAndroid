//! Supported persistence profiles and public support metadata.

use crate::activation::BootPatch;

pub const MIN_PERSISTENCE_BYTES: u64 = 1024 * 1024 * 1024;
pub const RECOMMENDED_PERSISTENCE_BYTES: u64 = 4 * 1024 * 1024 * 1024;
pub const CASPER_LABEL: &str = "casper-rw";
pub const DEBIAN_LABEL: &str = "persistence";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PersistenceProfile {
    /// Ubuntu, Linux Mint, and Pop!_OS live images using casper.
    Casper,
    /// Debian Live images using the persistence partition convention.
    DebianLive,
}

impl PersistenceProfile {
    pub fn label(self) -> &'static str {
        match self {
            PersistenceProfile::Casper => CASPER_LABEL,
            PersistenceProfile::DebianLive => DEBIAN_LABEL,
        }
    }

    pub fn needs_persistence_conf(self) -> bool {
        matches!(self, PersistenceProfile::DebianLive)
    }

    pub fn display_name(self) -> &'static str {
        match self {
            PersistenceProfile::Casper => "casper",
            PersistenceProfile::DebianLive => "Debian Live",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PersistenceActivation {
    /// The ISO already ships a working persistence boot entry.
    BuiltIn,
    /// A safe same-length patch can enable persistence automatically.
    Patchable,
    /// The ISO can use persistence only with a manual boot step.
    Manual,
}

/// Result of probing an ISO for persistence support.
#[derive(Debug, Clone)]
pub struct PersistenceSupport {
    pub profile: PersistenceProfile,
    pub activation: PersistenceActivation,
    pub min_bytes: u64,
    pub recommended_bytes: u64,
    pub label: String,
    pub reason: Option<String>,
    pub patches: Vec<BootPatch>,
}

impl PersistenceSupport {
    pub fn new(
        profile: PersistenceProfile,
        activation: PersistenceActivation,
        reason: Option<String>,
        patches: Vec<BootPatch>,
    ) -> Self {
        Self {
            profile,
            activation,
            min_bytes: MIN_PERSISTENCE_BYTES,
            recommended_bytes: RECOMMENDED_PERSISTENCE_BYTES,
            label: profile.label().to_string(),
            reason,
            patches,
        }
    }

    pub fn needs_persistence_conf(&self) -> bool {
        self.profile.needs_persistence_conf()
    }
}
