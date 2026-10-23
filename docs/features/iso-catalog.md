# ISO download catalog

The in-app Linux catalog is a bundled JSON asset:

`app/src/main/assets/iso-catalog.json`

It is parsed by `IsoCatalogParser` and validated by the signed APK build. The
UI has no distribution-specific branches: categories, distributions, variants,
descriptions, links, persistence information, and official download hosts all
come from the catalog.

## Structure

```
categories[]                 ordered section headers
  id, name, description

distributions[]              one card per distribution
  id, name, categoryId, description
  homepageUrl, licenseUrl, releaseNotesUrl?
  architectures[]            what the distribution publishes
  hosts[]                    allowed HTTPS hosts for this distribution
  variants[]                 downloadable ISOs

variant
  id, variantName, description
  desktopEnvironment?        omitted for installers
  imageType                   Live, Netinst, DVD, Installer, ...
  release, releaseChannel
  arch
  fileName
  url                         must end in .iso
  checksumUrl?                omitted when sha256 is supplied
  checksumFileName?           defaults to fileName
  checksumFormat              GNU | FEDORA | SHA256 | PLAIN | SINGLE
  sha256?                     static 64-hex hash, used instead of checksumUrl
  approxSizeBytes
  minUsbBytes?
  persistence {
    support                   app | distro | none
    profiles[]                e.g. casper, debian-live when support=app
    requiresConfiguration
    notes                     short user-facing explanation
  }
```

Distribution links are shown once at the top of the distribution detail screen.
`homepageUrl` points at the project's home page, while `releaseNotesUrl` and
`licenseUrl` point at official release-note and licensing pages. Variants do not
carry their own copies of these links.

### Persistence values

- `app` — Hyntix can create and activate persistence automatically. The flash
  pipeline still probes the downloaded ISO; the catalog value is guidance, not
  permission to skip validation.
- `distro` — the distribution provides its own persistent-volume tooling.
  `requiresConfiguration` is normally `true`; the note tells the user to create
  it inside the live session.
- `none` — no supported live-persistence workflow. Installer images use this
  value with a note explaining that persistence does not apply.

### Host allowlist

`hosts[]` is the per-distribution allowlist used by `UrlPolicy`. The URL parser
accepts only HTTPS, no credentials, port 443, and an `.iso` path. Checksum URLs
must also use one of the declared hosts. Redirects to mirror/CDN hosts are
allowed for the final ISO response only after the initial URL is validated.

Keeping hosts beside the distribution data means adding a new official source
does not require editing Kotlin.

### Checksum formats

- `GNU` — `hash  file.iso` (also accepts `hash *file.iso`).
- `FEDORA` — `SHA256 (file.iso) = hash`.
- `PLAIN` — the file contains only one 64-hex hash.
- `SINGLE` — use the only hash in the file even when its filename changes each
  build (used by NixOS and openSUSE snapshots).
- `SHA256` — a static `sha256` value is stored in the catalog (useful for APIs
  that publish a hash but not a checksum file, such as Pop!_OS).

For rolling images that use a stable `latest.iso` URL, set `fileName` to the
name users should see and `checksumFileName` to the name used by the checksum
file (for example, `latest.iso`).

## Updating the catalog

1. Check the distribution's official download page and release notes.
2. Prefer a stable versioned directory over a `latest` redirect when the
   official project publishes one.
3. Verify the ISO URL with a HEAD or range request, and fetch the published
   checksum file to confirm the filename and SHA-256.
4. Add or update the variant. Keep one variant per architecture/image type.
5. Run the catalog tests:

   ```sh
   ./gradlew :app:testDebugUnitTest
   ```

   `bundledCatalogIsValidAndMaintainable` parses the actual asset and rejects
   duplicate IDs, invalid URLs, undeclared hosts, and malformed persistence
   data.
6. Build the app so the new asset is packaged:

   ```sh
   ./gradlew :app:assembleRelease
   ```

## Removing a release

Remove the obsolete variant or the whole distribution. Do not leave an old
version advertised beside a newer one unless both are intentionally supported
(for example, Ubuntu LTS and the current interim release). The parser drops
empty distributions and empty categories, so removing the last variant also
removes the distribution card.

## Current selection

The catalog covers 20 distributions across Debian-based, Ubuntu-based,
Arch-based, Fedora-based, and independent families. It prioritizes widely used
desktop distributions, with specialized entries for security, older hardware,
gaming, and compartmentalized desktops. Windows downloads remain disabled by
`WINDOWS_ISO_DOWNLOAD_ENABLED` pending legal review.
