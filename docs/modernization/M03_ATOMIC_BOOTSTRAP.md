# M03 — Atomic bootstrap and feature failure isolation

Issue: [#322](https://github.com/Alaa91H/WA-X/issues/322) · Phase: LSPosed Modernization · Gate M03

This is the phase that decides what happens when part of startup does not work. Before it, the
answer was: everything stops, and the only trace is a line in a log the user cannot reach.

---

## What was wrong

`FeatureLoader.start()` was a straight line that began with the resolution engine:

```kotlin
if (!Unobfuscator.initWithPath(sourceDir)) {
    XposedBridge.log("Can't init dexkit")
    return
}
```

A `return` in the middle of a method cannot say which later work needed the thing it guarded. It
can only stop all of it — including the stages that never touched the engine, such as attaching
storage, registering the receivers and answering the Manager. So:

- a failure of one component produced **zero** hooks and **zero** reachable evidence;
- the stages that would have run anyway did not, and nothing recorded that they were skipped;
- there was no order. "What runs before what" was wherever the code happened to be written, so a
  change to one part of startup silently reordered another.

M01 fixed the silence (the record exists before the engine is asked). M02 made a stopped bootstrap
*reachable* by installing a probe responder on the failure path — a workaround, because the stage
that attaches to the target came after the stage that could stop it. M03 removes the need for it.

---

## The stage sequence

`BootstrapStage`, in declaration order, is the specification:

| # | Stage | Subsystem | Criticality | Needs the app |
| --- | --- | --- | --- | --- |
| 1 | `FRAMEWORK` | FRAMEWORK | CORE | no |
| 2 | `MODULE` | MODULE | CORE | no |
| 3 | `SCOPE` | SCOPE | CORE | no |
| 4 | `TARGET` | TARGET_PROCESS | CORE | no |
| 5 | `INJECTION` | INJECTION | CORE | no |
| 6 | `PREFERENCES` | PREFERENCES | ESSENTIAL | yes |
| 7 | `APPLICATION_ATTACH` | — | CORE | yes |
| 8 | `DEX_ENGINE` | DEXKIT | CORE | yes |
| 9 | `RESOLVER_CACHE` | RESOLVER | ESSENTIAL | yes |
| 10 | `CORE` | CORE_COMPONENTS | CORE | yes |
| 11 | `ESSENTIAL` | ESSENTIAL_HOOKS | ESSENTIAL | yes |
| 12 | `OPTIONAL` | OPTIONAL_HOOKS | OPTIONAL | yes |
| 13 | `RUNTIME_VERIFICATION` | — | CORE | yes |
| 14 | `READY` | — | CORE | yes |

**Two properties are asserted rather than intended.**

- **Every subsystem is written by exactly one stage.** `everySubsystemIsWrittenByExactlyOneStage`
  fails if two stages claim one subsystem or if an independent subsystem is left uncovered. Two
  writers make a subsystem's state a function of which writer ran last, and the aggregate the
  interface reads would depend on stage order.
- **Every CORE stage carries a justification.** `aCoreStageWithoutAJustificationIsRefused` fails
  if one does, and `aStageThatIsNotCoreCarriesNoJustification` fails if a stage that cannot stop
  anything carries one — so the field stays an argument rather than becoming a comment.

---

## Criticality, and what a failure costs

| | Effect on dependent stages | Effect on the bootstrap |
| --- | --- | --- |
| CORE | skipped | FAILED |
| ESSENTIAL | skipped | DEGRADED |
| OPTIONAL | untouched | DEGRADED |
| EXPERIMENTAL | untouched, and not attempted unless asked | DEGRADED |

Three rules that are easy to get wrong and are therefore tests:

1. **A degraded stage blocks nothing.** It still worked. Skipping everything downstream of a
   degradation is how one lost capability turns into no runtime at all.
2. **A skip names its root cause.** Six stages below an engine failure all report
   `DEX_ENGINE`, not "the stage immediately above me", which would be uninformative in every case.
3. **The asymmetry is deliberate.** An *essential* failure skips the optional set, because optional
   features are built on it and running them produces a cascade of noise for a consequence of the
   first failure. An *optional* failure skips nothing, ever.

---

## Two passes, and why

`start()` runs the sequence twice.

- **Pass 1** runs the five stages that do not need the target's `Application`. Reaching
  `start()` already proves the framework, the module, the effective scope, the target process and
  injection, so those five are recorded as five stages instead of as a comment.
- Then `callApplicationOnCreate` is hooked — **before the engine is asked for anything**, which is
  what removes M02's workaround.
- **Pass 2** runs when the `Application` arrives.

Stages needing the application return `AWAITING` on the first pass, which is neither success nor
failure: a process that has not finished starting is not a failed process. A stage that already has
a terminal result is never run again, so the second pass continues rather than repeating. That
idempotency is what makes the two-pass design possible at all.

The *attachments* need their own guard: `callApplicationOnCreate` is not guaranteed to fire once,
and everything a second firing would register — receivers, lifecycle callbacks, the crash handler
— is installed per registration. The runner makes the stages idempotent; `bootstrapAttached` makes
the attachments idempotent, which is a different thing.

---

## Bugs this phase found

- **A slow feature silently dropped every feature queued behind it.** `plugins()` awaited its
  executor with a fifteen-second bound and discarded the answer. One slow feature meant the rest
  were never installed, and nothing anywhere recorded that. The budget is now a named constant,
  the stage measures it, and a timeout records how many features were dropped.
- **A skipped stage never reached the health model.** It stayed `UNKNOWN` — indistinguishable
  from "nobody has said anything", which is the state a stopped bootstrap used to leave the whole
  interface in.
- **Skip propagation was one level deep**, so a stage three steps below a failure ran and failed
  on its own, producing a second misleading failure for a consequence of the first.
- **A failure could be recorded without a code**, which is the exact shape of every message this
  programme has removed: something did not work and nothing can say what. The stage declares its
  own code for that case, so a failure is never recorded without one.
- **`CORE` did not depend on the engine.** The shared components resolve members through it, so
  without the dependency an engine failure let `CORE` run and fail on its own.

---

## Gate M03

> DexKit failure is diagnosable, optional failure cannot block READY, and bootstrap is
> deterministic/idempotent.

| Clause | Test |
| --- | --- |
| diagnosable | `anEngineFailureSkipsOnlyWhatNeedsTheEngine` — only the six stages that need the engine are skipped, the code is `DEXKIT_INIT_FAILED`, the health aggregate names the engine, and the stages that do not need it still ran |
| diagnosable | `aSkippedStageNamesTheStageThatBlockedIt` — the cause, not the neighbour |
| diagnosable | `aStageThatThrowsIsRecordedAndDoesNotStopTheSequence` — containment records the stage's own code |
| diagnosable | `aFailureCarriesNoSkipForACode` — a failed stage cannot exist without one |
| optional cannot block READY | `anOptionalFailureCannotBlockReady` — DEGRADED, still ready, skips nothing, and every non-optional stage succeeded |
| optional cannot block READY | `theSameStageClassifiedCoreFailsTheBootstrap` — the same failure as CORE *is* fatal, so the classification is doing work |
| deterministic | `stagesRunInDeclarationOrder`, `theStageOrderIsTheOneThePhaseDefines`, `twoIdenticalRunsProduceIdenticalReports` |
| idempotent | `aSecondPassRunsNothingThatAlreadyFinished`, `theSecondPassContinuesWhereTheFirstStopped` |
| timing | `everyStageReportsHowLongItTook` — per stage and total, measured rather than summed |

Plus `aContainedFailureIsStillLogged`: containment that logs nothing is indistinguishable from a
swallowed failure, and `BootstrapLogSink` exists because a runner tested on a JVM cannot call the
framework's logger.

---

## What this does not do

- **Per-feature criticality metadata.** The stage list has it; the 67 feature classes do not. A
  feature that fails today is recorded with a code and its stage's criticality, not with its own.
  Filed as its own issue rather than left in a release note.
- **It does not decide which features are essential.** Deciding that is what the per-feature table
  is for, and guessing at it here would put an unproven classification in the code.
- **It does not continue past a failure by changing behaviour inside the target.** It changes what
  is *recorded* and what runs, not what a working component does.
- **No runtime evidence.** None of this has run inside WhatsApp on a device.

## Testing status

- 1281 unit tests, none failing. Every clause of the gate above is a test.
- `Spotless`, detekt and lint clean; every coverage counter above its floor.
- The engine failure itself cannot be exercised on a JVM — it needs a real dex file and an
  LSPosed-provided class loader — so its characterisation is asserted over the source and its
  behaviour over the runner.