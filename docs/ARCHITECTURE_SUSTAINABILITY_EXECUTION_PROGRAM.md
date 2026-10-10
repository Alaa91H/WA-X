# WA X — Architecture Sustainability Modernization Execution Program

> **Status:** Strategic Architecture Program
>
> **Source:** WA-X-Architecture-Sustainability-Modernization-Plan.md — 2026-10-07
>
> **Repository:** `Alaa91H/WA-X`
>
> **Execution branch:** `priority/architecture-sustainability`
>
> **Relationship to LSPosed modernization:** This program does **not** replace the existing BLOCKER/P0 LSPosed program (#318, M00–M13). It supplies the architectural work that must be integrated around it. Where the source plan overlaps M00–M13, the existing modernization Issue remains authoritative to avoid duplicate work.

---

# 0. Executive priority decision

The source plan is directionally correct: WA X should evolve toward a modular monolith with explicit Manager/Runtime boundaries, a generated feature registry, capability-driven resolvers, typed settings, strict architecture gates and a modern Xposed runtime.

The current repository confirms that this is an evolutionary refactor, not a rewrite:

- useful foundations already exist: `FeatureRegistry`, `FeatureMetadata`, `ResolverRegistry`, `FeatureInstaller`, `FallbackChain`, `SettingsStore`, `EffectiveSettingsResolver`, target-aware settings, compatibility tooling and CI;
- the project is still a single Gradle application module;
- there is no `build-logic` included build yet;
- there are no `androidTest` sources;
- `FeatureLoader.plugins()` still hard-codes feature classes and constructs them reflectively;
- runtime code still consumes `SharedPreferences` and legacy/global state;
- manager/runtime separation is not enforced by Gradle dependency boundaries.

The execution priority is therefore **risk-first**, not file-move-first:

~~~text
Tier 0A — Runtime architecture foundations
Tier 0B — Enforced boundaries and testability
Tier 1  — Manager/data/UI modernization
Tier 2  — Optional optimization/domain modularization
~~~

Do not start a broad multi-module file move before the runtime contracts, Feature Registry, Capability boundary and RuntimeGraph are explicit.

---

# 1. Verified repository baseline

Strict audit results on 2026-10-07:

| Item | Current state |
|---|---|
| Gradle app modules | one application module (:app) |
| build-logic included build | absent |
| proposed core/runtime/data/bridge modules | absent |
| instrumentation tests | 0 files under src/androidTest |
| FeatureRegistry | exists |
| FeatureMetadata | exists |
| ResolverRegistry | exists |
| FeatureInstaller | exists |
| FallbackChain | exists |
| SettingsStore | exists |
| EffectiveSettingsResolver | exists |
| target-aware settings | exists |
| PlatformFeatureCatalog | exists |
| FeatureLoader runtime list | hard-coded |
| Feature construction | reflection via constructor(ClassLoader, SharedPreferences) |
| compileSdk | 37 |
| targetSdk | 34 |
| current architecture branch base | priority/lsposed-modernization |

The source plan's modularization recommendation is consistent with current Android guidance: modularization is useful for large/growing codebases, but modules should remain cohesive and not become too fine-grained. Convention plugins are recommended for sharing build logic.

Navigation 3 is now stable; the architecture plan may target the current stable Navigation 3 line when the Compose shell is ready, but Navigation migration remains a later Manager concern and is not allowed to block runtime stabilization.

---

# 2. Binding priorities

## Priority P0-A — execute during/before LSPosed modernization

These are architectural prerequisites for a maintainable runtime:

- A00 Architecture baseline and dependency rules
- A01 Manager / Runtime contracts
- A02 RuntimeGraph and global-state containment
- A03 Unified + generated Feature Registry
- A04 Capability / Resolver boundary
- A05 FeatureInstaller v2 / health / circuit breaker
- A06 Versioned Manager ↔ Runtime bridge and IPC contract

These must be coordinated with #318 M00–M08.

## Priority P0-B — execute before general feature backlog resumes

These establish enforceable structure and testability:

- A07 build-logic and initial modular-monolith extraction
- A08 typed settings / DataStore / runtime snapshots / secrets
- A13 architecture tests and quality ratchet
- A14 instrumentation + synthetic hook testing
- A16 security boundary audit
- A17 dependency verification / SBOM / provenance

These must be green before the final M13 release/rollback gate.

## Priority P1 — execute after runtime modernization is stable

These improve Manager maintainability without destabilizing hooks:

- A09 Data repositories
- A10 Manager ViewModel/UDF + Hilt
- A11 Compose shell / Single Activity / Navigation 3
- A12 R8 / obfuscation hardening
- A15 benchmarking and build performance
- A18 architecture documentation / ADR

## Priority P2 — optional and evidence-driven

- A20 feature-domain Gradle modularization

Do not split every feature domain until build/profile measurements prove a benefit.

---

# 3. Source-plan phase mapping

Every source phase P00–P34 is accounted for.

| Source | Execution owner |
|---|---|
| P00 Baseline | A00 + M00 |
| P01 Build Logic | A07 |
| P02 Core Modules | A07 |
| P03 Settings Modules | A08 |
| P04 Runtime API | A01 |
| P05 Bridge | A06 |
| P06 Runtime Graph | A02 |
| P07 Unified Feature Registry | A03 |
| P08 Generated Registry | A03 |
| P09 Resolver Boundary | A04 |
| P10 Capability Registry | A04 |
| P11 Feature Installer v2 | A05 |
| P12 Runtime Health | existing M01–M03 |
| P13 Modern libxposed | existing M06 |
| P14 Legacy Compatibility Bridge | existing M06 + M13 |
| P15 Proto DataStore | A08 |
| P16 Settings Snapshot | A08 |
| P17 SecretStore | A08 |
| P18 Data Repositories | A09 |
| P19 ViewModel/UDF | A10 |
| P20 Compose Migration | A11 |
| P21 Navigation 3 | A11 |
| P22 Hilt Manager | A10 |
| P23 R8 Hardening | A12 + M11 |
| P24 Quality Ratchet | A13 + M11 |
| P25 Instrumentation | A14 |
| P26 E2E Xposed | existing M13 |
| P27 Benchmark | A15 |
| P28 Security Audit | A16 |
| P29 Dependency Verification | A17 + M11 |
| P30 Toolchain | existing M09 |
| P31 targetSdk 37 | existing M10 |
| P32 Android 17 Matrix | existing M10 + M13 |
| P33 Remove Legacy Xposed | existing M13 |
| P34 Feature Domain Modularization | A20 optional |

No source phase is dropped.

---

# 4. A00 — Architecture baseline and dependency law

**Priority:** P0-A  
**Synchronize with:** M00

### Tasks

A00.01 Record current package dependency graph.

A00.02 Record:
- source file counts;
- package counts;
- compile/test time;
- incremental build time;
- APK size;
- lint baseline count;
- detekt baseline count;
- Feature count;
- resolver count;
- direct SharedPreferences consumers;
- direct Unobfuscator consumers;
- Utils/global-state consumers.

A00.03 Define target architecture dependency rules:

~~~text
core -> no app/runtime
runtime:api -> core only
runtime:resolver -> runtime:api + core
runtime:features -> runtime:api + resolver + core
runtime:xposed -> runtime:features + resolver + api + bridge + core
bridge -> core
data -> core
app -> data + bridge + core
~~~

A00.04 Define forbidden edges:

~~~text
core -> app
core -> Xposed
feature -> UI
feature -> SharedPreferences
feature -> Unobfuscator
resolver -> UI
UI -> DexKit
UI -> XposedBridge
runtime -> Manager ViewModel
~~~

A00.05 Add an architecture allowlist only for temporary migration debt.

Every allowlist entry requires:
- owner;
- reason;
- removal phase;
- issue reference.

A00.06 Establish baseline metrics file under `docs/architecture/baseline/`.

### Gate A00

- zero behavior change;
- architecture graph is reproducible;
- forbidden-edge checker can fail on a fixture;
- all migration exceptions are explicit.

---

# 5. A01 — Explicit Manager / Runtime contracts

**Priority:** P0-A  
**Synchronize with:** M01–M03

### Goal

Create the contract boundary before moving files into Gradle modules.

### Tasks

A01.01 Define pure runtime interfaces:
- `WaFeature`;
- `FeatureContext`;
- `FeatureStartResult`;
- `HookEngine`;
- `CapabilityProvider`;
- `DiagnosticSink`;
- `RuntimeClock`;
- `RuntimeLogger`;
- `SettingsSnapshot`.

A01.02 Move interfaces/types away from concrete Xposed implementation packages.

A01.03 Ensure feature contracts do not expose:
- Activity;
- Fragment;
- View;
- SharedPreferences;
- XposedBridge;
- XposedHelpers;
- DexKit types.

A01.04 Add compile-time tests/checks for forbidden contract leakage.

A01.05 Keep implementation in current :app temporarily if extraction would create a large file-move diff.

### Gate A01

Runtime features can be unit-tested against fake HookEngine / CapabilityProvider without a real Xposed framework.

---

# 6. A02 — RuntimeGraph and global-state containment

**Priority:** P0-A  
**Synchronize with:** M02–M03

### Tasks

A02.01 Create one `RuntimeGraph` per target process.

It owns:
- TargetEnvironment;
- HookEngine;
- ResolverEngine;
- CapabilityRegistry;
- SettingsSnapshot;
- FeatureRegistry;
- FeatureInstaller;
- CompatibilityEngine;
- DiagnosticSink;
- RuntimeClock;
- Logger;
- SessionState.

A02.02 Inventory global mutable state:
- Utils.application;
- Utils.xprefs;
- FeatureLoader.moduleContext;
- current Activity;
- static caches;
- static target/runtime references.

A02.03 Classify each global:
- remove;
- inject;
- session-scope;
- immutable process constant;
- compatibility bridge only.

A02.04 Ban new writes to legacy globals.

A02.05 Migrate feature constructors to explicit context dependencies.

A02.06 Add lifecycle/shutdown semantics to RuntimeGraph.

### Gate A02

No newly migrated feature reads mutable global runtime state directly.

---

# 7. A03 — One Feature Registry + KSP generated registry

**Priority:** P0-A  
**Synchronize with:** M03

### Current issue

The repository already has typed `FeatureRegistry` / `FeatureMetadata`, but `FeatureLoader.plugins()` separately hard-codes the actual runtime classes and instantiates them by reflection.

### Tasks

A03.01 Define a single `FeatureDescriptor`:

~~~text
metadata
factory
requiredCapabilities
optionalCapabilities
settingKeys
runtimePolicy
~~~

A03.02 Make the registry the source for:
- runtime;
- compatibility;
- diagnostics;
- tests;
- settings linkage;
- documentation generation.

A03.03 Introduce `@WaFeature` KSP annotation.

A03.04 Generate:
- GeneratedFeatureRegistry;
- GeneratedFeatureFactories;
- GeneratedFeatureIndex.

A03.05 Replace reflective `getConstructor(ClassLoader, SharedPreferences)` creation with typed factories.

A03.06 Delete the hard-coded feature-class array after parity proof.

A03.07 CI fails if a feature lacks:
- metadata;
- factory;
- declared tests;
- settings contract;
- compatibility/capability contract.

A03.08 Generate registry consistency report.

### Gate A03

Exactly one authoritative feature registration source exists and runtime construction is non-reflective.

---

# 8. A04 — Capability-driven Resolver boundary

**Priority:** P0-A  
**Synchronize with:** M08

### Tasks

A04.01 Define typed `CapabilityId<T>`.

A04.02 Define `Resolver<T>` contract with:
- resolver ID;
- input context;
- Resolution result;
- confidence;
- evidence;
- fingerprint;
- duration;
- resolver version.

A04.03 Define standard outcomes:
- Resolved;
- NotFound;
- Ambiguous;
- Unsupported;
- Failed;
- Cached.

A04.04 Migrate:
~~~text
Feature -> CapabilityProvider -> ResolverEngine -> DexKit/Reflection
~~~

A04.05 Ban direct `Unobfuscator` use from feature packages.

A04.06 Build CapabilityRegistry.

A04.07 Persist only safe resolver cache/evidence.

A04.08 Add fingerprinted invalidation.

A04.09 Expose resolver evidence to diagnostics without private content.

### Gate A04

A feature cannot compile/pass architecture tests if it directly imports the resolver implementation/DexKit path.

---

# 9. A05 — FeatureInstaller v2 and runtime safety

**Priority:** P0-A  
**Synchronize with:** M03 + M12

### Tasks

A05.01 Make FeatureInstaller the only feature-start path:

~~~text
Descriptor
→ Compatibility
→ Capabilities
→ Kill switch
→ Settings
→ Factory
→ Initialize
→ Health
~~~

A05.02 Extend feature health to:
- UNKNOWN;
- STARTING;
- HEALTHY;
- DEGRADED;
- DISABLED;
- UNSUPPORTED;
- FAILED;
- QUARANTINED.

A05.03 Add session circuit breaker.

A05.04 Integrate local/signed-compat/runtime-safety kill switches.

A05.05 Make installer idempotent.

A05.06 Store install duration and failure evidence.

A05.07 Explicitly separate feature health from resolver health.

### Gate A05

One optional feature cannot abort module bootstrap and no feature starts outside FeatureInstaller v2.

---

# 10. A06 — Versioned Manager ↔ Runtime bridge and IPC security

**Priority:** P0-A  
**Synchronize with:** M02, M06, M10

### Tasks

A06.01 Define versioned protocol:
- BridgeProtocolVersion;
- RuntimeApiVersion;
- SettingsRevision;
- SessionId;
- Capability summary;
- HealthSnapshot.

A06.02 Separate request/response/event schemas.

A06.03 Validate:
- caller UID;
- caller package;
- protocol version;
- request schema;
- action permission.

A06.04 Use signature-level permission where IPC crosses process/application boundaries.

A06.05 Bound payload sizes and timeouts.

A06.06 Add malformed/old/new protocol tests.

A06.07 No UI component talks directly to Xposed/DexKit/root.

### Gate A06

Manager can query runtime state through one versioned bridge abstraction with negative security tests.

---

# 11. A07 — build-logic and initial Modular Monolith extraction

**Priority:** P0-B  
**Start after:** M04 legacy stability; preferably after A01–A06 contracts exist.

### Why not earlier

Moving hundreds of files before runtime contracts stabilize increases regression attribution cost.

### Tasks

A07.01 Create included `build-logic`.

A07.02 Convention plugins:
- wax.android.application;
- wax.android.library;
- wax.kotlin.library;
- wax.compose;
- wax.testing;
- wax.xposed.runtime;
- wax.quality;
- wax.benchmark.

A07.03 Move imperative build logic/tasks from module build files into plugins where practical.

A07.04 Extract pure modules first:
- :core:model;
- :core:platform;
- :core:compatibility;
- :core:diagnostics;
- :runtime:api.

A07.05 Then extract:
- :runtime:resolver;
- :runtime:features;
- :bridge;
- :data.

A07.06 Extract :runtime:xposed only after modern loader boundary is stable.

A07.07 Add Gradle dependency graph assertions.

A07.08 Prefer pure Kotlin/JVM modules when Android APIs are not required.

### Gate A07

Functional APK parity, no forbidden dependency edges, no cyclic modules, clean incremental build.

---

# 12. A08 — Typed settings architecture, DataStore and SecretStore

**Priority:** P0-B  
**Synchronize with:** M06 preferences migration.

### Tasks

A08.01 Define `SettingKey<T>`, `SettingSpec<T>`, category, scope, restart policy, secret flag.

A08.02 Create one Settings Catalog.

A08.03 Generate/derive:
- UI metadata;
- defaults;
- validation;
- backup schema;
- migration schema;
- runtime snapshot;
- docs.

A08.04 Manager storage migrates to Proto DataStore with versioned migrations.

A08.05 Runtime receives immutable SettingsSnapshot; it does not directly read DataStore.

A08.06 Add `settingsRevision`.

A08.07 Runtime refreshes only on revision changes.

A08.08 Add SecretStore using Android Keystore + encrypted payload.

A08.09 Secrets excluded from normal export/backup.

A08.10 Split backup domains:
- UserSettingsBackup;
- SecretsBackup;
- RuntimeCache;
- Diagnostics.

A08.11 Preserve target-aware inheritance and migration semantics.

### Gate A08

Settings migration is transactional, rollback-tested, target-isolated and covered by instrumentation tests.

---

# 13. A09 — Data layer and repositories

**Priority:** P1

### Tasks

Repositories:
- SettingsRepository;
- CompatibilityRepository;
- DiagnosticsRepository;
- UpdateRepository;
- RuntimeRepository;
- BackupRepository;
- FeatureRepository.

Data sources:
- DataStoreSettingsSource;
- RoomDiagnosticsSource;
- GitHubReleaseSource;
- XposedServiceSource;
- PackageManagerSource;
- RootDiagnosticsSource.

Root data source rules:
- fixed command templates;
- no arbitrary user input;
- timeout;
- sanitized output;
- explicit privilege handling.

### Gate A09

Manager UI no longer calls SharedPreferences, PackageManager, root shell, HTTP, Xposed, DexKit, Room or raw BroadcastReceiver infrastructure directly.

---

# 14. A10 — Manager ViewModel/UDF + Hilt

**Priority:** P1

Hilt is permitted only in the Manager application. Runtime injection remains manual constructor injection via RuntimeGraph/factories.

### Tasks

A10.01 Define Screen → ViewModel → UseCase → Repository → DataSource flow.

A10.02 Standardize UiState / UiAction / UiEffect.

A10.03 Refactor Home first:
- HomeScreen;
- HomeViewModel;
- ObserveRuntimeHealthUseCase;
- ObservePackageStatusUseCase;
- CheckUpdatesUseCase;
- RunDiagnosticsUseCase.

A10.04 Refactor Diagnostics second.

A10.05 Add Hilt to Manager only after repository boundaries exist.

A10.06 No Hilt/Dagger graph inside WhatsApp process.

### Gate A10

Home and Diagnostics have no direct infrastructure calls and ViewModels have pure/fakeable dependencies.

---

# 15. A11 — Compose shell, Single Activity and Navigation 3

**Priority:** P1  
**Start after:** A10

### Tasks

A11.01 New Manager screens use Compose by default.

A11.02 Migration order:
- Home;
- Diagnostics;
- Search;
- Settings;
- About;
- remaining fragments.

A11.03 Establish Single Activity shell where Android component requirements allow it.

A11.04 Use current stable Navigation 3 line after shell parity.

A11.05 Preserve deep links, saved state, back stack and process recreation.

A11.06 Remove legacy fragments only after parity tests.

### Gate A11

Navigation/state restoration/instrumentation tests pass and no runtime code depends on Manager UI framework.

---

# 16. A12 — R8 and obfuscation hardening

**Priority:** P1  
**Synchronize with:** M11

### Tasks

A12.01 Inventory broad rules.

A12.02 Remove global `-dontwarn *`.

A12.03 Remove `-dontoptimize` where safe.

A12.04 Every suppression declares dependency/reason/scope/issue.

A12.05 Re-evaluate `-dontobfuscate` after reflective feature construction is removed.

A12.06 Start obfuscation with Manager/pure modules first if runtime reflection risk remains.

A12.07 Add release-R8 smoke tests.

### Gate A12

No unexplained global suppressions and release build behavior remains verified.

---

# 17. A13 — Architecture tests and quality ratchet

**Priority:** P0-B  
**Synchronize with:** M11

### Tasks

A13.01 Architecture tests block:
- UI -> Xposed/DexKit;
- core -> Android app/UI;
- feature -> Unobfuscator;
- feature -> SharedPreferences;
- resolver -> UI;
- runtime -> Manager ViewModel.

A13.02 Lint: new issues = 0; baseline never increases.

A13.03 Detekt: new issues = 0; baseline never increases.

A13.04 Progressively switch detekt `ignoreFailures=false` for modernized modules.

A13.05 Architecture baseline ratchet.

A13.06 Registry/settings/capability schema validators run in CI.

### Gate A13

Architecture violations and new static-analysis debt are merge blockers.

---

# 18. A14 — Instrumentation and synthetic runtime testing

**Priority:** P0-B  
**Required before:** M13 final rollout.

### Test levels

~~~text
L0 Pure JVM
L1 Architecture
L2 Robolectric
L3 Instrumentation
L4 Synthetic Hook
L5 LSPosed E2E
L6 Target Compatibility
~~~

### Tasks

A14.01 Create androidTest source set.

A14.02 Instrument:
- DataStore migrations;
- Room migrations;
- Navigation;
- settings;
- backup/restore;
- providers;
- permissions;
- bridge IPC.

A14.03 Build fake target classes for HookEngine tests.

A14.04 Add smoke/full matrix split:
- PR smoke;
- nightly extended;
- release matrix.

### Gate A14

At least one meaningful test exists at every required level L0–L6, or an explicit reason documents why a level is not applicable.

---

# 19. A15 — Performance and build engineering

**Priority:** P1

### Tasks

A15.01 Add :benchmark after modularization is stable.

A15.02 Macrobenchmark Manager:
- cold/warm startup;
- home;
- settings navigation;
- diagnostics;
- search.

A15.03 Runtime metrics:
- DexKit time;
- resolver time;
- feature install time;
- total startup;
- cache-hit ratio;
- hook count/failure count.

A15.04 Baseline Profile + Startup Profile for Manager.

A15.05 Validate configuration cache.

A15.06 Validate build cache and parallel execution.

A15.07 Test Isolated Projects only after module ecosystem is ready; do not make it an early blocker.

### Gate A15

Performance changes have measured before/after evidence; no architecture change is justified only by intuition.

---

# 20. A16 — Security boundary audit

**Priority:** P0-B

### Tasks

Audit:
- BridgeService;
- HookProvider;
- RemotePreferenceProvider;
- Receivers;
- FileProvider;
- exported Activities;
- update endpoints;
- root commands;
- backup/import;
- CSS editor/WebView.

Rules:
- every exported component justified;
- signature permission where appropriate;
- caller UID/package validation;
- strict URI validation;
- no arbitrary root command input;
- secrets never logged;
- WebView disables unnecessary JS bridges/file access/external navigation.

### Gate A16

Security test matrix passes and every exported surface has an owner, reason and protection model.

---

# 21. A17 — Dependency verification, SBOM, provenance and reproducibility

**Priority:** P0-B  
**Synchronize with:** M11

### Tasks

A17.01 Enable Gradle dependency verification metadata.

A17.02 All normal dependency versions live in version catalog unless a documented exception exists.

A17.03 Local binaries require upstream version, commit, SHA-256 and license record.

A17.04 Generate CycloneDX or equivalent SBOM.

A17.05 Release provenance:
- commit SHA;
- build environment;
- dependency lock/verification;
- SBOM;
- signing cert fingerprint;
- APK SHA-256.

A17.06 Reproducibility test.

### Gate A17

A release can be traced from source revision through dependencies to signed artifact.

---

# 22. A18 — Documentation and ADR

**Priority:** P1

Create:
- docs/architecture/ARCHITECTURE.md
- MODULES.md
- RUNTIME.md
- FEATURE_MODEL.md
- RESOLVERS.md
- SETTINGS.md
- IPC.md
- SECURITY.md
- TESTING.md
- RELEASE.md
- COMPATIBILITY.md

ADRs:
- Manager/Runtime boundary;
- Modular Monolith;
- Generated Feature Registry;
- Modern libxposed;
- Proto DataStore;
- Capability-driven resolvers.

Mark old MASTER_PLAN / ROADMAP documents as historical where applicable.

### Gate A18

Architecture documentation matches enforced code/module rules and CI checks.

---

# 23. A19 — Integration with existing LSPosed modernization phases

**Priority:** P0 integration gate — no duplicate implementation.

This is a tracking/mapping concern, not a second implementation of the same work.

| Architecture concern | Existing owner |
|---|---|
| Runtime Health | M01–M03 |
| Modern libxposed | M06 |
| ResourceBridge | M07 |
| DexKit/resolver runtime | M08 |
| Toolchain | M09 |
| targetSdk 37 | M10 |
| strict quality/release gate | M11 |
| compatibility registry / watchdog | M12 |
| LSPosed E2E / Android matrix / legacy removal | M13 |

A19 closes only when every overlap is either implemented by M00–M13 or explicitly linked to the matching A-phase contract.

---

# 24. A20 — Optional feature-domain modularization

**Priority:** P2 / MEASURE FIRST

Candidate modules after evidence:

~~~text
:feature:privacy
:feature:messaging
:feature:media
:feature:status
:feature:calls
:feature:customization
:feature:automation
~~~

Do not create them merely for visual cleanliness.

Create a domain module only if at least one measurable benefit exists:
- ownership boundary;
- dependency isolation;
- test isolation;
- incremental build improvement;
- API encapsulation;
- independent compile/change frequency.

### Gate A20

Architecture/build metrics demonstrate benefit greater than module overhead.

---

# 25. Recommended actual execution sequence

This is the priority order I recommend, combining architecture with the existing M-program:

~~~text
M00 + A00
↓
M01 + A01
↓
M02 + A02 + A06(protocol skeleton)
↓
M03 + A03 + A05
↓
M04
↓
A07(build-logic + pure module extraction only)
↓
M05
↓
M06 + A06(full modern bridge)
↓
M07
↓
M08 + A04
↓
A08
↓
A13 + A14 + A16 + A17 foundations
↓
M09
↓
M10
↓
M11 + A12 + A13 + A17
↓
M12
↓
M13 + A14 release matrix
↓
A09
↓
A10
↓
A11
↓
A15
↓
A18
↓
A20 only if metrics justify it
~~~

This deliberately moves broad Manager/UI modernization after runtime stability.

---

# 26. Strict global gates

Every architecture phase must satisfy:

1. behavior-preserving unless the issue explicitly describes a behavior fix;
2. reversible migration;
3. no silent data migration;
4. no new warning debt;
5. no new forbidden dependency edge;
6. unit tests for pure logic;
7. instrumentation for Android migration/state where relevant;
8. WhatsApp/Business isolation where runtime-facing;
9. release build passes;
10. final APK contract passes;
11. performance delta measured for hot paths;
12. no sensitive diagnostics/logging;
13. rollback documented;
14. architecture docs updated if a public boundary changes.

---

# 27. Definition of sustainable architecture

Architecture foundation is complete only when:

~~~text
One Feature Registry
One Settings Catalog
One Resolver/Capability Engine
One Compatibility Engine
One RuntimeGraph
One versioned Bridge Protocol
One authoritative CI
No direct feature -> Unobfuscator
No direct feature -> SharedPreferences
No UI -> Xposed/DexKit
No self-hook activation dependency
Modern libxposed active
Runtime health explicit
Typed Manager settings + immutable runtime snapshot
Architecture dependency rules enforced
Instrumentation tests present
LSPosed E2E present
Lint/Detekt baselines decreasing
R8 suppressions targeted
Dependency verification enabled
SBOM/provenance generated
Rollback tested
~~~

---

# 28. Success metrics

## Architecture
- forbidden dependency edges: 0;
- duplicate registries: 0;
- reflective feature factories: 0;
- untyped feature registrations: 0.

## Quality
- new lint issues: 0;
- new detekt issues: 0;
- architecture gate regressions: 0.

## Runtime
- optional feature failure cannot crash module;
- resolver failure isolated;
- startup health observable;
- no false LSPosed state.

## Build
- configuration cache works for supported tasks;
- incremental build is measured;
- CI deterministic.

## Compatibility
- capability-level evidence;
- feature-level health;
- target/account separation.

## Release
- signed;
- SBOM;
- checksum;
- provenance;
- tested rollback.

---

# 29. Non-negotiable prohibitions

Do not:
- rewrite everything at once;
- migrate UI + Xposed + settings + Gradle modules in one branch;
- create one Gradle module per feature prematurely;
- infer compatibility from WhatsApp version alone;
- keep global R8/lint/detekt suppression forever;
- let UI call infrastructure directly;
- let features call Unobfuscator directly;
- download or execute remote hook code;
- place Hilt inside the injected WhatsApp runtime;
- start broad Compose migration while runtime modernization is unstable.

---

# 30. Final recommendation

The source plan's desired architecture is correct, but execution must be reordered around the already-active LSPosed blocker.

The four highest-value architecture moves are:

1. explicit Manager / Runtime contracts;
2. RuntimeGraph and removal of global mutable runtime state;
3. one generated Feature Registry / typed factories;
4. Capability-driven Resolver boundary.

Only after those are formalized should WA X perform broad module extraction and Manager UI modernization.

This preserves the source plan's evolutionary rule: isolate and formalize first, then replace.


protection probe
