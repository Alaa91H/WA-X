# API 102 migration status — single source of truth

> Authoritative ledger for the WA-X libxposed API 102 feature migration.
> Updated in the SAME commit as any code change it describes.
> Generated 2026-10-09 from source-derived evidence only:
> `tools/modernization/report_api102_migration.py --json` (registry order,
> source-wiring state) × `tools/compatibility/extract_features.py`
> (per-feature resolver-dependency counts). No row claims on-device
> compatibility: every device cell is `PENDING_USER_DEVICE_TEST`.
> Device testing is the user's job and never blocks migration or merges.

## Headline counts (2026-10-09, main @ 5251fe12 + Tasker branch)

- Total registered features: 64
- Source-wired to API 102 runtime (device UNVERIFIED): 11 + 2 in flight
  (…, ContextMenuActionProvider; TypingPrivacy and HideChat on their branches,
  the first two consumers actually running on the accessor layers)
- Tasker is forward-direction only; its reverse send direction is reported
  honestly as Partial until the send pipeline migrates.
- Modern adapter present but NOT wired into `ModernXposedEntry`: 1 (MinorFixes)
- Legacy-only: 52 (50 once the in-flight branch merges)
- Device-behavior verified: 0 — recorded as `PENDING_USER_DEVICE_TEST`,
  the correct state, not a gap and never a merge blocker.
- In-WhatsApp settings surface: BUILT as the embedded Control Center (#433,
  branch `feat/issue-433-embedded-control-center`): exactly one WA X overflow
  entry opening an in-process shell with categories, search, real toggles for
  the wired adapters, an inert pending area, restart-required status and a
  Manager fallback. Device validation PENDING_USER_DEVICE_TEST. Controls for
  the still-legacy-only features stay pending until their own waves land.

## Wave / batch plan (M06.08 order, batches of 5 per task spec)

Wave rule (mechanical, refinement-allowed but never silent):

- W0 pilot/canary: the 5 wired features + MinorFixes (staged adapter) +
  DebugFeature (contract test double).
- W1 core/infra (blocking listeners, providers, controllers):
  ContactItemListener, ConversationItemListener, MenuStatusProvider,
  ContextMenuActionProvider, ActivityController, Tasker.
- W4 device/data/service edge: AntiWa, BackupRestore, CallRecording,
  CaptureDevice, AudioTranscript.
- W3 resolver-heavy: any remaining feature with ≥3 resolver dependencies.
- W2 simple single-hook: any remaining feature with ≤2 resolver dependencies.

Batch rule: Batch 0 = W0 (prior PRs, device-unverified). Batches 1–12 take
W1 → W2 → W3 → W4 in registry order, 5 features each (batch 12 has 2).
A batch merges on green CI for the exact commit plus source-level
verification (hook path, preference path, UI wiring, tests); a green build
alone is never proof a feature works. Device testing is not a merge gate.

Wave sizes today: W0=7, W1=6, W2=28, W3=18, W4=5 (total 64).

## Prior migration PRs (evidence, not re-done work)

| PR | Feature(s) | State |
|----|-----------|-------|
| #411 | CustomTime (first opt-in modern hook) | MERGED, main CI green |
| #417 | ShareLimit (hook + settings relay + target diagnostics) | MERGED, main CI green |
| #418 | FreezeLastSeen / DndMode presence pilots | MERGED, main CI green |
| #423 | MinorFixes adapter, source-stage only, NOT wired | MERGED, main CI green |
| #426 | MenuHome overflow-menu entry (access part of #425) | MERGED, main CI green |
| #427 | Authenticated runtime heartbeat + accurate Manager target state | MERGED, main CI green |
| #429 | In-WhatsApp settings shell (superseded by #436's single entry) | MERGED, main CI green |
| #436 | Embedded Control Center, single WA X entry (#433 slice 1) | MERGED, main CI green |
| #439 | Control Center localization, favourites, accessibility (#433 slice 2) | MERGED, main CI green |
| #435 | ActivityController contact-picker relay | MERGED, main CI green |
| #441 | Release 1.2.0-beta.10 (120), tag v1.2.0-beta.10 | MERGED, signed APK published |
| #442 | ContextMenuActionProvider message-selection popup bus | MERGED, main CI green |
| #444 | Message/contact accessor layer (API102 replacement for FMessageWpp/WaContactWpp) | MERGED, main CI green |
| #431 | ContactItemListener bind fan-out bus (W1 infra, consumer pending) | MERGED, main CI green |
| #432 | ConversationItemListener row bus (W1 infra, consumers pending) | MERGED, main CI green |
| #421 | Derived source-wiring ledger (anti-false-claim guard) | MERGED, main CI green |
| #445 | Modern resolver dependency findings (reads `modern-runtime`, no guessed names) | MERGED, main CI green |
| #446 | JID accessor (API102 replacement for `WaContactWpp.getJid`) | MERGED, main CI green |
| #447 | TypingPrivacy (privacy pair) | MERGED, main CI green |
| #453 | HideChat (privacy pair) | MERGED, main CI green |
| #454 | ViewOnce (privacy pair) | MERGED, main CI green |
| #170 | F155 atomic diagnostic + self-test engine, Manager screen, SAF ZIP export | PR #457 MERGED (`44b90875`), all 12 checks green on `8c093fc8`, issue CLOSED |
| #455 | API102 resolver, hook and core privacy failure investigation | PR #459 MERGED (`599deca6`), all 12 checks green on `a6f0f3b4` |
| #449 | Read receipt privacy: hide read receipts, release after reply, delivery tick | PR #460 MERGED (`67fc086b`), all 12 checks green on `92e8109f`; delivery reported UNSUPPORTED |
| #450 | Stealth privacy: typing, recording and online reported separately | PR #460 MERGED (`67fc086b`), all 12 checks green on `92e8109f` |
| #451 | Anti-Delete / anti-revoke with explicit capability boundaries | PR #461 MERGED (`3b6b4842`), all 12 checks green on `2c099394` |

