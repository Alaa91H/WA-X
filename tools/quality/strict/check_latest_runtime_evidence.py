#!/usr/bin/env python3
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
DEFAULT_COMPAT = os.path.join(ROOT, "tools", "compatibility", "compatibility.json")
DEFAULT_EVIDENCE = os.path.join(ROOT, "quality", "device-lab", "latest-runtime-evidence.json")
DEFAULT_POLICY = os.path.join(ROOT, "quality", "strict-policy.json")


def load(path: str) -> dict:
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def latest_pattern(versions: list[str]) -> str:
    def key(value: str):
        return tuple(int(x) if x.isdigit() else -1 for x in value.replace("xx", "-1").split("."))
    return sorted(versions, key=key)[-1]


def matches_train(version: str, pattern: str) -> bool:
    escaped = re.escape(pattern).replace("xx", r"\d+")
    return re.fullmatch(escaped, version) is not None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--compat", default=DEFAULT_COMPAT)
    parser.add_argument("--evidence", default=DEFAULT_EVIDENCE)
    parser.add_argument("--policy", default=DEFAULT_POLICY)
    parser.add_argument("--out", default=os.path.join(ROOT, "build", "strict-quality", "latest-runtime.json"))
    parser.add_argument("--commit", default="")
    args = parser.parse_args()

    compat, evidence, policy = load(args.compat), load(args.evidence), load(args.policy)
    issues: list[dict[str, object]] = []
    expected_commit = args.commit.strip() or subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    if evidence.get("moduleCommit") != expected_commit:
        issues.append({"type":"commit","message":f"device evidence commit {evidence.get('moduleCommit')!r} != {expected_commit}"})

    generated_at = evidence.get("generatedAt", "")
    try:
        stamp = dt.datetime.fromisoformat(generated_at.replace("Z", "+00:00"))
        age = dt.datetime.now(dt.timezone.utc) - stamp.astimezone(dt.timezone.utc)
        max_days = int(policy["latestCompatibility"]["maxEvidenceAgeDays"])
        if age > dt.timedelta(days=max_days):
            issues.append({"type":"stale","message":f"device evidence is {age.days} day(s) old; max is {max_days}"})
    except Exception:
        issues.append({"type":"timestamp","message":"generatedAt must be a valid ISO-8601 timestamp"})

    features = compat.get("derived", {}).get("features", [])
    feature_ids = [item["id"] for item in features]
    resolver_ids = sorted({r for item in features for r in item.get("resolverDependencies", [])})

    registry_path = os.path.join(ROOT, "app", "src", "main", "java", "com", "wax", "module", "settings", "SettingKeyRegistry.kt")
    registry_text = open(registry_path, "r", encoding="utf-8").read()
    preference_ids = sorted(set(re.findall(r'Entry\(\s*"([^"]+)"', registry_text)))

    for target_key in policy["latestCompatibility"]["targets"]:
        package = compat.get("packages", {}).get(target_key, {})
        target = evidence.get("targets", {}).get(target_key, {})
        latest = latest_pattern(package.get("declaredVersions", [])) if package.get("declaredVersions") else ""

        if target.get("packageName") != package.get("packageName"):
            issues.append({"type":"package","target":target_key,"message":f"{target_key}: packageName mismatch"})
        version = target.get("version", "")
        if not latest or not matches_train(version, latest):
            issues.append({"type":"version","target":target_key,"message":f"{target_key}: {version!r} does not match newest declared train {latest!r}"})

        sha = target.get("apkSha256", "")
        if not re.fullmatch(r"[0-9a-fA-F]{64}", sha):
            issues.append({"type":"apk-sha256","target":target_key,"message":f"{target_key}: apkSha256 must be 64 hex characters"})

        device = target.get("device", {})
        if device.get("rooted") is not True or not device.get("lsposedVersion"):
            issues.append({"type":"device","target":target_key,"message":f"{target_key}: rooted LSPosed device evidence is required"})
        if not isinstance(device.get("api"), int) or device.get("api", 0) < 28:
            issues.append({"type":"device-api","target":target_key,"message":f"{target_key}: invalid Android API evidence"})

        observed_features = target.get("features", {})
        for feature_id in feature_ids:
            if observed_features.get(feature_id) != policy["latestCompatibility"]["requiredFeatureResult"]:
                issues.append({"type":"feature","target":target_key,"feature":feature_id,"message":f"{target_key}/{feature_id}: latest runtime result is not passed"})

        observed_resolvers = target.get("resolvers", {})
        for resolver in resolver_ids:
            if observed_resolvers.get(resolver) != "resolved":
                issues.append({"type":"resolver","target":target_key,"resolver":resolver,"message":f"{target_key}/{resolver}: resolver is not proven resolved"})

        observed_prefs = target.get("preferences", {})
        for key in preference_ids:
            record = observed_prefs.get(key)
            if not isinstance(record, dict):
                issues.append({"type":"preference","target":target_key,"preference":key,"message":f"{target_key}/{key}: no E2E UI evidence"})
                continue
            if record.get("visible") is not True:
                issues.append({"type":"preference-visible","target":target_key,"preference":key,"message":f"{target_key}/{key}: option was not visible"})
            if record.get("result") != policy["latestCompatibility"]["requiredPreferenceResult"]:
                issues.append({"type":"preference-result","target":target_key,"preference":key,"message":f"{target_key}/{key}: interaction did not pass"})

    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump({"issueCount":len(issues),"issues":issues}, handle, indent=2)
        handle.write("\n")

    print(f"latest runtime evidence: {len(issues)} issue(s)")
    for issue in issues[:250]:
        print(f"::error::{issue['message']}")
    if len(issues) > 250:
        print(f"::error::{len(issues)-250} additional runtime-evidence issues are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
