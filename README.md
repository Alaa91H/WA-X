# WA X

<div align="center">
  <img src="docs/assets/wa-x-app-icon.svg" alt="WA X logo" width="168" />

  <h3>Advanced WhatsApp Xposed Module</h3>

  <p>
    <a href="https://github.com/Alaa91H/WA-X/actions/workflows/ci.yml">CI</a> ·
    <a href="https://github.com/Alaa91H/WA-X/releases">Releases</a> ·
    <a href="https://t.me/WAXposed">Telegram Community</a> ·
    <a href="https://github.com/Alaa91H/WA-X/issues">Issues</a> ·
    <a href="https://ko-fi.com/alaa91h">Support development</a>
  </p>
</div>

> [!IMPORTANT]
> **WA X is a fork and continuation of [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer).**
> The upstream project and its contributors retain credit for inherited work.
> **Alaa** is the current developer and maintainer of this fork and does not claim authorship of code inherited from upstream.

## What WA X is

WA X is an open-source Android Xposed module that extends WhatsApp and WhatsApp Business at runtime. It is distributed as **one WA X module APK** with application ID `com.wax.module`, targeting:

- WhatsApp — `com.whatsapp`
- WhatsApp Business — `com.whatsapp.w4b`

The module keeps target-aware settings so the two target applications can use independent overrides while sharing one WA X installation.

WA X does **not** redistribute a modified WhatsApp APK, does not contain WhatsApp/Meta proprietary source code, and does not claim to weaken WhatsApp encryption or server security.

## Current status and compatibility

WA X is actively developed and should be treated as experimental software. WhatsApp changes frequently, and hooks that depend on internal classes or methods can stop working after a target-app update.

The repository maintains a generated [compatibility matrix](docs/COMPATIBILITY.md). A target version appearing in the declared-version list is **not** the same as resolver-verified support. The matrix intentionally reports `unknown` until enough runtime evidence exists.

There is no “anti-ban” guarantee. Using an Xposed module with a third-party application may violate that application's terms or trigger account-side checks. Keep backups and enable only features you understand.

## Representative capabilities

The current source tree contains features in areas such as:

- **Privacy & presence** — read/seen controls, typing/recording privacy, anti-revoke behavior, status privacy, per-contact privacy and call privacy.
- **Messaging & chats** — edited-message handling, forwarding controls, chat filters, pinned-chat limits and context actions.
- **Media & status** — status download, view-once handling, media-quality controls, audio transcription and status tools.
- **Calls** — call recording controls, call privacy/blocking and call-type behavior.
- **Appearance & interface** — themes, colors, bubbles, tabs, custom CSS and UI customization.
- **Automation & utilities** — Tasker integration, backup/restore, translation and navigation helpers.

Feature availability is version-dependent. A feature existing in source code or UI is not a promise that it works on every WhatsApp build.

## Installation — official API 102 module

Starting with **WA X 1.2.0-beta.9**, the only distributed WA X Android
package is `com.wax.module` using modern **libxposed API 102**. The previous
Legacy API 93 loader and the standalone Canary APK are no longer shipped.

1. Use a Vector/LSPosed installation that actually supports libxposed API 102.
   A framework advertising an older API is **not compatible** with this build.
2. Get the officially signed module from [GitHub Releases](https://github.com/Alaa91H/WA-X/releases)
   or the signed Beta Testing channel; retain your installed app's data.
3. Enable **WA X** in your Xposed manager, scoped **only** to:
   - `com.whatsapp`
   - `com.whatsapp.w4b` (only if you use WhatsApp Business)
4. Reopen the target app and verify that the WA X Manager has received a
   current-boot runtime heartbeat. A Framework Service connection or an
   installed HookHandle alone does **not** prove features work.

**Important (beta.9):** most old Xposed API 93 feature hooks have not yet
been ported to API 102. Their legacy settings may still be visible in the
Manager, but they cannot be assumed operational. The opt-in CustomTime
pilot is the first migrated user-visible hook and must be verified on a
rooted real device. This beta is **not feature complete or stable**.
Back up your preferences. Do not uninstall WA X to get around Android
signing errors, because uninstalling destroys app data.

The WA X APK is an Xposed module, not a replacement WhatsApp APK.
Historical releases remain in GitHub Releases as recovery references.

## Builds, releases and Telegram

The repository uses one authoritative GitHub Actions workflow.

| Build type | GitHub | Telegram |
|---|---|---|
| Pull request | Verification only | Not published |
| Successful non-release build | Actions artifact | [Beta Testing](https://t.me/WAXposed/18) |
| Version tag / GitHub Release | Release asset | [Updates & Releases](https://t.me/WAXposed/4) |

Telegram publishing uses direct MTProto. Stable releases and beta builds are intentionally kept in separate topics.

## Build from source

Typical local verification:

```bash
git clone --recursive https://github.com/Alaa91H/WA-X.git
cd WA-X

./gradlew assembleDebug testDebugUnitTest lintDebug spotlessCheck detekt
python3 tools/compatibility/validate_compatibility.py
python3 tools/compatibility/sync_generated.py --check
```

The project currently uses JDK 17 with minSdk 28, targetSdk 34 and compileSdk 37. Release signing is handled by CI; private signing material is not stored in this repository.

## Documentation

- [Extended project guide](docs/README.md)
- [Compatibility matrix](docs/COMPATIBILITY.md)
- [Project provenance & attribution](docs/PROJECT_PROVENANCE.md)
- [Support & contact](docs/SUPPORT.md)
- [Branding & logo usage](docs/BRANDING.md)
- [Telegram build/release publishing](docs/TELEGRAM_RELEASE_BOT.md)
- [Settings migration matrix](docs/SETTING_MIGRATION_MATRIX.md)
- [Customization / CSS guide](Theme.md)
- [Historical architecture plan](docs/MASTER_PLAN.md)
- [Historical 3.0 roadmap](docs/ROADMAP_3.0.md)

## Developer, community and support

| | |
|---|---|
| **Developer / current maintainer** | [Alaa](https://github.com/Alaa91H) |
| **Repository** | [Alaa91H/WA-X](https://github.com/Alaa91H/WA-X) |
| **Developer Telegram** | [@Alaa91h](https://t.me/Alaa91h) |
| **WA X Community** | [@WAXposed](https://t.me/WAXposed) |
| **Email** | [alahus2591@gmail.com](mailto:alahus2591@gmail.com) |
| **Voluntary support** | [Ko-fi / alaa91h](https://ko-fi.com/alaa91h) |
| **Upstream project** | [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer) |

For reproducible bugs, prefer [GitHub Issues](https://github.com/Alaa91H/WA-X/issues) with Android version, target package/version, steps to reproduce and sanitized diagnostics. For community discussion and help, use [@WAXposed](https://t.me/WAXposed).

Voluntary support does not buy access to locked project features. WA X does not maintain a paid feature tier.

## License

WA X is distributed under the **GNU General Public License v3.0 (GPL-3.0)**. See [LICENSE](LICENSE).

GPL-3.0 permits commercial use and distribution. Anyone distributing covered binaries or derivatives must comply with the applicable GPL source-code, notice and licensing obligations. Fork status does not erase upstream authorship or copyright.

## Independence & trademark disclaimer

**WA X is an independent open-source project and is not affiliated with, endorsed by, sponsored by, or officially associated with WhatsApp or Meta.**

“WhatsApp” and “Meta” are names/trademarks of their respective owners. References in this repository identify compatibility targets only.

---

Maintained by **Alaa**, with explicit credit to the original **Dev4Mod/WaEnhancer** project and its contributors.
