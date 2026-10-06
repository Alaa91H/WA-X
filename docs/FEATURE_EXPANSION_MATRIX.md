# WA X — Feature expansion matrix

> Scope: new feature additions and feature expansion only. Rebranding, package migration, the
> one-APK move, CI/CD, Material 3, legal attribution and the full architecture reorganisation
> are treated as already done and are not repeated here.

This document is the planning and status view of the feature-additions work. The
**source of truth for whether a feature is implemented is the platform catalog**,
`PlatformFeatureCatalog`; this file says what is intended, what exists and what is deferred, and
`tools/quality/check_matrix_ids.py` fails the build if the two disagree.

## How to read it

**Priorities**

| Value | Meaning |
|---|---|
| P0 | Highest value, implemented first |
| P1 | High value, second wave |
| P2 | Advanced, heavier |
| LAB | Experimental, capability-gated |

**Status vocabulary**

| Status | Meaning |
|---|---|
| `implemented` | The logic exists, is tested in this repository, and is registered in the catalog. |
| `foundation` | The shared machinery it depends on exists and is tested; the feature's own surface is not wired yet. |
| `planned` | Specified, not started. |
| `deferred` | Blocked on something that must be proven first. |
| `avoided` | Deliberately not built, with the reason recorded. |

**Other columns** — `WhatsApp` / `Business` are yes/no for isolation and support; `Acct` is
`aware` when the feature resolves per account, `target` when it is one value per target and says
so, `n/a` when accounts do not enter into it; `Native` records overlap with a native WhatsApp
feature, which the build extends rather than duplicates.

The contract every row is subject to:

```text
Feature availability =
    implementation exists
    AND target/version is compatible
    AND required capability exists
    AND required permission/capability is available
    AND safety policy allows it
```

never `user paid`. Supported reasons a WA X feature is unavailable are experimental status,
unsafe or broken on the installed version, an unsupported target or account type, a missing
root/API/hardware capability, or a compatibility kill switch. These are technical states; the
interface shows the real reason and never a tier.

---

## Part 1 — Additions, by milestone

### F1 — Status and first-wave policies

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `outgoing.policy` — shared send-time policy engine | P0 | high | med | med | settings scopes, capability probe | yes | yes | aware | none | implemented | `OutgoingPolicyEngineTest` |
| `outgoing.auto_view_once` — automatic View Once | P0 | high | high | med | send path resolver, `view_once.*` capability | yes | yes | aware | native `1` control | implemented | `OutgoingPolicyEngineTest`, `StockModeTest` |
| `media.policy` — per-chat / per-group media policy | P0 | high | low | low | shared scope chain, media classes | yes | yes | aware | none | implemented | `MediaPolicyTest` |
| `media.source_mode` — Photo Picker direct mode | P0 | med | low | low | Android Photo Picker | yes | yes | target | native gallery | implemented | `MediaSourcePolicyTest` |
| `notifications.cooldown` — burst control | P0 | high | low | med | notification profiles | yes | yes | aware | none | implemented | `NotificationCooldownTest` |
| `presence.activity_alerts` — typing / recording / upload alerts, per activity and per contact | P0 | high | low | med | chat-state and media-transfer signals, shared scope chain | yes | yes | aware | extends native "typing…" | implemented | `PresenceAlertTest`, `PresenceAlertEngineTest` |
| `status.audio_studio` — preparation planner and drafts (composer entry not wired) | P0 | high | med | high | Status capability resolver, native publish path | yes | yes | n/a | replacement for recording | foundation | `StatusAudioStudioTest` |
| Status Video Toolkit | P0 | med | med | high | same as above | yes | yes | n/a | none | planned | — |
| Status Viewer Toolkit | P0 | med | low | med | Status hooks | yes | yes | n/a | extends viewer | planned | — |

### F2 — Reminders, calls, focus

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Message reminders / snooze | P1 | high | low | low | local store | yes | yes | aware | none | planned | — |
| Manual call recording | P1 | med | med | high | call hooks, `CallRecordingSettingsActivity` | yes | yes | n/a | placeholder | foundation | `CallsTest` |
| Focus / quiet schedules | P1 | high | low | med | privacy schedule, notifications | yes | yes | aware | none | foundation | `PrivacyScheduleTest`, `NotificationsTest` |
| Call blocking schedule | P1 | med | med | med | call rules | yes | yes | aware | none | foundation | `CallsTest` |

