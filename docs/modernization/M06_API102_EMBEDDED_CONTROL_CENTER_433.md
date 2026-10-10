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
- The window is bound to the exact host `HomeActivity`. One session is reused
  for repeated taps in that Activity; when Android recreates the Activity,
  the old session is retired and cannot release the new session.
- The lifecycle callback closes and dismisses the dialog on the main thread
  when its host stops or is destroyed, and unregisters itself on every close
  path, including manual dismissal and show failure. Close is idempotent.
- Creation/show failures, including `WindowManager.BadTokenException`, return
  to the menu hook, which requests the Manager fallback through a per-Activity
  single-flight gate. The menu-item-added event is not evidence that the
  Control Center opened.
- Preference writes run on a daemon executor. Closing rejects new writes but
  drains writes already accepted, preserving completed saves. Session Handler
  callbacks are removed and late worker completions cannot update a closed
  window. Restart is queued behind accepted writes.
- Diagnostics distinguish window created, shown, reused, closed, and failed;
  failure logs use fixed reasons and exception class names only, without
  preference values or chat data.
- Native WhatsApp menu items are untouched: the hook still calls through
  first and keeps the original boolean result.

Root cause for the API 102 WindowLeaked regression: the shell created a
`Dialog` from `HomeActivity` without registering for that Activity's lifecycle.
When Android stopped or destroyed the host, the window and its views retained
the old Activity. The lifecycle-bound session and task-scope handling above
address that ownership gap. The WhatsApp source is not modified.

## Verification boundary

New pure tests: `ControlCenterModelTest` (writability, evidence mapping,
search/grouping, pending ordering, status text) and
`ModernControlCenterCatalogTest` (key parity with the runtime entry reads,
no pending/wired overlap, no duplicate ids, category coverage), plus the
provider allowlist test. Real rendering, RTL/theme rendering and the
Android 17 / WhatsApp 2.26.39.74 acceptance run are
`PENDING_USER_DEVICE_TEST`. Full CI must pass before merging.

Lifecycle regression coverage was added in commit
`8ef897d329f87f7d3a4f0970f1903637e82cfcc3`: host identity, stop/destroy,
main-thread dispatch, exactly-once cleanup, repeated open/close, Activity
replacement, save draining, Handler callback removal, and single-flight
fallback behavior. Unit and static gates pass for that branch. The new APK
has not been installed on the phone, so real `Dialog.show()`/
`BadTokenException` and rotation behavior on the target device remain
`NOT TESTED`.

## Slice 2 — localization, favourites, accessibility

Slice 1 shipped with hardcoded English labels, no favourites and thin
accessibility. Slice 2 replaces that with real behaviour:

- **Localization (`ControlCenterStrings`)**: an explicit EN/AR table chosen
  from the target's own current locale, because the modern runtime module
  carries no AndroidX and no bundled resource table and therefore cannot
  resolve layout strings. Unknown languages fall back to English rather than
  rendering blanks. Tests assert the tables are complete and that Arabic is
  translated rather than copied.
- **Favourites with real persistence**: the favourite list is stored through
  the authenticated write channel in the Manager preference file the relay
  syncs, as a comma-separated id list under
  `wax.control_center.favorites`. The Manager validates it before persisting:
  lowercase/digits/underscore only, bounded length, no empty segments — a
  path-traversal or injection-shaped value is rejected. A "Favourites only"
  filter and a per-row favourite button operate on that persisted state, so
  the feature survives a restart instead of being cosmetic.
- **Accessibility**: switches and the favourite button carry content
  descriptions built from the row's real status, and non-actionable rows are
  marked not-important for accessibility so a pending row cannot be announced
  as a switch.

Honesty boundary: RTL rendering on the device, TalkBack traversal and the
Android 17 / WhatsApp 2.26.39.74 acceptance run remain
`PENDING_USER_DEVICE_TEST`.
