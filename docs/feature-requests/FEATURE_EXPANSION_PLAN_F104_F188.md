# WA X — Comprehensive Missing Feature Expansion Plan

> Audit date: 2026-10-07
>
> Baseline: 101 staged feature requests (F003–F103) plus inherited/current source capabilities documented in the repository.
>
> Result: **85 genuinely additive candidate features (F104–F188)** plus **12 extensions to existing issues**. Candidates were filtered against the current issue manifest and repository keyword search to avoid obvious duplicates.

## Product rule

Do not create a new issue when native WhatsApp or an existing WA X feature already owns the behavior. Prefer:

```text
detect native/current capability
→ extend existing WA X abstraction
→ preserve native behavior
→ add local value
→ capability-gate
→ safe fallback
```

Never fake server-side functionality, bypass WhatsApp/Meta entitlements, or create surveillance/spam tooling.

## Mandatory contract for every new feature

Every feature must declare:

```text
featureId
priority
category
target support
account support
required resolvers
supported version range
restart requirement
risk level
fallback behavior
kill switch
diagnostics
visualImpact
stockModeCompatible
stockModeFallback
tests
dependencies
```

Every feature must preserve WhatsApp/Business isolation, target/account routing, Safe Mode, Stock WhatsApp Mode, no-paywall policy, sanitized logging, and feature-level compatibility handling.

## New feature candidates