### F3 — Scheduler, revocation, drafts

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `outgoing.timed_revoke` — timed delete for everyone | P0 | high | high | high | revoke-window probe, durable queue | yes | yes | aware | native Delete for Everyone | implemented | `MessageRevocationQueueTest`, `OutgoingPolicyEngineTest` |
| Advanced message scheduler (queue, recurrence, retry) | P0 | high | med | high | Room queue, AlarmManager, WorkManager | yes | yes | aware | detect and prefer native | foundation | `SchedulingTest` |
| Scheduler safety (rate limits, backoff, emergency pause, audit) | P0 | high | med | med | scheduler | yes | yes | aware | none | foundation | `SchedulingTest` |
| Status drafts | P1 | med | low | low | drafts store | yes | yes | aware | none | planned | — |
| Smart drafts hub | P1 | med | low | med | drafts store | yes | yes | aware | none | planned | — |
| Message templates / snippets with variables | P1 | high | low | low | templates store | yes | yes | aware | none | foundation | `SchedulingTest` |

### F4 — Voice notes

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Voice Note Control Center (playback speeds) | P0 | high | low | med | voice-note hooks | yes | yes | aware | extends player | foundation | — |
| Voice-note transcription rules | P0 | high | med | med | transcription engine | yes | yes | aware | overlaps native | foundation | `IntelligenceTest` |
| Voice-note summary | P1 | med | med | high | local AI pack | yes | yes | aware | none | planned | — |
| Voice-note browser | P1 | med | low | med | catalog | yes | yes | aware | none | foundation | `MediaToolkitTest` |
| Voice-note processing (trim, normalise, denoise) | P1 | low | med | high | encoders | yes | yes | n/a | none | planned | — |

### F5 — Status toolkits

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Status drafts (audio/video/image/text) | P1 | med | low | low | storage | yes | yes | aware | none | planned | — |
| Status archive and download | P1 | high | low | low | media downloads | yes | yes | n/a | extends viewer | foundation | `MediaToolkitTest` |

### F6 — Automation

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Safe auto reply | P1 | high | med | med | rules engine, cooldowns | yes | yes | aware | none | foundation | `RulesEngineTest` |
| Unified automation rule engine | P2 | med | med | high | rules, scheduler, profiles | yes | yes | aware | none | foundation | `RulesEngineTest` |
| Tasker 2.0 actions/events | P1 | med | med | med | auth token, rules | yes | yes | aware | none | foundation | `TaskerTest` |
| Quick Settings tiles | P1 | med | low | low | Android tile service | yes | yes | n/a | none | planned | — |
| Home-screen shortcuts | P1 | low | low | low | Android shortcuts | yes | yes | n/a | none | planned | — |

### F7 — Media hygiene

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| EXIF / metadata cleaner | P1 | high | med | med | media pipeline | yes | yes | aware | none | planned | — |
| Link tracking cleaner | P1 | high | low | low | send pipeline | yes | yes | aware | none | planned | — |
| Duplicate media finder | P1 | med | low | med | hashing, SAF | yes | yes | aware | none | foundation | `MediaToolkitTest` |
| Smart storage manager | P1 | med | med | med | catalog, hashes | yes | yes | aware | none | foundation | `StorageSecurityTest` |
| Media retention rules | P1 | med | med | low | storage policies | yes | yes | aware | none | foundation | `StorageSecurityTest` |

### F8 — Conversation productivity

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Contact notes / nicknames | P1 | high | low | low | encrypted local store | yes | yes | aware | none | planned | — |
| Contact tags | P1 | high | low | low | tags store | yes | yes | aware | none | planned | — |
| Local message bookmarks | P1 | med | low | low | history store | yes | yes | aware | none | foundation | `MessageHistoryTest` |
| Follow-up flag (follow up / waiting / done) | P1 | med | low | low | contact store | yes | yes | aware | none | planned | — |
| Edited-message diff viewer | P1 | med | low | med | edit history | yes | yes | aware | extends history | foundation | `MessageHistoryTest` |
| Advanced message search | P1 | high | low | high | indices | yes | yes | aware | none | foundation | `MessageHistoryTest` |
| Jump to date | P1 | med | low | med | native history | yes | yes | aware | extends native | planned | — |
| Archive enhancements | P1 | low | low | low | home screen hooks | yes | yes | n/a | extends native | planned | — |

