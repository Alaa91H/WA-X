# M06 Batch 2 (1/5) — ContextMenuActionProvider

First Batch 2 migration: the message-selection popup bus that contextual
actions attach to. Source- and CI-verified; on-device confirmation is
`PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `ContextMenuActionProvider` hooks the constructors of WhatsApp's
message-selection popup, restructures its reactions tray into a horizontal
reactions row plus a scrollable action row, and lets registered providers
contribute action pills.

`ModernContextMenuActionProviderFeature` keeps that shape:

- **Resolver from repo-derived evidence**: the class using
  `MessageSelectionDropDownRecyclerView` with a `PopupWindow` superclass, and
  exactly one match required (`RESOLVER_MISSING` / `RESOLVER_AMBIGUOUS`).
  Every constructor is hooked under registry ownership, matching the legacy
  `hookAllConstructors`.
- **Layout restructuring without duplication**: the tray's existing children
  move into a horizontal row and a scrollable action row is appended. The
  action row carries a tag, so a rebuilt popup reuses it instead of nesting a
  second wrapper — the legacy version had no such guard.
- **Framework widgets only.** The legacy pill is a `MaterialButton` built from
  the module's resources through an AndroidX view helper. The modern runtime
  module deliberately carries no AndroidX dependency, so pills are plain
  framework `TextView`s. Adding AndroidX to the injected runtime to obtain a
  rounded outline style would be the larger risk, so it is not done.

## Deliberate boundaries

- Providers receive the **raw message object**. `FMessageWpp` and its JID
  chain need their own resolver evidence and are re-derived by the consumer
  migrations (CopySelectionMessage, CaptureDevice, Others).
- Tray lookup uses the resource *name* (`reactions_tray_layout`) resolved from
  the target's own resources, never a hardcoded id.
- Always-on infrastructure with no user toggle: no in-WhatsApp control and no
  preference key; evidence event `CONTEXT_MENU_ACTION_PROVIDER`. It is a
  no-op while no provider is registered.

## Verification

Pure tests pin the popup anchor and tray resource to the legacy resolver,
cover the outcome set, and check the action defaults. Popup rendering and the
provider registrations need WhatsApp (`PENDING_USER_DEVICE_TEST`). Full CI
must pass before merging.