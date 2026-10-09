# M06 Batch 1 — ActivityController modern port (W1 core infra)

Fourth W1 migration: the Manager-driven contact-picker relay behind the
Manager's About/Contacts preference. Source- and CI-verified; on-device
confirmation is `PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `ActivityController.doHook()` hooks the app-lock auth check to force
it false while a WA X picker round trip is open, hooks `Activity.onCreate`
on the notification-settings Activity to launch the target's own About
picker when `contact_mode` is set, and normalizes the picker result in
`onActivityResult`.

`ModernActivityControllerFeature` keeps the same three hooks, atomically:

- app-lock auth method — resolved from the repo-derived anchors
  `privacy_fingerprint_enabled` + `app_lock_auth_needed` (the exact pair the
  legacy resolver requires, so ambiguity is rejected rather than guessed:
  `AUTH_RESOLVER_AMBIGUOUS`);
- notification-settings Activity — resolved from the target's **declared**
  activities using the proven `SettingsNotifications` suffix contract that
  `WhatsAppContactPickerLauncher` already uses, never a guessed name
  (`SETTINGS_ACTIVITY_UNRESOLVED` if absent);
- About picker Activity — same declared-activity lookup with the proven
  `.settings.About` / `.settings.ui.About` / `.About` suffixes;
- result normalization — pure `applyPickerResult()` fills only missing
  extras, then `setResult(RESULT_OK)` and `finish()`.

Safety: the bypass window is armed only when the relay itself opens a
picker, and disarmed on any activity result or activity destroy, so it can
never outlive the flow that opened it. The request code and every extra key
match the Manager's `ContactPickerPreference` / launcher contract.

## Deliberate boundaries

- Always-on infrastructure driven by the Manager; no user toggle, therefore
  no in-WhatsApp control. Evidence event `ACTIVITY_CONTROLLER`.
- `ContactPickerResult` payload entries are still filled empty; the Manager
  interprets them, exactly as the legacy path did.
- 9 wired, 1 staged, 54 legacy-only in the derived ledger.

## Verification boundary

New pure tests pin the auth anchors to the legacy resolver, the activity
suffix contract to the launcher, "fill only missing extras" result shaping,
picker-intent construction, and the closed-by-default bypass window. The
real round trip needs the target app (`PENDING_USER_DEVICE_TEST`). Full CI
must pass before merging.