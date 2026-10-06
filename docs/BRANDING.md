# WA X — Branding & Logo

## Naming

- **Product name:** WA X
- **Repository name:** WA-X
- **Tagline:** Advanced WhatsApp Xposed Module
- **Module applicationId:** `com.wax.module`

Use **WA X** in user-facing text and **WA-X** where a repository/file identifier is more appropriate.

## Official mark

The current WA X mark is the flat phone/chat-bubble + **X** design approved for this fork.

The repository uses the previously prepared WA X artwork as the source of truth. The phone/chat outline and green X were traced into scalable vector paths so the same mark is used consistently by Android adaptive icons, themed monochrome icons, round icons and documentation branding.

## Repository assets

Documentation:

- `docs/assets/wa-x-app-icon.svg` — app-icon presentation.
- `docs/assets/wa-x-logo.svg` — transparent project mark.

Android launcher:

- `app/src/main/res/drawable/ic_launcher_background.xml`
- `app/src/main/res/drawable/ic_launcher_foreground.xml`
- `app/src/main/res/drawable/ic_launcher_monochrome.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher.xml`
- `app/src/main/res/mipmap-anydpi-v33/ic_launcher_round.xml`

The Android manifest points to `@mipmap/ic_launcher` and `@mipmap/ic_launcher_round`. Because WA X has minSdk 28, adaptive icons cover every supported Android version; obsolete raster `launcher.png` resources were removed instead of maintaining a second, drifting logo set.

## Visual direction

The mark is intentionally flat and non-3D. Do not add fake 3D bevels, chrome, heavy drop shadows or a white square background.

Do not crop away the lower-left chat-bubble tail or the right side of the X. For circular avatars or masked icons, preserve enough safe area for the complete mark.

## Independence

The WA X mark represents an independent open-source Xposed module. It must not be presented as an official WhatsApp/Meta logo or in a way that implies sponsorship or endorsement.

**WA X is an independent open-source project and is not affiliated with, endorsed by, sponsored by, or officially associated with WhatsApp or Meta.**
