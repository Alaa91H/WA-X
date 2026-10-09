# M06 Batch 2 — TypingPrivacy (first migrated consumer)

The first feature ported **onto** the new accessor layers rather than beside
them. Source- and CI-verified; on-device confirmation is
`PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `TypingPrivacy` hooks WhatsApp's composing-state broadcast, reads the
state type and the recipient JID, and suppresses the state when the user asked
for typing or recording privacy — globally or per contact.

`ModernTypingPrivacyFeature` keeps that behaviour and replaces every legacy
dependency:

- **Hook target from repo evidence**: the method using
  `HandleMeComposing/sendComposing`, requiring the observed three-parameter
  shape whose third parameter is an `int` — the same guard the legacy resolver
  applies. Zero/several matches or a different signature disable only this
  feature (`RESOLVER_MISSING`, `RESOLVER_AMBIGUOUS`, `UNSAFE_SIGNATURE`).
- **Number through `ModernJidAccess`** instead of `FMessageWpp.UserJid`: the
  recipient is located by `jidClass.isInstance`, so no argument position is
  assumed, and the phone number comes from the signature-resolved raw string
  with the legacy derivation rules.
- **Suppression decision is pure and tested**: `shouldSuppress(state, typing,
  recording)` pins the legacy semantics, including that recording is governed
  by its own rule and that unrelated state values are never suppressed.

## Per-contact privacy without moving the address book

The legacy feature reads per-contact rules from the module's private
preferences. Those live in the Manager now, and RemotePreferences are
read-only inside WhatsApp, so the rules are fetched **per contact** through a
new UID-authenticated provider method `read-target-privacy-v1`:

- the request names one number — the one the hook is already inspecting;
- the answer is exactly two booleans, derived from that contact's stored JSON;
- the number is validated as digits only and nothing is persisted on this
  side;
- a cache answers repeat lookups immediately, and a miss answers with the
  global-only rule while the Manager is asked on a background thread, so a
  WhatsApp hook thread never blocks on IPC.

Bulk-syncing every contact number into RemotePreferences would have moved the
whole address book into the injected process for no benefit; this keeps the
transferred data to the minimum needed for the decision being made.

## Settings and controls

The relay now forwards the three global switches (`ghostmode`, `ghostmode_t`,
`ghostmode_r`) that the adapter reads, and the contract checker asserts each
one individually so the list cannot rot silently. The Control Center shows
"Hide Typing" as a real Privacy-category toggle, and the write allowlist
accepts exactly those three keys.

## Verification

Eight pure tests cover the anchor parity with the legacy resolver, the state
constants, every suppression branch, the preference-key parity, the outcome
set and the rule-cache reset. Real suppression needs WhatsApp
(`PENDING_USER_DEVICE_TEST`).