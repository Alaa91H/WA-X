# A03 — One feature registry

Issue: [#337](https://github.com/Alaa91H/WA-X/issues/337) · Program: Architecture sustainability

The strict gate:

> Exactly one authoritative feature registration source; reflective feature construction = 0.

Both halves are asserted in `FeatureRegistryTest`, and both are re-checked in CI by
`tools/quality/check_feature_registry.py`.

---

## The duplication, measured

There were two lists of the module's features, and nothing compared them:

| | Where | Kind |
| --- | --- | --- |
| The runtime list | `FeatureLoader.plugins()` — `val classes = arrayOf(DebugFeature::class.java, …)` | hand-maintained, 64 entries |
| The compatibility list | `tools/compatibility/derived_facts.json` → `compatibility.json` `derived.features` | extracted from source by `extract_features.py`, 64 entries |

They held **the same 64 ids in the same order**. That is the part worth stating carefully: the
failure this package prevents is not that the two disagree today, it is that they *can* disagree
with no signal. A feature added to one list and not the other would have shipped a module that
installs a different set of features than the compatibility matrix claims to describe — a
compatibility claim about code that is never loaded, discovered by a user on a build nobody tested.

And the second list was not even independent: `extract_features.py` did not scan for features, it
**parsed the array inside `FeatureLoader.kt`** to get their order. So the matrix was derived from
the runtime list, and the "second source" was a copy of the first with different facts attached.

## What replaced it

`com.wax.module.xposed.registry`:

- **`FeatureFactory`** — a sealed interface with two implementations, `Contract` (a `WaFeature`,
  constructed with nothing) and `Legacy` (the old `Feature(classLoader, prefs)`). Sealed is
  load-bearing: the loader starts a feature without branching on a kind, because a third kind cannot
  be added without the compiler forcing every caller to handle it.
- **`RuntimeFeatureRegistry.entries`** — 64 entries, in install order. The id is the class's simple
  name by construction, so a rename cannot leave a log line, a failure report and a compatibility
  cell describing three different features.

`FeatureLoader.plugins()` iterates the registry. The array is deleted, not commented out: two lists
that currently agree are one edit away from not agreeing, and a commented-out list is a list nobody
deletes.

## Reflective construction is gone

Three sites, all in `FeatureLoader.kt`:

| Before | Now |
| --- | --- |
| `clazz.getDeclaredConstructor().newInstance()` | `FeatureFactory.Contract("…") { DebugFeature() }` |
| `clazz.getConstructor(ClassLoader::class.java, SharedPreferences::class.java).newInstance(…)` | `FeatureFactory.Legacy("…") { loader, prefs -> MinorFixes(loader, prefs) }` |
| a **second** `getConstructor(...)` inside the plugin loop lambda | — it was the same lookup again |

The third one was a live defect, not just duplication: `startContractFeature` constructed the
legacy feature reflectively and then handed it to a lambda that constructed *another* one from the
same arguments, calling `doHook()` on the second. Two instances per feature, one of them thrown
away, and no test could have noticed.

Why the factories are worth it beyond the gate: a constructor looked up by parameter type fails at
runtime with a `NoSuchMethodException` that names neither the feature nor the reason. A lambda that
names the constructor fails to **compile** when the constructor changes, at the entry that has to
change with it.

The legacy path takes its class loader from `FeatureContext.targetClassLoader` rather than a
separate parameter, because it is the same object the reflection used — and taking it from the
context means a feature cannot be constructed against one target's loader and started with another's.
`plugins()` consequently lost a parameter it no longer used, which detekt caught.

## One failure code that was being thrown away

`recordContractFailure` took a `FailureCode` from the feature, built a throwable from a message, and
then wrote the code into `contractFailureCodes[timestamp]` — a `ConcurrentHashMap` that nothing ever
read. The report therefore carried a code re-derived from an exception message, not the code the
feature declared.

`FeatureFailureReport.fromThrowable` now takes an optional `code` that overrides the
classification, `recordFailure` passes it through, and the map is deleted. A feature that reports
`REQUIRED_CLASS_MISSING` now has that in the diagnostics dialog instead of whatever the classifier
made of its message.

## The gates

**`FeatureRegistryTest`** (8 tests, in the unit suite so they run where the others do):

- ids are unique;
- every entry has a factory, and every id is a class the registry actually constructs;
- the registry and `compatibility.json`'s derived list are **the same list in the same order**;
- the extractor reads `RuntimeFeatureRegistry.kt` and no longer reads the loader — asserting the
  *direction of the dependency*, not just its result;
- neither the loader nor the registry contains `getConstructor` or `getDeclaredConstructor`;
- `DebugFeature` is registered as a contract feature and the other 63 are legacy, asserted by name.

**`check_feature_registry.py`** + 9 self-test cases: an unregistered feature class, a duplicate id,
derived drift (naming which side has what), reflective construction, and an extractor that has gone
back to parsing the loader. The self-test also runs against the real tree, which is what stops the
checker being correct on fixtures and wrong on the repository.

## What is deliberately *not* in the registry

`preferenceKeys`, `resolutionTier`, `resolverDependencies` and `category` are facts about a feature's
**body**, and `extract_features.py` derives them from source. Writing them into the registry by hand
would create a second list of the same facts that could disagree with the first — the exact problem
this package exists to close, one layer down.

So the dependency now runs one way: the extractor reads the registry for *which features exist and in
what order*, and derives the per-feature facts from the feature files themselves. The two views of a
feature agree because one is computed from the other, and `check_feature_registry.py` fails when they
do not.

## KSP, and why it is not here

The issue also asks for a `@WaFeature` annotation and a KSP-generated registry index. That is not in
this package, and the reason is worth recording rather than leaving as a silent omission:

- KSP is already in the build (Room uses it), so the toolchain is not the obstacle. The obstacle is
  that generated code has to come from **annotations on 64 feature classes**, and those 64 files are
  the ones #342 A08 is already rewriting — moving their constructors off `SharedPreferences` is the
  same edit surface.
- Doing it now means touching all 64 files for a codegen change that is orthogonal to the problem,
  which would make the reflection-removal diff unreviewable.
- The part of the benefit that is real — one authoritative list that cannot silently disagree with
  itself — is delivered above, and enforced.

Filed as **#400** with that reasoning, so the decision is visible rather than inherited.

## What this does not do

- **It does not migrate the 63 legacy features.** Their `(ClassLoader, SharedPreferences)`
  constructor is now a lambda rather than a reflection, which is what A08 needs in order to change
  that constructor one file at a time: the change becomes a compile error at the registry entry
  instead of a runtime `NoSuchMethodException`.
- **It does not add per-feature risk or account-safety metadata.** The checklist appended to #337
  (from #378) asks for a reviewed, provenance-carrying policy field, explicitly defaulting to
  UNKNOWN/UNREVIEWED rather than treating today's `LOW` as a safety claim. Inventing 64 assessments
  here would be exactly the thing that rule exists to prevent, so the field is not added until there
  is evidence to put in it.
- **It does not change behaviour.** The same 64 features install in the same order with the same
  arguments. The one real behaviour change is the removal of the discarded second instance per
  legacy feature, which had no observable effect.
- **No runtime evidence.** This package's claims are about registration and construction, both of
  which are checked statically and by unit tests; nothing here was verified on a device.

## Testing status

- 1315 unit tests, 0 failures (1307 before this package).
- 9 self-test cases for the registry checker, each proving one defect it must catch.
- Every coverage counter above its floor; `assembleRelease` with R8 passes; the release APK's loader
  contract holds.