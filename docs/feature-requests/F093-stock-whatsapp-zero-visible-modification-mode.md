# [P0][F093] STOCK WHATSAPP / ZERO-VISIBLE-MODIFICATION MODE

## Objective

Implement **STOCK WHATSAPP / ZERO-VISIBLE-MODIFICATION MODE** as an independent WA X feature/work item. This issue is intentionally self-contained for implementation, review, testing, compatibility tracking, and release readiness.

## Source requirements

Implement a first-class **Stock WhatsApp Mode** whose purpose is to make the hooked WhatsApp application look visually and structurally indistinguishable from the unmodified official client while keeping compatible WA X behavior policies active in the background.

This is a core requirement.

## Visual contract

When enabled, the user must see the same native:

```text
Tabs
Menus
Overflow menus
Settings pages
Toolbar actions
Chat list
Conversation screen
Contact profile
Group profile
Calls screen
Updates / Status screen
Composer controls
Dialogs
Bottom sheets
Icons
Labels
Option ordering
Animations
Spacing
Colors
Search behavior
Navigation
```

as the installed official WhatsApp build.

WA X must not visibly advertise itself inside WhatsApp.

## Suppress all WA X visual modifications

Stock Mode must suppress:

```text
Injected menu items
Injected toolbar buttons
Injected preference rows
Injected tabs
Custom Status buttons
WA X badges
Custom banners
WA X-branded toasts
Inline diagnostics
Custom chat decorations
Custom icons
Custom colors
Custom layouts
Hidden native tabs/items
Reordered native options
Changed native labels
Custom bottom bars
Custom toolbars
Custom bubbles
Custom navigation
WA X Mini Control Center entry
```

Do not delete the user's saved customization settings.

They are merely suppressed while Stock Mode is active.

When Stock Mode is disabled, the user's previous visual customization preferences may become active again.

## Preserve invisible functionality

Background-only features may remain active when they do not alter WhatsApp's visible UI, including:

```text
Per-contact privacy
Per-group privacy
Per-List privacy
Presence policies
Read-receipt policies
Typing/recording privacy
Notification redaction
Notification cooldown
Media auto-download policy
EXIF cleaning
Tracking-link cleaning
Unknown-sender firewall
Timed Delete for Everyone
Automatic View Once defaults
Focus schedules
Call policies
Scheduler
Automation
Version Guardian
Security monitors
```

Every feature must explicitly declare whether it is compatible with Stock Mode.

## Preserve custom privacy per contact and group

This is mandatory.

The user must retain full custom privacy control for:

```text
Contacts
Groups
Lists
Targets
Accounts
```

but those controls must be managed from the **WA X manager app**, not by injecting a WA X row/button/menu into WhatsApp.

Provide manager-side flows such as:

```text
WA X
→ Features
→ Privacy
→ Custom Privacy
→ Select Contact / Group
```

The manager must show:

```text
Target
Account
Chat type
Contact/group
Privacy profile
Individual overrides
Inherited values
Effective values
```

Do not require a persistent WhatsApp-side privacy menu.

## Contact/group selection

Prefer:

- manager-side indexed selector;
- stable internal chat/contact identifiers;
- search;
- groups/contacts filters;
- optional native chooser only when required.

A temporary chooser is acceptable if it leaves no permanent UI modification.

## Visible-control fallback policy

Any feature that normally injects WhatsApp UI must define a Stock Mode fallback.

### Manual Call Recording

Normal mode:

```text
In-call Start / Pause / Stop controls
```

Stock Mode:

```text
Use predefined recording policy
or external Quick Settings / WA X notification action
```

No injected call-screen control.

### Status Audio Studio

Normal mode may expose:

```text
Choose Audio
```

inside the Status composer.

Stock Mode fallback:

```text
Launch Status Audio Studio from WA X
Android share sheet
launcher shortcut
```

Do not modify WhatsApp's Status composer.

### Per-message privacy override

Normal mode may expose a contextual control.

Stock Mode must instead use:

```text
chat/contact/group policy
```

or an external WA X next-send policy.

Do not inject a composer button.

## Native controls must remain native

If WhatsApp already displays:

```text
View Once `1`
Chat Lock
Native privacy rows
Native call settings
```

WA X may respect/read their state but must not visually replace, restyle, reorder, or duplicate them in Stock Mode.

## Feature visual-impact metadata

Extend feature metadata with:

```text
visualImpact:
  NONE
  EXTERNAL_ONLY
  MODIFIES_NATIVE_STATE
  INJECTS_UI
  HIDES_NATIVE_UI
  REORDERS_NATIVE_UI
  RESTYLES_NATIVE_UI

stockModeCompatible: true/false
stockModeFallback: ...
```

Default Stock Mode behavior:

```text
NONE
EXTERNAL_ONLY
MODIFIES_NATIVE_STATE
```

may remain active if the visible WhatsApp UI stays stock.

Suppress:

```text
INJECTS_UI
HIDES_NATIVE_UI
REORDERS_NATIVE_UI
RESTYLES_NATIVE_UI
```

unless proven visually identical to native behavior.

## Conflict precedence

Stock Mode has the highest **presentation** precedence.

Example:

```text
Distraction-Free Mode = ON
Custom Toolbar = ON
Hide Channels = ON
Stock Mode = ON
```

Effective visible result:

```text
Official stock WhatsApp UI
```

The underlying preferences remain stored.

## No visual WA X traces

No normal screen inside WhatsApp may reveal:

```text
WA X
WA Enhancer
Module
LSPosed
Xposed
custom privacy menu entry
diagnostics entry
module status badge
```

Diagnostics remain in WA X manager.

## Error handling

Under Stock Mode, avoid WA X-branded in-WhatsApp toasts during successful operation.

For recoverable failures:

