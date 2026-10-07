# WA X — Complete Customization Expansion Plan

> Audit date: 2026-10-07
>
> Goal: evolve WA X from a strong theme/CSS engine into a complete, version-aware, safe visual customization platform covering every practical local UI surface without duplicating native WhatsApp features or bypassing paid/native entitlements.

## 1. Audit result

WA X already has a substantial customization foundation. The current repository contains:

- `CustomView` / CSS-based view customization;
- global color replacement;
- primary/background/text colors;
- home wallpaper plus toolbar/navigation transparency;
- tab hiding;
- custom filters/selectors;
- custom DPI;
- theme manager;
- home-list animations;
- conversation options including floating context UI and emoji animation;
- incoming/outgoing bubble colors;
- floating bottom bar and radius;
- home-screen UI mode controls;
- multiple Status presentation options;
- `.waetheme` data-only theme packages;
- local theme gallery;
- global and per-chat theme assignment;
- Material You support;
- AMOLED mode;
- typography scale/weight/line spacing/message scale;
- theme shape, spacing and bubble-style controls;
- safe theme validation.

This means the next phase must **extend the existing engines** rather than introduce a second theming system.

## 2. Current native WhatsApp baseline

Current WhatsApp itself already supports chat themes, wallpapers and chat bubble colors globally and per-chat, including multiple built-in colors and AI-created wallpapers/themes in supported regions. WA X should coexist with these native settings and expose an effective-value model instead of blindly overriding them.

In 2026 Meta also introduced paid WhatsApp Plus customization such as exclusive themes and icons. WA X must **not unlock, clone or redistribute paid Meta assets/entitlements**. Any WA X icon/theme system must use WA X-owned, open-licensed, generated, or user-provided assets only.

## 3. Architecture rule

Every customization must resolve through one pipeline:

```text
Stock Mode
→ target/version capability
→ scope inheritance
→ native WhatsApp state
→ WA X semantic design tokens
→ surface/component override
→ accessibility validation
→ compiled visual rule
→ safe apply
→ visual/structural verification
→ rollback/fallback
```

No feature should directly scatter arbitrary resource replacements around the codebase when a shared token/surface/resolver path can own the behavior.

## 4. New customization feature candidates

### A. Foundation and theme architecture

| ID | Priority | Feature | Purpose |
|---|---|---|---|
| F189 | P0 | Unified Visual Design Token Engine | Replace ad-hoc color/size overrides with semantic tokens for colors, typography, shapes, spacing, icons, motion and elevation. |
| F190 | P0 | Visual Surface Registry & Capability Map | Register every customizable WhatsApp/Business surface/component with version-aware support state. |
| F191 | P0 | Scoped Appearance Inheritance | Extend appearance scope to Global → Target → Account → Contact / Group / List / Channel while preserving current per-chat themes. |
| F192 | P1 | Effective Appearance Inspector | Show inherited, native, overridden and effective visual values for any target/account/chat/surface. |
| F193 | P0 | Visual Theme Studio / No-Code Builder | Full manager-side visual theme builder without requiring CSS editing. |
| F194 | P0 | Live Preview, Staged Apply & Instant Revert | Preview changes before applying, stage them transactionally, and revert immediately on failure. |
| F195 | P1 | Conditional Theme Scheduler | Switch profiles by system light/dark mode, time window, battery saver, target/account or user-defined profile rules. |
| F196 | P1 | Theme Package Schema v2 | Expand safe data-only theme packages with semantic tokens, scopes, compatibility metadata and optional asset references. |
| F197 | P0 | Secure Theme Asset Bundle | Safely package user/open-licensed wallpapers, icons and fonts with size/type/path validation and no executable content. |
| F198 | P0 | Theme Compatibility Migration Engine | Migrate themes when WhatsApp resources/surfaces change instead of silently breaking or resetting them. |

### B. Color, typography, shape and assets

