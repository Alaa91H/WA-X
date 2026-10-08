# WA X — Package-Based Development & Beta Release Plan

> **Status:** governing execution plan
>
> **Derived from:** [LSPOSED_MODERNIZATION_EXECUTION_PROGRAM.md](LSPOSED_MODERNIZATION_EXECUTION_PROGRAM.md) (#318, M00–M13) and
> [ARCHITECTURE_SUSTAINABILITY_EXECUTION_PROGRAM.md](ARCHITECTURE_SUSTAINABILITY_EXECUTION_PROGRAM.md) (#333, A00–A20).
>
> **Rule:** the work is executed as a sequence of packages. One package is finished — merged to `main`, `main` CI green,
> Beta released, Issues updated — before the next one begins. `[x]` in an Issue is not evidence; the tree, the tests and the
> built artifact are.

## 1. Why the packages are ordered this way

The two programs already define their own dependencies, and this plan does not invent new ones:

- The LSPosed program is `BLOCKER / PRIORITY-0` and its execution order is mandatory: `M00 → M01 → … → M13`. It states that
  no large phase starts before the previous gate is green, and that no library migration is allowed before the first slice
  (M00 evidence + M01 health + M02 activation) is green.
- The architecture program supplies work that is integrated *around* it, with explicit coordination points
  (P0-A against M00–M08; P0-B before the final M13 gate).
- Within both, `BLOCKER`/`P0` outranks `P1`, and runtime/LSPosed correctness outranks new features. The feature backlog
  (F003–F254) stays frozen until the LSPosed program's final gate closes.

Three dependency facts decide the order of the early packages:

1. **M01 before M02.** M02 has to *replace* an activation signal with evidence; M01 defines the states and failure codes that
   evidence is expressed in. Detecting "scope missing" before the vocabulary for it exists produces the generic message the
   program exists to remove.
2. **M03 after M01–M02.** Stage isolation and criticality levels are classifications of the health model.
3. **A01 after A00 and alongside M0x.** A01 turns the AE-03/AE-05/AE-06 findings into explicit Manager/Runtime contracts.
   It owns rule limits that A00 measured, and it touches the activation signal M02 is rewriting, so it is sequenced after
   M01–M02 so that one concern changes at a time.

Everything else follows its program's own gate order. The P0-B packages (A07, A08, A13, A14, A16, A17) are placed so they
land **before** M13, which is where the architecture program requires them.

## 2. Versioning of a package Beta

The existing release contract is unchanged and remains authoritative:

- `gradle.properties` `waxVersionName` must equal the pushed tag without its leading `v`; `waxVersionCode` must be the
  integer the APK declares. A mismatch fails preflight, so a wrong version cannot be released.
- A tag push (`v*`) builds the optimized, signed **release** variant, requires the release-only Android E2E gate, and creates
  the GitHub Release with the APK and the notes taken from the first section of `changelog.txt`.
- A push to `main` without a tag publishes a signed optimized *development* beta to the Beta channel and creates no GitHub
  Release. That path is for testing, not for a package deliverable.

A package Beta is therefore a **tagged** release, and the beta sequence continues the project's numbering
(`versionCode = major*100 + minor*10 + patch`) so that the code stays strictly increasing while still leaving room for the
stable release that follows:

| Release | `waxVersionName` | `waxVersionCode` |
| --- | --- | --- |
| previous stable | `1.1.0` | `110` |
| package 1 beta 1 | `1.2.0-beta.1` | `111` |
| package 2 beta 2 | `1.2.0-beta.2` | `112` |
| … | `1.2.0-beta.N` | `110 + N` |
| eventual stable | `1.2.0` | `120` |

`110 + N < 120` is deliberate: a tester on any beta of `1.2.0` can update to the stable `1.2.0` without an uninstall.
A tag is never reused; a package whose Beta has already been published moves the version forward instead.

Because `waxVersionName`/`waxVersionCode` are recorded in the M00 evidence lock, every version bump re-derives that baseline
(`python3 tools/modernization/collect_m00_baseline.py --write`) in the same commit. That is the documented fix for that gate,
not a relaxation of it.

## 3. The mandatory cycle

```text
package scope
→ implement
→ full local gates (assembleDebug/assembleRelease, unit, lint, detekt, spotless,
                    architecture, modernization, compatibility, loader, security)
→ fix every failure and every new warning at its cause
→ PR to main
→ CI green (all required jobs)
→ merge to main
→ main CI green
→ beta build + artifact validation
→ tag → CI release workflow green → GitHub Release
→ update/close the package Issues with evidence
→ next package
```

A package stops the line when it leaves any of: a compile or release-build failure, a required CI failure, a known startup
crash, a signing failure, a broken manifest or loader contract, a broken required scope, a security or data-loss
regression, a failing mandatory test, or an architecture/modernization gate regression. No Beta is published from a
`main` that is not green, and no Beta is published from a branch.

Runtime claims are never asserted from CI. Evidence that needs a device, a real LSPosed framework or a target process is
recorded as `unverified runtime evidence` with the exact matrix cell that would close it.

## 4. Packages

| # | Package | Issues | Program gate | Depends on |
| --- | --- | --- | --- | --- |
| 1 | Evidence lock + architecture law | #319 M00, #334 A00 | M00 gate, A00 | — |
| 2 | Structured runtime health | #320 M01 | Gate M01 | 1 |
| 3 | LSPosed loader integrity (blocker insert) | #324 M05 R8 entry preservation, #361 | Release blocker | 2 |
| 4 | Activation, scope truth, heartbeat | #321 M02 | Gate A | 2 |
| 5 | Atomic bootstrap, failure isolation | #322 M03 | Gate M03 | 2, 4 |
| 6 | Explicit Manager/Runtime contracts | #335 A01 | AE-03/AE-05/AE-06 ownership | 1, 4 |
| 7 | Legacy path stabilization | #323 M04 | Gate B | 4, 5, 6 |
| 8 | Packaging & R8 transition readiness | #324 M05 | Gate M05 | 5 |
| 9 | Modern libxposed API 102 | #325 M06 | Gate C | 7, 8 |
| 10 | ResourceBridge / Android 17-safe resources | #326 M07 | Gate D | 9 |
| 11 | RuntimeGraph & global-state containment | #336 A02 | A02 | 6 |
| 12 | Unified + generated feature registry | #337 A03 | A03 | 11 |
| 13 | Capability-driven resolver boundary | #338 A04 | AE-01/AE-04 ownership | 11, 12 |
| 14 | DexKit 2.3.0 & resolver architecture | #327 M08 | Gate E | 9, 13 |
| 15 | Typed settings, snapshots, secret store | #342 A08 | AE-02 ownership | 14 |
| 16 | FeatureInstaller v2, health, circuit breaker | #339 A05 | A05 | 14, 15 |
| 17 | Versioned Manager ↔ Runtime bridge & IPC | #340 A06 | A06 | 15, 16 |
| 18 | Build logic & first module extraction | #341 A07 | A07 | 17 |
| 19 | Data layer & repositories | #343 A09 | A09 | 18 |
| 20 | Architecture tests & quality ratchet | #347 A13 | A13 | 18 |
| 21 | Instrumentation + synthetic runtime testing | #348 A14 | A14 | 20 |
| 22 | Security boundary audit | #350 A16 | A16 | 17 |
| 23 | Dependency verification, SBOM, provenance | #351 A17 | A17 | 18 |
| 24 | Toolchain upgrades as atomic PRs | #328 M09 | Gate F | 23 |
| 25 | targetSdk 37 hardening | #329 M10 | Gate G | 24 |
| 26 | Strict qualityGate & releaseGate | #330 M11 | Gate H | 20, 23, 25 |
| 27 | Compatibility registry, kill switches, watchdog | #331 M12 | Gate I | 26 |
| 28 | Full matrix, rollback drill, rollout, legacy removal | #332 M13 | FINAL gate | 26, 27 |
| 29 | Manager modernization (repos, UDF, Compose) | #343–#345 A09–A11 | P1 | 28 |
| 30 | R8 hardening, performance, ADRs | #346, #349, #352 A12/A15/A18 | P1 | 28 |
| 31 | LSPosed modernization integration gate | #353 A19 | P0-A | 28 |
| 32 | Optional feature-domain modularization | #354 A20 | P2, measurement-gated | 31 |

Package 31 exists to prove the two programs are *integrated* rather than merely both finished; F003–F254 stay frozen until
it closes.

Package 3 is an inserted blocker, not a program phase. It exists because verifying the package-2 Beta artifact produced a
measurement no gate could have produced: R8 removes the class `assets/xposed_init` names from every release build, along
with the entire injected runtime reachable only through it, so no published WA X APK can be loaded as an LSPosed module.
It is a release blocker and a stable regression (WaEnhancer 1.8.0 shipped its runtime), both of which the program's freeze
policy permits fixing out of phase order, and #324 M05 owns the part of it that is program work. Inserting it before the
activation phase is deliberate: M02, M03 and M04 need the legacy entry to run in the injected process on a device, and no
Beta released before this package could provide that. The measurements are on #324 and #318.

## 5. Package status

| # | Package | Beta | State |
| --- | --- | --- | --- |
| 1 | Evidence lock + architecture law (M00, A00) | `v1.2.0-beta.1` | merged to `main`; Beta published; #319, #334 closed |
| 2 | Structured runtime health (M01) | `v1.2.0-beta.2` | merged to `main`; Beta published; #320 closed with its runtime observability blocked on package 3 |
| 3 | LSPosed loader integrity (blocker insert) | `v1.2.0-beta.3` | fix, keep rule, source-level and release-APK gates merged; #324, #361 closed. Beta published from this commit |
| 4 | Activation, scope truth, heartbeat (M02) | `v1.2.0-beta.4` | merged to `main`; the activation model, the per-target heartbeat, the demoted self-hook and Gate A. Beta published from this commit |
| 5 | Atomic bootstrap, failure isolation (M03) | `v1.2.0-beta.5` | merged to `main`; the declared stage sequence, the criticality table, the engine's early return removed. Beta published from this commit |

## 6. What a package records when it closes

- the Issues it implemented and their real completion evidence (commit, PR, Beta version, gate output);
- bugs the package **found** and bugs it **fixed**, kept distinct;
- the tests that were run and the tests that were added, including the regression test for every important fix;
- everything that was left undone, with a severity and a reason, as an Issue link rather than a buried sentence;
- the runtime claims that remain unverified, named as such.
