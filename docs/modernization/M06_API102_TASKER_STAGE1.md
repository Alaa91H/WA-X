# M06 Batch 1 (5/5) — Tasker automation bridge

Final Batch 1 migration. Source- and CI-verified; on-device confirmation is
`PENDING_USER_DEVICE_TEST`.

## What was ported

Legacy `Tasker` hooks the inbound receipt method and forwards a received
message to Tasker on a package-targeted broadcast carrying the user's auth
token, plus a reverse receiver that lets Tasker ask WA X to send a message.

`ModernTaskerFeature` ports the forward direction:

- **Hook target from repo-derived evidence**, exactly the chain the legacy
  resolver uses: the `receipt` string, a `ProtocolTreeNode` return type
  (located via `ProtocolTreeNode/getAttributeJid`), and a `jid.DeviceJid`
  parameter. Ambiguity or absence disables only this adapter.
- **Opt-in and inert by default**: needs the Manager's `tasker` switch plus a
  non-blank `tasker_auth_token`, so a fresh install sends nothing. The relay
  now forwards both keys into RemotePreferences, which is what makes the
  Manager's existing Settings row work for the modern runtime.
- **Authenticated relay**: every broadcast is package-targeted at
  `net.dinglisch.android.taskerm` and carries the user's token.
  `isAuthorized` refuses blank or mismatching tokens.
- **No invented members**: the number and message are taken from hook
  arguments only when both are unambiguous strings. When they are not, nothing
  is sent — guessing which argument is the message would push the wrong
  content into a third-party app.

## Deliberate gap, stated plainly

The **reverse** direction (Tasker → WA X sends a message) is **not** wired.
It needs the send pipeline (`ActionUser`, `FMessage`, userJid construction),
which is still legacy-only. Rather than register a receiver that accepts a
send command and silently drops it, the adapter reports
`SEND_DIRECTION_PENDING` and the Control Center shows it as *Partial: part
still pending migration* — a state the model treats as still switchable for
the forward direction, and says so in plain words.

## Verification

Pure tests pin the preference keys to `res/xml/preference_general_home.xml`,
the resolver anchors to the legacy `Unobfuscator`, the broadcast contract,
token authorization, number normalization from both broadcast shapes, and the
outcome set including the honest pending state. The Control Center model test
covers the new `PARTIAL` state. Broadcast delivery needs Tasker installed
(`PENDING_USER_DEVICE_TEST`). Full CI must pass before merging.

With this, Batch 1 closes at 5 verified features: ContactItemListener,
ConversationItemListener, MenuStatusProvider, ActivityController, Tasker —
plus the Control Center surface that gives the migrated ones real in-WhatsApp
controls.