### F9 — High privacy and stock fidelity pack

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `platform.stock_mode` — Stock WhatsApp Mode | P0 | high | high | med | feature metadata, loader | yes | yes | n/a | suppresses WA X UI only | implemented | `StockModeTest`, `FeatureContractTest` |
| Visual parity check (structure, not pixels) | P0 | high | med | med | stock mode | yes | yes | n/a | reference is official build | implemented | `StockModeTest` |
| Branding leak check | P0 | high | low | low | stock mode | yes | yes | n/a | none | implemented | `StockModeTest` |
| `platform.access_contract` — all features free | P0 | high | low | low | catalog, migration | yes | yes | n/a | respects third-party entitlements | implemented | `NoPaywallContractTest`, `FeatureContractTest` |
| Privacy & Security Center | P0 | high | med | high | native setting detection | yes | yes | aware | detect + deep-link | planned | — |
| Sensitive Chat Mode | P0 | high | med | high | privacy profiles, screen shield | yes | yes | aware | integrates chat lock | planned | — |
| Untrusted sender firewall | P0 | high | med | med | notifications, media policy | yes | yes | aware | extends native | planned | — |
| `outgoing.auto_view_once` as a sensitive-chat default | P0 | high | high | low | engine, sensitive chat | yes | yes | aware | native View Once | foundation | `OutgoingPolicyEngineTest` |
| Sensitive data leak prevention (OTP, keys, cards) | P0 | high | med | med | local scanner | yes | yes | aware | none | planned | — |
| Wrong-recipient / forwarding guard | P0 | med | low | med | send pipeline | yes | yes | aware | none | planned | — |
| Screen capture & screen-sharing shield | P1 | high | med | med | window APIs | yes | yes | aware | none | planned | — |
| Incognito keyboard / no-learning mode | P1 | med | low | low | IME flags | yes | yes | aware | none | planned | — |
| Secure link guard | P1 | med | med | med | link parsing, cleaner | yes | yes | aware | none | planned | — |
| Attachment firewall & quarantine | P1 | med | med | med | media pipeline | yes | yes | aware | none | planned | — |
| Hidden chat footprint suppression | P1 | med | med | high | UI hooks — suppressed under stock mode | yes | yes | aware | none | planned | — |
| Contact identity mask / local alias | P1 | med | low | med | encrypted store | yes | yes | aware | none | planned | — |
| Linked device watch | P1 | high | low | med | session list capability | yes | yes | aware | reads native list | planned | — |
| Security code change monitor | P1 | high | low | med | identity-change events | yes | yes | aware | extends native | planned | — |
| Encrypted local privacy data | P1 | high | med | med | Keystore | yes | yes | aware | none | planned | — |
| Confidential chat session | P1 | med | med | med | policy engine | yes | yes | aware | none | foundation | `OutgoingPolicyEngineTest` |
| Overlay / tapjacking guard | P2 | low | low | low | obscured-touch API | yes | yes | n/a | none | planned | — |
| `platform.stock_mode` per-target independence | P0 | high | low | low | stock mode | yes | yes | n/a | none | implemented | `StockModeTest` |
| Account Takeover Guardian | P0 | high | med | high | security-state detection | yes | yes | aware | detect + deep-link | planned | — |
| Username / phone-number privacy manager | P1 | med | low | med | native capability detection | yes | yes | aware | detect + deep-link | planned | — |
| Encrypted private media vault | P1 | high | med | high | Keystore, SAF | yes | yes | aware | none | foundation | `StorageSecurityTest` |
| Permission & sensor privacy monitor | P1 | med | low | low | Android APIs | yes | yes | n/a | none | planned | — |
| Group security change alerts | P1 | med | low | med | group events | yes | yes | aware | none | planned | — |
| Secure media export | P1 | high | med | med | EXIF cleaner, SAF | yes | yes | aware | none | planned | — |
| Privacy event timeline | P1 | med | low | med | encrypted store | yes | yes | aware | none | planned | — |
| Native Lists+ (default list, badges, schedules) | P1 | med | low | med | native lists | yes | yes | aware | extends native | planned | — |
| Privacy profiles (Normal/Private/Stealth/Work/Family) | P1 | high | low | med | privacy profiles | yes | yes | aware | none | foundation | `PrivacyProfilesTest` |
| Temporary stealth mode | P1 | high | low | low | privacy schedule | yes | yes | aware | none | foundation | `PrivacyScheduleTest` |
| Panic privacy mode | P1 | med | low | low | privacy schedule | yes | yes | aware | none | planned | — |
| Notification digest | P1 | med | low | med | notifications | yes | yes | aware | none | planned | — |

