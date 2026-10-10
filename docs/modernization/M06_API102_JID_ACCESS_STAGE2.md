# M06 — JID accessor layer (Batch 2 infrastructure)

Source- and CI-verified; on-device confirmation is `PENDING_USER_DEVICE_TEST`.

## Why

Every privacy rule in WA X ultimately needs "which number is this JID?".
The legacy answer is `FMessageWpp.UserJid`, which calls
`XposedHelpers.callMethod(jid, "getRawString")` — a **literal member name**.
Copying that into the modern runtime would repeat exactly the guess this
migration exists to remove.

`ModernJidAccess` resolves the raw-string reader **by signature**: a public,
non-static, no-argument method returning `String` on the already-resolved JID
class. Zero candidates → `RAW_STRING_METHOD_MISSING`; more than one →
`RAW_STRING_METHOD_AMBIGUOUS`. Ambiguity is reported, never resolved by
picking one, because reading the wrong string would attribute a contact to the
wrong number.

## Derived rules, pinned by tests

`JidRules` reproduces the legacy derivation exactly and is covered branch by
branch, because this is the logic that silently produces a wrong number:

1. strip a device suffix (`.12:34@` → `@`);
2. if a `.` occurs before the `@`, take the part before the dot (this wins
   over the known-domain case, matching the legacy order);
3. otherwise, for `@g.us`, `@s.whatsapp.net`, `@broadcast` and `@lid`, take
   the part before the `@`;
4. otherwise return the raw string;
5. null/empty never becomes a number.

Group JIDs (`…@g.us`) therefore yield the group subject id rather than a
phone number, exactly as before — a number is never invented for a group.

## Wiring and state

The entry resolves the contact chain, then the JID accessor from its
`jidClass`, and reports the outcome as `JID_ACCESS`. The Control Center lists
it under Advanced as always-on infrastructure (no preference key, not
switchable), so a user can see whether the layer that privacy rules depend on
actually resolved.

## Verification

Nine pure tests cover each branch of the derivation, the device-suffix strip,
group/broadcast/LID handling, the unknown-domain fallback, the null cases, the
invalid-JID test and the ambiguity outcome. Reading a real JID needs WhatsApp
(`PENDING_USER_DEVICE_TEST`).

## Contact resolver cardinality hardening (2026-10-10)

`ModernContactAccess.resolve` now accepts a DexKit class or method match only
when exactly one candidate exists. It reports distinct `*_AMBIGUOUS` outcomes
for contact, contact-data, JID, phone-JID method/field, and user-JID field
matches. Reflection field lookup follows the same rule; zero matches retain
the existing missing outcome. This removes the previous `firstOrNull()` choice
from the contact/JID access chain and keeps ambiguous layouts unavailable
rather than binding to an arbitrary class or field.

Pure JVM tests cover zero, one, and multiple candidate selection, and assert
that each ambiguity outcome is part of the reported result vocabulary. These
tests establish fail-closed selection only. Candidate counts and structural
signatures on WhatsApp/Business, actual resolver success, and behavioral
effects remain `NOT_TESTED` on the changed build; the installed E0592465 APK
predates this source change.
