# WA X strict quality architecture

This directory defines the release-blocking quality contract. The contract is
fail-closed on everything CI can observe. Two requirements in it need something CI does
not have - a rooted device, or a measurement that cannot exist - and those are stated as
what they actually are: a floor to hold, and a claim to prove.

## Layers

1. **Source and UI contract** — every visible preference must be unique, localized,
   enabled, wired to implementation code, and represented by the generated settings
   contract where appropriate.
2. **Static quality** — Kotlin/Java/native compiler warnings are errors; Android Lint,
   detekt, ktlint/Spotless, compatibility, LSPosed, access-gate, URL and storage-type
   checks all run.
3. **Unit coverage floor** — every JaCoCo counter has a recorded floor in
   [`coverage-baseline.json`](coverage-baseline.json). A change fails when a counter's
   covered count falls, which means a test was removed, or when its percentage falls,
   which means production code was added without one. Both guards are exercised.
4. **E2E coverage floor** — the same mechanism, applied independently to the instrumented
   run. Unit coverage cannot compensate for a missing E2E path.
5. **Runtime evidence for every support claim** — the device-lab check binds a target the
   moment the compatibility matrix claims `supported` or `degraded` for it. Evidence must
   then name this exact module commit, be inside the freshness window, be taken on the
   newest declared train, on a rooted LSPosed device, with a recorded APK SHA-256, every
   registered feature resolved, every resolver each feature depends on resolved, and
   every visible preference both seen and interacted with.
6. **Atomic verdict** — CI executes gates independently with fail-fast disabled and
   produces a final verdict only after all gates have had a chance to report their
   failures.

## Why coverage has a floor and not a target

Absolute 100% across instructions, branches, lines, complexity, methods and classes is not
reachable for this module, and the measurement says so plainly:

| Counter | Covered | Total | Share |
| --- | --- | --- | --- |
| Instruction | 56 215 | 183 160 | 30.69% |
| Branch | 3 597 | 15 153 | 23.74% |
| Line | 9 084 | 34 201 | 26.56% |
| Complexity | 3 668 | 14 705 | 24.94% |
| Method | 2 446 | 7 068 | 34.61% |
| Class | 508 | 1 194 | 42.55% |

WA X is an Xposed module. Its largest classes resolve obfuscated members of WhatsApp's
own classes through Xposed reflection and run inside a hooked Android process, so they are
exercised by a device and not by a JVM test. `Unobfuscator.kt` is 11 801 instructions at
0%; 1 463 classes have no unit coverage and 1 215 of them are the hook installation layer.
A suite that covered them would have to reimplement WhatsApp.

A gate that cannot pass is not a gate, and this one gated the release path. The floor is
the measured state, it only moves up, and it catches the two failures that matter: a
removed test and untested new code.

Regenerate it with:

```bash
./gradlew -PstrictCollectAll=true :app:createDebugUnitTestCoverageReport
python3 tools/quality/strict/check_coverage_floor.py \
  --xml app/build/reports/coverage/test/debug/report.xml \
  --suite unit --out build/coverage.json \
  --update quality/coverage-baseline.json
```

A raised floor is a decision. Say in the commit message which counters moved and why.

## Why runtime evidence is claim-driven

A rooted LSPosed device with both WhatsApp builds installed is not something a CI runner
has. Requiring one unconditionally blocks every release on hardware nobody has, while
proving nothing: today every cell of the compatibility matrix is `unknown`, which its own
`statusVocabulary` defines as the only status permitted without evidence, and
`evidence` is empty.

The claim discipline that the device run was standing in for is enforced where it can be.
[`validate_compatibility.py`](../tools/compatibility/validate_compatibility.py) refuses a
`supported` cell whose resolvers are not all recorded as resolved with a `verifiedAt`
timestamp, and its self-test proves that in both directions. So the matrix cannot drift
into claiming something it has not proven.

The device-lab gate is what catches the case that validator cannot: evidence that exists
but does not match the build it is attached to. It binds the moment a claim appears, and
[`test_check_latest_runtime_evidence.py`](../tools/quality/test_check_latest_runtime_evidence.py)
proves that a planted `supported` cell still produces every finding it used to.

## Baselines

| Artifact | State |
| --- | --- |
| `lint-baseline.xml` | 4 entries, all `VectorPath` on artwork. Declared explicitly in `app/build.gradle.kts`, because AGP does not discover it - left implicit the file was inert. |
| detekt baseline | none. Thresholds in `config/detekt/detekt.yml` are the measured maximum, with the owning file named. |
| coverage floors | `coverage-baseline.json`. Unit floors recorded; the E2E floor bootstraps on its first run and becomes binding when pinned. |

Generated code, Android/Compose framework code and third-party libraries are not coverage
targets. No hand-written WA X source is excluded from the coverage measurement.

The machine-readable form of all of this is [`strict-policy.json`](strict-policy.json).