### F10 — Backup

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Selective backup | P1 | high | med | med | config schema | yes | yes | aware | none | foundation | `ConfigBackupSchemaTest` |
| Encrypted local backup | P1 | high | med | med | Keystore, HKDF | yes | yes | aware | none | foundation | `HKDFTest` |
| Backup verification | P1 | high | med | low | backup writer | yes | yes | aware | none | foundation | `BackupRestoreTest` |
| Selective restore (all-or-nothing) | P1 | high | med | med | backup reader | yes | yes | aware | none | implemented | `BackupRestoreTest`, `TargetScopedBackupTest` |

### F11 — Business

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Business workspace (notes, tags, follow-ups) | P1 | high | low | med | contact store | yes | yes | aware | extends native | planned | — |
| SLA / response timer | P1 | med | low | med | message events | yes | yes | aware | none | planned | — |
| Working hours profile | P1 | high | low | low | focus schedules | yes | yes | aware | none | planned | — |
| Post-call notes | P1 | med | low | low | call events | yes | yes | aware | none | planned | — |

### F12 — Compatibility and control surface

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Version Guardian | P1 | high | low | med | compatibility matrix | yes | yes | aware | reads Play state | foundation | `CanaryTest`, `CompatibilitySummaryTest` |
| Feature-level auto disable | P1 | high | med | low | kill switch | yes | yes | aware | none | implemented | `KillSwitchTest`, `RuntimeSafetyTest` |
| Safe Mode enhancements | P1 | high | med | low | safe mode | yes | yes | aware | none | foundation | `RuntimeSafetyTest` |
| WA X Mini Control Center (inside WhatsApp) | P1 | med | med | med | UI hooks — not injected under stock mode | yes | yes | aware | none | planned | — |
| Feature availability policy | P0 | high | low | low | catalog, facts | yes | yes | aware | none | implemented | `FeatureContractTest` |

### F13 — Intelligence

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Conversation intelligence (local-first) | P2 | med | med | high | local AI pack | yes | yes | aware | overlaps native | foundation | `IntelligenceTest` |
| Optional local AI pack (model download/delete) | P2 | med | med | high | transcription, summary | yes | yes | aware | none | planned | — |

### LAB

| Feature | Priority | Demand | Risk | Complexity | Dependencies | WhatsApp | Business | Acct | Native | Status | Tests |
|---|---|---|---|---|---|---|---|---|---|---|---|
| LAB — scheduled Status | LAB | med | med | high | scheduler + Status Studio | yes | yes | aware | none | deferred | — |
| LAB — video note attachment | LAB | low | high | high | video-note resolver | yes | yes | n/a | native video note | deferred | — |
| LAB — silent send | LAB | low | high | med | none known | yes | yes | n/a | none | deferred | — |
| LAB — multiple msgstore import/merge | LAB | low | high | high | schema detection, backups | yes | yes | n/a | none | deferred | — |

**What must be proven first for the deferred rows.** Scheduled Status needs the scheduler and
Status Studio stable first, and starts in reminder/user-confirmed mode before fully automatic
posting. Video note attachment needs a reliable resolver for the native video-note format.
Silent send ships only if the current client/protocol exposes a legitimate supported
silent-delivery capability; local sender behaviour cannot silence a recipient's device, and
pretending otherwise is not a feature. Msgstore import stays research until the schema is
understood, version detection exists, a dry run and a pre-mutation backup are mandatory, and an
unknown schema aborts.

### Deliberately not built

| Item | Reason |
|---|---|
| Message bomber / flood sender | Not a privacy or productivity feature; it is abuse tooling. |
| Stealth mass mention | Same reason, and it defeats the purpose of a group's notifications. |
| Covert online-history spy list | Continuous surveillance of contacts, which this project does not do. |
| Continuous block surveillance | Same reason. |
| WhatsApp / Meta / third-party entitlement bypass | Out of scope by policy: another party's paid capability is respected, never forged. |
| Fake anti-ban or fake server-limit bypass | Deceptive; it would promise a protection that does not exist. |
| "The recipient cannot know" claims | False for any remote recipient, who can always photograph a screen. |

---

## Part 2 — Platform catalog coverage

Every id registered in `PlatformFeatureCatalog`, grouped the way the catalog groups them.
`tools/quality/check_matrix_ids.py` asserts this list and the catalog are the same set, so a new
declaration cannot be added without documenting it here, and a row here cannot outlive the
declaration it describes.

