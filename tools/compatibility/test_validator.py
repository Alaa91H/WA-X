#!/usr/bin/env python3
"""Mutation tests for validate_compatibility.py (T01).

A validator that always passes is worse than no validator. Each case below breaks
exactly one invariant and asserts that the validator rejects the document.

Usage:
    python3 tools/compatibility/test_validator.py
"""

from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
VALIDATOR = os.path.join(HERE, "validate_compatibility.py")
MATRIX = os.path.join(HERE, "compatibility.json")

# A feature with real resolver dependencies, used by the evidence cases.
DEPENDED = "AntiRevoke"


def run_validator(path: str) -> tuple[int, str]:
    result = subprocess.run(
        [sys.executable, VALIDATOR, "--matrix", path],
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
    )
    return result.returncode, result.stdout + result.stderr


def with_mutation(mutate) -> str:
    with open(MATRIX, "r", encoding="utf-8") as handle:
        document = json.load(handle)
    mutate(document)
    handle_fd, path = tempfile.mkstemp(suffix=".json", prefix="wae-matrix-")
    with os.fdopen(handle_fd, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(document, handle, indent=2, ensure_ascii=False)
    return path


def drop_a_feature(document: dict) -> None:
    document["derived"]["features"] = [
        feature
        for feature in document["derived"]["features"]
        if feature["id"] != "Others"
    ]


def claim_supported_without_evidence(document: dict) -> None:
    document["matrix"] = {DEPENDED: {"whatsapp": {"versions": {"2.26.40.xx": "supported"}}}}


def claim_supported_with_partial_evidence(document: dict) -> None:
    feature = next(
        item for item in document["derived"]["features"] if item["id"] == DEPENDED
    )
    required = feature["resolverDependencies"]
    document["matrix"] = {DEPENDED: {"whatsapp": {"versions": {"2.26.40.xx": "supported"}}}}
    # Evidence covers only the first resolver; the rest are still unproven.
    document["evidence"] = {
        DEPENDED: {
            "resolvers": {
                required[0]: {"result": "resolved", "verifiedAt": "2026-10-04"}
            }
        }
    }


def claim_supported_with_unverified_timestamp(document: dict) -> None:
    feature = next(
        item for item in document["derived"]["features"] if item["id"] == DEPENDED
    )
    document["matrix"] = {DEPENDED: {"whatsapp": {"versions": {"2.26.40.xx": "supported"}}}}
    document["evidence"] = {
        DEPENDED: {
            "resolvers": {
                name: {"result": "resolved", "verifiedAt": None}
                for name in feature["resolverDependencies"]
            }
        }
    }


def drift_declared_versions(document: dict) -> None:
    document["packages"]["whatsapp"]["declaredVersions"].append("2.27.99.xx")


def drift_module_sdk(document: dict) -> None:
    document["module"]["minSdk"] = 21


def drift_feature_tier(document: dict) -> None:
    for feature in document["derived"]["features"]:
        if feature["id"] == DEPENDED:
            feature["resolutionTier"] = "none"
            feature["resolverDependencies"] = []


def unknown_dimension(document: dict) -> None:
    document["matrix"] = {DEPENDED: {"whatsapp": {"locale": {"ar": "supported"}}}}


def unknown_package(document: dict) -> None:
    document["matrix"] = {DEPENDED: {"telegram": {"versions": {"1.0.xx": "unknown"}}}}


def invalid_status_value(document: dict) -> None:
    document["matrix"] = {DEPENDED: {"whatsapp": {"versions": {"2.26.40.xx": "probably"}}}}


def unknown_feature_reference(document: dict) -> None:
    document["matrix"] = {"NotAFeature": {"whatsapp": {"versions": {"2.26.40.xx": "unknown"}}}}


def bump_schema_version(document: dict) -> None:
    document["schemaVersion"] = 99


CASES: list[tuple[str, object, bool, str]] = [
    ("pristine document passes", None, True, ""),
    ("a feature dropped from the inventory", drop_a_feature, False, "stale"),
    # These legacy mutation fixtures use wildcard versions. The stricter target
    # gate must reject those claims before examining incomplete resolver data.
    # Exact-version resolver failures are exercised by test_validate_compatibility.py.
    ("supported claimed with no evidence", claim_supported_without_evidence, False,
     "without an exact target version"),
    ("supported claimed with partial evidence", claim_supported_with_partial_evidence, False,
     "without an exact target version"),
    ("supported claimed without verifiedAt", claim_supported_with_unverified_timestamp, False,
     "without an exact target version"),
    ("declared versions drift from arrays.xml", drift_declared_versions, False,
     "drifted from supported_versions_wpp"),
    ("module SDK drifts from build.gradle.kts", drift_module_sdk, False, "app/build.gradle.kts says"),
    ("feature resolution tier drift", drift_feature_tier, False, "stale"),
    ("unknown dimension key", unknown_dimension, False, "unknown dimension"),
    ("unknown package key", unknown_package, False, "unknown package"),
    ("invalid status value", invalid_status_value, False, "invalid status"),
    ("matrix references an unknown feature", unknown_feature_reference, False,
     "unknown feature"),
    ("unsupported schemaVersion", bump_schema_version, False, "unsupported schemaVersion"),
]


def main() -> int:
    failures = 0
    for name, mutation, should_pass, expected_text in CASES:
        if mutation is None:
            code, output = run_validator(MATRIX)
            path = None
        else:
            path = with_mutation(mutation)
            code, output = run_validator(path)

        ok = (code == 0) if should_pass else (code == 1)
        if ok and not should_pass and expected_text not in output:
            ok = False
            name += " (missing expected diagnostic %r)" % expected_text

        status = "pass" if ok else "FAIL"
        print("[%s] %s (exit %d)" % (status, name, code))
        if not ok:
            failures += 1
            for line in output.strip().splitlines():
                print("         %s" % line)

        if path:
            os.unlink(path)

    print()
    if failures:
        print("%d of %d validator cases failed" % (failures, len(CASES)))
        return 1
    print("all %d validator cases behaved correctly" % len(CASES))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
