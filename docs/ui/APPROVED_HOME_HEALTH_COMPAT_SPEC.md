# WA X Manager — Home Health, Diagnostic Root Cause & Feature Compatibility UI

**Design extension approved/requested:** 2026-10-08  
**Package owner:** UIX-01 unified Manager redesign **#371**; this is **UIX-01 Home work**, not a separate release or a duplicate diagnostics engine.  
**Implementation order:** #318 LSPosed modernization, #333 architecture gates, #344 A10 state/UDF, #345 A11 Compose, #371 one end-to-end UIX-01 release gate.  
**Integration owners:** M01 #320 (structured health), M02 #321 (activation / scope proof), M03 #322 (bootstrap), A05 #339 (feature health), A06 #340 (manager/runtime bridge); F154 #169 (capability scan), F155 #170 (resolver self-test), F159 #177 (version diff), F166 #184 (framework/scope assistant), F143 #157 (target/account dashboard), M12 #331 (signed compatibility registry), F161 #179 (sanitized report export). Reuse systems; **do not duplicate their functionality**.

## User goal

From **Home**, a user must be able to answer, without reading logs:
1. Is WA X truly working on my selected WhatsApp / WhatsApp Business **now**?
2. If it is not working, **which exact stage failed, why is this conclusion supported, and how can I fix it safely?**
3. Which WA X features are **verified compatible** with my **actual installed target version**, which work through fallbacks, which are blocked/incompatible, unverified or not yet implemented?
4. What changed after a WhatsApp update and which features need attention?

No vague global "LSPosed not enabled" error when the framework/module/scope were not conclusively proven to be the cause. Do not claim root as a prerequisite unless target/runtime fact establishes it. No fake green.

## 1. Home — compact status hierarchy

- Top header **WA X** with App Settings gear; concise overall summary.
- **WhatsApp / WhatsApp Business** target selector with installed version, build/package ID, release channel **only when known** (Stable/Beta/Unknown), and independent last verified timestamp. Account/profile discriminator only if established by runtime evidence.
- First glance status card: **Working / Needs attention / Not running / Checking / Unverified / Unsupported / Safe Mode**. Proven stage(s) with a short explanation, no generic jargon or false "Connected".
- Clear primary **Check now** button for user-initiated low-cost scan, plus secondary **Advanced diagnosis** and **Feature compatibility** actions. Display scan progress, cancel (where safe), last completed scan time and stale evidence indicator. Do not flood network or install hooks from a Manager click without preconditions.
- Next compact **What needs attention?** card (only when issues exist): top actionable issue, number of affected features where proven, next safe action. Expand details on tap; never mix multiple unrelated causes in one claim.
- Next **Compatibility summary**: counts categorized **Verified compatible / Working in fallback (degraded) / Unverified / Incompatible or failed / Not implemented**. Counts derive from feature registry + health evidence for *selected target and installed exact version*; explain denominator and do not count feature toggles in unrelated category as compatible.
- Search/filters and short recent changes link to full compatibility page, with **category grouping** (Privacy, Customization, Media, Calls, Automation, etc.) and a collapsible technical section. Home shows short summary, Tools owns deep evidence and full reports.

## 2. Staged diagnosis with precise actionable cause

User-initiated scan stages, reusable from existing health snapshot/reporters:
1. Installed target details: package name, installed version/build, running/not running; check package availability first.
2. Framework/module: LSPosed framework/module enabled signals with evidence authority level, no self-hook as ground truth.
3. Recommended scope vs observed effective scope; display **missing scope** only when supported by trustworthy framework/scope evidence.
4. Target process/session: boot/target session, fresh/stale/expired heartbeat and runtime bootstrap phase.
5. Manager↔runtime authenticated IPC/bridge and preference access/isolation health.
6. DexKit/resolver availability and confidence, version metadata validity, per-feature resolver results; safe test skips unknown or dangerous probes.
7. Hook/capability availability and per-feature health (including fallback / circuit breaker / disabled / quarantined).
8. Compatibility assessment for exact WhatsApp/Business version + supported range/declared registry/signed metadata if available, without guessing versions.

