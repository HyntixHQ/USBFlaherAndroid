# Linux ISO source and catalog policy

This document covers the third-party Linux ISO source policy used by the in-app download feature.

## Sources

- Every catalog entry points at the distribution’s official HTTPS site.
- ISO URLs and checksum URLs must use a host declared in the distribution’s `hosts`
  allowlist.
- The app downloads directly to the user-selected destination. It never proxies,
  mirrors, stores, or redistributes ISOs.
- Distilled tarballs such as official website bundles are not copied into the app.

## Catalog format

The bundled catalog is schema v2:

```text
categories[]
  id, name, description
distributions[]
  id, name, categoryId, description
  homepageUrl, licenseUrl, releaseNotesUrl?
  architectures[]
  hosts[]
  variants[]
variant
  ... same fields as app/src/main/java/com/hyntix/android/usbflasher/download/IsoCatalog.kt
```

See `docs/features/iso-catalog.md` for the complete schema, persistence metadata, host
allowlist rules, and checksum formats.

## URL policy

- The initial ISO and checksum URLs must use HTTPS on the standard port with no
  credentials and an `iso`-compatible URL path.
- The ISO host must be in the distribution allowlist.
- Checksum URLs must use an allowed host.
- Safe redirects are followed to the official CDN response, but the destination
  URL policy is applied before the request.
- Downloads reject HTML-looking responses, which usually indicates a captive portal
  or error page instead of a Linux image.

## Checksum policy

- Every downloadable variant requires an official checksum source or embedded SHA-256.
- Supported checksum formats are `GNU`, `FEDORA`, `SHA256`, `SINGLE`, and `PLAIN`.
- “Use ISO” is disabled until the downloaded SHA-256 matches the expected value.
- A mismatched file is deleted and the record is marked failed.

## Catalog maintenance

1. Start from the distribution’s official download or release page.
2. Use home-page/official URLs for distribution links.
3. Verify each download URL with a HEAD or small range request.
4. Fetch the official checksum file and confirm the filename and hash.
5. Prefer a stable versioned directory over a `latest` redirect when the project
   publishes one.
6. Update one distribution at a time and run the JVM catalog tests.
7. Run `tools/verify-iso-catalog.sh` from the repository root for a structural report.
8. Record the source pages used for distribution links.

Run all gates:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease
```

## Legal and attribution

- Distro names, trademarks, logos, release notes, and licenses remain owned by
  their projects.
- The app links to official sites and does not claim endorsement or affiliation.
- Distribution URLs can go stale; update them in the catalog without changing Kotlin.
- Do not redistribute ISOs or bundle catalog images with a release.