# M02 — Activation detection, scope truth and target heartbeat

Issue: [#321](https://github.com/Alaa91H/WA-X/issues/321) · Phase: LSPosed Modernization · Gate A

This is the phase that removes the module's opinion of itself as the answer to "is it working?".
It replaces one boolean and two sentences with a small model, and it is deliberately the phase
where the model is *read* by something rather than only written by something.

---

## What was wrong

`HomeFragment` branched on `ModuleApplication.isXposedEnabled()`, which is
`System.currentTimeMillis() == 0L` with a hook installed over it. That single value carried six
distinct facts, and it could only carry one:

```
isXposedEnabled() == true
  ⟸ the self-hook ran
  ⟸ a framework loaded the module into WA X's OWN process
  ⟸ nothing at all about WhatsApp
```

So the two messages the interface could produce were:

- *"Whatsapp is not running or has not been activated in Lsposed"* — for a resolver failure, a
  stopped target, an app that is simply not installed, and a scope problem alike;
- *"Module Enabled"* — while the resolution engine had failed and **zero hooks were installed**,
  because that answer came from a different process than the one that had failed.

The second is the worse one, and M00 recorded it in full
([M00_RUNTIME_TRUTH_SIGNALS.md](M00_RUNTIME_TRUTH_SIGNALS.md)).

---

## The model

Four types, all in `com.wax.module.activation`, all JVM-testable.

### `ActivationSignal` — the authority hierarchy

| Source | Rank | What it can prove |
| --- | --- | --- |
| `FRAMEWORK_EVIDENCE` | 4 | the module's code is executing inside *this* target process |
| `TARGET_PROCESS_OBSERVATION` | 3 | a process for the target exists |
| `PACKAGE_METADATA` | 2 | the app is installed, and at which version |
| `LEGACY_SELF_HOOK_SIGNAL` | 1 | a framework loaded WA X into its own process |
| `NONE` | 0 | nothing has been observed |

The self-hook is kept and is still true when it is true. What changed is that it is ranked
below three things that say more, and that it can no longer stand in for any of them. The name
of the method changed with the rank — `isLegacySelfHookSignal` — because a method named for the
question it was asked was, by its name, claiming to answer it.

### `TargetHeartbeat` — the evidence

One record per target **process**, produced by code running inside it. Package *and* process are
part of its identity, so WhatsApp and WhatsApp Business — and a secondary process — can never
overwrite each other. It carries the pid, the boot id, both session ids, the current bootstrap
stage, the aggregate state, the worst failure code, its own timestamp and both versions.

It has no message field. There is no way to pass free text into one, so the redaction the
health model relies on holds across the process boundary too.

### `TargetProcessObservation` — the honest third state

`getRunningAppProcesses()` is one of the APIs modern Android narrows, and it narrows it
*silently*: on stock Android it returns only the caller's own processes. Treating its silence as
"the target is not running" is the same class of error as treating a resolver failure as
"LSPosed is disabled", so the tri-state is explicit and the platform is asked whether its answer
is worth believing:

> a list containing a process belonging to some **other** package demonstrably reaches beyond
> this app, so its silence about the target is evidence. A list containing only this app's own
> processes is the stock restricted result, and its silence is not evidence of anything.

`UNOBSERVABLE` is what modern Android usually produces, and it resolves to *nothing reported* —
never to "not running", never to a failure code.

### `ActivationState` and `ActivationAction` — what may be said, and what to do

`NOT_INSTALLED`, `NOT_RUNNING`, `RUNNING_NOT_INJECTED`, `BOOTSTRAPPING`, `DEGRADED`, `FAILED`,
`READY`, `UNKNOWN`. The action is part of the resolved status rather than a branch in a layout,
because the state→advice mapping is exactly where a resolver failure turns into "check the LSPosed
scope".

### `ActivationStatusResolver` — the only place a fact becomes a claim

A pure function of its arguments, so all nine failure simulations the issue names are unit tests
rather than screenshots from a device CI does not have.

---

## The channel, and why it is a broadcast

The runtime cannot write where the Manager can read.

M01 attached the health store to `RuntimeHealth.attachStore(application)` where `application` is
the **target's** `Application`, so the document has always been written into the target's private
data directory — a directory no other process on the device can read. The comment on that method
claimed the opposite ("it is read back by the manager process"), and the claim was never true.
That is why the heartbeat is not "read from the store": it is **sent**.

The transport is the broadcast the Manager was already using. It was already there, already
permission-guarded, and already proven in both directions; a second channel would have to be
built, secured, versioned and reviewed for nothing. The reply carries the encoded heartbeat as
one extra on the reply that already exists.

The Manager files what arrives in its own storage (`ActivationStore`), which buys the one thing a
broadcast cannot: **an age that survives the target dying**. A fresh broadcast says WA X is
running now. A record written forty seconds ago says WA X *was* running, and the difference is
what `HealthFreshness` is for.

---

## Scope: recommendation, evidence, and proof

The issue asks for three separate things, and the code now names all three:

| | What it is | Where |
| --- | --- | --- |
| **Recommended scope** | what `xposedscope` in the manifest declares | `arrays.xml` — a suggestion the user is free to edit |
| **Effective scope** | whether the framework reached this package, which is proof | `BootstrapStage.SCOPE` |
| **Runtime injection proof** | that the module's code is executing here | `FRAMEWORK_EVIDENCE` — the heartbeat |

Only the second and third are ever reported. `SCOPE_MISSING` and `MODULE_DISABLED` exist in the
failure vocabulary and are deliberately **never produced by a Manager-side absence**: the
framework does not report a module's scope to a third process, so naming either would be a
guess. Only a runtime that can see the framework's own decision may produce them.

---

## What the runtime reports, and why

M01 built a twelve-subsystem model and wired **one** subsystem to it. That left the aggregate
permanently `DEGRADED` by its own rule 5 — "some subsystems have reported and others have not" —
so a perfectly healthy bootstrap could only ever publish "degraded". That is the same defect as
the boolean, with more words. The remaining ten are recorded, each from something the framework
already told us:

> **Superseded by M03.** M02 recorded them through a class of its own, `ActivationProof`, whose
> methods were one per subsystem. M03 replaced it with the declared stage sequence in
> [`com.wax.module.bootstrap`](M03_ATOMIC_BOOTSTRAP.md), where each stage owns exactly one
> subsystem and the runner is its only writer. Two writers for one subsystem is how a subsystem's
> state becomes a function of which writer ran last, so the class was folded into the sequence
> rather than left beside it. The table below is unchanged in substance and in ownership.

| Subsystem | Evidence |
| --- | --- |
| `FRAMEWORK`, `MODULE`, `SCOPE`, `TARGET_PROCESS`, `INJECTION` | reaching `FeatureLoader.start()` at all |
| `PREFERENCES` | whether the shared preference file was read directly or through the fallback |
| `CORE_COMPONENTS`, `ESSENTIAL_HOOKS` | `initComponents` and `plugins` completing, or the throwable |
| `OPTIONAL_HOOKS` | the count of collected failure reports |
| `RESOLVER` | whether any collected report is resolver-classified |

`StageRunnerTest.aHealthyBootstrapProducesReadyAndNotDegraded` is the test that fails the day
one of them stops reporting.

### The engine failure, end to end

The DexKit failure was the sharpest case M00 found and it is the one this phase had to finish.
It is still recorded *before* the early return (M01) and the early return is still there (M03's
decision, unchanged and still asserted). What M02 adds is the other half of M00-DEF-02: a
runtime whose bootstrap stops installs a **probe responder only** — no feature, no behaviour
change inside the target — so the recorded failure is reachable by the Manager.

The card then says *"WA X could not start in WhatsApp"* with `DEXKIT_INIT_FAILED` under it,
instead of a green banner over a WhatsApp where nothing works.

### Degraded or failed

The aggregate deliberately ranks a non-core subsystem failure as `DEGRADED`, because some
subsystems are optional. What it cannot know is whether *this* failure was optional. The code
can, because `RuntimeFailureCode.severity` is a property of the failure. So the aggregate stays
the runtime's, and the one word the interface uses comes from the severity the runtime already
assigned. A runtime whose engine failed installed zero hooks; calling that "reduced capability"
is the soft-pedal Gate A exists to stop.

---

## Gate A

> A generic "WhatsApp is not enabled in LSPosed" message is forbidden unless the failure was
> actually proven.

Implemented three ways, because a rule that lives in one `if` is a convention:

1. **The strings are gone.** All six activation strings, in all thirteen locales. A screen with no
   generic message has nothing to fall back on, and
   `noScreenFallsBackOnAGenericActivationMessage` fails if one returns.
2. **The resolver cannot produce the claim.** `mayReportFrameworkActivationFailure` is true for
   exactly one code, `INJECTION_NOT_OBSERVED`, which requires both halves to have been observed:
   a framework demonstrably loaded the module somewhere, **and** a process demonstrably exists
   for the target with no report from inside it.
3. **It is a property, not a test case.** `aGenericActivationFailureIsNeverProducedWithoutProof`
   runs the whole matrix of installed × legacy-signal × process observation × every failure code,
   so "we fixed the DexKit message" is not mistaken for "the defect was structural".

---

## What this does not do

- **It does not continue the bootstrap after a DexKit failure.** That is M03's decision and the
  early return is unchanged, with the assertion that will fail if it is changed early.
- **It does not claim the scope is missing.** Nothing here can prove it, so nothing says it.
- **It does not claim a device is supported.** Nothing in this phase ran on a phone.
- **It does not close the health document's storage problem.** The runtime still writes into the
  target's private directory; the Manager's evidence now travels by broadcast. Where the runtime's
  own document should live is A06's (versioned Manager ↔ Runtime bridge), which is the phase that
  owns the IPC contract.

## Testing status

- The nine failure simulations named by #321 are unit tests over the resolver, plus the matrix
  property above.
- The wire format is round-trip tested and its reader is tested against a truncated payload, a
  foreign schema, a missing field, an unknown state name, an unknown failure code and an
  oversized field.
- The Manager-side store is tested for persistence, per-target isolation, corrupt-file quarantine,
  write-failure reporting, and a key that tries to choose its own path.
- No runtime evidence: no rooted LSPosed device with either WhatsApp build was available. Whether
  the module hooks correctly on a device is unverified, and that is what testing the Beta is for.