**Evidence presentation for each diagnosis:**
- Human readable **Issue → observed evidence → probable cause (confirmed vs suspected vs unknown) → affected features → safe remedy → verify again**.
- Provide stable failure code, stage, sanitized target/module/version, last seen session/time, reproducibility/evidence confidence, and optional **Technical details** accordion.
- Examples (must remain scenarios, not production test results):
  - **Target app stopped:** "WhatsApp is installed but not currently running; injection has not yet been checked" → open target then recheck (do not claim LSPosed disabled).
  - **Module disabled (verified):** enable module in LSPosed, restart target when required, recheck.
  - **Scope missing (verified):** show exact target to select and why it matters; link to explanatory instructions, not an unsupported auto-toggle.
  - **Stale heartbeat:** last injection was observed before target restart; status unknown until fresh evidence is received.
  - **Version/resolver mismatch:** flag *only affected features*, distinguish unsupported resolver vs framework-wide failure; leave unaffected healthy features alone; suggest fallback/feature disable only when safe and reversible.
  - **Preference bridge error:** explain why a toggle can be saved in Manager but not applied to WhatsApp runtime yet.
  - **Crash-loop / quarantined:** identify guarded failure path and Safe Mode option; warn before any destructive action.
- No one-tap destructive auto-repair, secret root operations, false fix promises or blanket failure labels. Actions are specific, reversible, optionally rechecked afterwards.

## 3. Feature compatibility catalog — discoverable and honest

- In Home, **See all compatible features** opens a page accessible from Tools and the Features browser.
- Header shows **selected target name, exact installed version, channel if known, last verified time and confidence/provenance**. Show **unverified/offline** if missing.
- Group by **user-facing feature category**, not resolver internal class name; secondary status chips:
  - **Verified compatible:** registry/version declaration + runtime health/verification evidence both pass, with provenance.
  - **Working via fallback:** verified functional compatibility path with relevant limitation and degraded signal.
  - **Unverified:** target build beyond declaration, stale or missing evidence; no automatic unsupported claim.
  - **Incompatible:** proven missing capability/resolver/version gate.
  - **Runtime failed / quarantined:** version may be compatible but a local operational fault prevents use; separate from inherent incompatibility.
  - **Disabled by user:** configuration preference (not evidence of incompatibility).
  - **Not implemented / planned:** declared unavailable; never count as working.
- Two orthogonal filters **Category** and **Compatibility status**, plus text search in Arabic/English/aliases. Show independent **desired enabled**, **effective runtime active** and **support verdict** fields. Do not simply use the switch color as proof.
- Each feature detail explains **exact version rule / resolver or capability / dependency / confidence / failure stage / fallback or workaround / restart needed / test timestamp**, with no leaked chat content. Option to "Test this feature" via F155 only if safe and a real test exists.
- Version update diff via F159: compare prior version vs current with **newly compatible, now unverified, regressed, unavailable, unchanged** sections; do not infer compatibility based only on a version string.
- All UI text in Arabic and English; clear support legend, keyboard accessibility, high contrast; loading, empty, stale, partial scan, unsupported and no-target-installed states.

## 4. UX flow

`Home → Check now → Stage progress → Health summary → [Diagnosis if needed | Feature compatibility] → category/filter/search → Feature detail → suggested fix / safe test → Recheck`.

The top summary does **not** list hundreds of features by default. Show 3-5 priority issues or summary categories. Deeper details progressively disclosed. Avoid blocking Home on slow per-feature scans; use cached result with clearly visible timestamp and invalidation. Manual scan must be cancellable at safe checkpoints.

## 5. Truth model / data ownership

- UI read-only presentation over **existing typed RuntimeHealthSnapshot, FailureCode, HealthReporter, FeatureRegistry, FeatureMetadata, FeatureHealth/FeatureOutcome, resolver diagnostics, bridge, verified compatibility metadata and version channel**. Connect through A10 state/UDF and A11 screens, not ad-hoc direct Xposed/DexKit reads from Manager.
- Preserve exact target/account and process/session separation. A WhatsApp Business success never proves WhatsApp success.
- Compute status **per installed exact build**, but do not misclassify unverified builds: `VersionStatusTone.UNVERIFIED` ≠ `UNSUPPORTED`. Release channel `UNKNOWN` is not Beta.
- Source-specific records must not cross-contaminate: framework status, process presence, injection proof, resolver outcomes, enabled preference and runtime working status remain independently displayed.
- Run passive cheap health snapshot on Home; deeper scan only on explicit user action or scheduled safe background health checks already owned elsewhere, respecting privacy/battery.
- Redact secrets, messages, contacts, paths and personal data; no automatic upload. Share/export through F161 after user review.

