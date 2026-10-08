# WA X Manager — Unified UI/UX Package (Approved)

**Approval date:** 2026-10-08  
**Decision:** APPROVED DESIGN / PENDING IMPLEMENTATION AND QUALITY GATES.  
**Package identifier:** `UIX-01` — **ONE coherent Manager UI/UX delivery package**, not separate feature releases.  
**Source of truth for pre-approved Features screen:** [APPROVED_FEATURES_SCREEN_SPEC.md](APPROVED_FEATURES_SCREEN_SPEC.md).  
**Architecture / order:** #318 M00→M13 blocker; #333 architectural dependency ordering; #344 A10 (data/UDF before new shell); #345 A11 (Compose shell), then implement and release **UIX-01** only when prerequisites are green.  
**Related owners:** #370 (Features screen), #260 F213 (WhatsApp-side customization), #69 F060 (distraction-free), #369 (Status AdBlock audit). Feature owners retain behavior; UIX-01 owns Manager surface, grouping, preview and interaction design.

## 1. Mission and binding guardrails

Deliver **one visually consistent, modern, low-distraction WA X Manager** with four mobile destinations: **Home / Features / Customization / Tools** and **App Settings through the top-right settings affordance** (accessible from the main shell; not a fifth bottom destination). Do not remove or silently change existing features or stored user preferences. This specification and the companion feature-screen spec are approved designs, **not evidence that code or APK is finished**.

The package has one shared design system, one typed settings source of truth, one navigation shell, one end-to-end migration test plan and **one package-level integration/release gate**. Individual milestones/commits/PRs are permitted for review and CI if staging is necessary; users must not receive a nominally complete release with disconnected half-migrated screens. Preserve working `main` after every merge; do not bypass #318/#333 strict ordered architecture gates.

**Out of scope:** changing WhatsApp internal visual surfaces beyond the separately-owned F213/F060 hooks; designing a separate Xposed runtime UI; reimplementing feature behavior; upgrading libxposed as part of this UI package (M06 owns API 102); implying unavailable planned features are working.

## 2. Unified information architecture

| Primary surface | Purpose | Entry |
|---|---|---|
| Home | Real target/runtime status, most-used shortcuts, recovery entry and targeted notices | Bottom nav |
| Features | All feature settings, search, categories, favorites, effective vs desired state | Bottom nav — detailed in companion approved spec and #370 |
| Customization | WhatsApp appearance, UI sections/tabs, theme, chat bubbles, toolbar, Status/Channels, optional advanced styles | Bottom nav |
| Tools | Diagnostics, compatibility, backup/restore, updates, logs, safe mode/recovery | Bottom nav |
| App Settings | WA X Manager language, own UI theme, motion, notifications/update checks, local log privacy, advanced, About/licenses | Header gear (not bottom nav) |

Both WhatsApp and WhatsApp Business must have **clearly isolated** target selection wherever settings affect the injected target. App Settings are global to Manager unless a setting is explicitly target-specific. Never conflate Manager appearance with native WhatsApp customization.

## 3. Common visual system

- Material 3 / responsive Compose, minimal typographic hierarchy, compact section titles, white/light and charcoal/dark/AMOLED surfaces, restrained corner rounding, consistent line icons.
- Dynamic/adaptive theme support where appropriate, with readable contrast and stable layouts under Arabic RTL, English LTR, large fonts, accessibility and light/dark modes.
- Every editable boolean feature uses the **same animated pill switch**: thumb physically **right + subtly illuminated green** when ON and **left + neutral gray** when OFF, no aggressive neon; 160–220ms or Android standard motion, reduced-motion obeyed, a11y semantics and at least 48dp touch area. Explicit ON/OFF state and safe disabled/unsupported treatment.
- Full-row tap navigates to details when applicable; pressing a switch changes only the switch and never opens the detail twice. Destructive actions are not switch toggles.
- Single source for colors, typography, spacing, shape, icon weight, motion and component states; reuse across all four surfaces.
- Show actual **desired preference** separately from **effective runtime/compatibility state**. Never fake success, activation, availability or a new release.

## 4. Customization — approved enhanced interactive preview

