# A01 — Explicit Manager / Runtime contracts

Issue: [#335](https://github.com/Alaa91H/WA-X/issues/335) · Program: Architecture sustainability

This is the phase that makes the claim *"a runtime feature can be unit-tested"* true, by giving a
feature something other than a `SharedPreferences` and a class loader to be written against.

---

## What was wrong

One abstract class was the entire feature contract:

```kotlin
abstract class Feature(
    @JvmField val classLoader: ClassLoader,
    @JvmField val prefs: SharedPreferences,   // android.content.SharedPreferences
) {
    abstract fun doHook()                      // returns Unit
    abstract fun getPluginName(): String
}
```

Sixty-four features extend it. Every one of them is constructed reflectively from
`(ClassLoader, SharedPreferences)` and started by calling `doHook()`. Three consequences, all
measurable:

| | Measurement |
| --- | --- |
| A platform type in the contract | 64 feature constructors take `SharedPreferences` |
| The hooking mechanism has no seam | 57 files import `XC_MethodHook`, 54 import `XposedBridge` |
| A feature cannot reach its dependencies | 52 files call `Unobfuscator` directly, 227 calls, `Others.kt` 37 |
| Logging cannot be asserted | `Feature.log`/`logDebug` call `XposedBridge.log`/`android.util.Log`; 190 call sites |
| A failure cannot be reported by a feature | no feature constructs a failure report at all |
| **The cost** | the feature layer sits at **zero unit coverage** — 1215 classes |

`doHook()` returning `Unit` is the other half of it: "installed", "not applicable to this build",
"installed but reduced" and "failed" were the same event, and only a throwable made any of them
visible.

---

## What is declared

`com.wax.module.contract`, nine types, no Android, no Xposed, no DexKit — enforced, not promised:

| Contract | Why it exists |
| --- | --- |
| `WaFeature` | `featureId` + `start(context): FeatureStartResult`. No preferences in a signature. |
| `FeatureContext` | the six capabilities a feature is allowed to reach for, plus the target class loader passed in rather than read from a global. |
| `FeatureStartResult` | `Installed` / `Degraded(lost)` / `Skipped(missing)` / `Failed(code)` — the four outcomes `Unit` could not express. |
| `HookEngine` | installs a hook, returns whether it installed, counts what it installed. |
| `CapabilityProvider` | `hasClass`, `findClass`, target version, SDK level, and `resolutionAvailable()`. |
| `DiagnosticSink` | a feature records its own failure, with the report redacted on the way in. |
| `RuntimeClock` | `nowMillis` for dating, `elapsedRealtimeMillis` for measuring — not interchangeable. |
| `RuntimeLogger` | `info` / `debug` / `error`, so debug output can be dropped centrally. |
| `SettingsSnapshot` | **already existed** and is used as-is. It is immutable, target-bound and Android-free. |

Three decisions worth stating because they were not the obvious ones:

- **`SettingsSnapshot` was not redefined.** It already exists in `com.wax.module.settings` with one
  production implementation and its own tests. Moving it would be churn across a package boundary
  that is not the point. `TargetSettingsBridge.snapshotFor` now produces one from the *scoped*
  preferences, which is what makes handing it to a feature safe: reading the raw delegate instead
  would give a Business process WhatsApp's overrides.
- **`ClassLoader` stays.** Reflecting on the target's classes is the feature's actual job, and it
  is a JDK type. The gain is that it is *passed in*, so a test controls it.
- **There is no `Context`.** A feature that wants a dialog is asking for something the injected
  runtime cannot honestly provide; the answer is a `DiagnosticSink` record plus the target's own
  UI. The absence is the constraint that keeps features testable.

## The adapters

`com.wax.module.xposed.contract` is where the framework is allowed to appear:

`XposedHookEngine`, `XposedRuntimeLogger`, `SystemRuntimeClock`, `FrameworkCapabilityProvider`,
`RuntimeDiagnosticSink`, and `RuntimeFeatureContexts` — which builds the one context a hooked
process hands to its features. It is built once per process rather than once per feature, because
its six capabilities are process-scoped, and a feature that hooked through one context and read
the clock through another would be relying on two identities for one process.

`RuntimeDiagnosticSink` routes to `FeatureLoader.recordFailure`, which stays private: the seam
exists so a feature can reach the funnel without the funnel becoming public API.

## The fakes

`Fakes` in the contract package, hand-written rather than generated — a generated double asserts
against a signature, and the point of these is to be the *opposite* of the real thing.
`RecordingHookEngine` records what it was asked to do and installs nothing; a test makes a hook
succeed or fail by declaring a class or not.

---

## The strict gate

> Runtime features can be unit-tested with fakes without a real Xposed framework.

`FeatureContractTest.aFeatureWrittenAgainstTheContractRunsOnAJvmWithFakes` constructs a real
feature class from `main`, hands it fakes and a *real* `SettingsSnapshot` over an in-memory store,
and starts it. No framework, no device, no `android.jar` on the classpath. If that compiles and
runs, the gate holds.

`tools/quality/check_feature_contracts.py` is the CI-wired half, because the claim is not visible
in a signature — a platform type can appear in a constructor parameter and nothing fails at the
point of declaration. It checks four things, with `test_check_feature_contracts.py` proving each
can fail:

1. the contract package imports nothing platform-facing;
2. a contract file does not *mention* a platform type — a fully qualified name leaks as much as an
   import, and documentation that names one is not a violation;
3. a production adapter exists, so "the contracts are clean" is not true because nobody
   implemented them;
4. every contract the issue requires is declared.

---

## The pilot, and what is left

`DebugFeature` is migrated: it implements `WaFeature`, takes no `SharedPreferences`, appears in the
same installed set and is started through the same stage. It was chosen because it does nothing,
so if the migration is wrong the only thing that can be wrong is the migration.

The other **63 features still extend `Feature`**, and the installer says so explicitly rather than
pretending otherwise: `startContractFeature` dispatches on the instance and constructs a legacy
feature reflectively when that is what the class is. Pretending the migration were finished would
make this phase look better than it is.

`AE-02` (`feature_shared_preferences`, limit 64) is the number that has to fall, and it belongs
to **#342 A08** — typed settings, DataStore and snapshots — because that is where the replacement
for `SharedPreferences` in the feature layer is designed. This phase makes the migration possible
and measures it; it does not own the other 63.

---

## What this does not do

- **It does not migrate the other 63 features.** Their `prefs` reads are A08's work and the gate
  above is what will let that be done one file at a time without regressing anything.
- **It does not clear AE-03 or AE-05.** The research for this package measured them precisely —
  AE-03 is 3 files (`IGStatusAdapter`, `TargetSettingsBridge`, `WallpaperView`); AE-05's
  layer-granular measure counts 30 files across five layers for **14 real offenders**, because
  the measure attributes a layer's edges to every file in it. Clearing them is real refactoring of
  fourteen Manager and shared-layer files and is filed separately with the file list, because it
  is separable from the contracts and carries behaviour risk that belongs in its own change.
- **It does not abstract member resolution.** `CapabilityProvider` answers "does this build have
  this class"; anything deeper belongs to the resolver layer, which is already pure and is M08's
  and A04's work. A feature that needs more than this is asking for something that has not been
  abstracted yet, and that is now visible rather than hidden.

## Testing status

- 1290 unit tests, none failing. Every clause of the strict gate is a test.
- The contract checker has 10 self-test cases, each proving one defect it must catch.
- `spotless`, detekt and lint clean; every coverage counter above its floor.
- The pilot feature has no observable behaviour, so nothing about the module's behaviour changed.