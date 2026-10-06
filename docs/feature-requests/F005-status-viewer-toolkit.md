# [P0][F005] STATUS VIEWER TOOLKIT

## Objective

Implement **STATUS VIEWER TOOLKIT** as an independent WA X feature/work item. This issue is intentionally self-contained for implementation, review, testing, compatibility tracking, and release readiness.

## Source requirements

Add optional controls:

```text
Pause
Seek
Rewind
Forward
Playback speed
Auto advance
Save
Repost
```

Support saving:

- images;
- video;
- voice/audio Status.

Use user-selected storage destination where possible.

Respect ownership and privacy.

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

- **featureId:** `F005`
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


## Status reply seen-receipt synchronization

Add an optional Status-specific receipt behavior requested by users:

> When the user replies/responds to another person's Status, WA X should send the corresponding native **seen/view receipt** for that exact Status item.

### Goal

A user may normally suppress Status view receipts for privacy, but still want the owner of a Status to receive a seen receipt when the user explicitly replies to that Status.

This must be implemented as a deliberate, user-controlled exception rather than silently changing global read-receipt/privacy behavior.

### Configuration

Add a target/account-aware option such as:

```text
Send Status seen receipt when replying
```

Recommended default:

```text
OFF
```

When enabled:

```text
Open Status
→ user writes/sends a reply
→ reply send succeeds
→ resolve the exact Status message/item
→ request WhatsApp's native Status seen/view receipt for that item
→ verify/persist result when possible
```

### Privacy and policy interaction

- This option affects only the specific Status item being replied to.
- Do not bulk-mark previous/future Status items as seen.
- Do not change normal chat read receipts.
- Do not globally re-enable Status view receipts.
- If another WA X privacy policy suppresses Status view receipts, this explicit option acts as a scoped exception only for replied-to Status items.
- The UI must clearly explain that enabling this option reveals to the Status owner that the user viewed that Status.
- If the feature cannot safely resolve the exact Status item, do not send a receipt for a different item.

### Native-only behavior

Use the legitimate native WhatsApp Status-view/read-receipt path for the currently installed version.

Do not:
- fabricate arbitrary server receipts;
- mark unrelated Status items as seen;
- spoof receipts when the native capability is unavailable;
- report success unless the native request was made successfully or strongly validated.

### Trigger and idempotency

Prefer triggering only after the reply has a stable successful-send result.

The operation must be idempotent:

```text
same target + account + statusId
→ at most one effective seen-receipt action
```

Repeated taps, retries, process recreation, or duplicate reply callbacks must not produce unrelated or duplicated receipt behavior.

### Scope

Support independently where technically possible:

```text
WhatsApp
WhatsApp Business
Account
```

Do not cross target/account boundaries.

### Failure behavior

Possible diagnostic states:

```text
Disabled
Pending
Receipt sent
Already seen
Reply failed — no forced receipt
Status identity unavailable
Unsupported WhatsApp version
Native receipt capability unavailable
Receipt request failed
Unknown result
```

Failures must not block or crash normal Status reply behavior.

### Stock WhatsApp Mode

This behavior is background-only and may remain active in Stock WhatsApp Mode because it does not require injected WhatsApp UI.

Configuration must remain in the standalone WA X manager.

Suggested metadata:

```text
visualImpact = NONE
stockModeCompatible = true
stockModeFallback = manager-side configuration
```

### Additional tests

Add explicit coverage for:

- option OFF → replying does not force a Status seen receipt;
- option ON → successful reply triggers native seen receipt for the exact replied-to Status;
- failed reply does not create an unrelated forced receipt;
- duplicate reply callbacks are idempotent;
- exact Status identity routing;
- WhatsApp target;
- WhatsApp Business target;
- account isolation;
- existing global Status-view privacy behavior remains unchanged for non-replied Status items;
- conflict with hidden Status-view policy is resolved only for the replied-to item;
- unsupported version fails safely;
- resolver failure never crashes Status viewer/reply;
- Stock WhatsApp Mode keeps the behavior functional without visible WA X UI.

### Acceptance criteria

- [ ] A dedicated toggle exists in WA X manager.
- [ ] Default state is non-invasive.
- [ ] Enabling it sends the native seen/view receipt when replying to a Status.
- [ ] Only the exact replied-to Status item is affected.
- [ ] Normal chat read receipts are untouched.
- [ ] Non-replied Status privacy behavior is untouched.
- [ ] WhatsApp and Business remain isolated.
- [ ] Duplicate callbacks are idempotent.
- [ ] Unsupported versions fail safely.
- [ ] No false success state is shown.

