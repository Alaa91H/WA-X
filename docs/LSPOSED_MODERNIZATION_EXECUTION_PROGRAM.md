# WA X — Priority-0 LSPosed Modernization & Stability Execution Program

> Status: BLOCKER / PRIORITY-0
>
> Source: WA-X-LSPosed-Modernization-and-Stability-Plan.md — 2026-10-07
>
> Repository: Alaa91H/WA-X
>
> Execution branch: priority/lsposed-modernization

## 1. Binding priority policy

This program has execution priority over the F003–F254 feature backlog.

Until the final modernization gate closes:

- keep all existing feature Issues open;
- freeze unrelated feature implementation;
- do not merge cosmetic or broad refactors unrelated to modernization;
- do not perform unrelated dependency upgrades;
- permit only critical security fixes, CI/release blockers, stable regressions, and work required by this program;
- master must remain buildable, testable, installable and rollback-capable.

Execution order is mandatory:

~~~text
M00 → M01 → M02 → M03 → M04 → M05 → M06
→ M07 → M08 → M09 → M10 → M11 → M12 → M13
~~~

No large phase starts before the previous gate is green.

## 2. Code-audit findings that define the work

The current repository confirms the source plan's core diagnosis:

- ModuleApplication.isXposedEnabled() is a false placeholder and is turned true through the module self-hook.
- ModuleEntryPoint is still a legacy Xposed entry using IXposedHookLoadPackage, IXposedHookInitPackageResources, IXposedHookZygoteInit, XSharedPreferences and XModuleResources.
- The self-hook also forces world-readable preference compatibility.
- FeatureLoader.start() exits immediately when Unobfuscator.initWithPath(sourceDir) fails.
- The current Gradle packaging excludes META-INF/**.
- DexKit is still bundled from libs/dexkit-android.aar.
- compileSdk is 37 while targetSdk is 34.
- AGP is 9.3.1 and Kotlin is 2.4.10.
- Existing FeatureHealth, compatibility tooling, compatibility canary and structured failure-reporting code already exist and must be reused.

A new blocker found during this audit:

- AndroidManifest declares xposedminversion=93.
- tools/quality/check_lsposed_contract.py declares MIN_LEGACY_API=93.
- gradle/libs.versions.toml currently pins xposed-legacy=82.
- the checker verifies the legacy dependency coordinate exists, but does not verify the actual dependency version.

Therefore the current loader gate can report green while the declared policy and dependency version disagree. M00 fixes this before any migration.

## 3. Verified technology targets

These are verified targets as of 2026-10-07, but every version must be re-verified at the start of the PR that changes it:

| Component | Current WA X | Target / policy |
|---|---:|---|
| compileSdk | 37 | 37 |
| targetSdk | 34 | 37 after runtime/resource readiness |
| AGP | 9.3.1 | 9.4.0 |
| Gradle | 9.6.1 | keep unless compatibility requires change |
| JDK | 17 | 17 |
| Kotlin | 2.4.10 | 2.4.20 |
| Xposed | legacy | libxposed API 102.0.0 |
| libxposed service | none | 102.0.0 when framework service access is required |
| DexKit | local AAR | org.luckypray:dexkit:2.3.0 after proof |
| targets | WhatsApp + Business | both remain first-class |

No dynamic dependency versions, floating SHAs or undocumented binary drops are allowed in release builds.

## 4. Global engineering rules

1. No single Boolean represents module health.
2. No generic LSPosed error is shown unless framework/module/scope failure was actually proven.
3. Every startup stage produces a structured result.
4. Every failure has a stable machine-readable FailureCode.
5. Every runtime has bootId, moduleSessionId and targetSessionId.
6. One optional feature failure cannot crash or block the complete module.
7. DexKit cannot be a global early-return that destroys diagnostics.
8. Modern and legacy loader contracts may never be accidentally packaged together.
9. Modern metadata may not be enabled before final-APK packaging verification exists.
10. The final modern path may not depend on world-readable preferences.
11. Reflection-based mutation of static-final resource fields must be gone before targetSdk 37.
12. Strict paths accept zero new warnings.
13. Checks may continue to collect all failures, but the final job must fail if any required gate failed.
14. Compatibility claims require evidence, not only declared target versions.
15. Diagnostics are redacted by construction.
16. Remote compatibility updates are data only: no DEX, scripts, code or hook bytecode.
17. Every stable release must be signed, verified, reproducible and rollback-tested.

# M00 — Evidence lock, baseline and false-green gate repair

Goal: establish one trustworthy baseline before runtime changes.

### M00.01 Baseline marker
Record:
- master commit SHA;
- module version;
- Gradle, AGP, Kotlin, JDK, SDK and NDK versions;
- compatibility declarations;
- CI workflow hash;
- current loader contract.

Store under docs/modernization/baseline/.

### M00.02 Current build evidence
Run and retain complete results:

~~~bash
./gradlew clean assembleDebug testDebugUnitTest lintDebug spotlessCheck detekt
python3 tools/compatibility/test_validator.py
python3 tools/compatibility/validate_compatibility.py
python3 tools/compatibility/sync_generated.py --check
python3 tools/quality/test_check_lsposed_contract.py
python3 tools/quality/check_lsposed_contract.py
~~~

### M00.03 Legacy API policy consistency
Resolve the xposedminversion / checker / compile dependency mismatch. The CI checker must parse and validate the actual pinned legacy API version.

### M00.04 Loader checker negative fixtures
Prove the checker fails on:
- wrong legacy API version;
- missing xposed_init;
- wrong entry class;
- missing legacy manifest metadata;
- broken scope declaration;
- accidental modern metadata;
- mixed legacy/modern loader contract.

### M00.05 Legacy mechanism inventory
Machine-inventory:
- de.robv.android.xposed usage;
- XSharedPreferences;
- makeWorldReadable;
- XModuleResources;
- IXposedHookInitPackageResources;
- XposedHelpers;
- generated R-field mutation;
- direct Unobfuscator calls;
- direct hook installation outside shared infrastructure.

### M00.06 Runtime truth inventory
Document every signal currently feeding Home/diagnostics:
- self-hook;
- broadcasts;
- process state;
- scope;
- target settings;
- compatibility canary;
- feature health;
- failure reports.

### M00.07 Test matrix lock
Define exact matrix IDs:
- Android 14, 15, 16, 17;
- supported stable LSPosed;
- newest supported libxposed implementation;
- WhatsApp stable;
- WhatsApp beta;
- WhatsApp Business stable;
- clean start, warm start, process kill, reboot.

### M00.08 False-disabled regression fixture
Capture the current reported false-disabled state so later work proves the actual bug was fixed.

### Gate M00
All required:
- baseline reproducible;
- loader policy consistent;
- checker proven able to fail;
- legacy inventory complete;
- current bug reproducible;
- no unrelated feature changes.

# M01 — Structured Runtime Health

Goal: create the health model before changing detection.

### M01.01 Typed state model
Independent states for:
- framework;
- module;
- scope;
- target process;
- injection;
- preferences;
- DexKit;
- resolver;
- core components;
- essential hooks;
- optional hooks;
- overall runtime.

### M01.02 RuntimeHealthSnapshot
Required fields:

~~~text
bootId
moduleSessionId
targetSessionId
timestamp
packageName
processName
pid
moduleVersion
targetVersionName
targetVersionCode
androidSdk
frameworkState
moduleState
scopeState
targetProcessState
injectionState
preferencesState
dexKitState
resolverState
featureSummary
overallState
failureCode
failureMessage
~~~

### M01.03 FailureCode taxonomy
At minimum:

~~~text
FRAMEWORK_UNAVAILABLE
MODULE_DISABLED
SCOPE_MISSING
TARGET_NOT_RUNNING
INJECTION_NOT_OBSERVED
INJECTION_FAILED
PREFERENCES_UNAVAILABLE
DEXKIT_INIT_FAILED
RESOLVER_FAILED
CORE_COMPONENT_FAILED
ESSENTIAL_HOOK_FAILED
OPTIONAL_FEATURE_FAILED
HEARTBEAT_STALE
HEARTBEAT_EXPIRED
VERSION_UNSUPPORTED
RUNTIME_TIMEOUT
WATCHDOG_TRIPPED
UNKNOWN
~~~

### M01.04 HealthEvent
Each stage records START / SUCCESS / FAILURE / SKIPPED, duration, session IDs, component ID and failure code.

### M01.05 RuntimeHealthStore
Requirements:
- atomic writes;
- last-known-good state;
- current-session state;
- stale detection;
- target/account separation;
- bounded retention;
- corruption recovery.

### M01.06 HealthReporter
One typed reporting API. Free-form log strings cannot be the source of truth.

### M01.07 Redaction
Never store phone numbers, JIDs, message bodies, contact names, tokens, API keys or private payloads.

### M01.08 Tests
State transitions, invalid transitions, stale snapshots, session replacement, last-known-good fallback, concurrency, redaction.

### Gate M01
Structured runtime health works independently of the UI and survives restart.

# M02 — Activation detection, scope truth and heartbeat

### M02.01 Authority hierarchy

~~~text
framework/service evidence
> fresh target heartbeat
> effective scope evidence
> injection event
> self-hook compatibility signal
> historical snapshot
~~~

### M02.02 Self-hook downgrade
Self-hook remains only as LEGACY_SELF_HOOK_SIGNAL during transition and cannot alone produce READY.

### M02.03 Target heartbeat
Each target process reports package, process, pid, targetSessionId, bootId, module version, target version, stage and timestamp.

### M02.04 Freshness
Defaults:
- fresh <30s;
- stale 30–120s;
- expired >120s.

Boundary tests are mandatory.

### M02.05 Scope separation
Never infer SCOPE_MISSING only because the target did not answer.

### M02.06 Process states
Differentiate installed / not running / running-unobserved / injected / bootstrapping / degraded / ready.

### M02.07 Multi-process and multi-account
No assumption that one isFirstApplication callback proves all runtime variants.

### M02.08 Home UI mapping
Replace generic activation errors with exact cards and suggested actions.

### M02.09 Failure simulations
No framework, module disabled, scope missing, target stopped, target running without injection, injected with DexKit failure, stale heartbeat, process restart.

### Gate M02 — Gate A
No generic LSPosed-disabled message unless that failure is actually proven.

# M03 — Atomic bootstrap and failure isolation

### M03.01 Stage runner

~~~text
FRAMEWORK
TARGET_IDENTIFICATION
PREFERENCES
APPLICATION_ATTACH
DEX_ENGINE
RESOLVER_CACHE
CORE_COMPONENTS
ESSENTIAL_HOOKS
OPTIONAL_HOOKS
RUNTIME_VERIFICATION
READY
~~~

### M03.02 Criticality
Every component declares CORE / ESSENTIAL / OPTIONAL / EXPERIMENTAL.

### M03.03 Remove DexKit global early-return
DexKit failure records DEXKIT_INIT_FAILED but preserves diagnostics and safe non-DexKit paths.

### M03.04 Initialize health before fragile work
Heartbeat and health store must start before DexKit/resolver work.

### M03.05 ComponentResult
Every stage returns status, duration, failure code, fallback and dependency data.

### M03.06 Optional isolation
Optional feature exception disables itself only.

### M03.07 Essential degradation
Essential failure produces DEGRADED unless continuing would corrupt state.

### M03.08 CORE review
Every CORE classification requires review justification.

### M03.09 Idempotency
Repeated callbacks cannot install duplicate hooks or receivers.

### M03.10 Startup timings
Measure preferences, DexKit, resolver cache, resolver groups, components, hook installation and total READY time.

### Gate M03
DexKit failure is diagnosable, optional failures are isolated and bootstrap states are deterministic.

# M04 — Legacy stabilization

### M04.01 Finalize exact legacy loader policy
CI must verify the actual compiled API version and manifest loader declaration.

### M04.02 Contain legacy preference path
Document and test current XSharedPreferences/world-readable compatibility without expanding its use.

### M04.03 Broadcast hardening
Verify package targeting, signature permission, receiver flags and lifecycle.

### M04.04 Resource-hook inventory
Map every legacy resource injection path.

### M04.05 Device/target legacy matrix
Android 14–17, WhatsApp, Business.

### M04.06 False-negative regression gate
The original activation problem must be fixed on legacy before modern migration starts.

### M04.07 Performance baseline
Record p50/p95 startup and DexKit times.

### Gate M04 — Gate B
Legacy path stable, no false disabled state, tests green, health trustworthy.

# M05 — Packaging and R8 transition readiness

Important execution correction: packaging readiness occurs before enabling the modern entry because current META-INF/** exclusion would otherwise remove the modern loader files.

### M05.01 Replace blanket META-INF exclusion
Keep only explicit safe exclusions.

### M05.02 Preserve modern metadata
Test final APK contains:
- META-INF/xposed/java_init.list
- META-INF/xposed/scope.list
- META-INF/xposed/module.prop

### M05.03 Final-APK verifier
Inspect built APK contents, not only source files.

### M05.04 R8 rules
Protect/adapt modern entry metadata and entry constructor.

### M05.05 Dual-mode loader checker
Support explicit legacy and modern checker modes and reject ambiguous mixed packages.

### M05.06 Signed-release verification
Run the same loader metadata gate on the distributed APK.

### Gate M05
CI can prove a valid legacy package and a valid modern package independently.

# M06 — Modern libxposed API 102 migration

### M06.01 Pin verified artifacts
Re-verify and pin:
- io.github.libxposed:api:102.0.0 as compileOnly;
- io.github.libxposed:service:102.0.0 only where service communication is needed.

### M06.02 Modern entry
Implement XposedModule entry.

### M06.03 Modern metadata
Add java_init.list, scope.list and module.prop.

### M06.04 Framework state
Use modern framework/service evidence instead of self-hook truth.

### M06.05 Remote preferences
Move away from world-readable preference dependency.

### M06.06 WA X hook adapter
Feature code should depend on a WA X abstraction, not scatter legacy/modern API calls everywhere.

### M06.07 No accidental dual loader
Do not publish a stable APK declaring competing legacy and modern contracts.

### M06.08 Migration waves
Migrate canary → core → simple feature → resolver-heavy feature → remaining groups. Each wave gets smoke-tested.

### M06.09 Hot reload policy
Default disabled until lifecycle and idempotency are proven.

### M06.10 Remove self-hook authority
Only after modern framework detection is verified.

### Gate M06 — Gate C
Modern entry loads, framework state works, preferences work, WhatsApp and Business inject, metadata survives signed packaging.

# M07 — ResourceBridge and Android 17-safe resources

### M07.01 Create shared bridge
ResourceBridge, ModuleResources, TargetResourceResolver, ResourceCompatibilityLayer.

### M07.02 Migrate legacy resource hooks
Every XModuleResources/resource-init use gets an explicit replacement.

### M07.03 Eliminate static-final R mutation
No reflective writes to generated resource fields.

### M07.04 Runtime resource resolution
Typed success/failure, target-aware.

### M07.05 Prefer module context/vector/assets
Do not mutate target resource classes when a local asset path works.

### M07.06 Per-feature isolation
Missing resource mapping disables only that visual feature.

### M07.07 Regression scanner
CI prevents reintroduction of static-final resource mutation.

### Gate M07 — Gate D
No legacy resource mutation remains on the modern release path.

# M08 — DexKit 2.3.0 and Resolver architecture

### M08.01 Replace local AAR after proof
Use pinned Maven org.luckypray:dexkit:2.3.0 after API/license/build verification.

### M08.02 DexEngine
Centralize DexKit lifecycle.

### M08.03 ResolverRegistry
Each resolver declares ID, dependency, target/version, confidence, cache and fallback.

### M08.04 Cache key
package, version, APK fingerprint, dex fingerprint, ABI, SDK, module version, resolver version.

### M08.05 Fingerprint validation
Never reuse cache across mismatches.

### M08.06 FallbackChain
Exact → heuristic → explicit legacy fallback.

### M08.07 ResolverHealth
Track exact/likely/fallback/failed/incompatible plus timing.

### M08.08 Lazy scanning
No scan for disabled features or valid cache hits.

### M08.09 Bound concurrency
Deterministic native bridge lifecycle.

### M08.10 Startup budget
Optional long resolution leaves the critical path.

### M08.11 Corruption recovery
Invalidate bad cache and retry once without loops.

### Gate M08 — Gate E
DexKit and individual resolver failures are isolated and measurable.

# M09 — Toolchain upgrades as atomic PRs

### M09.01 AGP 9.4.0
Keep targetSdk 34 for this PR.

### M09.02 Kotlin 2.4.20
Separate PR.

### M09.03 AndroidX groups
Update small coherent groups only after upstream verification.

### M09.04 Dependency verification
Update checksums/locks intentionally.

Forbidden mega-commit:

~~~text
AGP + Kotlin + targetSdk + DexKit + libxposed
~~~

### Gate M09 — Gate F
Every toolchain change independently passes the full pre-targetSdk gate.

# M10 — targetSdk 37 hardening

### M10.01 Static-final audit
Must already be clean.

### M10.02 Broadcast/receiver flags
Audit exported state, signature permissions and explicit targeting.

### M10.03 Background Activity Launch
Audit Activity/IntentSender launch paths.

### M10.04 Background audio
Audit call recording, playback and transcription service behavior.

### M10.05 Storage/media
SAF, scoped storage and media permission behavior.

### M10.06 Package visibility
Revalidate queries and target/framework detection.

### M10.07 PendingIntent
Mutability and explicit targeting.

### M10.08 Local network permission
Only request if an actual WA X LAN feature needs it.

### M10.09 Contacts Provider
Audit Android 17 CP2 assumptions.

### M10.10 RemoteViews and notification memory
Verify custom views/icons stay below Android 17 limits.

### M10.11 Native dynamic loading
Dynamically loaded native files must satisfy Android 17 read-only requirements.

### M10.12 Large screens/foldables
Test mandatory resize/orientation behavior.

### M10.13 Sensitive content
Use supported secure-window behavior where needed.

### M10.14 TLS/CT/ECH
Verify networking assumptions.

### M10.15 API matrix
Test API 28, 30, 33, 34, 35, 36 and 37.

### Gate M10 — Gate G
targetSdk 37 passes behavioral tests without runtime/resource regressions.

# M11 — Strict CI, qualityGate and releaseGate

### M11.01 qualityGate
Aggregate:
- spotlessCheck;
- lintRelease;
- detekt;
- testReleaseUnitTest;
- assembleRelease;
- resource verification;
- loader metadata verification;
- APK contents;
- compatibility data;
- version;
- signing when keys are available.

### M11.02 Collect-all reporting
Collect as many failures as possible in one run, then fail final status.

### M11.03 Warning ratchet
First zero new warnings, then burn down existing baselines. Modernization-touched code must end warning-clean.

### M11.04 Instrumentation tests
Required for lifecycle/UI/runtime flows not proven by unit tests.

### M11.05 APK structure verification
Loader metadata, output count, ABI set, resource integrity.

### M11.06 Strict dependency verification
Checksums/locks required.

### M11.07 SBOM
Generate release SBOM.

### M11.08 Security scans
CodeQL, dependency audit, secret scan.

### M11.09 Reproducibility
Two clean builds of one revision must produce equivalent verified output.

### M11.10 Signing
Verify certificate fingerprint on every distributed APK.

### M11.11 Device smoke
Minimum device/target matrix before release.

### Gate M11 — Gate H
qualityGate and releaseGate are mandatory blockers.

# M12 — Compatibility registry, kill switches, watchdog and telemetry

### M12.01 CompatibilityRegistry v2
Signed data-only version/fingerprint/resolver/feature compatibility metadata.

### M12.02 Signature validation
Reject invalid or unsafe registry updates.

### M12.03 Feature kill states
ENABLED / DISABLED / DEGRADED / EXPERIMENTAL / UNSUPPORTED.

### M12.04 Circuit breaker
Repeated feature/resolver failures disable only the affected path/session.

### M12.05 Runtime watchdog
Crash loops, repeated hook/resolver failures, startup timeout, ANR-like stalls.

### M12.06 Local telemetry
Allowed: session/version/SDK/feature/resolver/failure/duration/stack hash.
Forbidden: phone/JID/messages/contact names/tokens/API keys/private payloads.

### M12.07 Performance budgets
Core bootstrap, DexKit, cache, resolver groups, hook install and total READY time.

### M12.08 Integrate existing backlog
This phase is the shared implementation foundation for F154, F155, F157, F158, F159, F160, F161, F164, F165, F166 and F184. Do not duplicate them.

### Gate M12 — Gate I
A compatibility regression is diagnosable and feature-isolated without full WA X failure.

# M13 — Full validation, rollback, rollout and legacy removal

### M13.01 Device matrix
Android 14, 15, 16, 17.

### M13.02 Framework matrix
Supported stable LSPosed plus newest supported libxposed API 102 implementation.

### M13.03 Target matrix
WhatsApp stable, WhatsApp beta, WhatsApp Business stable.

### M13.04 Lifecycle matrix
Clean install, upgrade, supported downgrade, reboot, framework restart, scope remove/add, module disable/enable, force-stop, process kill, multi-account, target update, corrupt cache, missing permission, Safe Mode, rollback.

### M13.05 Last-known-good bundle
Keep tag, commit, dependency locks, compatibility snapshot, resolver snapshot, signed APK, signing fingerprint, SBOM and releaseGate report.

### M13.06 Real rollback drill
Rollback must be performed and verified.

### M13.07 Remove transition debt
After modern is proven:
- remove legacy entry;
- remove legacy API dependency;
- remove self-hook activation logic;
- remove world-readable preference compatibility;
- remove legacy resource bridge;
- remove transition-only flags/checks.

### M13.08 Release channels
Nightly for exploration, beta after releaseGate, stable only after full matrix and rollback drill.

### FINAL gate
All required:

~~~text
No false disabled state
No ambiguous LSPosed errors
Modern libxposed API 102 active
Modern metadata present in signed APK
No legacy self-hook dependency
No world-readable preference dependency
No reflective static-final R mutation
DexKit isolated
Resolver failures isolated
Feature failures isolated
targetSdk 37 validated
Android 17 validated
Strict CI mandatory
Zero new warnings
Reproducible release
Signing verified
Rollback drill passed
Signed compatibility registry
Exact diagnostic stage shown in Home
~~~

Only after this gate may normal F003–F254 implementation resume.

## 5. Required diagnostics UI

Cards:
Framework, Module, Scope, WhatsApp, WhatsApp Business, Injection, Bootstrap, Preferences, DexKit, Resolvers, Core hooks, Optional features, Resource bridge, SELinux, Root/framework bridge, Version compatibility, Watchdog, Last-known-good.

Every card shows:
- status;
- last update;
- session;
- duration where relevant;
- failure code;
- details;
- suggested action.

One card may never infer another subsystem's failure.

## 6. Pull request discipline

Every phase PR must include:
- exact task IDs;
- before/after state;
- risk;
- changed files;
- migration effect;
- rollback method;
- tests;
- device/target evidence;
- all warnings/errors;
- performance delta;
- compatibility delta;
- known limitations.

Rules:
- one architectural concern per PR;
- no drive-by refactors;
- no unrelated dependency bumps;
- no direct merge to master;
- no bypassing required checks;
- loader/runtime PRs attach sanitized runtime snapshots for WhatsApp and Business.

## 7. Recommended branch sequence

~~~text
master
priority/lsposed-modernization
modernization/runtime-health
modernization/activation
modernization/bootstrap
modernization/legacy-stabilization
modernization/packaging
modernization/libxposed-102
modernization/resource-bridge
modernization/dexkit-resolvers
modernization/toolchain
modernization/target-sdk-37
modernization/ci-gates
modernization/compat-watchdog
~~~

## 8. Immediate first execution slice

The first execution slice is fixed:

~~~text
M00.01 baseline marker
M00.02 current build evidence
M00.03 legacy loader version-policy fix
M00.04 negative loader self-tests
M00.05 legacy mechanism inventory
M01.01 health states
M01.02 RuntimeHealthSnapshot
M01.03 FailureCode
M01.04 HealthEvent
M01.05 RuntimeHealthStore
M01.06 HealthReporter
M01.07 redaction tests
Gate M01
M02 activation/heartbeat
~~~

No library migration is allowed before this slice is green.

## 9. Definition of program success

WA X must be able to answer with evidence:

~~~text
Is the framework available?
Is the module enabled?
Is scope correct?
Is the target running?
Was the target injected?
Which bootstrap stage is active?
Did preferences load?
Did DexKit initialize?
Which resolvers succeeded?
Which hooks installed?
Which features degraded?
What failed?
Which session failed?
When did it fail?
What action should the user take?
~~~

A failure in one optional component must never become a false statement that LSPosed is disabled.
