#!/usr/bin/env python3
"""Fail when the compatibility matrix claims support that no device-lab run proves.

The matrix is the claim. This check asks one question: does the project assert anything
works, and if so is that assertion backed by a recorded run?

Today every cell in `compatibility.json` is `unknown`, which its own `statusVocabulary`
defines as "the only status permitted without evidence", and `evidence` is empty. Under
that state there is nothing to prove and this gate passes - it reports the number of claims
it found so the position is visible rather than silent.

Once a maintainer marks a cell `supported` or `degraded`, the same gate that was always
here becomes binding for that target, and the evidence has to satisfy every condition it
checked before: this exact module commit, inside the freshness window, on the newest
declared train, on a rooted LSPosed device at API 28 or newer, with a recorded APK SHA-256,
every registered feature resolved, every resolver each feature depends on resolved, and
every visible preference both seen and interacted with.

That is the shape of gate the claim deserves. A rooted LSPosed device with both WhatsApp
builds installed is not something a CI runner has, so demanding one unconditionally would
block every release on hardware nobody has - while proving nothing, because the matrix was
already refusing to claim anything.

Exit codes:
    0  no target claims support, or every claim is backed by fresh evidence
    1  a claim has no evidence, or the evidence does not satisfy it
    2  bad input
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
DEFAULT_COMPAT = os.path.join(ROOT, "tools", "compatibility", "compatibility.json")
DEFAULT_EVIDENCE = os.path.join(ROOT, "quality", "device-lab", "latest-runtime-evidence.json")
DEFAULT_POLICY = os.path.join(ROOT, "quality", "strict-policy.json")
DEFAULT_OUT = os.path.join(ROOT, "build", "strict-quality", "latest-runtime.json")
REGISTRY = os.path.join(
    ROOT, "app", "src", "main", "java", "com", "wax", "module", "settings", "SettingKeyRegistry.kt"
)

# The statuses that assert something works. `unknown` asserts nothing and needs no
# evidence; `unsupported` asserts the opposite, which no run is needed to establish.
CLAIMED_STATUSES = ("supported", "degraded")

MAX_REPORTED = 250
MIN_DEVICE_API = 28
SHA256_RE = re.compile(r"[0-9a-fA-F]{64}")
ENTRY_KEY_RE = re.compile(r'Entry\(\s*"([^"]+)"')


def load(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def head_commit() -> str | None:
    """The commit this checkout is at, or None when git cannot answer."""
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return None
    return proc.stdout.strip() or None if proc.returncode == 0 else None


def latest_pattern(versions: list[str]) -> str:
    """The highest declared version, as the train pattern it stands for."""

    def key(value: str) -> tuple[int, ...]:
        return tuple(int(part) if part.isdigit() else -1 for part in value.replace("xx", "-1").split("."))

    return sorted(versions, key=key)[-1]


def matches_train(version: str, pattern: str) -> bool:
    return re.fullmatch(re.escape(pattern).replace("xx", r"\d+"), version) is not None


def evidence_age_days(generated_at: str) -> dt.timedelta | None:
    """How old the recorded run is, or None when the timestamp is unusable."""
    try:
        stamp = dt.datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError):
        return None
    if stamp.tzinfo is None:
        return None
    return dt.datetime.now(dt.timezone.utc) - stamp.astimezone(dt.timezone.utc)


def feature_ids(compat: dict) -> tuple[list[str], list[str]]:
    """Every registered feature id and every resolver id the features depend on."""
    features = compat.get("derived", {}).get("features", [])
    ids = [str(item.get("id", "")) for item in features if item.get("id")]
    resolvers = sorted({str(name) for item in features for name in item.get("resolverDependencies", [])})
    return ids, resolvers


def preference_ids() -> list[str]:
    """Every overrideable preference key, read from the generated registry."""
    with open(REGISTRY, "r", encoding="utf-8") as handle:
        return sorted(set(ENTRY_KEY_RE.findall(handle.read())))


def claimed_targets(compat: dict) -> dict[str, int]:
    """How many cells per target assert that something works.

    Read from both places a status can live: the per-target default, and any explicit cell
    in the matrix. A target whose default is `unknown` but which has a `supported` cell is
    claiming, and is checked.
    """
    claims: dict[str, int] = {}
    for target, package in (compat.get("packages") or {}).items():
        count = 0
        default = package.get("defaultStatus")
        if default in CLAIMED_STATUSES:
            # A default applies to every feature that has no explicit cell.
            count += len(feature_ids(compat)[0])
        for feature, states in ((compat.get("matrix") or {}).get(target) or {}).items():
            if isinstance(states, dict):
                if states.get("status") in CLAIMED_STATUSES:
                    count += 1
            elif states in CLAIMED_STATUSES:
                count += 1
        claims[target] = count
    return claims


def verify_target(
    target_key: str,
    compat: dict,
    evidence: dict,
    policy: dict,
    commit: str,
    feature_ids_list: list[str],
    resolvers: list[str],
    preferences: list[str],
    freshness: dict[str, object],
) -> list[dict[str, object]]:
    issues: list[dict[str, object]] = []
    package = (compat.get("packages") or {}).get(target_key, {})
    target = (evidence.get("targets") or {}).get(target_key, {})
    declared = package.get("declaredVersions") or []
    newest = latest_pattern(declared) if declared else ""

    if target.get("packageName") != package.get("packageName"):
        issues.append(
            {"type": "package", "target": target_key, "message": f"{target_key}: packageName mismatch"}
        )

    version = str(target.get("version", ""))
    if not newest or not matches_train(version, newest):
        issues.append(
            {
                "type": "version",
                "target": target_key,
                "message": f"{target_key}: {version!r} does not match newest declared train {newest!r}",
            }
        )

    if not SHA256_RE.fullmatch(str(target.get("apkSha256", ""))):
        issues.append(
            {
                "type": "apk-sha256",
                "target": target_key,
                "message": f"{target_key}: apkSha256 must be 64 hex characters",
            }
        )

    device = target.get("device", {})
    if device.get("rooted") is not True or not device.get("lsposedVersion"):
        issues.append(
            {
                "type": "device",
                "target": target_key,
                "message": f"{target_key}: rooted LSPosed device evidence is required",
            }
        )
    if not isinstance(device.get("api"), int) or int(device.get("api", 0)) < MIN_DEVICE_API:
        issues.append(
            {
                "type": "device-api",
                "target": target_key,
                "message": f"{target_key}: invalid Android API evidence",
            }
        )

    if evidence.get("moduleCommit") != commit:
        issues.append(
            {
                "type": "commit",
                "target": target_key,
                "message": f"device evidence commit {evidence.get('moduleCommit')!r} != {commit}",
            }
        )

    age = freshness.get("age")
    max_days = freshness.get("maxDays")
    if age is None:
        issues.append(
            {
                "type": "timestamp",
                "target": target_key,
                "message": "generatedAt must be an ISO-8601 timestamp with an explicit UTC offset",
            }
        )
    elif isinstance(max_days, int) and max_days and age > dt.timedelta(days=max_days):
        issues.append(
            {
                "type": "stale",
                "target": target_key,
                "message": f"device evidence is {age.days} day(s) old; max is {max_days}",
            }
        )

    required_feature = policy.get("latestCompatibility", {}).get("requiredFeatureResult")
    required_preference = policy.get("latestCompatibility", {}).get("requiredPreferenceResult")

    observed_features = target.get("features", {})
    for feature in feature_ids_list:
        if observed_features.get(feature) != required_feature:
            issues.append(
                {
                    "type": "feature",
                    "target": target_key,
                    "feature": feature,
                    "message": f"{target_key}/{feature}: latest runtime result is not passed",
                }
            )

    observed_resolvers = target.get("resolvers", {})
    for resolver in resolvers:
        if observed_resolvers.get(resolver) != "resolved":
            issues.append(
                {
                    "type": "resolver",
                    "target": target_key,
                    "resolver": resolver,
                    "message": f"{target_key}/{resolver}: resolver is not proven resolved",
                }
            )

    observed_preferences = target.get("preferences", {})
    for key in preferences:
        record = observed_preferences.get(key)
        if not isinstance(record, dict):
            issues.append(
                {
                    "type": "preference",
                    "target": target_key,
                    "preference": key,
                    "message": f"{target_key}/{key}: no E2E UI evidence",
                }
            )
            continue
        if record.get("visible") is not True:
            issues.append(
                {
                    "type": "preference-visible",
                    "target": target_key,
                    "preference": key,
                    "message": f"{target_key}/{key}: option was not visible",
                }
            )
        if record.get("result") != required_preference:
            issues.append(
                {
                    "type": "preference-result",
                    "target": target_key,
                    "preference": key,
                    "message": f"{target_key}/{key}: interaction did not pass",
                }
            )

    return issues


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--compat", default=DEFAULT_COMPAT)
    parser.add_argument("--evidence", default=DEFAULT_EVIDENCE)
    parser.add_argument("--policy", default=DEFAULT_POLICY)
    parser.add_argument("--out", default=DEFAULT_OUT)
    parser.add_argument(
        "--commit",
        default="",
        help="the commit this run is building; read from git when omitted",
    )
    args = parser.parse_args()

    try:
        compat = load(args.compat)
        evidence = load(args.evidence)
        policy = load(args.policy)
    except (OSError, json.JSONDecodeError) as error:
        print(f"::error::cannot read a strict input: {error}")
        return 2

    ids, resolvers = feature_ids(compat)
    preferences = preference_ids()
    claims = claimed_targets(compat)
    unproven = sorted(target for target, count in claims.items() if count)

    directory = os.path.dirname(args.out)
    if directory:
        os.makedirs(directory, exist_ok=True)

    if not unproven:
        total = sum(claims.values())
        payload = {
            "claims": claims,
            "verifiedTargets": [],
            "issueCount": 0,
            "issues": [],
            "note": (
                "The compatibility matrix records no supported or degraded cell, so nothing "
                "is claimed and there is no runtime evidence to prove. tools/compatibility/"
                "validate_compatibility.py refuses a supported cell without resolver evidence, "
                "so this position cannot drift without that gate failing first."
            ),
        }
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2)
            handle.write("\n")

        print("Latest-target runtime evidence")
        for target, count in sorted(claims.items()):
            print(f"  {target}: {count} cell(s) claim support")
        print(f"  {total} claim(s) in total")
        print(
            "No cell claims supported or degraded, so there is no runtime evidence to prove. "
            "A device-lab run becomes binding for a target the moment one does."
        )
        return 0

    commit = args.commit.strip() or head_commit() or ""
    if not commit:
        print("::error::no module commit to compare against; pass --commit")
        return 2

    max_days = int(policy.get("latestCompatibility", {}).get("maxEvidenceAgeDays", 0))
    freshness = {"age": evidence_age_days(str(evidence.get("generatedAt", ""))), "maxDays": max_days}

    issues: list[dict[str, object]] = []
    for target_key in unproven:
        issues.extend(
            verify_target(
                target_key,
                compat,
                evidence,
                policy,
                commit,
                ids,
                resolvers,
                preferences,
                freshness,
            )
        )

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(
            {"claims": claims, "verifiedTargets": unproven, "issueCount": len(issues), "issues": issues},
            handle,
            indent=2,
        )
        handle.write("\n")

    print("Latest-target runtime evidence")
    for target_key in unproven:
        print(f"  {target_key}: {claims[target_key]} cell(s) claim support; evidence required")
    print(f"  {len(issues)} issue(s)")
    for issue in issues[:MAX_REPORTED]:
        print(f"::error::{issue['message']}")
    if len(issues) > MAX_REPORTED:
        print(f"::error::{len(issues) - MAX_REPORTED} additional issues are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())