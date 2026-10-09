# M06 Batch 1 — ConversationItemListener modern bus (W1 core infra)

Second W1 migration: the message-row bus six registered features subscribe
to (TagMessage, HideSeenView, AntiRevoke, CaptureDevice, Others,
ShowEditMessage, plus the chat-UI translator). Source- and CI-verified;
on-device confirmation is `PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `ConversationItemListener.doHook()` hooks the framework
`ListView.setAdapter`, captures the conversation adapter, reflectively
hooks its `getView(int, View, ViewGroup)`, and fans out bound message rows.
The modern `ModernConversationItemListenerFeature` keeps that shape:

- Outer hook on the stable platform `ListView.setAdapter` — no DexKit, no
  guessed names — under registry ownership `conversation.set_adapter`.
- Inner row hook resolved reflectively per bound adapter and re-hooked on
  every adapter change, mirroring the legacy lifecycle (including unhook on
  activity destroy). Per-adapter and short-lived by design; owned by the
  bus, not the registry, and documented as such.
- `WeakHashMap` binding store replaces the legacy `XposedHelpers`
  additional instance fields with an explicit structure (`boundMessage()`
  accessor included for consumers).
- Conversation detection ported from the legacy runtime: exact
  `com.whatsapp.Conversation` activity name via lifecycle callbacks, plus
  the tablet+home fallback reusing the verified `ModernMenuHomePolicy`
  home name. Per-listener exceptions isolated.

## Deliberate boundaries

- The bus delivers raw message objects. Message-ID/row-ID/JID
  interpretation stays with each consumer migration; nothing obfuscated
  was re-derived here.
- Always-on infrastructure with no user toggle: no in-WhatsApp control,
  no Manager preference key. Evidence event `CONVERSATION_ITEM_LISTENER`.
- Naming follows the adapter convention so the derived ledger recognizes
  the wiring: 7 wired, 1 staged, 56 legacy-only.

## Verification boundary

New pure tests pin the conversation class evidence, null-safety of the
conversation check, adapter-unwrap behavior, and the outcome set. Hook
execution needs the target process (`PENDING_USER_DEVICE_TEST`). Full CI
must pass before merging.
