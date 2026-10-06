# WA X strict quality architecture

This directory defines the release-blocking quality contract. The contract is intentionally
fail-closed: a missing report, missing device-lab result, warning, untested branch, hidden
preference, stale registry entry, or unverified latest-target feature is a failure.

## Layers

1. **Source and UI contract** — every visible preference must be unique, localized, enabled,
   wired to implementation code, and represented by the generated settings contract where
   appropriate.
2. **Static quality** — Kotlin/Java/native compiler warnings are errors; Android Lint, detekt,
   ktlint/Spotless, compatibility, LSPosed, access-gate, URL and storage-type checks all run.
3. **Unit coverage** — JaCoCo counters for production code are required to be 100% for
   instructions, branches, lines, complexity, methods and classes.
4. **E2E coverage** — instrumented Android tests have the same 100% requirement independently;
   unit coverage cannot compensate for a missing E2E path.
5. **Latest WhatsApp runtime evidence** — a rooted LSPosed device-lab run must record the exact
   WA X commit, target package/version/APK SHA-256, every loaded feature result, every resolver
   result and every visible option result for the newest declared WhatsApp and Business trains.
6. **Atomic verdict** — CI executes gates independently with fail-fast disabled and produces a
   final verdict only after all gates have had a chance to report their failures.

Generated code, Android/Compose framework code and third-party libraries are not production
source owned by WA X and therefore are not coverage targets. No hand-written WA X source is
excluded merely to improve a percentage.

The strict policy is machine-readable in `quality/strict-policy.json`.
