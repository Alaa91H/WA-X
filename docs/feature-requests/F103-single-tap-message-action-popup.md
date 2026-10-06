# [P1][F103] SINGLE-TAP MESSAGE ACTION POPUP

## Objective

Implement an optional **Telegram-style single-tap message action popup** for WhatsApp conversations.

When enabled, a single tap on an eligible message bubble should open a compact contextual popup/sheet that exposes the user's selected message actions without requiring the normal long-press flow.

This feature must be configurable, must not duplicate unsupported actions, and must preserve native WhatsApp behavior when disabled.

## Requested behavior

When the user single-taps an eligible message, show a contextual popup containing configurable actions such as:

```text
Reactions bar
Forward
Copy Selection
Pin
Info
Delete Options
Save
Star
```

The action set must be capability-aware and message-type-aware.

Examples:

- **Save** should only appear for media/file types that can actually be saved.
- **Copy Selection** should appear only when meaningful for text/caption content.
- **Info** should follow native eligibility, such as messages for which WhatsApp exposes message info.
- **Pin** must respect current native pin capability and chat permissions.
- **Delete Options** must expose only the native delete choices currently available for that message.
- **Reactions bar** must use WhatsApp's native reaction capability/state rather than inventing a separate incompatible reaction model.

## User configuration

Add a WA X manager toggle:

```text
Single-tap message actions
[ Off / On ]
```

Default:

```text
Off
```

Also provide an action-selection/reordering screen where the user can choose which items are visible.

Suggested configurable actions:

```text
Reactions bar
Forward
Copy Selection
Pin
Info
Delete
Save
Star
```

Support:

- enable/disable each action;
- reorder actions where practical;
- restore defaults;
- message-type-aware visibility;
- target/account-aware configuration where appropriate.

Do not display actions that are unsupported by the installed WhatsApp version or by the selected message.

## Interaction model

Preferred flow:

```text
single tap message
→ determine message type/state
→ resolve configured actions
→ capability-filter actions
→ render compact popup/sheet
→ execute selected action through native WhatsApp path
```

The popup should feel lightweight and native-quality, similar in interaction speed to Telegram's contextual message popup, while following WA X/WhatsApp visual and accessibility constraints.

## Native behavior preservation

This feature must not break ordinary WhatsApp message interaction.

Special handling is required for message content where a normal single tap already has an important native meaning, including where applicable:

- opening an image/video;
- playing audio/voice notes;
- opening documents;
- opening links;
- expanding quoted/replied content;
- polls;
- locations;
- contacts;
- stickers/GIFs;
- interactive/business messages;
- view-once media;
- other version-specific interactive message types.

Implement deterministic conflict handling.

Recommended policy:

```text
If safe single-tap interception is supported for that message type:
    show WA X popup
Else:
    preserve native single-tap behavior
```

Optionally allow a user-selectable interaction mode if reliable:

```text
Single tap → popup
Single tap empty/bubble area → popup
Long press → native WhatsApp actions
```

Do not make important native content inaccessible.

## Reactions bar

Where supported:

- show currently available native reaction choices;
- apply reactions through WhatsApp's native reaction path;
- reflect current reaction state when safely resolvable;
- do not create a parallel reaction database;
- fail safely if the reaction resolver is unavailable.

## Forward

Use WhatsApp's native forward flow.

Preserve:

- current forwarding limits;
- recipient selection;
- native privacy/security behavior;
- any WA X Wrong-Recipient / Forwarding Guard policies.

Do not bypass server-side forwarding restrictions.

## Copy Selection

For eligible text/caption messages:

- allow selecting/copying part of the message text where technically feasible;
- preserve Unicode, RTL, emoji, links, and line breaks;
- do not silently copy hidden metadata;
- integrate with WA X clipboard privacy/auto-clear policy where configured.

If partial text selection cannot be implemented reliably for a version/message surface, fall back safely to native full-copy behavior or hide the action.

## Pin

Use native WhatsApp pinning capability only.

Respect:

- message eligibility;
- group/admin restrictions;
- supported pin durations/options;
- target/version capability.

Do not claim success when native pinning rejects the operation.

## Info

Open the native message-info surface where available.

Do not recreate delivery/read metadata independently when WhatsApp already provides the authoritative view.

## Delete Options

Expose only valid native options for the selected message, for example where applicable:

```text
Delete for me
Delete for everyone
Cancel
```

The actual available choices must come from current message state/capability.

Never convert a failed **Delete for Everyone** into **Delete for Me** silently.

Integrate with the Timed Auto Delete feature so manually deleted messages cancel relevant pending revocation jobs where applicable.

## Save

For eligible media/files:

- image;
- video;
- audio;
- voice note;
- document;
- GIF/other supported media;

allow saving through the existing/shared WA X media-save pipeline.

Requirements:

- scoped/user-selected destination where possible;
- no duplicate copy when the same destination/item is already safely known;
- preserve privacy settings;
- optional metadata sanitization where configured;
- handle unavailable/expired media safely.

