# M00 — Evidence Lock, Baseline & False-Green Gate Repair

Issue: [#319](https://github.com/Alaa91H/WA-X/issues/319) · Epic: [#318](https://github.com/Alaa91H/WA-X/issues/318)
Program: LSPosed Modernization & Stability
Companion: [#334](https://github.com/Alaa91H/WA-X/issues/334) — A00 Architecture Baseline

This document records what M00 established. It exists because two of the findings below
contradict the plan that ordered the work, and a program that silently "fixes" them would make
the module worse.

## M00.03 — The `93` vs `82` mismatch is not a mismatch

The plan records this as an inconsistency:

> manifest/checker policy says legacy API 93 while version catalog pins xposed-legacy 82

It was checked against upstream rather than reconciled on paper. **Both numbers are correct and
they belong to different numbering spaces.** There is nothing to align.

### What `93` is

A **runtime Xposed API level**. LSPosed reads `xposedminversion` when it loads the module and
compares it against `XposedBridge.getXposedVersion()`, which in LSPosed returns
`XposedInterface.API` (`LSPosed/core/src/main/java/de/robv/android/xposed/XposedBridge.java`).

93 is not arbitrary. From the LSPosed wiki, *New XSharedPreferences*:

> Since LSPosed API 93, a new `XSharedPreferences` is provided targeted for modules with
> sdk > 27. Module developers can enable this new `XSharedPreferences` support by modifying the
> meta-data of your modules to set `xposedminversion` to 93 or above, or add `xposedsharedprefs`
> meta-data.

This module reads its settings through `XSharedPreferences` (`ModuleEntryPoint.kt`) and declares
both `xposedminversion=93` and `xposedsharedprefs=true`. **93 is the level that turns that
behaviour on.** Lowering the manifest to 82 would not fail a build and would not fail a gate; it
would change preference semantics inside the hooked process at runtime.

### What `82` is

A **Maven artifact version** for the compile-only stubs `de.robv.android.xposed:api`. That line
is frozen. `https://api.xposed.info/de/robv/android/xposed/api/maven-metadata.xml` lists exactly:

| Version | Published |
| --- | --- |
| 53 | 2016-04-01 |
| 81 | 2016-04-03 |
| 82 | 2016-04-15 |

There is no artifact version 93 and there never will be. 82 is the only value a correct build can
pin, and it carries the whole surface this module imports: `XSharedPreferences`, `SELinuxHelper`,
`IXposedHookZygoteInit`, `XModuleResources` and the `callbacks` package.

### Resolution

Keep `93` in the manifest, keep `82` in the catalog, and **make the relationship enforced instead
of accidental**. "Make them equal" is not available; the only reachable variant of that plan is
the destructive one.

The stale audit line in `docs/modernization/execution-manifest.json` is corrected below.

## M00.04 — The loader contract checker was blind

The checker matched this pattern anywhere in `gradle/libs.versions.toml`:

```python
r'"%s"\s*,\s*name\s*=\s*"%s"' % ("de.robv.android.xposed", "api")
```

It never read the version. So it reported

```
LSPosed loader contract intact: legacy API >= 93, ...
```

while the version could be absent, dynamic, mistyped, or pointed at an artifact that does not
exist. Only coordinate *presence* was asserted. That is the false-green gate M00 exists to remove.

The checker now:

1. Parses `gradle/libs.versions.toml` as TOML rather than pattern-matching it.
2. Resolves `version.ref` through `[versions]` (`version.ref = "x"` and
   `{ version = { ref = "x" } }` are the same TOML and both arrive as a nested table; reading it
   as a string is what made an earlier revision report the ref itself as the version).
3. Rejects a pin that is missing, unresolved, dynamic, or a snapshot.
4. Rejects a version outside the published set `{53, 81, 82}`.
5. Rejects a published-but-wrong version, holding the pin at the one release the module's class
   surface was verified against.
6. Requires the alias to actually be consumed on the `compileOnly` configuration — a catalog entry
   nothing reads is the same as no pin at all, and it reads as if it were one.
7. With `--verify-artifact`, opens the resolved artifact and asserts it publishes every
   `de.robv.android.xposed` type the module imports. Nested imports (`XposedBridge.log`,
   `XC_LoadPackage.LoadPackageParam`) are reduced to the type the artifact actually publishes.

The manifest level is now held to an **exact** value rather than a range. A range passes 82, 91 and
94, and each of those is wrong.

## Two further defects found while proving the checker could fail

### The scope array was only checked for the system framework

`check_manifest` required `android` in `@array/scope` and nothing else. A scope array that had
quietly lost `com.whatsapp.w4b` passed the gate: the module stays enabled, the manager shows no
scope problem, and WhatsApp Business is simply never hooked. To a Business user that is
indistinguishable from a WhatsApp bug.

All three required entries are now enforced, each with the reason it is there:
`android`, `com.whatsapp`, `com.whatsapp.w4b`.

### CI did not run on this repository

`.github/workflows/ci.yml` and `codeql.yml` triggered on `master`. The default branch is `main`
(`git fetch --prune` reports `origin/master` deleted; `gh repo view` reports
`defaultBranchRef.name = main`). **Neither workflow ran on this repository at all.** The Telegram
publisher's ref filter had the same defect.

Every branch filter now carries both names. `master` is kept deliberately: forks of this
repository still carry it, and a pipeline matching only the new name would silently stop running
on a fork that has not renamed.

## M00.01 — The baseline is derived, not written

`docs/modernization/baseline/m00-baseline.json` is produced by
`tools/modernization/collect_m00_baseline.py`, which reads the version catalog, the module build
file, the manifest, the compatibility matrix, the workflow files and the source tree. `--check`
re-derives it and fails when the committed copy no longer matches, so "the baseline is current"
is provable rather than remembered.

Two properties are deliberate:

- **No machine-specific values.** SDK paths, JDK paths and Gradle caches are recorded as the
  versions the build asks for, not as paths on one disk, so the file means the same thing in CI.
- **No revision identity.** Commit SHA, branch and tree state change on every run; comparing them
  would make the gate fail constantly and train everyone to ignore it.

This is not a stylistic choice. `docs/modernization/execution-manifest.json` asserted
`AGP=9.3.1 and Kotlin=2.4.10` while the catalog said `9.4.1` and `2.4.20`, and nothing noticed,
because prose is not compared to anything. The baseline is machine-readable for that reason.

### What the derived baseline says about the tree

| Field | Value |
| --- | --- |
| AGP / Kotlin / KSP | 9.4.1 / 2.4.20 / 2.3.12 |
| Gradle / CI JVM | 9.7.0 / 21 |
| compileSdk / targetSdk / minSdk | 37 / **34** / 28 |
| NDK | 28.2.13676358 |
| lint | `warningsAsErrors`, `abortOnError`, baseline present, 3 rules disabled |
| detekt | `ignoreFailures = false`, `failOnSeverity = Info`, no baseline |
| sources | 344 Kotlin, 3 Java, 425 native, 62 unit tests, 1 androidTest |
| loader | level 93, artifact 82, entry `com.wax.module.ModuleEntryPoint`, no `META-INF/xposed` |

Two of these confirm audit findings that matter later: `targetSdk` is still 34 against
`compileSdk` 37 (M10), and there is exactly **one** `androidTest` source file, which is the
instrumentation gap A14 exists to close.

## M00.02 — Baseline gate result

Recorded at the M00 starting commit, on this machine, with submodules initialised.

| Gate | Result |
| --- | --- |
| `assembleDebug` (Kotlin + Java + CMake, both ABIs) | pass |
| `testDebugUnitTest` | pass |
| `lintDebug` | pass |
| `spotlessCheck` | pass |
| `detekt` | pass |
| compatibility selftest / matrix / render | pass |
| settings registry, preference wiring, preference types | pass |
| ui-surface, access-gate selftest, access gates | pass |
| LSPosed contract selftest (45 cases) + contract | pass |
| repository URL selftest + URLs | pass |
| M00 baseline `--check` | pass |

One environment note, recorded rather than hidden: the first `assembleDebug` failed because
`app/src/main/cpp/{ogg,opus,libopusenc}` are git submodules and had not been checked out. CI
uses `submodules: recursive`; a local clone that skips them fails in CMake configure, not in
Kotlin. No source change was made for this.

## Corrected audit lines

`docs/modernization/execution-manifest.json` said:

- `"AGP=9.3.1 and Kotlin=2.4.10"` — wrong; the catalog has always said 9.4.1 / 2.4.20.
- `"manifest/checker policy is legacy API 93 while version catalog pins xposed-legacy 82"` —
  presented as a defect; it is two numbering spaces, both correct.
- `"LSPosed contract checker currently validates coordinate presence but not actual dependency
  version"` — correct, and now fixed.

## Gate status

M00.01–M00.05 complete and verified. M00.06–M00.09 remain: legacy API inventory, runtime truth
signals, test matrix lock, and the false-disabled regression fixture.