| ID | Priority | Feature | Purpose |
|---|---|---|---|
| F199 | P0 | Semantic Color Role System | Expand from a few colors to background/surface/container/accent/error/success/links/selection/badges/ticks/status/calls/etc. |
| F200 | P1 | Advanced Material You / Dynamic Color | Derive complete accessible palettes from Android dynamic color while allowing controlled overrides. |
| F201 | P1 | AMOLED & Contrast Optimizer | True-black policy plus automatic contrast correction for text/icons/ticks on customized surfaces. |
| F202 | P0 | Advanced Typography Studio | Per-surface family, size, weight, width, line height, letter spacing, message scale and fallback policy. |
| F203 | P1 | Custom Font Importer | User-provided local fonts with safe validation, licensing reminder, fallback, script coverage and per-surface assignment. |
| F204 | P0 | Density & Spacing Studio | Independent row height, padding, gutter, margin and density controls instead of only global DPI/base spacing. |
| F205 | P0 | Shape & Corner System | Per-component radius/shape for bubbles, cards, avatars, buttons, inputs, sheets, tabs, media and menus. |
| F206 | P1 | Icon Pack Engine | Data-only icon mapping for supported UI actions using WA X/open/user assets; never unlock Meta paid icon packs. |
| F207 | P1 | Motion & Animation Studio | Per-surface transitions, list/message/reaction animation policy, duration/scale and reduce-motion compatibility. |
| F208 | P2 | Haptic Feedback Studio | Configure supported local haptic feedback for actions without interfering with system accessibility or call behavior. |

### C. Background and home/navigation surfaces

| ID | Priority | Feature | Purpose |
|---|---|---|---|
| F209 | P1 | Wallpaper Effects Studio | Blur, dim, tint, gradient overlay, crop, fit/fill, parallax-off and per-surface wallpaper policy. |
| F210 | P0 | Home / Chat List Layout Builder | Reorder/size visible chat-list regions while preserving native functionality and Stock Mode. |
| F211 | P0 | Chat Row Designer | Avatar size, name/message/date placement, line count, preview visibility, separators, padding and row density. |
| F212 | P1 | Avatar / Presence / Badge Designer | Avatar shape/size/border, online indicator, unread/mute/pin/archive markers and accessibility-safe colors. |
| F213 | P0 | Navigation / Tabs / Bottom Bar Builder | Order, visibility, labels, icons, bar height, radius, selected state and adaptive navigation mode. |
| F214 | P0 | Toolbar / App Bar Designer | Height, title/subtitle, avatar, action order, colors, transparency, elevation and edge-to-edge behavior. |
| F215 | P1 | FAB / Quick Action Designer | Position, size, icon, shape, visibility and safe action assignment for supported floating actions. |
| F216 | P1 | Chat Folder / Filter Visual Designer | Visual style for native/WA X chat filters, folders, selected states, counters and compact modes. |
| F217 | P1 | Unread / Counter / Badge Designer | Badge shape, font, color, count presentation and high-contrast behavior. |
| F218 | P1 | Swipe Gesture Appearance & Action Visualizer | Customize swipe affordances, icons, color and preview while keeping native actions/capabilities authoritative. |

### D. Conversation and composer

| ID | Priority | Feature | Purpose |
|---|---|---|---|
| F219 | P0 | Conversation Layout Builder | Control conversation paddings, max content width, alignment, edge-to-edge and component spacing. |
| F220 | P0 | Message Bubble Studio | Incoming/outgoing shape, tails, radius, fill/gradient, border, width, padding and grouping styles. |
| F221 | P0 | Message Metadata / Ticks / Time Designer | Timestamp, edited mark, ticks, delivery/read state, pin/star/forward markers and alignment. |
| F222 | P1 | Reply / Quote / Reactions Designer | Quote bar, reaction chips, reply preview, colors, shape, spacing and overflow behavior. |
| F223 | P1 | Date Separator / System Message Designer | Date chips, encryption notices, group events and system-message cards. |
| F224 | P1 | Media Card Designer | Image/video/GIF frame, radius, caption region, duration/size badges, overlays and progress visuals. |
| F225 | P1 | Voice Note UI Designer | Waveform/progress/speed/transcription region, play controls, colors, compact/expanded layouts. |
| F226 | P1 | Document / Contact / Location / Poll Card Designer | Surface-specific cards while preserving native action semantics. |
| F227 | P0 | Composer / Input Bar Builder | Input shape/height, reply bar, text field, send/mic/camera/attachment placement and adaptive behavior. |
| F228 | P1 | Attachment / Camera / Emoji Action Strip Builder | Choose supported action order/visibility with native capability filtering. |
| F229 | P1 | Emoji / Sticker / GIF Panel Appearance | Panel height, grid density, tabs, search/header appearance and theme tokens without replacing native content sources. |
| F230 | P1 | Fine-Grained Per-Chat Visual Overrides | Per-component visual overrides for selected chats/groups beyond assigning one whole theme. |

