# [P0][F077] PRIVACY & SECURITY CENTER

## Objective

Implement **PRIVACY & SECURITY CENTER** as an independent WA X feature/work item. This issue is intentionally self-contained for implementation, review, testing, compatibility tracking, and release readiness.

## Source requirements

Create a unified **Privacy & Security Center** that audits, explains, and helps configure the strongest available privacy protections without duplicating native WhatsApp features unnecessarily.

## Goal

Give the user one place to understand:

```text
What is protected
What is exposed
What is using native WhatsApp protection
What WA X adds locally
What needs user action
What is unsupported on this version
```

## Native protection audit

Where the installed WhatsApp version exposes these capabilities, detect and surface the state of:

```text
Strict Account Settings
Protect IP Address in Calls
Disable Link Previews
Silence Unknown Callers
Block / limit high volumes of unknown messages
End-to-End Encrypted Backups
Chat Lock / Secret Code
Passkeys
Two-Step Verification / account password protection
Username / username-key privacy controls
Restricted Chat or equivalent native privacy controls
```

Do not reimplement native cryptographic/account-security features.

Prefer:

```text
detect
explain
deep-link to native setting
verify state when technically possible
```

## Privacy health score

Optionally provide a local score such as:

```text
Basic
Good
Strong
Maximum
Custom
```

The score must be transparent.

Show exactly which settings contribute.

Do not claim that a high score makes the user anonymous or unhackable.

## Suggested profiles

Provide optional presets:

```text
Balanced
Private
High Privacy
Maximum Local Privacy
Public-Facing / High-Risk User
Custom
```

A preset may combine:

- native WhatsApp settings;
- WA X notification privacy;
- media policy;
- unknown sender firewall;
- link preview policy;
- call IP protection;
- secure screen;
- clipboard protection;
- auto View Once;
- timed remote delete;
- EXIF cleaning.

Never silently change native account settings without explicit confirmation.

## Acceptance

- state is target-aware;
- account-aware where possible;
- native settings are not falsely reported;
- unsupported options are clearly labeled;
- every recommendation explains privacy benefit and trade-off.

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

- **featureId:** `F077`
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
