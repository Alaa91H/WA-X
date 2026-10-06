# [P0][F011] AUTOMATIC VIEW-ONCE MEDIA POLICY

## Objective

Implement **AUTOMATIC VIEW-ONCE MEDIA POLICY** as an independent WA X feature/work item. This issue is intentionally self-contained for implementation, review, testing, compatibility tracking, and release readiness.

## Source requirements

Implement an automatic outgoing View Once policy using WhatsApp's **native View Once mechanism**.

Do not emulate View Once by sending normal media and deleting it later.

## Current supported native media classes

The implementation must capability-check the installed client, but current WhatsApp behavior supports native View Once for:

```text
Photos
Videos
Voice messages
```

Treat a native **voice message** separately from a generic audio-file attachment.

Do not assume that an arbitrary MP3/audio document supports View Once merely because voice messages do.

If a future WhatsApp version adds native View Once for another message type, enable it through capability metadata.

## Goal

Allow users to make selected outgoing media automatically use View Once without manually tapping the `1` control every time.

Example:

```text
Global:
Auto View Once = Disabled

Contact: Alice
Photos = Always View Once
Videos = Always View Once
Voice Messages = Normal

Contact: Bob
Photos = Normal
Videos = Normal
Voice Messages = Always View Once
```

## Scope hierarchy

Support:

```text
Global
Target
Account
WhatsApp List
Contact
Group
Per-message override
```

## Type-specific policy

Each scope should independently control:

```text
Photos
Videos
Voice Messages
```

Policy values:

```text
Use Parent
Normal
Always View Once
Ask Each Time
```

Optional compact model:

```text
Inherit
Off
On
Ask
```

## Outgoing media sources

The policy should work for eligible media sent from:

```text
Camera
Gallery
Android Photo Picker
Share intent
Document/media picker when it resolves to a supported native media class
WA X media tools
Imported voice-note-style media when valid
```

Do not apply View Once blindly to an unsupported attachment category.

## Send-pipeline integration

Apply the policy after final media type is known but before the final native send operation.

Conceptually:

```text
select/record media
→ edit/transcode
→ resolve final message/media class
→ resolve View Once policy
→ apply native View Once flag/state
→ native send
```

This is important because transcoding or editor output may change the final media representation.

## Native-only behavior

Use WhatsApp's actual View Once message representation / flag / send path for the installed version.

Never implement:

```text
normal media
→ custom local timer
→ pretend it was View Once
```

That would not provide the same recipient-side behavior.

## Per-message override

Allow easy one-time override before sending:

```text
Use chat policy
Normal
View Once
```

If the native WhatsApp `1` button is already visible:

- keep it functional;
- synchronize WA X policy with it;
- do not create two contradictory controls.

Example:

```text
Contact policy = Always View Once

User manually turns View Once off for this message
→ honor explicit per-message override
```

## Voice messages

Support automatic View Once for **native voice messages** when the installed client supports it.

Examples:

```text
All voice messages to selected contact → View Once
All Business voice messages → Normal
Selected contact voice messages → Ask each time
```

Do not apply the policy to generic audio attachments unless the current client explicitly supports native View Once for that class.

## Imported audio / voice-note conversion

If WA X later supports importing an audio file and converting it into a genuine native voice-message representation:

```text
audio file
→ validated voice-message conversion
→ native voice-message class
→ View Once policy may apply
```

Only after conversion is complete and capability is verified.

## Scheduled-message integration

If an eligible photo/video/voice message is scheduled:

```text
scheduled item
→ retains resolved policy source / explicit override
→ at send time revalidate current capability
→ apply native View Once
→ send
```

If WhatsApp changed and View Once is no longer resolvable:

- do not silently send normal media if the scheduled item explicitly required View Once;
- fail safely;
- notify user;
- allow manual retry.

This avoids accidentally sending sensitive media persistently.

## Auto Reply / Automation integration

For automation-generated eligible media:

- default to normal media unless explicitly configured;
- if View Once is required, apply the same native policy engine;
- never downgrade silently.

## Shared outgoing policy engine

Create/reuse a common layer such as:

```text
OutgoingMessagePolicyEngine
```

Possible responsibilities:

```text
Auto View Once
Timed Delete for Everyone
EXIF cleanup
Tracking-link cleanup
Media policy
Future send-time policies
```

Do not let each sender path reimplement these rules independently.

## Conflict resolution

Define deterministic precedence:

```text
Explicit per-message choice
→ Contact/Group override
→ List override
→ Account
→ Target
→ Global
→ Feature default
```

If a message has:

```text
View Once = ON
Auto Delete = 5 minutes
```

native View Once governs media disappearance after opening.

Timed Delete may still revoke the unopened message at its configured deadline if native behavior allows it and the user configured both.

Do not create race conditions.

## UI indication

Before sending, show a subtle but unmistakable indicator when policy automatically enabled View Once.

Examples:

```text
View Once · Contact policy
```

or the native `1` icon in active state.

Do not silently change media semantics with no visible cue.

## Compatibility

Use feature capability metadata:

```text
view_once.photo
view_once.video
view_once.voice_message
```

per:

```text
Target
Version
```

