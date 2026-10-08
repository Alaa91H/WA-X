# WA X — Automatic WhatsApp/Business Compatibility Discovery, Analysis & Build Pipeline

**Decision date:** 2026-10-08  
**Status:** SPECIFICATION / ISSUE EXECUTION PLAN ONLY — **NOT IMPLEMENTED OR VERIFIED**.  
**Umbrella:** AUTO-COMPAT-01 (see newly linked Issue).  
**Scope:** CI/quality pipeline + reproducible target artifact acquisition + static/dynamic capability verification + evidence-based compatibility updates for `com.whatsapp` and `com.whatsapp.w4b`.  
**Binding phase order:** #318 M00→M13, especially M08 #327 (DexKit), M11 #330 (quality/release), M12 #331 (signed registry), M13 #332 (device matrix); #333 A04 #338, A05 #339, A14 #348, A17 #351, and Manager UI #371. **Do not bypass phase gates**.

## 0. Goal and truthful limitations

In **EVERY WA X build** (PR, main, tag/release and manual dispatch), automatically inspect the latest *discoverable* version **for each configured target and channel** using a trusted, policy-compliant source. If a **new version/APK fingerprint** is detected and a lawfully accessible authentic artifact is available, fetch it into a **private ephemeral analysis cache**, compare it with the last analyzed artifact, run bounded resolver/capability analysis and the relevant test matrix, preserve compatible features, quarantine only demonstrably failing/unsafe ones, and generate precise reports. Schedule separate background discovery runs so new targets are noticed even between WA X builds.

**Do not promise full compatibility purely from version-number matching, static Dex scanning, or a green build.** Full feature functionality needs actual target-process/LSPosed test evidence. The target application's upstream binaries cannot be silently patched or rebuilt as part of WA X's own APK build. Code-level behavioral changes can require human review.

### Current reality baseline (2026-10-08)

- `.github/workflows/ci.yml` already has PR/main/tag/workflow_dispatch and **Monday 04:17 UTC** schedule, but the scheduled job is a **Ubuntu 26.04 forward-compatibility canary**, **not discovery/testing of latest WhatsApp APK**.
- `tools/compatibility/compatibility.json` is source-of-truth and lists legacy declared version ranges/derived 64-feature inventory, **`"matrix": {}`, `"evidence": {}` and default `unknown`**. Version declarations are NOT resolver-runtime evidence.
- `CompatibilityCanary.kt`, `TargetFingerprint`, `TargetVersions` and `ResolverCacheKey` exist, but current sources alone do not prove new-version auto-acquisition/full production wiring.
- `FeatureLoader.kt` already tolerates newer undeclared WhatsApp versions as **unverified/degraded** subject to runtime checks; do not replace this with false `supported` labels or a blanket crash-prone bypass.
- Existing CI already applies multiple strict build/test/signing gates; **extend** the single `ci.yml` rather than introduce a second competing production release workflow.

## 1. Trusted target discovery — on EVERY build

- [ ] Add a separate reusable `target-version-discovery` stage to **the existing CI workflow**, required for every PR, main push, release tag and manual invocation; additional scheduled discovery should run daily or more often only within upstream rate limits. Never confuse the existing Ubuntu canary with a WhatsApp APK canary.
- [ ] Define a **declarative sources-and-channels file** for WhatsApp Messenger and Business separately: package ID, Stable/Beta if verifiable, source ID+trust, metadata URL/provider, fetch capability, build/versionCode, cert allowlist, supported ABI/split strategy, rate limit, license constraint, fallback/provenance and last-known-good identity. Do not infer beta from a version string.
- [ ] Messenger primary source: official WhatsApp Android direct-download page (`https://www.whatsapp.com/download/android`) for officially offered direct APK **when authorized and technically available**; verify parsed website claim against the downloaded artifact instead of blindly trusting HTML. Official website may lag Play rollouts. Treat Play listing metadata as **an indication**, not an authorized APK acquisition API.
- [ ] Business primary official metadata: Meta/WhatsApp's official Business store listing (Google Play). Google Play Developer Publishing API is **NOT** a general-purpose interface for downloading another publisher's APK. Only acquire binary from an officially/contractually authorized endpoint or a maintainer-provisioned licensed private artifact (including split APKs/AAB-derived installed set if legally obtained); otherwise mark `BINARY_UNAVAILABLE`, never scrape behind access controls or use random APK mirrors to attain a nominal 'latest'.
- [ ] A source's reported 'latest' may depend on country, device, rollout, track and cache. Persist `source`, `checkedAt`, `observedVersion`, `versionCode`, `track`, `availability` and `confidence`; distinguish `LATEST_FROM_CONFIGURED_SOURCE` from global 'latest', and `UNKNOWN` on inaccessible/ambiguous source.
- [ ] Version discovery must be **network-read-only**, timeout/retry/backoff bounded with checksum-validated deterministic fixture fallback, rate-limited, and free of secrets on untrusted PR forks. On upstream outage, report **`DISCOVERY_UNAVAILABLE`** and preserve last-good data; never assert nothing changed.
- [ ] Compare package ID + channel + versionCode + **artifact SHA-256** + verified signing certificate/lineage + split set + ABI/dex fingerprint. A changed binary with the same version label is **new** and invalidates resolver evidence/cache; filename and website badge alone are insufficient.
- [ ] Persist a compact **version observation manifest** (no third-party APK) as a machine-readable artifact with provenance and timestamps for every build, even when nothing changed. Correctly distinguish `UNCHANGED`, `NEW_VERSION`, `NEW_BINARY_SAME_VERSION`, `VERSION_REGRESSION`, `DISCOVERY_UNAVAILABLE`, `UNVERIFIED_SOURCE`, `BINARY_UNAVAILABLE`.