| ID | Priority | Feature | Area | Scope / value | Dependencies |
|---|---|---|---|---|---|
| F104 | P0 | Undo Send Buffer | Messaging | Local 2–15s send buffer with cancel/undo before native send; never fake server revocation. | F081, outgoing send pipeline |
| F105 | P0 | Multi-Select Batch Message Actions | Messaging | Select multiple messages and expose capability-filtered native actions: star, save, copy, forward, delete, bookmark, export metadata. | F103 |
| F106 | P1 | Reply Chain / Quote Navigator | Messaging | Navigate quoted-message chains backward/forward and show a local reply tree without copying the message database. | Advanced search/native quote resolver |
| F107 | P1 | Pinned Message Hub | Messaging | One place to view/jump/manage native pinned messages across selected chats, with expiry awareness. | Native pin capability |
| F108 | P1 | Composer Formatting Toolbar & Preview | Messaging | Accessible formatting toolbar for bold/italic/strike/monospace/lists/quotes/code with preview; use supported native syntax. | Composer hooks |
| F109 | P1 | Saved Searches & Smart Filters | Search | Save advanced-search queries and expose reusable smart views such as unread docs, mentions, starred media, overdue follow-ups. | F049 |
| F110 | P1 | Unified Local Search Hub | Search | Search WA X notes, bookmarks, transcripts, recordings metadata, reminders and OCR index from one manager surface. | F030,F047,F051,F090 |
| F111 | P1 | Message Navigation Backstack | Messaging | Back/forward navigation after jumping to quoted messages, search results, bookmarks or dates. | F049,F050,F051 |
| F112 | P0 | Status Audience Profiles | Status | Reusable Status audience presets with target/account scope and per-post effective-audience preview. | Status capability resolver |
| F113 | P1 | Own Status Archive & Memories | Status | Opt-in local archive of the user's own published Status media/text before expiry; never scrape other users in background. | F006,F090 |
| F114 | P1 | Status Feed Filters / Favorites / Snooze | Status | Local filters for favorites, muted contacts, tags and temporary snooze; no covert viewing or tracking. | F048,F005 |
| F115 | P1 | Status Quality & Network Optimizer | Status | Data-aware encode/quality profiles for Status uploads while respecting actual native limits. | F003,F004 |
| F116 | P1 | Status Cross-Post Privacy Guard | Status | Before native Facebook/Instagram/Accounts Center cross-post, show destination/audience/privacy confirmation and optional metadata cleanup. | F077,F099 |
| F117 | P2 | Channel Organizer | Channels | Local pin/mute/folder-style views for followed Channels without recreating server subscriptions. | Native Channels |
| F118 | P2 | Channel Digest & Keyword Alerts | Channels | Local digest/keyword alerts for Channels the user already follows; no hidden background surveillance of non-followed content. | F033 |
| F119 | P1 | Call Recording Transcription | Calls | Transcribe user-created call recordings locally when legal/consent policy permits; no cloud upload by default. | F037,F056 |
| F120 | P1 | Call Summary & Action Items | Calls | Generate local summaries, decisions and follow-up items from permitted call recordings/transcripts. | F119,F038,F056 |
| F121 | P1 | Call Quality Diagnostics | Calls | Local call-health view for network type, route changes, reconnects and available quality indicators; sanitized logs only. | Call resolvers |
| F122 | P1 | Call Network / Media Policy | Calls | Rules such as warn on metered video, Wi-Fi-preferred video, data-use thresholds, and safe downgrade prompts where native capability exists. | F035,F016 |
| F123 | P2 | Call Audio Route Profiles | Calls | Per-context defaults/preferences for Bluetooth, earpiece, speaker and wired routes without fighting Android safety behavior. | Android audio routing |
| F124 | P1 | In-Call Bookmarks / Markers | Calls | User-tap timestamp markers during permitted recordings for later notes/transcript navigation. | F037,F038 |
| F125 | P0 | Media Send Editor & Redaction | Media | Generic pre-send editor: crop, rotate, annotate, blur/redact, draw, trim and preview for supported media. | F023,F080 |
| F126 | P1 | Media Compatibility Converter | Media | Convert HEIC/WebP/unsupported audio/video formats to safe WhatsApp-compatible formats with quality preview. | Media pipeline |
| F127 | P1 | Secure Document Scanner & Redactor | Documents | Scan/crop/deskew documents, OCR locally, redact regions, sanitize metadata and export via scoped URI. | F080,F099 |
| F128 | P1 | OCR & Document Content Index | Search/Media | Opt-in encrypted local OCR/text index for images and documents to enable search without cloud processing. | F090,F049 |
| F129 | P1 | Sticker & GIF Manager | Media | Favorites, dedupe, pack backup/export, search and local organization while reusing native sticker sending. | Native sticker APIs |
| F130 | P2 | Recipient Watermark Profiles | Media/Privacy | Optional visible watermark templates (recipient/chat/time/custom text) applied before send to selected chats. | F125,F078 |
| F131 | P1 | Attachment Filename Sanitizer & Renamer | Media/Privacy | Preview/rename outgoing file names, remove chat/contact/path hints and normalize unsafe names before send. | F099 |
| F132 | P1 | Notification Action Customizer | Notifications | Choose safe quick actions per chat/profile: reply, mark read, mute, snooze, archive, open, with privacy-aware lockscreen behavior. | F034,F035 |
| F133 | P2 | Wear OS Privacy & Quick Actions | Devices | Per-profile wearable redaction plus safe quick actions without exposing sensitive chats on the watch. | F034,F078 |
| F134 | P1 | Android Auto / Driving Privacy Mode | Devices | Hands-free profile for sender/content redaction, read-aloud policy, reply restrictions and call behavior while driving. | F035,F015 |
| F135 | P2 | Bluetooth / Headset Privacy & Route Rules | Devices | Control when message TTS, voice-note playback and call audio may use paired devices; prevent accidental speaker exposure. | F035 |
| F136 | P1 | Group Event ↔ Calendar Sync | Groups | Mirror native WhatsApp events into local/system calendar with reminders and deep links; never create server events silently. | Native events |
| F137 | P1 | Poll Center & Templates | Groups | Local templates, reminders, export/summary and jump-to-poll hub using native polls; respect anonymous/end-time/edit capabilities. | Native polls |
| F138 | P1 | Group Member Notes & Local Roles | Groups | Private local notes/tags/roles for group members, encrypted and never sent to the group. | F047,F048,F090 |
| F139 | P0 | Group Safety Assistant | Groups/Security | Integrate native group safety overview with local unknown-sender rules, attachment quarantine and exit/review shortcuts. | F079,F085 |
| F140 | P1 | Group Mention Controls | Groups/Notifications | Local @all/mention notification policy, highlight rules and exemptions; do not spoof or mass-mention. | F034,F035 |
| F141 | P1 | Community / Announcement Digest | Groups | Periodic local digest of announcement groups/communities the user belongs to, privacy-redacted by default. | F033 |
| F142 | P2 | Local Group Topic / Subfilter Views | Groups | Client-side filtered views by sender/tag/keyword/media type for very busy groups; no server-side fake topics. | F049 |
| F143 | P0 | Unified Target / Account Dashboard | Accounts | One manager dashboard for target/account health, unread counts, active profiles, automation state, compatibility and deep links; no message-body mirroring by default. | F075,F062 |
| F144 | P1 | Cross-Account Unread Center | Accounts | Metadata-only consolidated unread/mention/follow-up view across WhatsApp, Business and detected accounts with correct routing. | F143,F052 |
| F145 | P0 | E2E WA X Settings Sync | Sync | End-to-end encrypted sync of WA X settings/profiles across the user's devices, with explicit device enrollment and conflict handling. | F090,F074 |
| F146 | P1 | Backup Destination Providers | Backup | Pluggable destinations: SAF/local, USB, WebDAV, SFTP and user-controlled NAS paths; no mandatory project cloud. | F066 |
| F147 | P0 | Incremental / Deduplicated Backup | Backup | Content-addressed incremental snapshots so unchanged auxiliary/media data is not recopied each run. | F066,F146 |
| F148 | P1 | Backup Snapshot Retention & Pruning | Backup | Keep N/daily/weekly/monthly snapshots, estimate space, verify before pruning, transactional cleanup. | F147,F067 |
| F149 | P0 | Restore Dry-Run & Conflict Resolver | Backup | Preview schema/settings conflicts and effective changes before selective restore; never overwrite blindly. | F068,F067 |
| F150 | P1 | Disaster Recovery Wizard | Backup/Recovery | Guided recovery for broken settings, key invalidation, failed migrations and corrupted backup sections. | F064,F067,F149 |
| F151 | P0 | Automatic Pre-Change Checkpoint & Rollback | Recovery | Create lightweight checkpoint before risky migration/import/profile bulk change; one-tap rollback of WA X-owned state. | F068,F163 |
| F152 | P1 | Human-Readable WA X Data Export | Data | Export selected WA X auxiliary data to sanitized JSON/CSV/Markdown for user portability; not a raw WhatsApp DB dump. | F065,F099 |
| F153 | P1 | Local Data Retention & Temporary-File Auditor | Data/Privacy | Central retention rules for WA X caches, temp shares, transcripts, diagnostics and orphaned files with dry-run cleanup. | F090,F099 |
| F154 | P0 | First-Run Capability Scanner & Setup Wizard | Compatibility | Detect target apps/accounts, LSPosed scope, permissions, resolvers and supported features; recommend safe defaults. | F062,F097 |
| F155 | P0 | Resolver Self-Test / Feature Health Check | Compatibility | User-triggered atomic self-test for every fragile resolver with PASS/WARN/FAIL and safe-disable suggestions. | F062,F063 |
| F156 | P0 | Hook Conflict Detector | Compatibility | Detect likely conflicts with other Xposed modules/hooks and identify feature surfaces without exposing private data. | Hook registry |
| F157 | P0 | Crash-Loop Auto Safe Mode | Recovery | Detect repeated WhatsApp/module crash loops and automatically disable recently activated high-risk features, preserving settings. | F064,F063 |
| F158 | P1 | Feature Performance / Battery Profiler | Diagnostics | Per-feature hook latency, wakeups, CPU/memory and battery-impact diagnostics to identify expensive features. | Diagnostics |
| F159 | P0 | WhatsApp Update Compatibility Diff | Compatibility | After target update, show which resolvers/features changed state since previous version and what was auto-disabled. | F062,F063 |
| F160 | LAB | Signed Resolver-Metadata Hotfix Packs | Compatibility | Signed data-only compatibility metadata updates with rollback; no arbitrary remote code execution. | F062,F063 |
| F161 | P0 | Diagnostics Bundle & One-Tap Issue Reporter | Diagnostics | Create sanitized reproducible report with versions, resolver states, logs and enabled-feature snapshot; user previews before sharing. | F155 |
| F162 | P1 | Update Channel & Release Signature Verification | Updates | Stable/beta channel selector, changelog, artifact signature/hash verification and downgrade warning for WA X releases. | Release infrastructure |
| F163 | P1 | Settings Change History & Rollback | Recovery | Local audit of WA X setting changes with diff and selective rollback; exclude secrets. | Settings store,F090 |
| F164 | P0 | Feature Dependency / Conflict Resolver | Architecture | Central engine that explains prerequisites/conflicts and prevents invalid combinations instead of scattered UI checks. | Feature registry |
| F165 | LAB | Opt-In Sanitized Compatibility Telemetry | Compatibility | Explicit opt-in submission of non-content resolver/version success metrics to improve compatibility; off by default and inspectable. | F155,F090 |
| F166 | P0 | LSPosed / Scope / Root Health Assistant | Compatibility | Validate module activation, recommended scope, framework bridge, root/Xposed state and common misconfiguration. | F154 |
| F167 | P1 | Local Semantic Search | AI/Search | Encrypted local embedding search across opted-in transcripts, notes, bookmarks and OCR text. | F090,F128,F169 |
| F168 | P2 | Local RAG — Ask Your Archive | AI | On-device Q&A over user-selected local indexed content with source jump-backs and strict data scoping. | F167,F169 |
| F169 | P1 | AI Runtime & Model Manager | AI | Manage local model downloads, checksums, size, quantization, hardware acceleration, per-feature model choice and deletion. | F056 |
| F170 | P1 | AI Privacy Router & Provider Consent | AI/Privacy | Per-task local-vs-cloud routing, explicit provider consent, data preview, retention policy and no silent upload. | F056,F090 |
| F171 | P1 | Business Inbox Triage | Business | Priority queue for unanswered, due-soon, overdue, VIP and follow-up conversations using local metadata. | F057,F058,F052 |
| F172 | P1 | CRM Pipeline & Customer Stages | Business | Local Lead/Qualified/Customer/Waiting/Won/Lost stages with tags, notes and next action; no spam automation. | F047,F048,F052 |
| F173 | P1 | Customer Consent / Preference Log | Business/Privacy | Encrypted local record of communication preferences/consent notes and last confirmation; never invent legal consent. | F090,F172 |
| F174 | P1 | Native Business Labels+ Extensions | Business | Enhance WhatsApp Business labels with local smart views, reminders, SLA and automation conditions without replacing native labels. | F057,F058,F016 |
| F175 | P1 | Response Analytics & SLA Dashboard | Business | Local metrics for response time, overdue count and workload trends using minimal metadata; no message-body analytics by default. | F058,F171 |
| F176 | P1 | Customer Timezone-Aware Scheduler | Business/Scheduler | Display recipient timezone when known/configured and warn on off-hours scheduled sends; never infer sensitive location. | F008,F059 |
| F177 | P1 | Conversation / CRM Handoff Export | Business/Data | Sanitized export of notes, tags, follow-ups and selected user-approved conversation references for CRM handoff. | F152,F172 |
| F178 | P1 | Read Aloud / TTS for Selected Messages | Accessibility | Local/system TTS for selected text with privacy-aware output routing and language choice. | F073 |
| F179 | P2 | Hands-Free Conversation Mode | Accessibility | Optional local speech-to-text + TTS workflow for accessibility/driving with confirmation before send. | F178,F134 |
| F180 | P1 | Video / Call Captioning | Accessibility/AI | Generate local captions/transcripts for user-selected videos and permitted call recordings; do not upload silently. | F056,F119 |
| F181 | P1 | Command Palette & Global Manager Search | UX | Search features/settings/routes and execute safe manager actions from one command palette. | Manager navigation |
| F182 | P1 | Favorite / Recent Settings | UX | Pin frequently used WA X settings and show recent changes without adding WhatsApp-side UI. | F163 |
| F183 | P1 | Profile Share / Import | Profiles | Export/import reusable WA X profiles with preview, validation and target/account mapping; no secrets by default. | F074,F152 |
| F184 | P0 | Feature Troubleshooting Wizard | Diagnostics/UX | For any unavailable/broken feature, explain resolver, permission, target/version, conflict and exact safe remediation. | F154,F155,F164 |
| F185 | P0 | QR / Deep-Link Security Guard | Security | Inspect QR/wa.me/http/intent-style targets locally for scheme, host, punycode and dangerous handoff before opening. | F084 |
| F186 | P0 | External Share-Intent Guard | Security | When content enters WhatsApp via Android share sheet/deep link, confirm target/account/chat and privacy policies before final send. | F081,F099 |
| F187 | P1 | Accounts Center / Meta Account Privacy Auditor | Security/Privacy | Detect/explain whether WhatsApp is linked to Meta Account/Accounts Center where observable and deep-link to native controls; never change silently. | F077,F094 |
| F188 | P1 | Security Posture Drift Monitor | Security | Detect when updates/migrations change important native/WA X privacy settings and surface a before/after review. | F077,F094,F100 |

