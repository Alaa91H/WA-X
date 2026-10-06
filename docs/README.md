# WA X — Project Guide

<div align="center">
  <img src="assets/wa-x-app-icon.svg" alt="WA X logo" width="160" />
  <p><strong>Advanced WhatsApp Xposed Module</strong></p>
</div>

## Project identity

WA X is an independent, open-source Android Xposed module and a **fork/continuation of [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer)**.

The upstream project and its contributors remain credited for inherited work. This fork is currently developed and maintained by **[Alaa](https://github.com/Alaa91H)**. Maintaining the fork does not imply authorship of upstream code.

```text
Project: WA X
Repository: Alaa91H/WA-X
Module applicationId: com.wax.module
Targets: com.whatsapp, com.whatsapp.w4b
Distribution model: one WA X module APK
```

## Runtime model

WA X hooks selected behavior inside the installed WhatsApp/WhatsApp Business process through Xposed. It does not rebuild or redistribute WhatsApp itself.

The project currently uses the legacy Xposed API. In addition to the two WhatsApp targets, the hook scope contains `android` (System Framework) and `com.android.providers.settings` (Settings Provider) as infrastructure processes for package visibility and the settings bridge. They are not feature targets and should only be removed after that bridge is redesigned and verified.

Settings use a target-aware model so one module APK can retain separate effective configuration for WhatsApp and WhatsApp Business.

## Feature areas

The current source includes privacy, messaging, media/status, calls, customization, automation, storage and diagnostics functionality. Examples include anti-revoke behavior, privacy controls, edited-message handling, status/media tools, media-quality controls, call controls, themes/custom CSS, Tasker integration and backup/restore helpers.

Many hooks depend on internal WhatsApp code. Availability therefore changes with target versions.

## Compatibility policy

Read [COMPATIBILITY.md](COMPATIBILITY.md) before treating a feature/version pair as supported.

The compatibility model separates:

- **declared target versions** — versions accepted by the maintenance/runtime gate;
- **resolver evidence** — observed proof that required hook targets resolve;
- **feature state** — `supported`, `degraded`, `unsupported` or `unknown`.

A declared version is not a blanket support guarantee. `unknown` means the project does not yet have enough evidence to make a stronger claim.

## Installation

1. Install a compatible Xposed environment such as LSPosed.
2. Download the WA X module APK from [GitHub Releases](https://github.com/Alaa91H/WA-X/releases) for stable versions.
3. Install WA X and enable it in the Xposed/LSPosed manager.
4. Scope the target package(s):
   - `com.whatsapp`
   - `com.whatsapp.w4b`
5. If your Xposed manager requires explicit package scope, also include **System Framework** (`android`) and **Settings Provider** (`com.android.providers.settings`) for the current bridge implementation.
6. Restart the target app after enabling the module or changing hook-sensitive settings.

WA X is not a modified WhatsApp APK.

## Release channels

| Channel | Purpose | Location |
|---|---|---|
| Stable | Versioned builds that are also GitHub Releases | [GitHub Releases](https://github.com/Alaa91H/WA-X/releases) / [Updates & Releases](https://t.me/WAXposed/4) |
| Beta Testing | Successful builds without a GitHub Release | [Beta Testing](https://t.me/WAXposed/18) |
| Community | Questions, discussion and project community | [@WAXposed](https://t.me/WAXposed) |

## Safety and limitations

WA X does not promise permanent compatibility, server-side bypasses or “anti-ban” protection. WhatsApp may change client internals or server behavior at any time.

Keep backups of important data. If a target version is not resolver-verified, waiting for evidence is safer than assuming old hooks remain valid.

WA X does not provide tools to break WhatsApp encryption, compromise accounts, bypass paid services or weaken server security.

## Developer, contact and support

- **Developer / current maintainer:** [Alaa](https://github.com/Alaa91H)
- **Repository:** [github.com/Alaa91H/WA-X](https://github.com/Alaa91H/WA-X)
- **Developer Telegram:** [@Alaa91h](https://t.me/Alaa91h)
- **WA X Community:** [@WAXposed](https://t.me/WAXposed)
- **Email:** [alahus2591@gmail.com](mailto:alahus2591@gmail.com)
- **Bugs:** [GitHub Issues](https://github.com/Alaa91H/WA-X/issues)
- **Voluntary development support:** [Ko-fi](https://ko-fi.com/alaa91h)

Support is voluntary and does not purchase access to features. The open-source project does not maintain a paid feature layer.

## Provenance and license

See [PROJECT_PROVENANCE.md](PROJECT_PROVENANCE.md) for the detailed attribution policy.

This repository preserves its origin in **Dev4Mod/WaEnhancer** and the history of previous contributors. WA X branding and current maintenance do not rewrite that history.

The repository is licensed under **GNU GPL-3.0**. See [../LICENSE](../LICENSE). GPL-3.0 permits commercial use when the applicable license obligations are followed.

## Disclaimer

**WA X is an independent open-source project and is not affiliated with, endorsed by, sponsored by, or officially associated with WhatsApp or Meta.**

Use the module responsibly and understand that third-party terms and compatibility can change independently of this project.
