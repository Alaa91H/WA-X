# M06 Android 17 / Vector real-device evidence (2026-10-09)

## Consent / device boundary

The user connected their Poco F5 (`marble`) and explicitly asked to **read logs only**, not to install any APK or change apps/device settings. All commands used for this report were read-only ADB package/process/logcat and root `cat` against Vector's existing log files. The user's next APK installation is manual. Raw traces remain local, not committed or uploaded.

Device: Android SDK 37; WhatsApp `com.whatsapp` build `2.26.39.74` (Business absent).
Observed installed WA X: `1.2.0-beta.9-dev+6620182A` (the merged #419 runtime IPC fix).

## Vector independently confirms the modern class *loader*

`/data/adb/lspd/log/modules_2026-10-09T00:54:59.073891.log` includes:

```text
09:13:55.552 D/VectorModuleManager Loading module com.wax.module
09:13:55.588 V/VectorModuleManager Loading class class com.wax.module.modern.ModernXposedEntry
09:13:55.591 D/VectorModuleManager Loaded module com.wax.module successfully.
```

Earlier loader records appear at 08:00:58 and 08:27:56. This establishes class loading inside a WhatsApp-owned process, but not any libxposed callback, hook registration, target `Application.attach` callback or authenticated bootstrap IPC.

The available Android Logcat and Vector module log snapshot contained **no**
`WA-X Modern`, `Bootstrap lifecycle`, `Application.attach observed` or
`Target-to-Manager bootstrap delivery` entries. This **does not prove callbacks
did not run**; they could have used a different logging channel. Therefore the
next diagnostic builds must record each milestone independently through Android
`Log.i/w/e` rather than claiming all hooks are operational.

## Distinct failures — no attribution without evidence

- Vector Manager process `org.matrix.vector.manager` crashed at 09:11:47,
  `Resources$NotFoundException`: attempted to load
  `com.android.shell:integer/cancel_button_image_alpha` as a Drawable while
  initializing `MainActivity`. This is an upstream framework Manager/activity
  resource issue; it is **not** a demonstrated WA X process crash.
- Earlier legacy module run at 01:15:41 was `DEGRADED`:
  `TypingPrivacy` GhostMode method did not match expected argument type;
  `loadMediaQualitySelectionMethod` was unresolved. These refer to an earlier
  legacy APK and must not be used to assess current API102 callback behavior.
- One release update check failed to resolve `api.github.com`
  (`UnknownHostException`); no causal link to injection was established.

## Diagnostic-only source change

`ModernXposedEntry` now mirrors exactly seven **non-sensitive lifecycle
milestones** to Android's `Log.i/e`, while retaining existing framework logs:

- `M06_LIFECYCLE_MODULE_CALLBACK`
- `M06_LIFECYCLE_PACKAGE_CALLBACK`
- `M06_LIFECYCLE_HOOK_INSTALLED`
- `M06_LIFECYCLE_HOOK_FAILED`
- `M06_LIFECYCLE_ATTACH_OBSERVED`
- `M06_LIFECYCLE_PROVIDER_RESULT accepted=true/false`
- `M06_LIFECYCLE_PROVIDER_ERROR`

Each entry includes only the package name, hook origin or delivery boolean.
It never records chat text, account identifiers or contact data.

## How to validate after the user manually installs the next APK

1. Confirm the **new installed version/commit** via read-only
   `adb shell dumpsys package com.wax.module`.
2. After the user opens WhatsApp and WA X, collect
   `adb logcat -d -v threadtime` and the relevant Vector module log.
3. Compare stage order. A Vector `Loaded module` event without
   `M06_LIFECYCLE_MODULE_CALLBACK` narrows investigation to the framework's
   callback invocation or log delivery. A hook-installed event without
   `ATTACH_OBSERVED` narrows it to lifecycle timing. A provider result of
   `accepted=false` points to target UID/provider contract. A successful
   provider report but absent UI heartbeat points to Manager-side reads.
4. Prove visible feature behavior separately; a hook-installed or callback
   report is not enough to declare the modernization completed.

**Do not install, uninstall, clear app data, restart or alter Vector settings
through ADB for this check.**