### #170 scope notes

Delivered and merged (PR #457, `44b90875`): atomic engine with quick/deep modes,
cancellation, per-check timeouts and progress by verified counts; the shared
pipeline inventory; the per-feature half that appends one hook check and one
trigger check per wired feature with the resolver chain each one reads; guided
external verification recorded per feature and bound to the WhatsApp build it
was made on; redacted SAF ZIP export verified by re-opening; verified import
and comparison of a previous archive; root-cause clustering; EN+AR+10 more
localisations.

Deliberate ceilings, stated rather than hidden:

- a feature switched off in preferences is `NOT_TESTED`, never a failing hook;
- `L4` trigger evidence exists only for the feature that reports an invocation
  counter; for every other feature the trigger check stays `NOT_TESTED`;
- `L5` is unreachable without a person with a second account confirming it,
  and the confirmation is bound to one WhatsApp build;
- the legacy features in the #337 runtime registry report no per-feature state
  to the Manager, so the scan reports them as registered, never as hooked.

Residual unknowns: instrumentation coverage now covers restart, build binding
and the no-false-L5 rule (`ExternalVerificationStoreTest`); what is still absent
is instrumentation for IPC denial and version change. The anonymised sample ZIP
schema was posted on #170.

### #455 scope notes

Root cause established from the sanitised device evidence on the issue: the
embedded Control Center built its window without a lifecycle owner, so stopping
the host leaked the window and the centre never reported as opened. That is a
root cause, not a symptom, and it is fixed at the source: the window is now
bound to the host Activity, a replaced session is retired, accepted preference
writes are drained before closing, and a creation or show failure routes through
a single Manager fallback. Unit coverage covers lifecycle identity, repeated
open/close, replacement, fallback de-duplication, save draining, callback
cancellation and failure reasons.

Still open in this issue, and stated rather than glossed over:

- the contact-data anchor is corrected and selection fails closed: no
  `firstOrNull()`, ambiguity reported as its own outcome, and a class defined by
  another loader rejected before reflection. The correction is derived from the
  legacy resolver in this repository, so it needs a device run to confirm on a
  real target; until then the per-feature state stays `PENDING_USER_DEVICE_TEST`.
- `M06_CONTACT_ACCESS_EVIDENCE` now reports candidate counts and loader
  provenance, and it will appear in the exported diagnostics archive once the
  owner runs a scan on a real build.
- the everyday-privacy order (second tick, blue tick, blue after reply, status
  viewed, last seen, typing/recording) has no migrated implementation behind it;
  the Control Center lists them as pending rather than exposing dead toggles.
- no device reproduction has been performed; every per-feature state stays
  `PENDING_USER_DEVICE_TEST`.

Issue #425 stays OPEN until the in-WhatsApp per-feature settings surface
exists (the Manager link alone is not the full acceptance criterion).
Issue #369 (F060 status-adblock regression guard) stays OPEN and is out of
scope until its feature's wave arrives.

## Per-feature ledger

Columns: runtime = source-wiring fact; UI = in-WhatsApp control state;
branch/PR/CI = change evidence; device = `PENDING_USER_DEVICE_TEST` for
every row; status = honest roll-up.

| # | Feature | Wave | Batch | Deps | Runtime | In-WhatsApp UI | Branch / PR | CI | Device | Status |
|---|---------|------|-------|------|---------|----------------|-------------|----|--------|--------|
| 1 | DebugFeature | W0 | 0 | 0 | legacy-only (contract test double) | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 2 | MinorFixes | W0 | 0 | 0 | adapter present, NOT wired | pending | #423 MERGED | main green | PENDING_USER_DEVICE_TEST | staged, not active |
| 3 | ContactItemListener | W1 | 1 | 3 | wired (device UNVERIFIED) | infra (no user control) | #431 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 4 | ConversationItemListener | W1 | 1 | 0 | wired (device UNVERIFIED) | infra (no user control) | #432 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 5 | MenuStatusProvider | W1 | 1 | 3 | wired (device UNVERIFIED) | infra (no user control) | feat/m06-menu-status-provider | — | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 6 | ShowEditMessage | W3 | 7 | 3 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 7 | AntiRevoke | W3 | 8 | 4 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 8 | CustomToolbar | W2 | 2 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 9 | CustomView | W2 | 2 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 10 | SeenTick | W3 | 8 | 7 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 11 | BubbleColors | W3 | 8 | 3 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 12 | CallPrivacy | W2 | 2 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 13 | ActivityController | W1 | 1 | 1 | wired (device UNVERIFIED) | infra (Manager-driven) | #435 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 14 | CustomThemeV2 | W2 | 2 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 15 | FloatingBottomBar | W2 | 3 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 16 | ChatLimit | W3 | 8 | 5 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 17 | SeparateGroup | W3 | 8 | 15 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 18 | ShowOnline | W3 | 9 | 4 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 19 | DndMode | W0 | 0 | 1 | wired (device UNVERIFIED) | in-WhatsApp toggle + Manager | #418 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 20 | FreezeLastSeen | W0 | 0 | 1 | wired (device UNVERIFIED) | in-WhatsApp toggle + Manager | #418 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 21 | TypingPrivacy | W2 | 3 | 1 | wired; typing and recording reported separately (#450) | in-WhatsApp toggle (Privacy) | #460 MERGED (`67fc086b`) | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 22 | HideChat | W2 | 3 | 1 | wired (device UNVERIFIED) | in-WhatsApp 3-state control (Privacy) | feat/m06-hide-chat | — | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 23 | HideSeen | W3 | 9 | 7 | migrated: receipt_privacy_read / _after_reply / _delivery | Privacy: hide read receipts, release after reply, delivery reported UNSUPPORTED | #460 MERGED (`67fc086b`) | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 24 | HideSeenView | W2 | 3 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 25 | TagMessage | W2 | 3 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 26 | HideTabs | W3 | 9 | 4 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 27 | IGStatus | W3 | 9 | 6 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 28 | MediaQuality | W3 | 9 | 10 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 29 | NewChat | W2 | 4 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 30 | Others | W3 | 10 | 28 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 31 | PinnedLimit | W3 | 10 | 4 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 32 | CustomTime | W0 | 0 | 1 | wired (device UNVERIFIED) | in-WhatsApp toggle + Manager | #411 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 33 | ShareLimit | W0 | 0 | 1 | wired (device UNVERIFIED) | in-WhatsApp toggle + Manager | #417 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 34 | StatusDownload | W2 | 4 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 35 | ViewOnce | W2 | 4 | 1 | wired (device UNVERIFIED) | in-WhatsApp toggle (Privacy) | feat/m06-view-once | — | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 36 | CallType | W2 | 4 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 37 | MediaPreview | W2 | 4 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 38 | FilterGroups | W3 | 10 | 3 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 39 | Tasker | W1 | 1 | 1 | wired, forward direction only | in-WhatsApp toggle (partial status) | #440 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-partial (send direction pending) |
| 40 | DeleteStatus | W2 | 5 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 41 | DownloadViewOnce | W2 | 5 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 42 | Channels | W3 | 10 | 4 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 43 | DownloadProfile | W2 | 5 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 44 | ChatFilters | W2 | 5 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 45 | GroupAdmin | W2 | 5 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 46 | Stickers | W2 | 6 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 47 | CopyStatus | W2 | 6 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 48 | CopySelectionMessage | W2 | 6 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 49 | TextStatusComposer | W3 | 10 | 3 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 50 | ToastViewer | W2 | 6 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 51 | MenuHome | W0 | 0 | 0 | wired (device UNVERIFIED) | overflow-menu Manager link + settings shell host | #426 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |
| 52 | AntiWa | W4 | 11 | 3 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 53 | CustomPrivacy | W2 | 6 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 54 | AudioTranscript | W4 | 11 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 55 | GoogleTranslate | W2 | 7 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 56 | ContactVerify | W3 | 11 | 3 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 57 | LockedChatsEnhancer | W3 | 11 | 4 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 58 | CallRecording | W4 | 11 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 59 | BackupRestore | W4 | 12 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 60 | JumpFirstMessage | W2 | 7 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 61 | AboutContactPicker | W2 | 7 | 0 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 62 | DefaultEmoji | W2 | 7 | 2 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 63 | CaptureDevice | W4 | 12 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 64 | ContextMenuActionProvider | W1 | 2 | 1 | wired (device UNVERIFIED) | infra (no user control) | #442 MERGED | main green | PENDING_USER_DEVICE_TEST | migrated-pending-user-test |

Batch map (mechanical chunks of the W1 → W2 → W3 → W4 registry order):
batch 1 = ContactItemListener, ConversationItemListener, MenuStatusProvider,
ActivityController, Tasker; batch 2 = ContextMenuActionProvider,
CustomToolbar, CustomView, CallPrivacy, CustomThemeV2; batch 3 =
FloatingBottomBar, TypingPrivacy, HideChat, HideSeenView, TagMessage;
batch 4 = NewChat, StatusDownload, ViewOnce, CallType, MediaPreview;
batch 5 = DeleteStatus, DownloadViewOnce, DownloadProfile, ChatFilters,
GroupAdmin; batch 6 = Stickers, CopyStatus, CopySelectionMessage,
ToastViewer, CustomPrivacy; batch 7 = GoogleTranslate, JumpFirstMessage,
AboutContactPicker, DefaultEmoji, ShowEditMessage; batch 8 = AntiRevoke,
SeenTick, BubbleColors, ChatLimit, SeparateGroup; batch 9 = ShowOnline,
HideSeen, HideTabs, IGStatus, MediaQuality; batch 10 = Others, PinnedLimit,
FilterGroups, Channels, TextStatusComposer; batch 11 = AntiWa,
AudioTranscript, ContactVerify, LockedChatsEnhancer, CallRecording;
batch 12 = BackupRestore, CaptureDevice.

## Remaining work (as of `3b6b4842`)

Nothing below is claimed as done.

### Merged, device validation still outstanding

| issue | state |
|---|---|
| #170 | merged `44b90875`, **CLOSED**, anonymised schema posted on the issue |
| #455 | root causes merged `599deca6`: control-center lifecycle, contact-data anchor, fail-closed resolver selection |
| #449 | merged `67fc086b`: read receipts withheld, release-after-reply, delivery tick `UNSUPPORTED` |
| #450 | merged `67fc086b`: typing and recording reported separately; online `SERVER_CONTROLLED` |
| #451 | merged `3b6b4842`: anti-revoke, bounded retention, no protected content copied |
| #452 | merged `9a9d0a72`: Status seen privacy, separate from chat receipts |
| #357 | merged `5c8d91b6`: the Status reply seen-receipt rule; native path `NATIVE_PATH_UNRESOLVED` |
| #433 | **CLOSED**: Control Center acceptance met, verified in the tree |
| #425 | **CLOSED**: single menu entry restored, Control Center is the control path |
| #396 | **CLOSED**: merged `e6b056a0`, inherited-certification refused at the generator |

Every row carries `PENDING_USER_DEVICE_TEST`. Sender-visible behaviour needs the
owner's second account and is not substitutable by a build.

### Next, in order

1. **#391** — resolver evidence identity and package/version scoping. #396 is
   closed, so this is the remaining half of the evidence gate.
2. **#390 / #388** — the risk-ranked resolver audit and the compatibility-cell
   evidence gap.
3. **#383** — the master audit that aggregates the above.
4. **#377** — per-build WhatsApp/Business version discovery and the
   compatibility canary.
5. **#403** — the release APK growth budget, which currently watches debug only.
6. **#448** stays open on its own device gate: its four P0-CORE children are all
   merged, and closing it would close a parent whose acceptance says the
   sender-account check has to pass first.
7. **#455** stays open for the same reason: the code-side root causes are fixed,
   but the anchor correction wants a run on a real build to confirm.

### Not started

Ascending, once the items above are done: #458, #437, #400, #395, #394, #393,
#389, #387, #386, #385, #384, #379, #378, #372, #371, #370, #369, #368, #354,
#353, #352, #351, #350, and the remainder of the open list.

### Standing constraints

- One integration branch; one visible PR per owner-level task or batch of five.
- CI is the only place builds and tests run; no gate is ever weakened.
- Device testing belongs to the owner and never blocks a merge or a closure.
- A hook being installed is never reported as a feature working.
