//! Post-apply verification.

use crate::Result;
use crate::activation::{self, BootPatch};
use crate::ext2;
use hyntix_partition::{self as partition, AppendPlan};
use hyntix_storage::BlockDevice;

/// Verify the partition entry, the ext2 payload, and any boot patches.
pub fn verify_persistence(
    dev: &mut dyn BlockDevice,
    plan: &AppendPlan,
    label: &str,
    expect_persistence_conf: bool,
    patches: &[BootPatch],
) -> Result<()> {
    partition::verify_append(dev, plan)?;
    ext2::verify(dev, plan.start_lba, label, expect_persistence_conf)?;
    activation::verify_patches(dev, patches)?;
    Ok(())
}
