# M06 stage — in-WhatsApp settings shell for migrated features

This changeset builds the Phase 1 settings surface: real per-feature
controls rendered inside WhatsApp for the features already wired to the
API 102 runtime. It is source- and CI-verified; on-device confirmation is
`PENDING_USER_DEVICE_TEST`.

## Why a new write channel

`XposedModule.getRemotePreferences()` is READ-ONLY inside hooked apps, so a
toggle flipped in WhatsApp cannot persist directly. The shell adds a second
provider method, `write-target-setting-v1`, next to the existing one-way
telemetry report:

- Target side (`ModernTargetSettingsClient`, `ModernInWhatsAppSettingsMenu`):
  no legacy Xposed API, no module resources, no RemotePreferences writes.
- Manager side (`ModernTargetTelemetryProvider.writeSetting`): same
  UID authorization as telemetry (caller UID must own the claimed
  `com.whatsapp` / `com.whatsapp.w4b` package), an allowlist of exactly the
  four enable keys of wired adapters, boolean values only. Writes land in
  the Manager default preferences — the file
  `ModernRuntimePreferenceRelay` already observes — so the existing relay
  syncs them into RemotePreferences. No new preference file, no cleared
  keys, no chat/contact/data transfer.
- Hooks read the new value after a WhatsApp restart. Every save tells the
  user a restart is required (dialog text + toast), and the menu carries a
  restart item mirroring the long-standing legacy restart behavior
  (relaunch intent + process exit).

## Scope discipline

- Four toggle items only: Custom Time, Share Limit, Freeze Last Seen,
  DND Mode — each key-verified by unit test against the exact
  `ENABLE_KEY` / pilot preference key the modern entry reads.
- One disabled note item: "other features pending (see Manager)" — a
  timeless honest label, never a fake control. MinorFixes stays staged and
  unwired; all 58 legacy-only features stay untouched.
- Hook ownership `in_whatsapp_settings` / `settings.options_menu` through
  `ModernHookRegistry`; idempotent install; same HomeActivity eligibility
  policy as the menu link. Menu-item IDs do not clash with the Manager
  link ID. Toggle labels snapshot on the bootstrap thread so the menu hook
  never performs RemotePreferences Binder IPC on the UI thread.
- Evidence event `IN_WHATSAPP_SETTINGS` with fixed outcome strings,
  queued through the same telemetry path as the menu link.

## Verification boundary

New pure tests: toggle-key parity with entry reads, menu-ID uniqueness,
honest ON/OFF titles (target side); write allowlist and fixed evidence
states (Manager side). Full CI (build, strict static/unit, CodeQL,
signature, `tools/quality` checkers, migration ledger) must pass before
merging. No device install or tap-through is claimed.
