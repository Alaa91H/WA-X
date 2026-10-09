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
- Source-wired to API 102 runtime (device UNVERIFIED): 10
  (CustomTime, ShareLimit, DndMode, FreezeLastSeen, MenuHome, ContactItemListener,
  ConversationItemListener, MenuStatusProvider, ActivityController, Tasker)
- Tasker is forward-direction only; its reverse send direction is reported
  honestly as Partial until the send pipeline migrates.
- Modern adapter present but NOT wired into `ModernXposedEntry`: 1 (MinorFixes)
- Legacy-only: 53
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
| #431 | ContactItemListener bind fan-out bus (W1 infra, consumer pending) | MERGED, main CI green |
| #432 | ConversationItemListener row bus (W1 infra, consumers pending) | MERGED, main CI green |
| #421 | Derived source-wiring ledger (anti-false-claim guard) | MERGED, main CI green |

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
| 21 | TypingPrivacy | W2 | 3 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 22 | HideChat | W2 | 3 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
| 23 | HideSeen | W3 | 9 | 7 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
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
| 35 | ViewOnce | W2 | 4 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |
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
| 64 | ContextMenuActionProvider | W1 | 2 | 1 | legacy-only | pending | — | — | PENDING_USER_DEVICE_TEST | pending |

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

## Remaining work (updated every turn)

0. HIGHEST PRIORITY (#433, owner P0): embedded Control Center — branch
   `feat/issue-433-embedded-control-center`. Vertical slice committed: exactly
   one WA X overflow entry opening an in-process control-center shell (not a
   Manager redirect), honest state model (requested vs verified effective vs
   pending/error), verified-state read channel, Manager fallback. Next: CI
   green, merge, then slices 2-3 (category navigation polish, favourites,
   RTL/Arabic localization, themes, accessibility) and the Control Center
   progress comments on #433.
1. DONE — Batch 1 closed at 5 verified features (ContactItemListener,
   ConversationItemListener, MenuStatusProvider, ActivityController, Tasker)
   and released as 1.2.0-beta.10 (PR #441). NEXT: Batch 2 —
   ContextMenuActionProvider, CustomToolbar, CustomView, CallPrivacy,
   CustomThemeV2, one at a time, each with hook path + preference path + UI
   wiring + tests + CI verified before the next.
2. THEN: continue Batch 2 and the rest of the wave plan in the table above,
   one feature at a time, each with hook path + preference path + UI wiring +
   tests + CI verified before the next.
3. Device acceptance checklist for the user (consolidated, at the end):
   every wired feature, the single WA X entry opening the Control Center
   (#433), each toggle applying after restart, and every later batch. Never a
   merge gate.
4. Gate M06 / Gate C proof only after the waves complete with CI + source
   evidence; hot reload stays disabled (M06.09); no dual-loader stable APK
   (M06.07).