### E. Other WhatsApp surfaces

| ID | Priority | Feature | Purpose |
|---|---|---|---|
| F231 | P1 | Contact / Group / Profile Screen Designer | Header, avatar, action row, section cards, spacing and colors. |
| F232 | P1 | Updates / Status Screen Designer | Status/channel sections, cards, spacing, header and list/grid presentation. |
| F233 | P1 | Status Viewer Designer | Progress bar, reply region, controls, overlays and safe accessibility options. |
| F234 | P1 | Channels Screen Designer | Channel cards, header, counters, list density and followed-channel presentation only. |
| F235 | P1 | Calls List Designer | Call rows, call-type indicators, missed-call styling, spacing and filters. |
| F236 | P1 | In-Call UI Designer | Supported local colors/layout/controls with strong capability checks; never hide critical call/safety controls. |
| F237 | P1 | Settings Screen Designer | Preference cards, sections, icons, density, navigation and typography. |
| F238 | P1 | Search / Archive / Linked Devices Screen Designer | Consistent theming for secondary surfaces normally missed by simple color hooks. |
| F239 | P1 | Dialog / Bottom Sheet / Menu Designer | Semantic styling for dialogs, sheets, popups, menus and contextual actions. |
| F240 | P1 | Notification Visual Profiles | Customize supported Android notification presentation/icon/accent/redaction within OS limits. |

### F. Identity, adaptive layouts and accessibility

| ID | Priority | Feature | Purpose |
|---|---|---|---|
| F241 | P1 | App / Launcher Identity Customization | User/WA X-owned shortcut/icon/name variants where Android allows; never unlock or copy WhatsApp Plus paid assets. |
| F242 | P1 | One-Handed / Reachability Layout Mode | Move safe controls into reachable zones and increase touch targets without hiding critical controls. |
| F243 | P1 | Compact / Comfortable / Dense Layout Profiles | Consistent density presets across chat list, messages, menus, status and settings. |
| F244 | P0 | Large-Screen / Foldable Adaptive Layouts | Window-size/posture-aware layouts, hinge avoidance, list-detail modes and navigation rail support where practical. |
| F245 | P1 | Orientation-Specific Layout Profiles | Separate portrait/landscape component arrangement with safe fallback. |
| F246 | P1 | RTL / Bidirectional Visual Controls | Strong Arabic/RTL mirroring, mixed-script alignment and per-component bidi testing. |
| F247 | P0 | High-Contrast / Color-Blind Theme Assistant | Contrast analysis, color-blind-safe palette checks and automatic corrective suggestions. |
| F248 | P1 | Reduced-Motion / Low-Stimulation Visual Profile | Disable/reduce nonessential animations, flashing and visual noise across customized surfaces. |

### G. WA X manager and advanced tooling

