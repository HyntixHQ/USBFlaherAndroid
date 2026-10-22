# Persistent Storage for Linux Live USBs

Hyntix can optionally add a persistent storage partition to supported Linux live
USBs. Files and settings saved while running from the USB are kept across
reboots.

Persistence is **opt-in** and runs after the normal ISO write. When it is off,
the Linux and Windows flash paths are unchanged.

## Supported images

| Profile | Distributions | Partition label | Boot activation |
|---|---|---|---|
| casper | Ubuntu, Linux Mint, Pop!_OS | `casper-rw` | `persistent` kernel parameter; patched into the live boot entries when a safe same-length edit exists |
| Debian Live | Debian live images | `persistence` | Debian's built-in persistence boot entry |

The profile is detected from the ISO contents. Other Linux and Windows images do
not offer the persistence switch.

## Requirements

- Linux live ISO written in normal (single ISO) mode.
- MBR or GPT partition table with a free primary slot and at least 1 GiB of free
  space after the ISO.
- 512-byte logical sectors. 4Kn drives are rejected.
- No hybrid MBR. A hybrid table is rejected before any write so the drive stays
  bootable.

The persistence partition is always appended after the existing ISO partitions.
The ISO partitions are never moved, shrunk, or reformatted.

## What gets created

- One ext2 partition, 1 MiB aligned, 4 KiB blocks, 256-byte inodes, no journal.
- Label `casper-rw` (casper) or `persistence` (Debian Live).
- `lost+found` and, for Debian Live, `/persistence.conf` containing `/ union`.
- For casper images, same-length edits to live GRUB/isolinux entries to add or
  replace the kernel parameter with `persistent`.
- Minimum size 1 GiB, default 4 GiB, maximum limited by free tail space. The
  last 1 MiB of the drive is reserved for the backup GPT.

The partition table is committed last. A failure before the commit restores any
boot-config bytes and leaves the ISO bootable, so persistence can be retried.

## Boot activation

If the toggle shows **Automatic**, the image was patched during setup (casper)
or already ships a persistence entry (Debian Live). No extra step is needed.

If it shows **Manual step required**, the boot configuration could not be edited
safely. To boot with persistence:

- **casper**: at the boot menu, select the live entry, press `e`, add
  `persistent` to the `linux` line, then boot.
- **Debian Live**: select the persistence boot entry. If it is absent, add
  `persistence` to the kernel command line.

## Failure and retry

If the ISO was written but persistence failed, the app reports a partial
success instead of an unusable drive:

- the USB remains bootable as a normal live USB,
- the reason is shown in the completion sheet,
- **Retry persistence** runs only the persistence stage and leaves the ISO
  untouched.

Cancelling during persistence follows the same partial-success rules.

## Limitations

- Persistence data is **not encrypted**. Anyone with the drive can read it.
- Windows persistence is not supported.
- Fedora, Arch, openSUSE, Kali, Tails, and Parrot are not supported in this
  release. Kali, Tails, and Parrot share Debian Live's `/live` layout, so they
  are rejected by ISO volume ID before the persistence toggle is offered.
- Ventoy multiboot persistence is not supported.
- The ISO partition layout must be extendable: four existing MBR primaries or an
  inconsistent hybrid MBR will be rejected.

## Implementation

Persistence is isolated in three Rust crates:

- `hyntix-storage`: block-device abstraction and unaligned read/write helpers.
- `hyntix-partition`: append-only MBR/GPT edits, CRC updates, rollback, and
  free-tail sizing.
- `hyntix-persistence`: ISO probing, ext2 formatting, boot activation, planning,
  apply, and verification.

The UniFFI bridge exposes `probePersistence` and `applyPersistence`. Kotlin keeps
the image descriptor and USB handle open across the ISO write and the
persistence stage, so the drive is never reopened between the two operations.