`Registration` is the catalog's own `StartupPolicy`, which is what the loader obeys.

| Id | Category | Registration |
|---|---|---|
| `platform.diagnostics` | RUNTIME_SAFETY | EAGER_CRITICAL |
| `platform.compat` | RUNTIME_SAFETY | EAGER_CRITICAL |
| `platform.settings` | RUNTIME_SAFETY | EAGER_CRITICAL |
| `platform.recovery` | RUNTIME_SAFETY | EAGER_CRITICAL |
| `platform.kill_switch` | RUNTIME_SAFETY | EAGER_CRITICAL |
| `platform.safe_mode` | RUNTIME_SAFETY | EAGER_CRITICAL |
| `platform.canary` | RUNTIME_SAFETY | EAGER_NORMAL |
| `platform.compat_summary` | RUNTIME_SAFETY | LAZY |
| `platform.access_contract` | RUNTIME_SAFETY | EAGER_CRITICAL |
| `platform.stock_mode` | THEME | EAGER_NORMAL |
| `outgoing.policy` | PRIVACY | EAGER_NORMAL |
| `outgoing.auto_view_once` | PRIVACY | LAZY |
| `outgoing.timed_revoke` | PRIVACY | EAGER_NORMAL |
| `privacy.profiles` | PRIVACY | EAGER_NORMAL |
| `privacy.overrides.contacts` | PRIVACY | LAZY |
| `privacy.overrides.groups` | PRIVACY | LAZY |
| `privacy.schedule` | PRIVACY | LAZY |
| `history.edits` | MESSAGE_HISTORY | LAZY |
| `history.deleted` | MESSAGE_HISTORY | LAZY |
| `history.timeline` | MESSAGE_HISTORY | ON_DEMAND |
| `history.notes` | MESSAGE_HISTORY | LAZY |
| `history.bookmarks` | MESSAGE_HISTORY | LAZY |
| `history.context_actions` | MESSAGE_HISTORY | LAZY |
| `scheduler.messages` | MESSAGE_HISTORY | EAGER_NORMAL |
| `scheduler.recurring` | MESSAGE_HISTORY | LAZY |
| `scheduler.undo_send` | MESSAGE_HISTORY | EAGER_NORMAL |
| `scheduler.templates` | MESSAGE_HISTORY | LAZY |
| `automation.rules` | AUTOMATION | EAGER_NORMAL |
| `automation.simulator` | AUTOMATION | ON_DEMAND |
| `automation.audit` | AUTOMATION | LAZY |
| `automation.tasker` | AUTOMATION | LAZY |
| `intelligence.translation` | INTELLIGENCE | LAZY |
| `intelligence.transcription` | INTELLIGENCE | LAZY |
| `intelligence.summary` | INTELLIGENCE | ON_DEMAND |
| `media.center` | MEDIA | LAZY |
| `media.downloads` | MEDIA | LAZY |
| `media.quality` | MEDIA | LAZY |
| `media.duplicates` | MEDIA | ON_DEMAND |
| `media.status_archive` | MEDIA | LAZY |
| `media.cleanup` | MEDIA | ON_DEMAND |
| `media.policy` | MEDIA | EAGER_NORMAL |
| `media.source_mode` | MEDIA | LAZY |
| `status.audio_studio` | MEDIA | LAZY |
| `theme.engine` | THEME | EAGER_NORMAL |
| `theme.packages` | THEME | ON_DEMAND |
| `theme.typography` | THEME | LAZY |
| `theme.accessibility` | THEME | LAZY |
| `notifications.profiles` | NOTIFICATION | LAZY |
| `notifications.actions` | NOTIFICATION | LAZY |
| `notifications.otp` | NOTIFICATION | LAZY |
| `notifications.quiet_hours` | NOTIFICATION | LAZY |
| `notifications.calls` | NOTIFICATION | EAGER_NORMAL |
| `notifications.cooldown` | NOTIFICATION | EAGER_NORMAL |
| `presence.activity_alerts` | NOTIFICATION | EAGER_NORMAL |
| `storage.dashboard` | STORAGE | LAZY |
| `storage.cleanup` | STORAGE | LAZY |
| `storage.duplicates` | STORAGE | ON_DEMAND |
| `storage.vault` | STORAGE | ON_DEMAND |
| `storage.backup` | STORAGE | LAZY |
| `multi.packages` | MULTI_ACCOUNT | EAGER_NORMAL |
| `multi.accounts` | MULTI_ACCOUNT | LAZY |
