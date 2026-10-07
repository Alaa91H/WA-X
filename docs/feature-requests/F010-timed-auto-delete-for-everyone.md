# [P0][F010] TIMED AUTO DELETE FOR EVERYONE

## Objective

Implement **TIMED AUTO DELETE FOR EVERYONE** as an independent WA X feature/work item. This issue is intentionally self-contained for implementation, review, testing, compatibility tracking, and release readiness.

## Source requirements

Implement a durable automatic outgoing-message revocation policy.

## Goal

Allow the user to define a timer such as:

```text
30 seconds
1 minute
5 minutes
15 minutes
30 minutes
1 hour
6 hours
12 hours
24 hours
Custom
```

After a successfully sent message reaches the configured age, WA X should automatically request WhatsApp's native:

```text
Delete for everyone
```

for that message.

The user must be able to choose shorter or longer custom delays within the actual revoke window supported by the currently installed WhatsApp version.

## Important semantic rule

This feature is for **messages sent by the current user**.

Do not automatically delete messages sent by other participants merely because the user is a group administrator.

Group-admin moderation is a separate behavior and must not be silently mixed into this feature.

## Supported outgoing message types

The policy must be designed around a generic outgoing message identity rather than a text-only hook.

Apply it, where the current WhatsApp client supports native Delete for Everyone, to:

```text
Text
Emoji
Image
Video
Voice message
Audio attachment
Document
Sticker
GIF
Contact card
Location
Poll or other supported outgoing message types
Future compatible message types
```

Do not maintain separate deletion schedulers for each media type.

Resolve the canonical sent-message ID and route all eligible types through one deletion queue.

## Timer start point

Do not start the deletion timer merely when the Send button is tapped.

Prefer:

```text
send request
→ successful local/server send acknowledgement
→ stable message identity available
→ create auto-delete job
→ countdown begins
```

If a reliable acknowledgement signal is unavailable for a specific version, use the safest validated fallback and record that fallback in diagnostics.

## Scope hierarchy

The setting must support:

```text
Global
Target
Account
WhatsApp List
Contact
Group
Per-message override
```

Example:

```text
Global:
Auto Delete = Disabled

WhatsApp:
Use Parent

Contact: Alice
Auto Delete = 5 minutes

Group: Work
Auto Delete = 1 hour
```

## Policy UI / values

Conceptually:

```text
Use Parent
Disabled
Enabled
```

When enabled:

```text
Delete after:
[ duration ]

Message types:
[ All ]
or selected types

Fallback if revoke is no longer available:
[ Do nothing ]   ← default
[ Delete local copy only ]  ← optional explicit choice
```

Never silently convert a failed Delete for Everyone into Delete for Me.

## Per-message override

Add an optional composer/send action:

```text
Auto delete this message
```

Possible values:

```text
Use chat policy
Do not auto delete
30 sec
1 min
5 min
15 min
1 h
Custom
```

This should not clutter the normal composer.

Use a long-press Send action, overflow action, or compact sheet depending on the current UI architecture.

## Native revoke-window capability

Do not hardcode the maximum revoke period.

Current WhatsApp behavior must be discovered through:

```text
target
version
capability resolver
validated compatibility metadata
```

At the time this prompt was written, WhatsApp documents a finite Delete-for-Everyone window rather than indefinite remote deletion.

Therefore:

```text
requested delay
> actual current native revoke window
```

must result in:

- clear warning;
- refusal to promise remote deletion;
- optional user-selected local-only fallback;
- no deceptive success state.

If WhatsApp changes this limit later, WA X should adapt through capability metadata rather than requiring a redesign.

## Durable scheduling

The auto-delete queue must survive:

- WA X manager process death;
- WhatsApp process death;
- device reboot;
- timezone changes;
- Doze;
- app updates where migration remains compatible.

Reuse the scheduler infrastructure where possible.

Recommended conceptual entity:

```text
PendingMessageRevocation
```

Fields:

```text
id
target
account
chatId
messageId
messageType
sentAt
deleteAt
policySource
attemptCount
lastAttempt
state
failureReason
```

Do not store message content merely to perform deletion.

## Trigger behavior

At due time:

```text
resolve target/account
→ confirm message identity
→ confirm current capability
→ confirm job has not already completed/cancelled
→ request native Delete for Everyone
→ verify result when possible
→ persist result
```

Use idempotency.

A retry must not cause duplicate or unrelated deletion.

## Manual deletion interaction

If the user manually deletes the message before the timer:

```text
cancel pending auto-delete job
```

If a scheduled message is cancelled before sending:

```text
do not create a deletion job
```

If a message send fails:

```text
do not schedule remote deletion
```

## Editing interaction

If a sent message is edited:

- preserve the original message identity when WhatsApp does;
- do not create duplicate deletion jobs;
- delete the final logical message at the originally configured deadline unless the user resets the timer explicitly.

If WhatsApp models edits differently in a future version, handle via capability resolver.

## Scheduler / Auto Reply / Templates integration

All outgoing paths should converge through a shared outgoing-message policy layer.

This includes:

```text
manual send
scheduled send
template send
auto reply
automation rule send
```

If a contact has:

```text
Auto Delete = 5 minutes
```

a scheduled message to that contact should follow the same rule unless the scheduled item explicitly overrides it.

## Failure states

Expose meaningful states:

```text
Pending
Deleted for everyone
Cancelled
Message already deleted
Revoke window expired
Target unavailable
Unsupported version
Message identity unavailable
Delete request failed
Unknown result
```

Do not display success unless it is actually known or strongly validated.

## Remote-deletion limitations

The feature must clearly communicate that Delete for Everyone cannot guarantee that content was never seen or independently saved.

For media in particular, recipients may have already viewed, downloaded, exported, photographed, or otherwise retained content before revocation.

Do not describe this feature as secure erasure of another person's device.

## Privacy

The deletion scheduler should persist only identifiers and scheduling metadata required to perform the operation.

Do not store:

- message body;
- media bytes;
- contact phone number in plaintext if a stable internal ID is sufficient.

## Acceptance criteria

- custom duration works;
- 5-minute use case works;
- shorter and longer valid durations work;
- Global policy works;
- per-target policy works;
- per-account policy works where supported;
- per-contact policy works;
- per-group policy works;
- List policy works where supported;
- per-message override works;
- text messages work;
- eligible media works;
- voice messages work;
- documents work where native revoke supports them;
- timer survives reboot;
- manual deletion cancels the job;
- failed sends do not create jobs;
- retries are idempotent;
- expired native revoke windows never report false success;
- WhatsApp and Business queues never cross.

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

- **featureId:** `F010`
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