- record sanitized diagnostics;
- show status inside WA X;
- optionally use a normal Android notification outside WhatsApp if user action is needed.

## Visual parity tests

Add regression tests against an unmodified reference for the same WhatsApp version.

Cover at minimum:

```text
Chat list
Conversation
Contact info
Group info
Calls
Updates / Status
Settings
Privacy settings
Media composer
Overflow menus
Search
```

Use:

- screenshot/visual diff where stable;
- view-tree/menu structure checks where screenshot tests are brittle.

Ignore dynamic message/contact content while verifying WA X did not add, remove, reorder, or restyle UI.

## Version behavior

Stock Mode means:

```text
match the currently installed official WhatsApp version
```

not one frozen historical design.

The safest implementation is generally to avoid visual hooks entirely while the mode is enabled.

## Acceptance

- no WA X menu item;
- no WA X toolbar item;
- no WA X settings row;
- no WA X tab;
- no hidden native option;
- no reordered native option;
- no WA X branding;
- no WA X custom colors/layouts;
- contact profile unchanged;
- group profile unchanged;
- Settings unchanged;
- Calls unchanged;
- Updates/Status unchanged;
- per-contact privacy still works from WA X;
- per-group privacy still works from WA X;
- WhatsApp and Business can enable Stock Mode independently;
- disabling Stock Mode restores saved visual customizations.

## Implementation contract

Before implementing this issue:

1. Inspect the current repository and identify any existing or partial implementation.
2. Reuse and extend existing infrastructure instead of creating duplicate systems.
3. Preserve every current WA X feature and native WhatsApp behavior not explicitly changed by this issue.
4. Keep WhatsApp and WhatsApp Business resolver paths, state, settings, queues, and failures isolated.
5. Preserve target-aware and account-aware behavior wherever the feature can vary by target/account.
6. Prefer the shared WA X settings hierarchy where applicable:
   `Global → Target → Account → Contact / Group / List`.
7. Never hard-code fragile WhatsApp internals, protocol limits, or version-specific values when a capability resolver/metadata path is appropriate.
8. Any resolver/hook failure must fail safely and must never crash WhatsApp.
9. High-risk behavior must have compatibility checks, diagnostics, fallback behavior, and a feature-level kill switch.
10. Reuse shared scheduler, outgoing-policy, privacy, media, storage, audit, and automation foundations where applicable instead of implementing parallel copies.

## Feature metadata required

Add/update feature metadata for:

- **featureId:** `F093`
- **priority:** `P0`
- category
- target support
- account support
- required resolver(s)
- supported version range
- restart requirement
- risk level
- fallback behavior
- kill switch
- tests
- diagnostics
- `visualImpact`
- `stockModeCompatible`
- `stockModeFallback`

## Compatibility and Stock WhatsApp Mode

- The implementation must follow `resolver → capability check → exact path → fallback → safe disable`.
- WhatsApp and WhatsApp Business must be validated independently.
- Unsupported versions must produce an explicit unsupported/disabled state instead of a crash.
- When **Stock WhatsApp / Zero-Visible-Modification Mode** is enabled, no WA X-specific UI may remain inside WhatsApp unless it is proven visually identical to stock behavior.
- Any feature that normally injects WhatsApp UI must provide an external/manager-side fallback when possible.
- Saved visual preferences must be suppressed rather than destroyed by Stock Mode.

## Privacy and storage requirements

- Store only the minimum data required for the feature.
- Do not log message bodies, secrets, private media paths, tokens, or unnecessary personal identifiers.
- Use Room for durable structured entities, settings storage for configuration, and scoped file/SAF storage for media where appropriate.
- Prefer Android Photo Picker / SAF / app-private storage over broad storage permissions.
- Request permissions only when the feature actually needs them.

## WA X access contract

This is a WA X-owned feature and must be available without an internal payment gate.

- No Premium / Pro / Donor / Supporter feature tier.
- No donation-to-unlock behavior.
- No remote entitlement server controlling availability.
- Technical/version/root/hardware/LAB safety restrictions are allowed when genuine.
- WhatsApp / Meta / third-party paid entitlements must remain respected and must not be bypassed.

## Required tests

At minimum add coverage for:

- WhatsApp target;
- WhatsApp Business target;
- target isolation;
- account routing/isolation where applicable;
- supported version path;
- unsupported version path;
- resolver failure/fallback;
- feature kill switch;
- Safe Mode behavior;
- process/app restart where state is durable;
- no settings cross-leak;
- no sensitive logging;
- dark/light/RTL review for any UI;
- Stock Mode behavior according to `visualImpact`;
- release build compatibility.

Also implement every feature-specific test implied or explicitly listed in **Source requirements** above.

## Definition of done

- [ ] Existing/partial implementation was audited before adding new code.
- [ ] Feature works on every declared target.
- [ ] Target/account state cannot cross-leak.
- [ ] Compatibility metadata is updated.
- [ ] Required resolver(s) and capability checks are implemented.
- [ ] Safe fallback and feature-level kill switch exist where needed.
- [ ] Diagnostics are sanitized and actionable.
- [ ] Feature-specific tests are complete.
- [ ] Unsupported versions fail safely.
- [ ] Safe Mode is verified.
- [ ] Stock WhatsApp Mode contract is verified.
- [ ] No internal WA X paywall/entitlement gate exists.
- [ ] No sensitive data is logged unnecessarily.
- [ ] Translations and accessibility are reviewed for any user-facing UI.
- [ ] Lint/static analysis/tests/release build pass.
- [ ] Known limitations are documented before closing.

## Completion report

When closing this issue, report:

- files changed;
- resolver(s) used;
- target support;
- account support;
- compatibility/version coverage;
- tests added;
- known limitations;
- storage/performance impact where relevant.
