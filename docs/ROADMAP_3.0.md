# WA X — Historical 3.0 Expansion Roadmap

> [!IMPORTANT]
> This is an **English historical roadmap** summarizing the former T76–T160 expansion program. It is retained for engineering context, not as a promise that every planned capability is currently complete or compatible with every WhatsApp build.
>
> WA X is a fork/continuation of [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer), maintained by [Alaa](https://github.com/Alaa91H).

## Roadmap intent

The 3.0 roadmap extended the earlier architecture work toward a compatibility-aware feature platform where one broken resolver or feature should not destabilize the whole module.

The core architectural goals were:

- metadata-driven feature registration;
- target-aware settings;
- feature-level compatibility state;
- safe hook/fallback behavior;
- local-first privacy for intelligence features;
- sanitized diagnostics;
- testable automation/storage/media components;
- one module APK supporting both WhatsApp targets.

## Historical workstreams

| Workstream | Former tasks | Intended scope |
|---|---|---|
| Privacy profiles | T76–T79 | Per-target/per-chat privacy profiles and schedules |
| Platform foundation | T80–T85 | Registry metadata, kill switch, Safe Mode, compatibility summary |
| History & scheduling | T86–T96 | Timeline helpers, reminders, scheduled messages, templates |
| Automation | T97–T105 | Rule model/engine and Tasker integration |
| Intelligence | T106–T115 | Translation, transcription, summaries with explicit privacy gates |
| Media | T116–T124 | Media catalog, download policy, quality and maintenance |
| Theme & accessibility | T125–T134 | Theme packages, accessibility and UI controls |
| Notifications & calls | T135–T143 | Notification/call policy controls |
| Storage | T144–T151 | Storage dashboard, backup and duplicate management |
| Multi-target/account work | T152–T160 | Package/account context and migration strategy |

## Verification philosophy

Historical roadmap notes recorded large test counts and separate flavor builds. Those figures represented the repository at that time and are not automatically current after the one-APK migration.

Current verification should be taken from the active CI workflow and regenerated baseline rather than copied from historical notes.

The acceptance philosophy remains useful:

1. Register features through one architecture instead of adding parallel frameworks.
2. Define compatibility requirements explicitly.
3. Isolate failures.
4. Test storage/automation logic independently of hooks.
5. Keep cloud processing opt-in.
6. Prevent diagnostics from leaking message bodies or raw identifiers.
7. Never mark compatibility as supported without evidence.

## Current state that supersedes the old roadmap

```text
applicationId: com.wax.module
targets:
  - com.whatsapp
  - com.whatsapp.w4b
```

Settings support Global, WhatsApp and WhatsApp Business contexts. Release builds publish one APK.

For current truth use:

- [../README.md](../README.md)
- [README.md](README.md)
- [COMPATIBILITY.md](COMPATIBILITY.md)
- [SETTING_MIGRATION_MATRIX.md](SETTING_MIGRATION_MATRIX.md)
- [TELEGRAM_RELEASE_BOT.md](TELEGRAM_RELEASE_BOT.md)

## Provenance and contact

The roadmap applies to the WA X fork and does not erase the history of Dev4Mod/WaEnhancer or its contributors.

- Developer/maintainer: [Alaa](https://github.com/Alaa91H)
- Developer Telegram: [@Alaa91h](https://t.me/Alaa91h)
- Community: [@WAXposed](https://t.me/WAXposed)
- Email: [alahus2591@gmail.com](mailto:alahus2591@gmail.com)
- Voluntary support: [Ko-fi](https://ko-fi.com/alaa91h)
