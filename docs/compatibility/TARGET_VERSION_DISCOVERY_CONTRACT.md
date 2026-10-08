# Target version discovery contract

**Status:** Standalone metadata discovery preparation for #377 / sub-issue #379. This package is not wired into CI and does not establish WhatsApp compatibility.

## Sources and honest outcomes

| Target | Configured source | Exact version handling |
|---|---|---|
| Messenger (`com.whatsapp`) | Official Android download page | Reads one visible, explicitly labeled version value. Reports `UNKNOWN` when missing or ambiguous. |
| Business (`com.whatsapp.w4b`) | Public Google Play listing | Reports an exact version only if the official listing visibly exposes one explicitly labeled version. Update date alone remains `UNKNOWN`. |

The claim is scoped to the configured page, locale and observation time. It is not a claim about every country, rollout cohort, device, Beta track, installed APK, versionCode or binary fingerprint. A source page is not permission to download an APK. This tool does not scrape behind access controls or use mirrors.

## JSON manifest

Run from the repository root:

```sh
python3 tools/compatibility/discovery/cli.py
python3 tools/compatibility/discovery/cli.py --target whatsapp --target business
```

The CLI emits schema version 1 with one observation per requested target. Each row contains package ID, source ID and URL, channel, observation time, state, exact source version if exposed, optional source update date, confidence and a human-readable detail. `version_code` is deliberately null. A result can be `LATEST_FROM_SOURCE`, `UNKNOWN` or `DISCOVERY_UNAVAILABLE`; an outage is never represented as unchanged.

The process uses HTTPS only, exact source-host allowlists including every redirect, a timeout, a response-size bound and visible HTML text (script/style payloads are ignored). The transport is injectable, so tests use synthetic fixtures and do not contact live sources.

Comparison reports only `NEW_VERSION`, `VERSION_CHANGED`, `UNCHANGED` or `UNKNOWN` for exact version labels. Matching version text does not prove the APK is unchanged. This package has no binary hash, signing identity, APK acquisition, resolver scan or feature-verdict capability.

## Integration boundaries

- M08 #327 and A04 #338 own DexKit, resolver, fingerprint and cache semantics.
- M11 #330 / A14 #348 own CI event orchestration, report gates, synthetic/device testing and production integration.
- A17 #351 owns source authorization, binary admission, signature/provenance, retention and supply-chain evidence.
- M12 #331 / A05 #339 and F159/F160 own signed data-only compatibility promotion, runtime outcomes, diff, rollback and triage.
- M13 #332 owns device/release matrix and rollout proof; UIX-01 #371 owns product display.

Do not integrate or promote these observations until ordered phase gates and owners approve the contract. Metadata discovery cannot claim a feature is supported.