## Extend existing issues instead of creating duplicates

| Existing issue | Expansion | Required change |
|---|---|---|
| F003/F004/F005 | Status native-feature coexistence | Detect and preserve native Status layouts, music/photo stickers, Add Yours, and Spotify/Apple Music sharing; extend rather than duplicate. |
| F026 | Native storage coexistence | Reuse WhatsApp's current per-chat Manage Storage/media-only clear where available and add WA X dry-run/duplicate intelligence around it. |
| F036/F079 | Unknown-caller context | Use current native country/common-group caller context as input to user-visible decisions; never infer compromise automatically. |
| F037 | Modern call capability integration | Preserve native noise suppression, QuickHD, waiting-room and call-transfer behavior; WA X policies must not break them. |
| F040 | Modern group/list coexistence | Respect native @all mute controls, group-message history, poll upgrades and create-chat-from-group workflows. |
| F055/F056 | Native AI coexistence | Do not duplicate native Writing Help/Meta AI invisibly; offer a clearly labeled local/private alternative and provider routing. |
| F065–F068 | Native encrypted backup posture | Detect/deep-link to native passkey-encrypted chat backup while WA X backup remains limited to WA X-owned/restorable data. |
| F077 | Strict Account Settings | Add state detection/explanation for native Strict Account Settings and its trade-offs. |
| F094 | 2026 account security | Track stronger alphanumeric two-step verification and multiple passkeys in the takeover checklist. |
| F093 | 2026 visual parity | Update Stock Mode parity baselines for current group, call, status, storage and multi-account UI changes. |
| F103 | Native quick-reaction conflict rules | Single-tap popup must coexist with WhatsApp's double-tap quick reactions and any native single-tap media behavior. |
| F097/F077 | Meta Account/permission posture | Surface cross-app/account-link posture separately from Android runtime permissions; do not merge the concepts. |