### Hierarchy
1. Page heading, short subtitle, and **WhatsApp / WhatsApp Business segmented target selector**.
2. **Live miniature WhatsApp appearance preview** that actually reflects preview changes: target, chosen accent color, light/dark appearance, optional chat bubble colors, toolbar style, bottom navigation visibility, and Updates sections.
3. Clean quick-theme swatches / presets, with `Use system colors`, `Custom`, `Restore default` treated as separate explicit actions. Preview only before applying; visible `Apply` and `Cancel`/reset buttons for complex theme edits so changes are not accidentally persisted while experimenting.
4. Grouped areas:
   - **Navigation & sections:** independent Hide Channels Section, Hide Communities, Hide Status Section, Hide Calls Tab; legacy Hide Updates Tab represented separately if needed (not conflated with Hide Status).
   - **Updates & Status:** Hide Channel Recommendations and Hide Sponsored Status Ads (pending #369 audit/compatibility; disabled/informational unless implemented and supported), Status presentation, IGStatus/old style compatibility.
   - **Chat appearance:** bubble background/colors, font/spacing options where supported.
   - **Home & toolbars:** menu toolbar, floating bottom bar, radius, artwork/wallpaper and translucency.
   - **Advanced appearance:** custom CSS, filters, DPI and developer-specific controls behind **Advanced** with clear compatibility warning.
5. Optional collapsible subsections avoid one endless full settings list. One row → one clear action or toggle.

### Preview must behave honestly
- A preview simulation is not the real WhatsApp rendering engine: mark `Illustrative preview`, avoid "pixel-perfect" and show **requires confirmation/restart** labels where appropriate.
- When `Hide Channels` is ON, Channels section disappears **while contact Status remains**. When `Hide Status` is ON, contact Status section disappears **without automatically hiding Channels**. If both disappear, show an intentional empty Updates state rather than a fake error.
- Hiding Communities/Calls changes only the miniature nav bar; phone and community memberships/notifications remain conceptually intact.
- Do not let mutually incompatible options visually misrepresent the target: resolved effective values/unsupported variants and Stock Mode (#102) precedence apply.
- Preview selected color/theme/chat bubbles/toolbar changes live without modifying persisted settings until explicit Apply for staged theme changes. Ordinary independent section switches may save immediately with correct state/undo semantics, not forced staged transactions.
- Handle 16 combinations of the four section toggles, both targets, native/floating bar, RTL/LTR, large fonts, empty view, expanded Advanced and app restart.

### Existing code to migrate, NOT delete
- `app/src/main/res/xml/fragment_customization.xml`: `hidetabs`, `channels`, `removechannel_rec`, `igstatus`, `status_style`, `oldstatus`, `floating_bottom_bar`, `changecolor`, bubble/theme keys, `css_theme`, `change_dpi`.
- `HideTabs.kt` and `Channels.kt` are old hook behavior; **do not replace with another Manager-side hook**. #260/#69 own their eventual compatibility/refactor.
- Map current `hidetabs` values (Updates 300, Communities 600, Calls 400, Tools 700) into explicit UI semantics without losing previous selections. Updates entire tab ≠ Status section; keep a distinct legacy option until migration is proven.
- Backups, account/target scopes and override precedence retained.

## 5. Tools — approved focused diagnostics center

- First section: a **real** runtime/target health summary (e.g. verified connected, not connected, checking, degraded, or unsupported), derived from existing diagnostic contracts, with separate target badges where relevant. Do not show mock GREEN states in production.
- A small, accessible **2-column tool grid** on compact screens, adaptive for tablets: **Diagnostics; Compatibility; Backup & Restore; Updates; Sanitized Logs; Safe Mode & Recovery**. The tile shows icon, title, one-line explanation and actual status only if evidence exists.
- Selecting a tile opens its focused details; no scrolling wall of raw stack traces by default. Provide an optional `Advanced technical details` expansion.
- Backup includes preview/validation and confirmation before destructive restore; logs are redacted, not automatically uploaded; real update checks distinguish available/downloaded/verified; recovery includes a clear reversal and warnings.
- Existing code/services such as `UpdateChecker.kt`, `BackupV3.kt`, `ConfigBackupSchema.kt`, `RootDiagnostics.kt`, `ResolverDiagnostics.kt` should be *reused*, not replaced with demo implementations.
- Accessibility and error states: permission denied, unsupported service, no network, corrupted backup, failure details, cancellable long-running actions, never false 'completed' labels.

## 6. App Settings — approved compact settings page

Entry via header gear; independent of WhatsApp visual customization.

- **Appearance & language:** Manager-only color mode System/Light/Dark/AMOLED, localization and accessibility/motion preferences.
- **Updates & notifications:** auto-check policy and how WA X itself notifies, not WhatsApp feature alerts.
- **Diagnostics privacy:** local crash log retention, redaction, export; no hidden background upload.
- **Advanced:** developer settings/technical toggles behind explicit advanced group.
- **About:** installed app version obtained from build at runtime, license/credits, repository and community.
- Keep UI quick to scan: grouped single-line rows, brief contextual descriptions, links to subpages; only settings with a true binary action have a switch. Choice sets use radio/select; irreversible operations require confirmation.
- Preserve saved Manager settings and never display mock version, active target or checked statuses as production truth.

## 7. Internal workstream order — **ONE UIX-01 package**

After prerequisites and A10/A11 foundations:
1. **UIX-01.0 Inventory and reference:** enumerate every existing preference XML key, custom activity, menu, feature registry item, diagnostic/backup/update surface. Create old→new IA/key/target mapping, identify orphans; establish screenshot goldens and backwards-compatibility baseline.
2. **UIX-01.1 Shared foundation:** Manager navigation shell, design tokens, reusable green/gray switch, category/filter/search/group controls, target selector, status indicators, scalable lists/animations/accessibility.
3. **UIX-01.2 Features screen:** implement #370/companion spec. Use real registry/state, target isolation, favorites and localized search.
4. **UIX-01.3 Customization screen:** enhanced live miniature preview, independent toggles, staged theme apply/reset, old preference migration, progressive Advanced organization, #260/#69 hooks referenced not duplicated.
5. **UIX-01.4 Tools screen:** real health/diagnostic/update/backup/recovery entry points, safe actions and source-backed status.
6. **UIX-01.5 App Settings:** Manager-only settings, language/appearance/motion/updates/log privacy/About. Header gear placement; no fifth nav destination.
7. **UIX-01.6 Integration & final release gate:** entire package tested and reviewed together, reversible, final changelog + release. No standalone "finished screen" release until integrated global gate.

The sequence is internal to UIX-01 (no parallel competing implementation). Separate commits/reviewable PRs are OK only if their intermediate merges keep `main` buildable and do not claim package-level completion.

## 8. Strict acceptance criteria (one combined gate)

- [ ] Every current feature and setting has a reachable destination (coverage report, no silent deletion) and existing preferences survive upgrade/rollback.
- [ ] Four bottom navigation destinations; App Settings gear accessible; deep links/predictive back/process recreation correct.
- [ ] Features (#370) full search/category/favorites, target state, true runtime compatibility behavior and safe independent switches.
- [ ] Customization preview accurately simulates toggles/colors/toolbar/status/channel layout changes; explicit target indicator; visually distinct preview vs real effect; advanced settings collapsed.
- [ ] Four independent section switches + separate channel recommendations and sponsored Status filter (only when truly supported) behave consistently with F060/F213 and Stock Mode.
- [ ] Tools use real source-backed diagnostics/compatibility/backup/update/recovery, with safe confirmation, redaction and no misleading green/complete states.
- [ ] App Settings are independent of WhatsApp target behavior; accessibility, language/motion/themes and logs privacy work correctly.
- [ ] 16× four-section combination matrix with both WhatsApp and WhatsApp Business; target/account isolation; unsupported/safe-mode/restart paths; native/floating navigation, Stock Mode, version-aware fallback.
- [ ] Golden/screenshot + UI navigation tests on Arabic RTL and English LTR, light/dark/AMOLED, small/large/font scaling/TalkBack/keyboard and motion-disabled.
- [ ] No Manager UI imports Xposed/DexKit; consistent architecture-law checks; restore existing crash/diagnostic behavior; no new lint/detekt debt; signed Release and rollback drills verified.
- [ ] Full package changelog in English with source/commit/test evidence; close package only after final gate, not merely after documents or design previews are added.

## 9. Deliverable references

- Approved Features screen reference: [APPROVED_FEATURES_SCREEN_SPEC.md](APPROVED_FEATURES_SCREEN_SPEC.md)
- Parent architecture modernization: [#333](https://github.com/Alaa91H/WA-X/issues/333)
- Manager ViewModel/UDF prerequisite: [#344](https://github.com/Alaa91H/WA-X/issues/344)
- Manager Compose shell: [#345](https://github.com/Alaa91H/WA-X/issues/345)
- Features screen execution: [#370](https://github.com/Alaa91H/WA-X/issues/370)
- WhatsApp appearance sections: [#260](https://github.com/Alaa91H/WA-X/issues/260)
- Selective hiding: [#69](https://github.com/Alaa91H/WA-X/issues/69)
- Status AdBlock audit: [#369](https://github.com/Alaa91H/WA-X/issues/369)