## 2. Secure acquisition and artifact admission (only when new or evidence stale)

- [ ] Fetch **only** after source policy, authorization, checksum/size allowance, TLS/host/redirect allowlist and artifact trust checks pass. Never bypass Google Play authentication, pin anonymous scraping proxies or depend on unauthorized APK mirrors.
- [ ] Permit a configurable **trusted private artifact provider** for Business/Play-only targets, with restricted credentials/short-lived tokens on trusted runners. If absent, use metadata-only compatibility/last-good evidence and request a human-provided lawful test fixture. No public APK mirror fallback.
- [ ] Validate before any analyzer or install: package ID, versionName, versionCode, Android SDK/ABI/split set, **v2/v3/v4 signing identity/lineage as applicable**, pinned approved publisher signing cert fingerprints/lineage (initial pins derived from known-trusted baseline and reviewed), hash, archive size/file count/ZIP bomb limits. Reject suspicious signed-by-other-publisher APKs, unexpected privileged components and SDK drops with actionable evidence.
- [ ] Separate download area from trusted build workspace; no execution of downloaded binaries on CI host. Run parsing in sandboxed least-privilege containers / isolated Android test devices with no personal accounts, shared secrets or real chats. Restrict network/filesystem; set CPU/RAM/time limits.
- [ ] Keep APKs, AAB/APKS/splits or extracted dex **private, encrypted if persisted and short-lived**; never commit to git, distribute in public GitHub artifact, publish in Release/Telegram, expose in logs/PR comments, or include in WA X APK. Check license/Terms/retention policy before caching. Prefer redacted hash/metadata/test evidence artifacts only.
- [ ] Cache by content-addressed hash and target identity; avoid re-download and full Dex scanning on *every* build if unchanged. Every build must still **check freshness** of upstream metadata and use a recently verified cache only with identical fingerprint/evidence TTL, not a stale assumption.
- [ ] Reject candidate to `SOURCE_UNTRUSTED` or `BINARY_UNAVAILABLE` without blocking unrelated builds silently; release claims requiring coverage of that candidate are gated separately as described below.

## 3. Automated compatibility analysis with per-feature truth

- [ ] Use `tools/compatibility/compatibility.json`, feature registry/metadata (A03), M08 DexKit 2.3.0 and A04 typed resolver boundaries as **one canonical inventory/engine**. Do not synthesize a second 'supported versions' list from release page strings.
- [ ] On changed artifact fingerprint, invalidate all target-specific resolver caches/evidence that depend on changed dex/ABI/SDK/metadata, retaining versioned historical reports and rollback snapshots. Keep WA vs Business and Stable vs Beta wholly isolated.
- [ ] Layered tests: **L0** metadata/version/source/cert checks; **L1** static dex structural diff + versioned symbol/fingerprint comparison; **L2** DexKit resolver probe with exact/heuristic/fallback confidence and timeouts; **L3** synthetic hook/dependency unit tests; **L4** sandbox Android instrumentation on compatible device images; **L5** LSPosed end-to-end runtime smoke with target installed/running and per-feature observables; **L6** manual behavioral validation only when evidence cannot be automated. Clearly map to A14 #348's test-level terminology instead of creating conflicting labels.
- [ ] Classify every feature into at least `VERIFIED_COMPATIBLE`, `VERIFIED_FALLBACK`, `UNVERIFIED`, `INCOMPATIBLE`, `RUNTIME_FAILED`, `DISABLED_BY_USER` and `NOT_IMPLEMENTED`; record evidence source, tested exact build/fingerprint, resolver/capability, confidence, timestamp, test runner level and stage. These labels must not collapse into one green color; `ENABLED` preference is not a compatibility verdict.
- [ ] Only promote a feature to `VERIFIED_COMPATIBLE` when **all required dependencies resolved at sufficient confidence AND required runtime behavior was actually observed** on that exact candidate (or a formally reviewed transferable-equivalence proof, never a version prefix alone). Static-only matches remain `STATIC_MATCH / UNVERIFIED_RUNTIME`.
- [ ] Progressive canary policy: keep features proven safe on the new target; try approved known-safe fallback when exact resolver breaks; hold heuristic/ambiguous/high-risk features until safe validation; **isolate** a failed resolver and its dependants via kill switch/circuit breaker without breaking unrelated features. No user preferences deleted.
- [ ] Measure per-feature hook install/runtime exceptions, crash-loop, watchdog, responsiveness and relevant regressions. Do not run invasive tests on real user data/accounts; some UI/video/network features require an actual device and controlled test fixtures.
- [ ] Diff old→new: `unchanged`, `newly compatible`, `regressed`, `fallback-only`, `unverified`, `unsupported`; match by stable featureId rather than UI label. Distinguish diagnostic cause: binary changed / resolver missing / IPC failed / test environment missing / source down.
- [ ] Generate machine-readable JSON, Markdown summaries and SARIF/annotations for target+version+feature+resolver with **privacy-scrubbed logs only**. Store enough provenance to reproduce, not private messages. Never log contacts, numbers, JIDs, real messages, secrets or paid-entitlement data.