## Recommended execution order

### Milestone G0 — Compatibility and recovery foundations

Implement first:

```text
F154 Capability Scanner
F155 Resolver Self-Test
F156 Hook Conflict Detector
F157 Crash-Loop Auto Safe Mode
F159 WhatsApp Update Compatibility Diff
F161 Diagnostics Bundle
F164 Feature Dependency/Conflict Resolver
F166 LSPosed/Scope/Root Health Assistant
F184 Troubleshooting Wizard
```

These reduce the cost and risk of every later feature.

### Milestone G1 — Messaging interaction

```text
F104 Undo Send Buffer
F105 Multi-Select Batch Actions
F106 Reply Chain Navigator
F107 Pinned Message Hub
F108 Composer Formatting Toolbar
F109 Saved Searches
F110 Unified Search Hub
F111 Message Navigation Backstack
```

### Milestone G2 — Status and Channels

```text
F112 Status Audience Profiles
F113 Own Status Archive
F114 Status Feed Filters
F115 Status Quality Optimizer
F116 Status Cross-Post Privacy Guard
F117 Channel Organizer
F118 Channel Digest / Keyword Alerts
```

### Milestone G3 — Calls

```text
F119 Call Recording Transcription
F120 Call Summary
F121 Call Quality Diagnostics
F122 Call Network Policy
F123 Call Audio Route Profiles
F124 In-Call Bookmarks
```

