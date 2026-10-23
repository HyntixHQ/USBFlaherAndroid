# Linux ISO Downloads

Hyntix can browse and download Linux ISOs from official distribution HTTPS sources, verify each
image against the published SHA-256, and then hand the verified file to the existing flash flow.
The app does not host, mirror, proxy, redistribute, or modify ISOs.

## User flow

1. On the main screen, tap the image card and select **Download ISO**.
2. Choose a distribution and variant from the **Distributions** list.
3. Tap **Download ISO** inside a variant card.
4. Choose where to save the ISO with Android’s Safety-First document picker.
5. Follow progress in the download screen and the foreground-service notification.
6. When verification succeeds, use **Use ISO** to send the downloaded file to the flash screen.

The normal file-picker flow is unchanged. A downloaded image can be reused, reselected from local
storage, or deleted later.

## What the catalog provides

- 20 distributions across Debian-based, Ubuntu-based, Arch-based, Fedora-based, and independent
  families.
- Distribution descriptions, architectures, hosts, and downloadable variants.
- Persistence support information for each variant (`app`, `distro`, or `none`).
- Official source URLs and SHA-256 checksums.
- Download size estimates where the project publishes one.

## Download behavior

- Downloads are direct HTTPS downloads from the official distro host allowlist.
- Downloads can be paused, resumed, and cancelled while they are active.
- Range requests are used when the server supports them; otherwise the service falls back to a
  full retry.
- A foreground service with a notification keeps long downloads running and is required for
  background progress on Android.
- Free space is checked before starting when possible, including the device selected for flashing.
- A download is not offered for flashing until its SHA-256 matches the official checksum.
- A checksum mismatch deletes the destination and reports the download as failed.
- Downloads are Wi-Fi by default; metered-network use is an explicit opt-in.

## Persistence

The download screen shows each variant’s persistence support. Hyntix can add persistent storage
only to casper-based live images (Ubuntu, Linux Mint, Pop!_OS) and Debian Live images. Installer
images, DVD images, and unsupported distros are marked unavailable.

## Permissions

- No storage permission is requested.
- The user chooses the destination through SAF `ACTION_CREATE_DOCUMENT`.
- Persisted URI permission is retained so a download can survive app restarts.
- `POST_NOTIFICATIONS` is requested on Android 13+ before download destinations are selected.

## Troubleshooting

- **Catalog could not be loaded**: use **Retry** on the download screen; local file selection still
  works.
- **Destination unavailable**: reselect the save location if the URI permission was revoked.
- **No space**: choose a larger destination or flash drive.
- **Checksum mismatch**: retry the download; a partially written or captive-portal response is not
  usable.
- **Windows**: Windows ISOs are not in the catalog. Flash them from local storage only.

## Maintainers

- Update source URLs and checksums in `app/src/main/assets/iso-catalog.json`.
- Run `docs/features/iso-catalog.md` validation steps.
- Run `tools/verify-iso-catalog.sh` from the repository root for a structural report.
- Add or update user-visible strings in `res/values/strings.xml`; do not hardcode app copy.