## 4. Automatic repair and human-review policy

- [ ] Fully automatic, **safe and reversible** outcomes: refresh caches, re-resolve symbols, select previously verified alternate resolver paths, repair metadata values only when schema allows and signing/test gates are green; recover previous known-good compatibility data; quarantine individual regressions.
- [ ] Generate a **candidate data-only** signed compatibility/Resolver metadata manifest (M12/F160), with exact target fingerprint, provenance, TTL, kill switch and rollback index. Automatically promote only if all applicable test levels pass, certification/publisher trust is confirmed, signing occurs in a trusted gated job and policy allows it; otherwise keep it as an unmerged candidate for review.
- [ ] AUTO-GENERATE focused GitHub regression Issues only for **confirmed** failures that demand source-code changes or missing test capability, keyed by target+version+fingerprint+feature+failure signature; de-duplicate/update existing reports on rerun. Labels and assignees follow repository conventions, and never upload third-party binaries, secrets or private traces.
- [ ] If target changes semantics, event ordering, hook signature needs actual code, security/account-risk boundary changes, dynamic heuristic confidence is insufficient, or Business artifact unavailable, require **manual source-code/test/fixture review**; do not fabricate patch code or silently merge risky PRs.
- [ ] On automatic check failure, preserve last-good signed WA X release and compatibility manifest, honor user-preferred settings for future recovery, show accurate `UNVERIFIED/DEGRADED`, and keep proven independent features running.
- [ ] No unreviewed automatic release of WA X code, no changing WhatsApp update/Play policy, no downgrade/install on user devices without explicit consent. Binary fetching is CI-only for testing, never WhatsApp auto-installation.

## 5. Every-build CI orchestration, source-specific gates and costs

**Integrate into existing `.github/workflows/ci.yml`** rather than introduce a duplicate release workflow. Implement stages as one reusable script/tool in `tools/compatibility/` with mockable providers, immutable manifests and repeatable fixtures.