### Milestone G4 — Media and documents

```text
F125 Media Send Editor & Redaction
F126 Media Compatibility Converter
F127 Secure Document Scanner
F128 OCR / Document Index
F129 Sticker & GIF Manager
F130 Recipient Watermark Profiles
F131 Attachment Filename Sanitizer
```

### Milestone G5 — Groups and communities

```text
F136 Group Event Calendar Sync
F137 Poll Center
F138 Group Member Notes
F139 Group Safety Assistant
F140 Group Mention Controls
F141 Community Digest
F142 Local Group Topic Filters
```

### Milestone G6 — Notifications, devices and accounts

```text
F132 Notification Action Customizer
F133 Wear OS Privacy
F134 Android Auto / Driving Privacy
F135 Bluetooth / Headset Privacy
F143 Unified Target/Account Dashboard
F144 Cross-Account Unread Center
```

### Milestone G7 — Sync, backup and data durability

```text
F145 E2E WA X Settings Sync
F146 Backup Destination Providers
F147 Incremental / Deduplicated Backup
F148 Snapshot Retention
F149 Restore Dry-Run
F150 Disaster Recovery Wizard
F151 Pre-Change Checkpoints
F152 Human-Readable Data Export
F153 Retention / Temp-File Auditor
F163 Settings Change History
F183 Profile Share / Import
```

