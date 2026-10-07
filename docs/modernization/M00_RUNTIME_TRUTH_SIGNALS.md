# M00.07 — Runtime truth signals and UI mapping

Issue: [#319](https://github.com/Alaa91H/WA-X/issues/319) · Program: LSPosed Modernization

Read-only. This records what the module currently claims to know about its own state and where
that claim is wrong. It fixes nothing: the wiring belongs to M01, and doing it here would
pre-empt the phase that owns the design.

**One finding in this document contradicts the M00 audit and changes M01's scope, so it is
first.**

---

## The runtime health architecture is already ~70% built and nobody is using it

The M00 audit lists as open work: *"Runtime health is a single Boolean `isXposedEnabled`."* That
is true of the **UI**. It is not true of the **runtime**, which already has a structured health
model that is simply not connected to anything the user can see.

`com.wax.module.resolver`:

| Type | Lines | What it already does |
| --- | --- | --- |
| `FeatureHealth` | 7 states | `HEALTHY`/`DEGRADED`/`FALLBACK`/`DISABLED`/`INCOMPATIBLE`/`FAILED`/`UNKNOWN`, with `isRunning` and `isNotable` |
| `FeatureOutcome` | | per-feature result |
| `FeatureInstaller` | | installation |
| `ResolverRegistry` / `ResolverCacheKey` / `Resolution` / `Confidence` / `FallbackChain` / `ResolverDiagnostics` | | resolution, caching, fallback, diagnosis |
| `UserExplanation` | | `FailureCode` → user-facing sentence; `shouldTellUser(FeatureHealth)` |

`com.wax.module.diagnostics`:

| Type | What it already does |
| --- | --- |
| `FailureCode` | 12 stable machine-readable codes, contract documented as append-only |
| `FeatureFailureReport` | structured record, **redacted at construction**, cannot be built containing an identifier |
| `FailureReportStore` | persists to one local JSON file, 50-report cap, whole-file rewrite so a crash cannot corrupt it |
| `FailureReportCodec` / `FailureReportParser` | encoding and parsing |
| `ReportRedactor` | bounds and redacts every free-text field |

`FeatureLoader` wires the feature-level half: `recordFailure(...)` on startup and per-plugin
failure, `attachStore(application)` once a context exists, and a dialog on WhatsApp's
`HomeActivity` that renders every collected report through `FailureReportCodec.renderText`.

The docstring on `FeatureHealth` states the intent in exactly the terms the program uses:

> These states are deliberately distinguishable from each other: "running with an older code
> path" and "not running at all" call for different user actions, so they must not collapse into
> a single "disabled".

**So M01 is a wiring and extension job, not a build.** Per the program's own rule — *do not
rewrite what already works; extract, isolate, formalise* — the existing types are the extraction
target. The parts that genuinely do not exist yet are the **runtime-level** states (framework,
module, scope, target process, injection, preferences, overall) and the **channel** that carries
them from the injected process to the Manager.

---

## What the user actually sees today

One Boolean, two mutually exclusive renderings:

```kotlin
// ModuleApplication.kt:84
fun isXposedEnabled(): Boolean = System.currentTimeMillis() == 0L
```

`System.currentTimeMillis()` has never returned `0` since 1970, so unhooked this is
**unconditionally `false`** — the placeholder the audit already named. The only way it returns
`true` is the hook:

```kotlin
// ModuleEntryPoint.kt:105, inside hookSelf()
XposedBridge.hookAllMethods(clazz, "isXposedEnabled", XC_MethodReplacement.returnConstant(true))
```

`hookSelf` is reached from exactly one place — `ModuleEntryPoint.kt:59`:

```kotlin
if (packageName == BuildConfig.APPLICATION_ID) {
    hookSelf(classLoader)
    return
}
```

So:

```
isXposedEnabled() == true
  ⟸ hookSelf ran
  ⟸ LSPosed loaded the module into WA X's OWN process
  ⟸ LSPosed has WA X in its own scope for WA X
```

**The banner means "LSPosed is running and has WA X in its own scope."** It cannot be affected by
WhatsApp's scope, the target process, injection, preferences, DexKit, resolvers, or any feature.
The comment at `ModuleEntryPoint.kt:54` is candid that this is the mechanism.

Rendering — `HomeFragment.checkStateWpp()`, `ui/fragments/HomeFragment.kt:388`:

| Signal | Icon | Title | Summary | Background |
| --- | --- | --- | --- | --- |
| `true` | `ic_round_check_circle_24` | `R.string.module_enabled` | `v<VERSION_NAME>` | `gradient_success` |
| `false` | `ic_round_error_outline_24` | `R.string.module_disabled` | `GONE` | `gradient_error` |

Called on view entry. Nothing re-runs it when runtime state changes underneath.

---

## The precise defect: `RESOLVER_INIT_FAILED` is unreachable

This is the sharpest finding in M00, and it is not "everything says LSPosed is disabled".

`FailureCode.RESOLVER_INIT_FAILED` is documented as:

> The module could not initialise its own DexKit or reflection state.

and `UserExplanation` translates it for the user:

> "WA X could not read WhatsApp's internals at all"

Both were written, named, documented as a stable contract, and wired to **nothing**:

- `FailureCode.classify()` has no branch that returns it. It returns `CLASS_NOT_FOUND`,
  `MEMBER_NOT_FOUND`, `ACCESS_DENIED`, `TIMEOUT`, `RESOLVER_AMBIGUOUS`, or `UNEXPECTED`. Never
  `RESOLVER_INIT_FAILED`.
- No production code constructs a report with that code.

Meanwhile the exact situation it describes is handled like this — `FeatureLoader.kt:218`:

```kotlin
if (!Unobfuscator.initWithPath(sourceDir)) {
    XposedBridge.log("Can't init dexkit")
    return
}
```

`Unobfuscator.initWithPath` itself swallows the cause and returns a bare `false`:

```kotlin
fun initWithPath(path: String): Boolean =
    try {
        bridge = DexKitBridge.create(path)
        true
    } catch (_: Exception) {
        false
    }
```

### Why it cannot report there

This is the root cause, and it is structural rather than an oversight:

`recordFailure` persists through `FailureReportStore`, which is constructed from an `Application`
(`FailureReportStore(application)`), and `attachStore` runs inside
`Instrumentation.callApplicationOnCreate`. `FeatureLoader.start()` runs **before** that. So at the
moment DexKit fails there is no application context, no store, and no channel — the failure is
logged to the Xposed log and returns.

### What the user experiences

1. WhatsApp starts. DexKit fails. `start()` returns. **Zero hooks are installed** — not a degraded
   runtime, an empty one.
2. Nothing is logged to a place the user can reach. `XposedBridge.log` goes to the LSPosed log.
3. No failure dialog: `getFailureReports()` is empty because nothing was recorded.
4. Opening the WA X app shows **"Module enabled"** with a version number, because the self-hook
   fired in a different process and knows nothing about WhatsApp.
5. Every feature is silently absent.

The user has a green status banner, an app that reports itself healthy, and a WhatsApp where
nothing works. That is the false-green, and it is far more damaging than a wrong "disabled"
message: it removes the user's only reason to look.

### Two corrections to the M00 audit

- The audit's *"FeatureLoader exits early if DexKit init fails"* is **correct** and is the
  mechanism above.
- The program directive's *"a DexKit, resolver or feature problem must not become the message
  `LSPosed is disabled`"* is **not** what happens. Nothing reports a DexKit failure at all, so no
  wrong message is produced — the failure is simply absent. Feature and resolver failures *are*
  reported correctly, through `FeatureFailureReport`, and surfaced in a WhatsApp dialog. The
  problem is the one case with no reporter, not a mislabelled one.

---

## Two latent defects in the same file, found while tracing it

### `modulePath` is nullable and unguarded at the point of use

```kotlin
private var modulePath: String? = null                                       // :27
override fun initZygote(startupParam: …) { modulePath = startupParam.modulePath }   // :182, only assignment
override fun handleInitPackageResources(resparam: …) {
    val modRes = XModuleResources.createInstance(modulePath, resparam.res)   // :133
}
```

`createInstance`'s first parameter is a Java platform type, so Kotlin accepts `null`. If
`initZygote` has not run, resource injection is handed a null module path.

### Resource injection reports nothing when it fails

```kotlin
} catch (_: Exception) {
}                                                    // :174-175
```

The loop that walks `R.array`, `R.string` and `R.drawable` and rewrites their static `int` fields
swallows every exception. Failure means injected resources silently do not apply, while the banner
reads "enabled". One of the 54 empty catch bodies counted under `silent_catch`.

Both belong to M03 and M07. Recorded here because M01's design has to tolerate them: a health store
whose own write path can throw is not a health store.

---

## Verified against the built APK, not just the sources

Read from `app/build/outputs/apk/debug/WA-X-1.1.0-dev+608C7EE1.apk` with `aapt2 dump xmltree` and
a zip listing, because an audit assumption about packaging turned out to be wrong:

| Check | Result |
| --- | --- |
| `assets/xposed_init` present | yes |
| `META-INF/xposed/` directory present | no |
| `xposedmodule` | `true` |
| `xposedminversion` | `93` |
| `xposedsharedprefs` | `true` |
| `xposedscope` | present |
| APK size | 74.7 MB |

### `packaging excludes META-INF/**` does not exclude all of META-INF

`app/build.gradle.kts` declares `excludes += "META-INF/**"`, and the APK nonetheless contains:

```
META-INF/com/android/build/gradle/app-metadata.properties
META-INF/services/java.security.Provider
META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler
META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory
```

`packaging.resources.excludes` governs the Android resources/Java-resources merge path, and these
entries reach the APK by routes it does not cover. The audit line *"packaging currently excludes
META-INF/\*\*"* is accurate as a declaration and misleading as a fact.

This changes how M05 has to be done. The plan assumes M06's blocker is removing an exclude so
`META-INF/xposed/java_init.list` can be packaged. The evidence says the exclude is not what keeps
those files out, and that `src/main/resources/META-INF/...` would most likely be packaged anyway.
So the requirement is an **APK-content assertion in CI, against the built artifact** — which the
program directive already demands and which nothing currently does. M05 owns building it. Recorded
here because the assumption M05 would otherwise start from is wrong.

---

## What M01 has to build, given all of the above

| Already exists | Missing |
| --- | --- |
| `FeatureHealth` 7 states | Runtime-level states: framework, module, scope, target process, injection, preferences, DexKit, overall |
| `FailureCode` 12 codes | A reachable `RESOLVER_INIT_FAILED`, and a pre-`Application` reporting channel |
| `FeatureFailureReport` + store | Boot/session identity: `bootId`, `moduleSessionId`, `targetSessionId` |
| Redaction by construction | Build context in the report: `pid`, `moduleVersion`, `targetVersion` |
| `UserExplanation` mapper | A Manager-side consumer — the banner still reads `isXposedEnabled` |
| Resolver cache key + diagnostics | Resolver failure isolation: one resolver must not stop the runtime |
| Feature-level dialog in WhatsApp | The same information when WhatsApp never starts |

## Regression fixture for M00.09

Written so it does **not** become a specification of the bug. A test asserting
`isXposedEnabled() == false` unhooked would be correct and would pin the defect in place, making
M01 fail it on purpose.

| Case | Asserts | Still valid after M01? |
| --- | --- | --- |
| `initWithPath` failure discards the cause and returns bare `false` | structural | No — M01 makes it report. Replaced, not contradicted |
| `start()` returns before any report can exist | structural | No — replaced |
| **`hookSelf` is reachable only when `packageName == APPLICATION_ID`** | **the invariant** | **Yes** — the self-hook must stop being the activation signal, and this is what notices if it does not |
| `checkStateWpp` branches on `isXposedEnabled` alone | documents the collapsed signal | M01 replaces the branch; the test moves with it |
| `FailureCode.RESOLVER_INIT_FAILED` is declared but never produced | **the actual defect** | No — M01 fixes it, and the test must flip |

The two cases marked in bold are the ones with lasting value: one pins the invariant the whole
program is trying to remove, the other pins the precise bug being fixed.

## Next

M00.09 writes the fixture above. M01 then extracts runtime-level health onto the existing
`FeatureHealth`/`FailureCode` model rather than beside it, makes `RESOLVER_INIT_FAILED`
reachable, and gives the Manager a channel to read it.