Do not mutate WhatsApp message databases.

## Star

Use native WhatsApp starred-message behavior.

The popup state should reflect starred/unstarred state where reliable.

## Architecture

Create/reuse a shared contextual-action model rather than hard-coding each action into one view.

Conceptually:

```text
MessageAction
MessageActionRegistry
MessageActionCapabilityResolver
MessageActionPopupController
```

Each action should declare at least:

- stable action ID;
- title/icon;
- supported message types;
- target/version requirements;
- resolver/capability;
- execution path;
- visual priority;
- user-visible enable state;
- fallback behavior.

Avoid separate duplicated implementations for WhatsApp and WhatsApp Business unless their internals genuinely differ.

## Feature metadata

- **featureId:** `F103`
- **priority:** `P1`
- **category:** Messaging / UI / Productivity
- **targets:** WhatsApp + WhatsApp Business
- **account-aware:** Yes where target/account settings apply
- **risk:** High / UI-hook and resolver-sensitive
- **restart requirement:** Prefer none; declare actual behavior
- **kill switch:** Required
- **diagnostics:** Required

Suggested visual metadata:

```text
visualImpact = INJECTS_UI
stockModeCompatible = false
stockModeFallback = native WhatsApp message actions
```

## Stock WhatsApp / Zero-Visible-Modification Mode

Because this feature visibly changes message interaction and injects a popup, it must be **suppressed when Stock WhatsApp Mode is enabled**.

In Stock Mode:

- do not intercept native message taps for this feature;
- do not inject the custom popup;
- preserve official WhatsApp interaction exactly;
- retain the user's saved F103 settings so they return when Stock Mode is disabled.

## Accessibility and UX

The popup must support:

- light/dark theme;
- RTL;
- TalkBack/content descriptions;
- large text;
- adequate touch targets;
- keyboard/focus navigation where relevant;
- orientation/configuration changes;
- no clipping on small screens;
- correct positioning near screen edges.

The popup must not cover critical system/navigation areas.

## Compatibility and safety

Use:

```text
message selected
→ resolve message class/state
→ resolve current WhatsApp version/target
→ filter supported actions
→ render
→ native action execution
→ verify result where possible
```

A single broken action resolver must not break the entire popup.

If one action fails compatibility checks:

```text
hide/disable only that action
```

not the entire module.

Maintain a feature-level kill switch for the popup itself.

## Required tests

Add unit/integration/E2E coverage for at least:

### Toggle / configuration
- feature disabled → native single tap remains unchanged;
- feature enabled → popup appears on eligible message;
- per-action enable/disable;
- action ordering;
- restore defaults;
- settings persist across restart;
- WhatsApp/Business settings isolation.

### Message types
- text;
- image;
- video;
- voice note;
- audio;
- document;
- sticker;
- GIF;
- quoted/reply message;
- outgoing message;
- incoming message;
- group message;
- unsupported/interactive message fallback.

### Actions
- reactions bar;
- Forward;
- Copy Selection;
- Pin;
- Info;
- Delete Options;
- Save;
- Star.

### Native interaction conflicts
- media open behavior;
- voice/audio playback;
- links;
- documents;
- polls/interactive content where supported;
- view-once media;
- quoted-message navigation.

### Compatibility
- supported WhatsApp version;
- unsupported version;
- partial action resolver failure;
- full popup kill switch;
- WhatsApp target;
- WhatsApp Business target;
- account isolation;
- Safe Mode;
- Stock WhatsApp Mode suppression.

### UI
- light theme;
- dark theme;
- RTL;
- large font scale;
- TalkBack labels;
- screen-edge positioning;
- rotation/process recreation where applicable.

## Acceptance criteria

- [ ] A WA X toggle enables/disables the single-tap popup.
- [ ] Disabled mode leaves native WhatsApp behavior unchanged.
- [ ] User can choose which actions appear.
- [ ] Reactions bar is supported where native capability exists.
- [ ] Forward works through the native path.
- [ ] Copy Selection works for eligible text or fails safely.
- [ ] Pin respects native eligibility/permissions.
- [ ] Info opens native message information where supported.
- [ ] Delete shows only valid native delete choices.
- [ ] Save works only for supported media/file types.
- [ ] Star uses native starred-message behavior.
- [ ] Unsupported actions are hidden/disabled individually.
- [ ] Native single-tap interactions are not made inaccessible.
- [ ] WhatsApp and Business remain isolated.
- [ ] No resolver failure can crash the conversation UI.
- [ ] Stock WhatsApp Mode suppresses the injected popup completely.
- [ ] Settings return after Stock Mode is disabled.
- [ ] No internal WA X paywall/entitlement gate exists.
- [ ] Tests, lint, static analysis, and release build pass.

## Implementation note

Before adding hooks, audit current conversation/message-click handling and native contextual-action code. Prefer invoking/reusing WhatsApp's own action handlers and WA X's existing media/privacy infrastructure rather than reimplementing message operations.