### Milestone G8 — Compatibility evolution

```text
F158 Performance / Battery Profiler
F160 Signed Resolver-Metadata Hotfix Packs (LAB)
F162 Update Channel / Signature Verification
F165 Opt-In Compatibility Telemetry (LAB)
```

### Milestone G9 — Local AI

```text
F167 Local Semantic Search
F168 Local RAG
F169 AI Runtime & Model Manager
F170 AI Privacy Router
```

### Milestone G10 — Business

```text
F171 Business Inbox Triage
F172 CRM Pipeline
F173 Customer Consent Log
F174 Native Business Labels+
F175 Response Analytics
F176 Timezone-Aware Scheduler
F177 CRM Handoff Export
```

### Milestone G11 — Accessibility and manager UX

```text
F178 Read Aloud / TTS
F179 Hands-Free Conversation Mode
F180 Video / Call Captioning
F181 Command Palette
F182 Favorite / Recent Settings
```

### Milestone G12 — Security edge cases

```text
F185 QR / Deep-Link Guard
F186 External Share-Intent Guard
F187 Accounts Center / Meta Account Privacy Auditor
F188 Security Posture Drift Monitor
```

## Gate before converting any candidate to an issue

For each candidate, perform all of the following:

```text
1. Search existing issues by concept and synonyms.
2. Search repository code, settings keys, strings, hooks and docs.
3. Check current official WhatsApp capability for the installed target version.
4. Decide: NEW / EXTEND EXISTING / NATIVE-ONLY / DEFER / REJECT.
5. Identify WhatsApp + Business resolver ownership.
6. Define Stock Mode behavior.
7. Define target/account scope.
8. Define privacy/storage footprint.
9. Define tests and failure states.
10. Create issue only after duplicate/native-overlap gate passes.
```

## Features intentionally not proposed as new work

Do not create new standalone features merely to duplicate current/native capabilities already covered by WA X or WhatsApp, including generic translation, basic visual themes/CSS, ordinary Status download, generic View Once handling, basic media-quality toggles, native sticker creation, native group @all, native poll end-time/anonymous/edit controls, native event reminders, native call transfer, native waiting rooms, native noise suppression, native QuickHD, or native passkeys/two-step verification.

WA X should detect, expose, integrate, protect, or extend these when useful — not fork their logic unnecessarily.

## Final recommendation

Treat **F104–F188 as the next expansion pool**, but execute G0 first and require the duplicate/native-overlap gate before opening each issue.

The highest-value immediate additions are:

```text
F104 Undo Send Buffer
F125 Media Send Editor & Redaction
F139 Group Safety Assistant
F143 Unified Target / Account Dashboard
F145 E2E WA X Settings Sync
F147 Incremental Backup
F154 Capability Scanner
F155 Resolver Self-Test
F156 Hook Conflict Detector
F157 Crash-Loop Auto Safe Mode
F159 Compatibility Diff
F161 Diagnostics Bundle
F164 Dependency / Conflict Resolver
F166 LSPosed Health Assistant
F184 Troubleshooting Wizard
F185 QR / Deep-Link Guard
F186 External Share-Intent Guard
```

These improve user value and make the growing feature set substantially safer to maintain.
