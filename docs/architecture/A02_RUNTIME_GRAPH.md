# A02 — RuntimeGraph and global-state containment

Issue: [#336](https://github.com/Alaa91H/WA-X/issues/336) · Program: Architecture sustainability

This is the phase that gives mutable state in the injected process an owner and a lifetime. It
deliberately does **not** remove that state — it makes the removal safe to do one file at a time.

---

## What is being contained

The runtime-surface inventory counts module-level mutable state in code that runs inside WhatsApp: 232
declarations across 59 files when this package started work, 230 when it started. That count is a
poor measure for two reasons, both of which mattered here: it counts declarations rather than
writes, so a second owner of one field reads the same as a first; and it counts function-local
`var`s and KDoc prose, so its own regexes match about 40 lines that are not state at all.

So this package measured **write sites**, which is what actually makes a global a global:

| Global | Writers | Files writing it | Read by |
| --- | --- | --- | --- |
| `FeatureLoader.mApp` (`Application`) | 1 | `FeatureLoader` | 5 files, 20 sites |
| `FeatureLoader.moduleContext` (module `Context`) | 1 | `FeatureLoader` | 3 files, 7 sites |
| `Utils.xprefs` (target preferences) | 2 | `FeatureLoader`, `TargetSettingsBridge` | 4 files, 9 sites |
| `Utils.appClassLoader` (target `ClassLoader`) | 1 | `FeatureLoader` | 1 file, 7 sites |
| `Feature.isDebug` | 1 | `FeatureLoader` | 2 files, 3 sites |
| `ModuleRuntime.mCurrentActivity` | 3 | `WaCallback` | 14 files via `getCurrentActivity()` |
| `ModuleEntryPoint.pref` | 1 | `ModuleEntryPoint` | 1 caller |

Eleven write sites, seven symbols, five files. That is the shape A02 actually had to work with, and
it is very different from "232 declarations": the state is written in a handful of places and read
widely, which is the signature of code that is *correct* and *unowned*.

The declaration count moved **230 → 232** with this package: `RuntimeGraphs` and its field are two
of those, and the deleted `ActivityStateRegistry` is one of them back. A package whose job is
containment ending with a higher count is worth stating plainly rather than hiding, and it is the
count that is wrong for the question being asked — a number that cannot tell a new owner from an old
one, or a `@Volatile var` from a `var` in a function body, is not a measure of containment. The
write-site table below is, and it is the one the gate enforces.

Two of the research findings did not survive verification, which is why they are not in the table:

- `ModuleEntryPoint.resParam` looks write-only, and is not — `DesignUtils.kt:208` reads it to set a
  resource replacement.
- `Utils.xprefs` has two writers that the inventory reports as duplicates. They are ordered, not
  duplicated: `FeatureLoader` calls `TargetSettingsBridge.install()`, which writes the scoped value
  itself, and then assigns the same object again.

## What is built

`com.wax.module.graph`, two types, no platform type:

- **`TargetIdentity`** — package name, version, SDK level, target class loader. Immutable except for
  `RuntimeGraph.recordTargetVersion`, and immutable because a process is one app: the two facts that
  get confused in this module are package and version, and a graph that could swap either would be a
  graph whose identity depends on write order.
- **`RuntimeGraph`** — the container. Three things the globals did not have:
  1. **one owner** — attachment is explicit and idempotent;
  2. **a lifetime** — `close()` releases every slot, notifies listeners in reverse registration order
     and returns a `Released` naming what it released;
  3. **type-checked slots** — `get`/`require` are `reified`.

The slot indirection (`TARGET_APPLICATION`, `MODULE_CONTEXT`, `TARGET_PREFERENCES`, all `Any` at the
boundary) is what keeps the graph free of `Application`, `Context` and `SharedPreferences`. The cost
is a cast that could be wrong; the benefit is that the container's own semantics are testable on a
plain JVM, which is the difference between a lifetime that is asserted and one that is hoped for.

`com.wax.module.xposed.graph.RuntimeGraphs` is the single holder, and it is the one global this
project now has **on purpose**. It lives in the injected layer rather than beside `RuntimeGraph`,
because a holder a JVM test can reach is a holder a JVM test can trip over.

### One thing that had to change for the tests to mean anything

`get<T>` was written first as `value as T`. With an erased type parameter that cast is a no-op, so a
caller reading the wrong type would have silently received the wrong object and the "slot mismatch"
path could never have run. `RuntimeGraphTest.a slot read as the wrong type names itself` failed on
the first run and the method became `inline fun <reified T>`. A gate that cannot fail is not a gate,
and this one had been passing by being unexecutable.

## The loader wiring

Four stages publish into the graph, in the order the state actually becomes available:

| Stage | Publishes |
| --- | --- |
| first framework callback | the graph itself, and `TARGET_APPLICATION` |
| PREFERENCES | `TARGET_PREFERENCES` |
| RESOLVER_CACHE | `MODULE_CONTEXT` |
| ESSENTIAL | the feature context |
| APPLICATION_ATTACH | the target version, once the `PackageManager` has been asked |

The version is the awkward one. The graph is created at the first framework callback, before the
version is readable, so `TargetIdentity.versionName` starts null and
`recordTargetVersion` fills it in exactly once. A version that has been read does not become
unreadable, and a second value would make the identity depend on write order.

The globals are **still written**. `FeatureLoader.mApp`, `moduleContext`, `Utils.xprefs` and
`Feature.isDebug` all keep their single writer, because their readers cannot be converted by this
package: `mApp` has 20 read sites in 5 files, `moduleContext` is the only route to the module's own
resources, and `Utils.xprefs` has exactly two readers — `CustomPrivacy` and `Tasker` — that #342
owns. Deleting a global while its readers still exist would be a build failure at best and a null
dereference at worst; publishing the same object twice is honest duplication with one owner and one
mirror, and the gate below stops the mirror from becoming the source.

## The gates

**`check_legacy_global_writes.py`** — fails when a legacy global gains a writer outside
`tools/quality/legacy_global_writes.json`, when it gains a *declaration*, when a second file holds
the `RuntimeGraph`, or when an unexpected file constructs one. Owners are keyed by file, not line,
because a rule that has to be re-approved every time an unrelated edit shifts a line number trains
people to re-approve it unread.

The ratchet is one-directional on purpose: **a writer disappearing is not a failure.** Legacy state
is meant to go away, and a gate that failed when it did would make progress look like breakage.

Its self-test has 10 cases, and three of them exist because of what this tree actually contains: a
local `val moduleContext = …` in an unrelated file, a local `val pref = …`, and `var isDebug =
false` without a type annotation. All three read like writes to a naive regex, and a gate that
cries to fail on ordinary code gets switched off.

**`FeatureGlobalIsolationTest`** — A02's strict gate, in the unit suite so it runs where the other
tests are:

> No newly migrated feature reads mutable global runtime state directly.

Four rules: a `WaFeature` implementation reads none of the seven globals and does not reach for the
graph either; the graph package reads none of them and names no platform type; and there is at least
one migrated feature, so the gate cannot pass by having nothing to check.

Reaching for `RuntimeGraph` from a feature is banned for the same reason as reading a global: it
would turn "your dependencies are arguments" back into "your dependencies are whatever the process
happens to hold", and the feature would be untestable again.

## What was removed

`ActivityStateRegistry` — two synchronized maps, written on all six lifecycle callbacks, read by
nothing. Deleting it was the right call rather than containing it: a container for state nobody
reads is a leak with a data structure. One of its maps held `WeakReference`s to activities under a
`HashMap`, which is not a cache but a way of keeping the names of destroyed activities alive for the
life of the process.

That is one file and six call sites, and it is the *only* removal in this package. Everything else
here adds a seam or a gate.

## What this does not do

- **It does not migrate the 63 remaining features.** Their `prefs` reads and `SharedPreferences`
  constructors are #342 A08. What changes for them is that a new global cannot be created and that a
  migrated feature cannot read one.
- **It does not contain the 24 `ModuleRuntime` reflection fields, the `Unobfuscator` bridge, the
  three database singletons, or the ~34 per-feature `companion object` statics.** They are measured
  in `RUNTIME_SURFACE.md` and in the write-site report, and each of them is a separate change with
  its own risk: a resolver cache that has an injected lifetime is a different thing from a feature
  flag that does.
- **It does not give `ModuleRuntime.listenerActivity` a shutdown path yet.** Seven features register
  an activity listener and there is no removal API, so those listeners outlive whatever registered
  them. `RuntimeGraph.onClose` is the mechanism for it and it is tested; the seven call sites are
  A08's, because they are features.
- **It does not change behaviour.** No feature reads differently, no setting is read differently,
  and the one deletion had no readers to affect. There is no runtime evidence and none is claimed.

## Testing status

- 17 new unit tests in `com.wax.module.graph`: the graph's lifetime (release, idempotent close,
  reverse-order notification, refused state after close, one-way version) and the strict gate.
- 10 self-test cases for the write-site checker, each proving one rule can fail.
- One of the new tests failed on its first run and found a real defect — see the reified `get` above.
  The gate suite as a whole is at 1290 tests and rising.