1. **`discover-target-versions` (EVERY build event):** source metadata for both targets + channels configured; produce manifest, SHA and provenance; timeout/backoff. This is cheap, always observable and does **not** block code compilation during network outage.
2. **`admit-new-target` (NEW fingerprint OR evidence expired OR forced):** authorized download/private fixture availability, signing identity/hash/version/package/split validation; no binary → clearly `BINARY_UNAVAILABLE`.
3. **`static-compat-analysis` (admitted new binary):** deterministic diff, DexKit cache invalidation, resolver dependency graph, feature impact matrix, baseline comparison, bounded CPU/time.
4. **`synthetic-and-device-canary` (only when test infra and trusted fixtures available):** run synthetic/instrumented/LSPosed checks; separate stage outputs `passed`, `failed`, `not-run`, `inconclusive` and attach evidence.
5. **`prepare-compat-manifest`:** validated data-only candidate with no executable code; signed promotion only via controlled policies and validated compatibility gates.
6. **`report-and-triage`:** annotate checks, update per-target/feature summary, create/update issue for confirmed regression, and feed the Home/Tools UI (#371) without manual copying.
7. **`qualityGate` and `releaseGate`:** preserve current Gradle/static/unit/build gates; require target-discovery job to **report a truthful status** in each run, forbid green supported badges or automated promotion without required evidence; release may pass on **documented last-known-good tested targets** while clearly saying 'latest discovered but not tested' if new target download/probe unavailable. Product/release support claims for a new target must be blocked until evidence proves them. Security or regression failures inside an already-declared target must block the corresponding release claim and may block release according to risk/severity. No silent skip/false green.
8. **`schedule`:** daily target discovery+analysis-if-needed (separate job in same CI definition, not the Ubuntu canary). With no new binary fingerprint, reuse cache and issue a small unchanged report; do not waste device-lab budget on every no-op.

### Evidence and performance budgets
- [ ] Each CI run exposes `target|channel|source|observed version|fingerprint|discovery timestamp|artifact available?|analysis level|confirmed-compatible count|fallback|unknown|failed|blocked|next action`.
- [ ] Limit network requests/size, parser time, DexKit memory/CPU, emulator/device hours and artifact storage with explicit timeout, retries, concurrency and cancellation policy; cache unchanged candidates.
- [ ] PR from fork: read-only untrusted context, no signing/source secrets and no test-fixture exfiltration. Trusted main/tag lanes may use locked-down credentials and private runners. Untrusted content never directly controls shell commands/host/URL.
- [ ] Check workflows at parse/CI level for the event matrix, job conditions, permission scoping, fail/unknown and source-down semantics; test using synthetic metadata providers and fixture APKs developed for tests, not copyrighted binaries in git.
- [ ] There is no hard-coded claim that upstream 'latest' always has a downloadable standalone APK, especially Business/Play distributed split artifacts.

## 6. Home / Tools / Features integration (UIX-01 #371)

- Show **Installed** vs **Latest detected at configured source** vs **Latest verified by WA X** for WhatsApp and Business; show source, track and time, not a vague `latest` promise.
- After new build detection show `Auto-analysis pending / Analyzing / Verified / Partially compatible / Unverified / Regression found / Artifact unavailable`.
- Count feature support per category and per target as `verified`, `fallback`, `unknown`, `failed`, `planned`. Deep-link each failure to safe remedy, test stage and evidence.
- Explain reasons and safe options, show whether a newer WA X compatibility data-pack exists and when it was signed, and allow one-tap manual recheck.
- Never use fake runtime statuses. Do not download/upload WhatsApp APK via the Manager or expose a user's app files unless they explicitly provide a permitted local fixture.

## 7. Definition of Done / gates

- [ ] Test matrix: PR/main/tag/manual/discovery schedule × WhatsApp/Business × Stable/Beta available × no-new-version/new-version/same-version-new-binary/source-unreachable/missing-artifact/malicious-cert/changed-signing-lineage/split-set/rollback.
- [ ] Per-feature stable IDs; no unverified candidate automatically published as supported, no false green or total startup failure from one optional resolver.
- [ ] Cryptographic authentic source checks, private artifact retention, verified signing/provenance and no third-party APK in public outputs.
- [ ] Deterministic synthetic fixtures, L0–L5 evidence (or clear unavailable/not-run) and REAL target smoke on both packages where fixtures/devices exist; test old→new diff and safe rollback.
- [ ] No manual intervention on straightforward unchanged/resolved/fallback-safe paths; manual review escalated precisely when needed with one deduplicated issue and diagnostics.
- [ ] All current CI/release signatures, strict gates, changelog and rollback pass; package/test budget recorded; no new warnings.
- [ ] Implementation staged behind the mandatory #318/#333 dependency gates, with each milestone demonstrably green before the next. **This document is a plan, not proof of completed build automation.**

## 8. Owner mapping

| Existing Issue | Owner (do not duplicate) |
|---|---|
| #327 M08 / #338 A04 | Resolver/DexKit scanning, cache-fingerprints, exact/heuristic/fallback and safe budgets |
| #330 M11 / #348 A14 | Every-build discovery orchestration, CI gates, synthetic and device test stages |
| #331 M12 / #339 A05 | Signed compatibility data, runtime states, per-feature kill switches and recovery |
| #332 M13 | Release smoke matrix, rollout/rollback, real target verification and stable/beta rules |
| #177 F159 | old→new target diff, regression and impact report |
| #178 F160 | data-only signed metadata candidates, validation and rollback |
| #169 F154 / #170 F155 / #184 F166 | capability discovery, optional manual self-test and detailed root-cause diagnostics |
| #71 F062 | installed/latest observed/latest verified target version UX and update recommendation |
| #351 A17 | artifact provenance, signature/lineage/retention/license and SBOM evidence |
| #371 UIX-01 | Home/Features/Tools display; no second analyzer |

**Scope control:** Target-binary metadata retrieval is a CI concern. User requests a highly automated process; if a source makes automatic binary acquisition impossible, report this faithfully rather than bypassing access limits or inventing compatibility evidence.