## 6. UIX-01 internal delivery placement

- **UIX-01.0** inventory existing Home, M01/M02 health, F154/F155/F159/F166/F161, UI current diagnostic/compatibility surfaces and baseline tests.
- **UIX-01.1** shared health badges/target selector/status/scan progress/cause-action row, feature status chips and empty/failure states.
- **UIX-01.2** Features view consumes same support/status model, with direct links to cause and compatibility detail.
- **UIX-01.3** Customization preview may show disabled/unsupported effect truth without making status claims.
- **UIX-01.4** Tools deep diagnosis, resolver detail, export/report and update diff.
- **UIX-01.5** App Settings own diagnostics privacy preferences and motion, no duplicate health logic.
- **UIX-01.6** package-wide Home/Features/Tools flow tests, signed release and rollback.
- **Explicit new workstream: UIX-01.H** Home health & compatibility hub, implemented after shared foundation and **before the integrated package gate**, using existing underlying diagnostic owners. This is part of UIX-01, not a separate shipped release.

## 7. Acceptance — attach verifiable evidence

- [ ] Home shows separate actual WhatsApp and Business version/package/session state; switching target updates the entire view and all counters without cross-talk.
- [ ] Low-cost `Check now` works or is clearly unavailable, has progress/cancel/check timestamps/stale state and never invents check completion.
- [ ] Diagnoses can differentiate no app, stopped app, module disabled, verified missing scope, missing injection, stale heartbeat, IPC/preference failure, resolver mismatch, unsupported version, safe-mode quarantine and feature-specific hook error **without generic false blame**.
- [ ] Each issue has evidence basis, uncertainty indicator, **affected feature(s)**, safe suggested fix and recheck affordance; deep technical detail is optional and sanitized.
- [ ] Every registered feature (including planned metadata entries) is classified into status/category for exact target version; no false green and no absent features, no count that conflates compatible with enabled.
- [ ] Compatible/fallback/unverified/incompatible/failed/disabled/planned clearly distinguished, including target version and fallback details, with status/category/bilingual search filters.
- [ ] Update diff states are version-aware; stale/unverified metadata and partial scan results fail safely rather than calling everything unsupported.
- [ ] F154/F155/F159/F166 owners reused with proper skip/unsupported behavior when their backend is still pending; do not invent UI results.
- [ ] Simulated failure matrix + synthetic version matrix, target isolation, stale sessions, bootstrapping, support registry, resolver failures, Safe Mode, dark/light/AMOLED, Arabic RTL/English LTR, accessibility/screen readers/large font and offline verified.
- [ ] Manager has no direct LSPosed/Xposed/DexKit dependency, reports are privacy-redacted, scan is cancelable/safely bounded, no new lint/detekt warnings, tests/release/rollback green.
- [ ] Package #371 is complete **only after Home, Features, Customization, Tools and App Settings all pass one integration gate**.

## References
- UIX-01 master #371: https://github.com/Alaa91H/WA-X/issues/371
- Main unified spec: [APPROVED_MANAGER_UI_UX_UNIFIED_PACKAGE.md](APPROVED_MANAGER_UI_UX_UNIFIED_PACKAGE.md)
- Feature browsing: [APPROVED_FEATURES_SCREEN_SPEC.md](APPROVED_FEATURES_SCREEN_SPEC.md)
- M01 #320, M02 #321; A05 #339, F154 #169, F155 #170, F159 #177, F166 #184, F161 #179

- Illustrative interactive Home + diagnosis + feature compatibility preview: [HOME_DIAGNOSTICS_COMPATIBILITY_PREVIEW.html](preview/HOME_DIAGNOSTICS_COMPATIBILITY_PREVIEW.html)
