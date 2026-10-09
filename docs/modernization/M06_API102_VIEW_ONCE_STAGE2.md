# M06 Batch 2 — ViewOnce

Source- and CI-verified; on-device confirmation is `PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `ViewOnce` keeps a **viewed** view-once message visible by rewriting
the caller's view-state argument from 1 to 0 when the message did not come
from this account.

`ModernViewOnceFeature` reproduces the legacy four-step resolver chain —
the method using `INSERT_VIEW_ONCE_SQL`, the two-method interface it invokes,
the classes implementing that interface, and their single-`int`/void methods —
and keeps the same evidence. Two guards are added where the legacy resolver
would silently accept a wrong target:

- **every** hook target must take a primitive `int` as its only argument and
  return `void`, because the hook rewrites exactly that argument. This is a
  pure predicate, `isRewritableStateMethod`, so it is unit-tested against a
  class with a right method, a wrong return type, a wrong parameter type and
  a no-argument method.
- the rewrite happens **only when the message key actually resolves**. The
  legacy code read `fMessage.key.isFromMe` unconditionally; an unreadable key
  here leaves the message untouched instead of guessing who sent it.

The rewrite itself uses the official `chain.proceed(args)` API, because API
102 exposes no mutable `param.args` array.

## Wiring

The entry now resolves the **message** accessor chain alongside the contact
one, hoists both, and reports them (`MESSAGE_ACCESS`, reused by every
message consumer) before installing ViewOnce. The `viewonce` switch is
relayed, added to the write allowlist, asserted by the contract checker, and
shown in the Control Center's Privacy category.

## Verification

Five pure tests cover the anchor parity, the rewritable-signature predicate,
the rewrite decision (viewed + incoming rewrites; own messages and unviewed
states do not), and the outcome set. Rewriting a real message state needs
WhatsApp (`PENDING_USER_DEVICE_TEST`).