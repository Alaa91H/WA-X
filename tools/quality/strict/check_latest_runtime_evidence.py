#!/usr/bin/env python3
"""Require device-lab evidence for the newest declared WhatsApp and Business trains.

`quality/strict-policy.json` says a release may only claim what was observed on the
newest declared target. This check is the mechanical half of that: it compares the
compatibility matrix, the generated settings registry and a recorded device-lab run, and
fails on anything the run does not prove.

What it enforces, per target:

  * the evidence names the exact module commit this run is building
  * it is recent enough to still describe the current build
  * it was taken on the newest declared train, on a rooted LSPosed device
  * every registered feature resolved, every resolver it depends on resolved, and every
    visible preference was both seen and interacted with successfully

A missing or unreadable input is a finding, never a pass: the failure mode of a gate like
this is a document that stopped being regenerated and quietly stopped meaning anything.

Exit codes:
    0  every target's evidence is complete and current
    1  at least one unproven claim
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
    ROOT,
    "app",
    "src",
    "main",
    "java",
    "com",
    "wax",
    "module",
    "settings",
    "SettingKeyRegistry.kt",
)

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
        return 1

    issues: list[dict[str, object]] = []

    expected_commit = args.commit.strip() or head_commit() or ""
    if not expected_commit:
        issues.append(
            {"type": "commit", "message": "no module commit to compare against; pass --commit"}
        )
    elif evidence.get("moduleCommit") != expected_commit:
        issues.append(
            {
                "type": "commit",
                "message": f"device evidence commit {evidence.get('moduleCommit')!r} != {expected_commit}",
            }
        )

    max_days = int(policy.get("latestCompatibility", {}).get("maxEvidenceAgeDays", 0))
    age = evidence_age_days(str(evidence.get("generatedAt", "")))
    if age is None:
        issues.append(
            {
                "type": "timestamp",
                "message": "generatedAt must be an ISO-8601 timestamp with an explicit UTC offset",
            }
        )
    elif max_days and age > dt.timedelta(days=max_days):
        issues.append(
            {
                "type": "stale",
                "message": f"device evidence is {age.days} day(s) old; max is {max_days}",
            }
        )

    ids, resolvers = feature_ids(compat)
    preferences = preference_ids()
    required_feature = policy.get("latestCompatibility", {}).get("requiredFeatureResult")
    required_preference = policy.get("latestCompatibility", {}).get("requiredPreferenceResult")

    for target_key in policy.get("latestCompatibility", {}).get("targets", []):
        package = compat.get("packages", {}).get(target_key, {})
        target = evidence.get("targets", {}).get(target_key, {})
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

        observed_features = target.get("features", {})
        for feature in ids:
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

    directory = os.path.dirname(args.out)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump({"issueCount": len(issues), "issues": issues}, handle, indent=2)
        handle.write("\n")

    print(f"latest runtime evidence: {len(issues)} issue(s)")
    for issue in issues[:MAX_REPORTED]:
        print(f"::error::{issue['message']}")
    if len(issues) > MAX_REPORTED:
        print(f"::error::{len(issues) - MAX_REPORTED} additional runtime-evidence issues are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