| ID | Priority | Feature | Purpose |
|---|---|---|---|
| F249 | P1 | WA X Manager Theme Studio | Customize the manager app independently from hooked WhatsApp UI. |
| F250 | P0 | CSS Engine v2 | Add variables/tokens, conditions, pseudo-states and bounded media/screen/night/RTL/version rules with strict sandboxing. |
| F251 | P0 | Visual Inspector & Selector Recorder | Developer/user tool to inspect a view, record stable selectors and show resolver/version support without guessing IDs manually. |
| F252 | P0 | Theme Performance Compiler & Cache | Precompile/cache effective rules to avoid per-frame reflection or repeated selector parsing. |
| F253 | P1 | Theme Preset / Reset / Clone Tools | Reset by surface, clone target/account/chat appearance, compare presets and restore defaults safely. |
| F254 | P1 | Theme Gallery v2 | Extend the existing local gallery with safe previews and optional curated/signed data-only remote distribution; no executable themes. |

## 5. Extensions to existing work instead of new duplicate features

These should extend current features/engines rather than become parallel systems:

| Target | Required extension |
|---|---|
| Existing `ThemeRepository` / per-chat themes | Preserve current assignments and migrate them into F191 inheritance without breaking existing users. |
| Existing `ThemePackages` | Add schema-v2 migration, asset manifest support, strict path/size/type checks and backwards compatibility. |
| Existing `CustomView` / CSS | Make F250 backwards-compatible with current selectors/properties; old themes must continue to load. |
| F074 Profile Composition | Allow a profile to include a visual theme/profile reference and appearance scope. |
| F072 Tick Color Accessibility | Route tick/read-state styling through F221 semantic metadata tokens. |
| F073 Accessibility Profiles | Integrate F247/F248 and typography/touch-target presets rather than duplicating accessibility switches. |
| F093 Stock WhatsApp Mode | Hard-disable all non-stock visual customization paths while preserving saved settings and restoring them when Stock Mode is off. |
| F145 E2E WA X Settings Sync | Sync theme metadata/assets only when explicitly selected, encrypted and deduplicated. |
| F154 Capability Scanner | Scan visual surfaces/selectors and report unsupported customization areas per WhatsApp/Business version. |
| F159 WhatsApp Update Compatibility Diff | Show which visual surfaces/selectors stopped matching after an update. |
| F160 Signed Resolver-Metadata Hotfix Packs | Permit signed data-only selector/surface metadata updates, never executable theme code. |
| F163 Settings Change History & Rollback | Include theme/component diffs and appearance rollback. |
| F164 Feature Dependency / Conflict Resolver | Define precedence among Stock Mode, accessibility, native themes, WA X profiles, CSS and per-chat overrides. |
| F181 Command Palette | Add quick theme/profile switch, preview, reset and effective-value inspection commands. |
| F183 Profile Share / Import | Include referenced themes/assets after validation and preview; exclude private identifiers. |
| F146/F147 Backup system | Include selected theme packages/assets in incremental backup with deduplication. |

## 6. Exact precedence model

Recommended precedence:

```text
1. Safety / accessibility hard requirements
2. Stock WhatsApp Mode
3. Unsupported-version safe disable
4. Native WhatsApp critical/safety controls
5. Per-chat/group/list explicit visual override
6. Account appearance profile
7. Target appearance profile
8. Global WA X appearance profile
9. Native WhatsApp theme values
10. System Material You / system defaults
```

Stock Mode must suppress WA X visual modifications without deleting the saved theme.

## 7. Surfaces that must be covered before calling customization “complete”

The visual coverage matrix must include at least:

```text
Home / Chats
Navigation / Tabs / Bottom bar
Chat filters / folders
Conversation
Composer
Text messages
Reply / quoted messages
Reactions
Images / video / GIF
Voice notes / audio
Documents
Contacts
Locations
Polls
System messages
Date separators
Search
Archive
Starred messages
Contact info
Group info
Community surfaces
Updates / Status
Status viewer
Channels
Calls list
In-call UI
Settings
Privacy settings
Storage
Linked devices
Dialogs
Bottom sheets
Context menus
Toasts/snackbars where controllable
Notifications where Android permits
WA X manager
Landscape
Large screen
Foldable
RTL
Dark/light/AMOLED
Accessibility modes
```

## 8. Items that WA X should NOT pretend to customize

