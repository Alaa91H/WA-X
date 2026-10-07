# [P0][F080] SENSITIVE DATA LEAK PREVENTION

## Objective

Implement **SENSITIVE DATA LEAK PREVENTION** as an independent WA X feature/work item. This issue is intentionally self-contained for implementation, review, testing, compatibility tracking, and release readiness.

## Source requirements

Implement a local **pre-send privacy scanner** that warns the user when outgoing content appears to contain highly sensitive information.

## Local-only detection

Detect, where practical:

```text
One-time passwords / verification codes
Passwords or password-like phrases
API keys
Access tokens
Private keys
Recovery codes
Credit-card numbers with Luhn validation
IBAN / bank-account patterns
Crypto seed phrase patterns
Government-ID-like patterns only when confidence is high
Email + password combinations
Wi-Fi credentials
Sensitive clipboard contents
```

## Behavior

Before send:

```text
potential sensitive content detected
→ show concise warning
→ highlight category, not necessarily full secret
→ Send Anyway / Edit / Cancel
```

Do not upload content for classification.

Do not persist detected secrets.

Do not log detected values.

## Per-chat configuration

Modes:

```text
Off
Warn
Strict confirmation
```

Scopes:

```text
Global
Target
Account
Contact
Group
List
```

## Media / image extension

Optional P2 extension:

- local OCR;
- detect visible OTPs, cards, IDs, QR codes;
- warn before sending;
- allow manual blur/redaction.

No cloud OCR without explicit opt-in.

## False positives

Always allow user override.

Never permanently block a legitimate send based only on heuristic detection.

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

- **featureId:** `F080`
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
