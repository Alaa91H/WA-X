# WA X — Historical Architecture & Migration Plan

> [!IMPORTANT]
> This file is an **English historical record**, not the current source of truth for releases or compatibility.
>
> WA X is a fork/continuation of [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer), currently maintained by [Alaa](https://github.com/Alaa91H). The project now ships one `com.wax.module` APK for WhatsApp and WhatsApp Business with target-aware settings.
>
> Current operational truth is maintained in [../README.md](../README.md), [COMPATIBILITY.md](COMPATIBILITY.md), [SETTING_MIGRATION_MATRIX.md](SETTING_MIGRATION_MATRIX.md) and the CI workflow.

## Purpose

The original T00–T75 program was created to move an inherited Xposed module toward a safer architecture without pretending that WhatsApp compatibility could be proven statically.

The plan was governed by these principles:

1. Measure a baseline before large refactors.
2. Keep compatibility evidence separate from declared target versions.
3. Refactor one architectural layer at a time.
4. Add tests before replacing fragile hook/resolver behavior.
5. Fail individual features safely instead of crashing the whole module.
6. Keep user data out of diagnostics by default.
7. Preserve release signing and reproducible CI behavior.
8. Document architectural deviations instead of silently changing assumptions.

## Historical phases

| Phase | Focus | Current interpretation |
|---|---|---|
| T00–T04 | Baseline, CI regression gates, compatibility inventory | Superseded by current CI and generated baseline/compatibility tooling |
| T05–T15 | Resolver isolation and Unobfuscator hardening | Foundation for typed resolver outcomes and fallback behavior |
| T16–T25 | Feature registry, failure isolation, security | Converged into the current runtime/platform safety layer |
| T26–T40 | Tests, native/runtime hardening | Continued as normal maintenance |
| T41–T55 | UI, preferences, diagnostics | Evolved into target-aware settings and manager UI work |
| T56–T65 | Compatibility/release discipline | Current compatibility matrix and CI enforce the core intent |
| T66–T75 | Cleanup and release readiness | Historical completion criteria; current release workflow is authoritative |

## Current architecture that supersedes old flavor assumptions

Earlier versions of the plan referred to separate WhatsApp/Business product flavors and separate module application IDs. That is no longer the current architecture.

```text
One module APK
applicationId: com.wax.module

Targets:
- com.whatsapp
- com.whatsapp.w4b

Settings:
- Global defaults
- WhatsApp overrides
- WhatsApp Business overrides
```

The target apps remain separate processes, but WA X itself is one application/module.

## Current release model

```text
Pull request
→ verification only
→ no Telegram publication

Successful non-release build
→ GitHub Actions artifact
→ Telegram Beta Testing (topic 18)

Version tag
→ validated signed APK
→ GitHub Release
→ Telegram Updates & Releases (topic 4)
```

## Compatibility truth

The project intentionally distinguishes target versions declared by maintainers, structural feature dependencies, runtime resolver evidence and feature-level support state.

No documentation should equate “declared version” with “fully supported version” unless runtime evidence justifies that claim.

## Security and privacy principles retained from the plan

- Do not log raw phone numbers, JIDs or message bodies in diagnostics.
- Do not expose signing or Telegram secrets in CI.
- Do not execute untrusted pull-request code with release secrets.
- Do not claim anti-ban guarantees.
- Do not claim server-side capabilities that are not implemented locally.
- Preserve upstream license and authorship history.

## Historical metrics

Older task notes referenced per-flavor builds and test counts. Those figures represented the repository at that time. They are not automatically current after the one-APK migration.

For current metrics, regenerate:

```bash
bash tools/baseline/generate_baseline.sh
```

and use the generated baseline files.

## Maintainer and provenance

- Current developer/maintainer: [Alaa](https://github.com/Alaa91H)
- Telegram: [@Alaa91h](https://t.me/Alaa91h)
- Community: [@WAXposed](https://t.me/WAXposed)
- Email: [alahus2591@gmail.com](mailto:alahus2591@gmail.com)
- Support: [Ko-fi](https://ko-fi.com/alaa91h)
- Upstream: [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer)

For attribution details see [PROJECT_PROVENANCE.md](PROJECT_PROVENANCE.md).