Do not assume Business and regular WhatsApp always share identical resolver mappings.

## Acceptance criteria

- automatic View Once works for photos;
- automatic View Once works for videos;
- automatic View Once works for native voice messages when supported;
- generic audio files are not incorrectly treated as voice messages;
- Global policy works;
- target policy works;
- account policy works where available;
- per-contact policy works;
- per-group policy works;
- List policy works where available;
- native `1` control remains functional;
- per-message override wins;
- Photo Picker path works;
- Camera path works;
- Gallery path works;
- scheduled eligible media revalidates capability before sending;
- explicit View Once scheduled media never silently downgrades to normal;
- WhatsApp and Business remain isolated;
- unsupported versions fail safely.

## Implementation contract

Before implementing this issue:

1. Inspect the current repository and identify any existing or partial implementation.
2. Reuse and extend existing infrastructure instead of creating duplicate systems.
3. Preserve every current WA X feature and native WhatsApp behavior not explicitly changed by this issue.
4. Keep WhatsApp and WhatsApp Business resolver paths, state, settings, queues, and failures isolated.
5. Preserve target-aware and account-aware behavior wherever the feature can vary by target/account.
6. Prefer the shared WA X settings hierarchy where applicable:
   `Global → Target → Account → Contact / Group / List`.
7. Never hard-code fragile WhatsApp internals, protocol limits, or version-specific values when a capability resolver/metadata path is appropriate.
8. Any resolver/hook failure must fail safely and must never crash WhatsApp.
9. High-risk behavior must have compatibility checks, diagnostics, fallback behavior, and a feature-level kill switch.
10. Reuse shared scheduler, outgoing-policy, privacy, media, storage, audit, and automation foundations where applicable instead of implementing parallel copies.

## Feature metadata required

Add/update feature metadata for:

- **featureId:** `F011`
- **priority:** `P0`
- category
- target support
- account support
- required resolver(s)
- supported version range
- restart requirement
- risk level
- fallback behavior
- kill switch
- tests
- diagnostics
- `visualImpact`
- `stockModeCompatible`
- `stockModeFallback`

## Compatibility and Stock WhatsApp Mode

- The implementation must follow `resolver → capability check → exact path → fallback → safe disable`.
- WhatsApp and WhatsApp Business must be validated independently.
- Unsupported versions must produce an explicit unsupported/disabled state instead of a crash.
- When **Stock WhatsApp / Zero-Visible-Modification Mode** is enabled, no WA X-specific UI may remain inside WhatsApp unless it is proven visually identical to stock behavior.
- Any feature that normally injects WhatsApp UI must provide an external/manager-side fallback when possible.
- Saved visual preferences must be suppressed rather than destroyed by Stock Mode.

## Privacy and storage requirements

- Store only the minimum data required for the feature.
- Do not log message bodies, secrets, private media paths, tokens, or unnecessary personal identifiers.
- Use Room for durable structured entities, settings storage for configuration, and scoped file/SAF storage for media where appropriate.
- Prefer Android Photo Picker / SAF / app-private storage over broad storage permissions.
- Request permissions only when the feature actually needs them.

## WA X access contract

This is a WA X-owned feature and must be available without an internal payment gate.

- No Premium / Pro / Donor / Supporter feature tier.
- No donation-to-unlock behavior.
- No remote entitlement server controlling availability.
- Technical/version/root/hardware/LAB safety restrictions are allowed when genuine.
- WhatsApp / Meta / third-party paid entitlements must remain respected and must not be bypassed.

## Required tests

At minimum add coverage for:

- WhatsApp target;
- WhatsApp Business target;
- target isolation;
- account routing/isolation where applicable;
- supported version path;
- unsupported version path;
- resolver failure/fallback;
- feature kill switch;
- Safe Mode behavior;
- process/app restart where state is durable;
- no settings cross-leak;
- no sensitive logging;
- dark/light/RTL review for any UI;
- Stock Mode behavior according to `visualImpact`;
- release build compatibility.

Also implement every feature-specific test implied or explicitly listed in **Source requirements** above.

## Definition of done

- [ ] Existing/partial implementation was audited before adding new code.
- [ ] Feature works on every declared target.
- [ ] Target/account state cannot cross-leak.
- [ ] Compatibility metadata is updated.
- [ ] Required resolver(s) and capability checks are implemented.
- [ ] Safe fallback and feature-level kill switch exist where needed.
- [ ] Diagnostics are sanitized and actionable.
- [ ] Feature-specific tests are complete.
- [ ] Unsupported versions fail safely.
- [ ] Safe Mode is verified.
- [ ] Stock WhatsApp Mode contract is verified.
- [ ] No internal WA X paywall/entitlement gate exists.
- [ ] No sensitive data is logged unnecessarily.
- [ ] Translations and accessibility are reviewed for any user-facing UI.
- [ ] Lint/static analysis/tests/release build pass.
- [ ] Known limitations are documented before closing.

## Completion report

When closing this issue, report:

- files changed;
- resolver(s) used;
- target support;
- account support;
- compatibility/version coverage;
- tests added;
- known limitations;
- storage/performance impact where relevant.