- keyboard UI owned by the user's keyboard app;
- Android system notification shade layout beyond supported notification APIs;
- lock-screen/system UI owned by Android;
- server-rendered behavior that has no local controllable view;
- paid/exclusive Meta/WhatsApp Plus themes, icons or assets unless the user is legitimately entitled and WhatsApp itself exposes them;
- arbitrary executable code inside themes;
- hidden modifications while Stock Mode is enabled.

## 9. Quality gates

Every customization feature must pass:

```text
WhatsApp + WhatsApp Business
target/account isolation
supported/unsupported target versions
resolver/selector failure
Stock Mode
Safe Mode
light/dark
AMOLED
RTL
large text
TalkBack
high contrast
color-blind checks
orientation
tablet/foldable where applicable
process restart
theme import/export
rollback
no sensitive logging
no theme code execution
performance budget
visual regression comparison
release build
```

## 10. Performance rules

- Parse and compile theme/CSS rules once, not during every frame.
- Cache selector matches by surface/version where safe.
- Invalidate only affected surfaces/tokens.
- Avoid global recursive view traversal on every layout pass.
- Put hard limits on imported asset count/size and image dimensions.
- Decode wallpapers/assets asynchronously and with bounded memory.
- Record per-feature customization latency through F158 profiler.
- Auto-disable a repeatedly crashing visual rule through F157/F254 recovery path.

## 11. Implementation milestones

### C0 — Architecture
F189–F198

### C1 — Tokens and assets
F199–F209

### C2 — Home/navigation
F210–F218

### C3 — Conversation/composer
F219–F230

### C4 — Remaining WhatsApp surfaces
F231–F240

### C5 — Adaptive/accessibility
F241–F248

### C6 — Manager/developer tooling
F249–F254

### C7 — Integration hardening
Apply every extension listed in section 5, then run the full visual coverage matrix.

## 12. Definition of “complete customization”

Customization is complete only when:

- every supported surface is registered in the visual surface registry;
- users can customize it from a no-code UI without needing CSS;
- advanced users can still use CSS v2;
- every value has a clear inheritance/effective-value explanation;
- native WhatsApp themes remain compatible;
- Stock Mode restores exact official presentation;
- unsupported WhatsApp versions disable only broken surfaces;
- accessibility rules can override unsafe visual choices;
- imported themes are data-only and sandboxed;
- theme changes are previewable and reversible;
- large screens, foldables, RTL and orientation changes do not break layouts;
- theme performance has measurable budgets;
- visual regression tests exist for all major surfaces.

## 13. Research notes

Official WhatsApp currently supports global/per-chat chat themes, wallpapers, chat bubble colors and AI-generated themes/wallpapers in supported regions:
https://faq.whatsapp.com/6298875896807790/

WhatsApp introduced colorful chat themes and per-chat customization:
https://blog.whatsapp.com/chat-themes-to-reflect-your-style

Meta's 2026 WhatsApp Plus offering includes paid exclusive themes/icons. WA X must respect those entitlements rather than unlock/copy them:
https://about.fb.com/news/2026/09/introducing-meta-one-subscription-service-more-features-ai/

Android Material 3 dynamic color/accessibility guidance:
https://developer.android.com/develop/ui/views/theming/dynamic-colors
https://developer.android.com/develop/ui/compose/designsystems/material3

Android 17 adaptive/resizable UI direction and foldable guidance:
https://developer.android.com/blog/posts/prepare-your-app-for-the-resizability-and-orientation-changes-in-android-17
https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware

## Final recommendation

Yes: WA X needs a dedicated **Complete Customization Program** if the goal is truly “customize everything.”

The existing customization engine is a strong foundation, but today it is still a mixture of global theme data, CSS/selectors and feature-specific hooks. The biggest architectural upgrade is not “more color pickers”; it is creating one semantic token + surface registry + scope inheritance + preview/rollback system and then migrating every visual feature onto it.

Do C0 first. Without F189–F198, adding dozens of per-screen toggles will make compatibility maintenance much harder after every WhatsApp update.
