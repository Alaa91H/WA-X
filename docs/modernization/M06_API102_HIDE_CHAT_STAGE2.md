# M06 Batch 2 — HideChat (archived-chat hiding)

Source- and CI-verified; on-device confirmation is `PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `HideChat` swaps WhatsApp's archive chat view for one that is always
`GONE` and ignores every `setVisibility` call, which hides archived chats
without touching the archive itself.

`ModernHideChatFeature` keeps that behaviour exactly:

- **Targets from the repository's own resolver evidence, in the same order the
  legacy resolver tries them**: the class using
  `archive/set-content-indicator-to-empty`, falling back to
  `archive/Unsupported mode in ArchivePreviewView:`. A unique match is
  required; ambiguity reports `RESOLVER_AMBIGUOUS` rather than hooking a class
  that may not be the archive view.
- The single `View`-typed field is replaced after construction, exactly as the
  legacy hook did.
- Every constructor is hooked under registry ownership with an
  `archive.<n>` hook id, so a rollback removes all of them.

## A three-state control, not a fake switch

The Manager exposes archived-chat hiding as a **list** preference
(`typearchive`: disabled / hide after click count / hide while holding the
title), not a boolean. Rendering it as a switch would have shown a wrong state
and written a value the feature does not read, so:

- the Control Center row is a button that **cycles the three modes** and shows
  the current one, localized in EN and AR;
- the mode travels under its own `mode.` prefix on the read channel, because
  the boolean loop would coerce it to "off";
- the write path validates the mode against the three known values and
  refuses anything else;
- the relay forwards `typearchive` as a **string**, and the contract checker
  asserts that specific write.

## Verification

Four pure tests pin both anchors and the preference key to the legacy
resolver and `res/xml/fragment_privacy.xml`, cover the enabled/disabled
decision, and cover the outcome set; a provider test pins the mode constants
and the string-vs-boolean read. Hiding the archive view needs WhatsApp
(`PENDING_USER_DEVICE_TEST`).