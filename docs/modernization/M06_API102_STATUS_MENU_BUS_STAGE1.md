# M06 Batch 1 — MenuStatusProvider modern bus (W1 core infra)

Third W1 migration: the status-playback menu bus three registered features
subscribe to (StatusDownload, SeenTick, DeleteStatus). Source- and
CI-verified; on-device confirmation is `PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `MenuStatusProvider.doHook()` resolves the status menu method, the
menu manager class, the two playback fragment classes, the status list
field and the current-index field, then offers every registered provider a
menu slot.

`ModernMenuStatusProviderFeature` keeps that resolver chain using only
repo-derived anchors:

- menu method — the method using the `menuitem_conversations_message_contact`
  resource ID, read from the **target's own resources** at runtime, never a
  hardcoded number;
- menu manager class — declaring class of the void method using
  `"MenuPopupHelper cannot be used without an anchor"`;
- playback fragments — proven `StatusPlaybackBaseFragment` /
  `StatusPlaybackContactFragment` name suffixes (the legacy resolver already
  relies on these);
- status list — the `List` field on the contact fragment;
- current index — best-effort int field from the
  `"playbackFragment/setPageActive no-messages "` anchor, same two-step
  evidence as the legacy resolver, degrading to 0.

Guards: `RESOURCE_ID_MISSING` when the target lacks the resource,
`RESOLVER_MISSING` when a step finds nothing. Provider add/click exceptions
are isolated so one consumer cannot break the bus.

## Deliberate boundaries

- `StatusData` exposes the raw status list plus the playback fragment;
  `StatusItemWpp` interpretation stays with each consumer migration.
- Always-on infrastructure with no user toggle: no in-WhatsApp control and
  no Manager preference key. Evidence event `MENU_STATUS_PROVIDER`.
- 8 wired, 1 staged, 55 legacy-only in the derived ledger.

## Verification boundary

New pure tests pin every anchor and resource name to the legacy
`Unobfuscator` evidence, assert no obfuscated member name is hardcoded, and
cover the outcome set. Hook execution needs the target process
(`PENDING_USER_DEVICE_TEST`). Full CI must pass before merging.