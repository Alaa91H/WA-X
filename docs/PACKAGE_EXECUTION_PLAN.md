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
| 3 | Activation, scope truth, heartbeat | #321 M02 | Gate A | 2 |
| 4 | Atomic bootstrap, failure isolation | #322 M03 | Gate M03 | 2, 3 |
| 5 | Explicit Manager/Runtime contracts | #335 A01 | AE-03/AE-05/AE-06 ownership | 1, 3 |
| 6 | Legacy path stabilization | #323 M04 | Gate B | 3, 4, 5 |
| 7 | Packaging & R8 transition readiness | #324 M05 | Gate M05 | 4 |
| 8 | Modern libxposed API 102 | #325 M06 | Gate C | 6, 7 |
| 9 | ResourceBridge / Android 17-safe resources | #326 M07 | Gate D | 8 |
| 10 | RuntimeGraph & global-state containment | #336 A02 | A02 | 5 |
| 11 | Unified + generated feature registry | #337 A03 | A03 | 10 |
| 12 | Capability-driven resolver boundary | #338 A04 | AE-01/AE-04 ownership | 10, 11 |
| 13 | DexKit 2.3.0 & resolver architecture | #327 M08 | Gate E | 8, 12 |
| 14 | Typed settings, snapshots, secret store | #342 A08 | AE-02 ownership | 13 |
| 15 | FeatureInstaller v2, health, circuit breaker | #339 A05 | A05 | 13, 14 |
| 16 | Versioned Manager ↔ Runtime bridge & IPC | #340 A06 | A06 | 14, 15 |
| 17 | Build logic & first module extraction | #341 A07 | A07 | 16 |
| 18 | Data layer & repositories | #343 A09 | A09 | 17 |
| 19 | Architecture tests & quality ratchet | #347 A13 | A13 | 17 |
| 20 | Instrumentation + synthetic runtime testing | #348 A14 | A14 | 19 |
| 21 | Security boundary audit | #350 A16 | A16 | 16 |
| 22 | Dependency verification, SBOM, provenance | #351 A17 | A17 | 17 |
| 23 | Toolchain upgrades as atomic PRs | #328 M09 | Gate F | 22 |
| 24 | targetSdk 37 hardening | #329 M10 | Gate G | 23 |
| 25 | Strict qualityGate & releaseGate | #330 M11 | Gate H | 19, 22, 24 |
| 26 | Compatibility registry, kill switches, watchdog | #331 M12 | Gate I | 25 |
| 27 | Full matrix, rollback drill, rollout, legacy removal | #332 M13 | FINAL gate | 25, 26 |
| 28 | Manager modernization (repos, UDF, Compose) | #343–#345 A09–A11 | P1 | 27 |
| 29 | R8 hardening, performance, ADRs | #346, #349, #352 A12/A15/A18 | P1 | 27 |
| 30 | LSPosed modernization integration gate | #353 A19 | P0-A | 27 |
| 31 | Optional feature-domain modularization | #354 A20 | P2, measurement-gated | 30 |

Package 30 exists to prove the two programs are *integrated* rather than merely both finished; F003–F254 stay frozen until
it closes.

## 5. Package status

| # | Package | Beta | State |
| --- | --- | --- | --- |
| 1 | Evidence lock + architecture law (M00, A00) | `v1.2.0-beta.1` | merged to `main`; Beta release in progress |
| 2 | Structured runtime health (M01) | `v1.2.0-beta.2` | not started |

## 6. What a package records when it closes

- the Issues it implemented and their real completion evidence (commit, PR, Beta version, gate output);
- bugs the package **found** and bugs it **fixed**, kept distinct;
- the tests that were run and the tests that were added, including the regression test for every important fix;
- everything that was left undone, with a severity and a reason, as an Issue link rather than a buried sentence;
- the runtime claims that remain unverified, named as such.
