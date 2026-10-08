# WA X Manager — Approved Features Screen Design

**Status:** DESIGN APPROVED (2026-10-08) · **Implementation status:** NOT YET VERIFIED / NOT CLAIMED.
**Owner:** Manager UI modernization, A10 (#344) → A11 (#345). **Related:** F213 (#260), F060 (#69), Status AdBlock (#369), Architecture Epic #333, LSPosed blocker #318.

## Design decision
The user explicitly approved the interactive WA X **Features** screen design shown in the 2026-10-08 conversation. Treat the structure, visual intent and interaction behavior below as the **approved reference** when implementing Manager UI. The chat prototype was a visual/interactivity demo only; its mock values are NOT production activation or compatibility claims.

### Visual language
- Modern, quiet, clean Material 3 / Material You, with WA X's own identity.
- Restrained rounded surfaces and grouped rows; tight but comfortable spacing; lightweight consistent outline icons and readable Arabic/English typography.
- Dark, light, AMOLED and dynamic-color-friendly surfaces; preserve strong contrast.
- **Switches**: smooth horizontal pill switch, thumb physically right and track **subtle illuminated green** = ON; thumb physically left and track **neutral grey** = OFF. No excessive neon/shadows. Approx. 160–220ms motion or platform-standard; respect reduced motion and system animator settings. Keep accessible state/semantics and 48dp minimum touch target. In RTL, maintain this explicit requested physical-position convention while correctly communicating state.
- Adaptive design for compact/large screens, font scaling, RTL/LTR, TalkBack, switch keyboard/assistive interaction.

### Global Manager navigation
The approved compact mobile shell has **four** primary destinations:
1. Home (runtime/target status, shortcuts)
2. Features (**this** screen)
3. Customization (appearance, tabs, sections and themes)
4. Tools (diagnostics, backup, updates, import/export)
Use an adaptive alternative on tablets/foldables; keep existing deep links/back behavior and do not put runtime logic in UI. Shell migration is owned by A11 and is not part of WhatsApp in-app navigation customization F213.

## Features screen — approved hierarchy
1. **Header:** WA X name/identity and clear `Features` title plus a short human-readable subtitle, not technical log output.
2. **Target selector:** a single segmented control for **WhatsApp** vs **WhatsApp Business**. All rows, settings, effective values, favorite context when appropriate, and compatibility must reflect the selected target/account. Never silently cross-write state.
3. **Search:** prominent one-line input with immediate local filtering across feature name, localized title/description, synonyms and relevant English technical terms. Debounce expensive queries if needed; no network requirement. Show useful empty state.
4. **Category chips** (horizontally scrollable): All, Favorites, Privacy, Customization, Media, Tools. Additional categories may be data-driven if feature inventory justifies it; keep the top-level uncluttered.
5. **Grouped feature rows:** each row contains a consistent icon, title, one-line/two-line natural-language description, optional favorites star, and its **own independent animated switch** when the feature can be directly enabled. Group headings show item counts.
6. **Details on demand:** tapping the feature name/row opens a detail sheet or subpage with prerequisites, explanation, compatibility, effective setting, limitations, warnings (only when necessary), and advanced controls. Do not open complex controls for routine enabling. Handle tap targets so toggling does not accidentally open details or toggle twice.
7. **Bottom navigation:** Home / Features / Customization / Tools, with one clearly selected destination.

### State and behavior
- UI must distinguish **desired setting** (user intent) from **effective runtime state** (actual supported and injected behavior). The green switch must not falsely imply confirmed working injection; where state is unknown/unsupported, expose an explicit status and avoid claiming activation. Provide loading, disabled, error and requires-restart variants.
- Default rows should never display fake connection, hook, or compatible states. Read actual target/runtime diagnostics through existing contracts.
- Safe, reversible writes via typed settings and Manager state model; persistence across process death, restart, app updates, backup/restore; no destructive migration of legacy keys.
- Favorites are a lightweight local Manager convenience; they must not enable features implicitly. Empty Favorites screen has a helpful affordance.
- Search/filter/category/scroll/target selection should restore across configuration and process recreation where practical; respect per-target/account isolation.
- Preserve all existing features, keys, classifications and settings, even if some remain under details/Advanced. No functional deletion or silent hiding under the redesign.
- Features not yet implemented—e.g., Status AdBlock (#369) pending validation—must not appear as fully functional active toggles in production. Show planned/unsupported state only if such an informational entry is explicitly offered.

### User interaction examples
- Choose WhatsApp Business → catalog and state update without touching WhatsApp preferences.
- Search for `status`, `حالات`, `إعلانات`, `adblock`, `blue ticks` → relevant existing features where searchable aliases exist.
- Tap category `Privacy` → see only the group of privacy features, with a count.
- Tap favorite star → bookmark without changing its ON/OFF state.
- Tap a real feature's switch → change its preference once; animated right+green/left+grey state; show requirements for restart where appropriate.
- Tap title → see advanced settings/details and real compatibility evidence.

## Architecture and ownership
- A10 (#344) owns Screen → ViewModel → UseCase → Repository → DataSource and UiState/UiAction/UiEffect; reusable fakeable state, no Hilt in injected runtime.
- A11 (#345) owns gradual Compose UI and Navigation 3 integration when stable/appropriate, shell, back navigation, accessibility and migration from old fragments.
- F213 (#260) owns **WhatsApp-side** tab/section customization, not the Manager's Features catalog. F060 (#69) owns selective UI hiding. #369 owns Status AdBlock audit/implementation; no competing hooks.
- Reuse the existing FeatureRegistry/FeatureMetadata/typed Settings catalog as the single source of feature truth. Do not create a second hard-coded list to replace it.
- Strict architecture rules: UI must not depend directly on Xposed/DexKit; target/process state must be properly isolated, and no forbidden architecture dependency edges may increase.
- Preserve the mandatory LSPosed sequence #318 before scheduling implementation of the new Manager shell per #333.

## Acceptance / quality gate
- [ ] A before/after screen inventory maps **every current feature and setting** to the new IA (information architecture); no orphaned or missing controls.
- [ ] Screen matches the approved visual hierarchy and style without unnecessary decorative clutter.
- [ ] Four Manager destinations work and preserve back/deep-link/state restoration.
- [ ] Localized live search, category filtering, Favorites and target switch work together; no unwanted cross-target changes.
- [ ] Each real supported toggle has correctly persisted desired/effective state; independent green-right/grey-left animation; restart and error messaging where necessary.
- [ ] Both WhatsApp and Business tested separately, with inactive/uninstalled/unsupported states covered.
- [ ] Existing feature values, user customizations and backup data are preserved, including migration and rollback tests.
- [ ] Screenshots/golden or screenshot regression on light/dark/AMOLED, Arabic RTL, English LTR, font scaling, TalkBack, compact/large/foldable.
- [ ] Performance checked with a full real catalog (lazy lists, stable keys, no whole-screen recomposition on every toggle, search responsive), plus no new lint/detekt debt.
- [ ] Unit/UI/instrumentation/architecture checks and signed release build pass; attach machine-verifiable evidence before closing implementation.
- [ ] Documentation and English changelog updated; do not mark implemented solely from this approved specification.

## Non-goals
This decision does not authorize rewriting the injected WhatsApp UI, adding more features, making unverified LSPosed activation claims, removing old settings, or bypassing the phase gates. The demo prototype is not production code.
