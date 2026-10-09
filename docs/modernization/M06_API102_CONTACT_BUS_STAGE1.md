# M06 Batch 1 — ContactItemListener modern bus (W1 core infra)

First W1 migration: the contact-bind fan-out bus other features subscribe
to. Source- and CI-verified; on-device confirmation is
`PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `ContactItemListener.doHook()` resolves three targets through
`Unobfuscator` and fans out bound (contact, view) pairs to registered
`contactListeners` (sole consumer today: ShowOnline, W3).

The modern `ModernContactItemListenerFeature` resolves the same targets
from repo-derived anchors — `ConversationViewFiller/setParentGroupProfilePhoto`
(status-change method), `"not recyclable"` (view-holder class) — plus the
holder field on the method's superclass and the `View` field inside it.
Guards mirror the established pilot pattern: unique match required at each
step (`RESOLVER_MISSING` / `RESOLVER_AMBIGUOUS`), method parameter count
must fall in the observed 6..8 range (`UNSAFE_SIGNATURE`), per-listener
exceptions are isolated so one consumer cannot break the bus.

## Deliberate boundaries

- The bus delivers the raw bound contact object plus its item `View`. JID
  interpretation stays with the ShowOnline migration: the legacy
  `WaContactWpp` JID chain needs its own resolver evidence and is not
  re-derived here. No obfuscated member name was guessed.
- Always-on infrastructure with no user toggle: no in-WhatsApp control and
  no Manager preference key. The entry installs it unconditionally (like the
  menu link) on the background bootstrap reporter thread; an empty bus
  returns early. Evidence event `CONTACT_ITEM_LISTENER` carries the fixed
  outcome.
- Naming follows the adapter convention (`Modern<Name>Feature`) so the
  derived migration ledger recognizes the wiring: 6 wired, 1 staged,
  57 legacy-only.

## Verification boundary

New pure tests pin the anchors to the `Unobfuscator` evidence, the
parameter range to the legacy fallback refinement, and the outcome set.
Hook execution needs the target process and is `PENDING_USER_DEVICE_TEST`.
Full CI must pass before merging.
