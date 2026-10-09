# Issue #433 — Embedded WA X Control Center inside WhatsApp

First vertical slice of the owner's P0 UX integration issue. Source- and
CI-verified; on-device confirmation is `PENDING_USER_DEVICE_TEST` and is
not a merge gate.

## What replaced what

Before, the WhatsApp overflow carried up to seven WA X rows: the Manager
link (#426) plus four per-feature entries, a restart row and a truncated
"other features pending" placeholder (#429). That is the clutter the issue
describes.

Now there is **exactly one** WA X row. `ModernMenuHomeFeature` keeps its
single `MENU_ITEM_ID` item, and tapping it opens the **embedded Control
Center in-process** (`ModernControlCenterShell`) instead of redirecting to
the Manager. The Manager is reached only through the explicit fallback when
the embedded surface cannot be shown, which is also signalled by a
diagnostic log. `ModernInWhatsAppSettingsMenu` and the truncated placeholder
are deleted, not hidden.

## Honesty rules encoded in code, not in the UI layer

`ControlCenterModel` is pure and unit-tested, and it is where "all controls
reflect verified effective status" is enforced:

- `ControlEffective` separates what the user requested from what the runtime
  actually reported: `NOT_OBSERVED`, `WORKING`, `INSTALLED`, `DISABLED`,
  `RESOLVER_FAILED`, `UNSAFE_SIGNATURE`, `PENDING_MIGRATION`, `ERROR`,
  `RESTART_REQUIRED`.
- `ControlPolicy.isWritable` allows a live switch only for a feature with a
  real preference key and a non-failed runtime state. Resolver failures,
  unsafe signatures, runtime errors and pending migration are **not**
  switchable.
- `ControlPolicy.effectiveFrom` maps raw runtime evidence to those states, so
  `RESOLVER_MISSING` can never render as working and an enabled-but-not-yet-
  reported feature honestly reads as restart-required.
- The dedicated pending area is a real list of named legacy-only features,
  rendered inert with "pending migration" status — replacing the truncated
  placeholder with something accurate.

## Effective state actually reaches the UI

libxposed RemotePreferences are read-only inside the hooked app, so the
Control Center reads the Manager's verified state through a new
UID-authenticated provider method `read-target-states-v1`. It returns only
allowlisted keys (`pref.<preferenceKey>` for the four migrated toggles and
`state.<evidenceKey>` for the reported states). A rejected or missing
response degrades to "not observed", never to a false "working".

Toggles write through the existing `write-target-setting-v1` channel into
the Manager preference file the relay already syncs, so hooks pick the new
value up after a restart, and the row says so immediately.

## Safety

- Framework widgets only: the modern runtime module deliberately carries no
  AndroidX dependency, so nothing theme- or context-surprising is pulled
  into WhatsApp.
- `show()` refuses when the Activity is finishing/destroyed or off the main
  thread, and wraps construction in a catch that falls back to the Manager.
  A failure in the UI can never crash WhatsApp; every write is on a daemon
  thread and results are posted back to the main thread.
- Native WhatsApp menu items are untouched: the hook still calls through
  first and keeps the original boolean result.

## Verification boundary

New pure tests: `ControlCenterModelTest` (writability, evidence mapping,
search/grouping, pending ordering, status text) and
`ModernControlCenterCatalogTest` (key parity with the runtime entry reads,
no pending/wired overlap, no duplicate ids, category coverage), plus the
provider allowlist test. Real rendering, RTL/theme rendering and the
Android 17 / WhatsApp 2.26.39.74 acceptance run are
`PENDING_USER_DEVICE_TEST`. Full CI must pass before merging.