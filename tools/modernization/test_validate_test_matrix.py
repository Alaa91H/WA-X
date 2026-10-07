#!/usr/bin/env python3
"""Self-test for tools/modernization/validate_test_matrix.py.

A test matrix is the most dangerous kind of document, because its failure mode is silence: a
matrix that quietly stops listing a lifecycle scenario does not look broken, it looks current. And
a matrix whose evidence rule is decorative is worse than no matrix, because it converts an
unverified assumption into an apparent guarantee.

So the checker is mutation-tested the same way the loader-contract checker is. Build a matrix that
satisfies it, break exactly one thing, assert it fails.

The cases that matter most are the ones a hand-written review would miss: a matrix with every
required row present but a cell claiming `verified` with nothing behind it, and a matrix that has
silently dropped Android 16.

Usage:
    python3 tools/modernization/test_validate_test_matrix.py
Exit codes: 0 every case behaved as expected, 1 a case did not.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load():
    spec = importlib.util.spec_from_file_location(
        "validate_test_matrix", os.path.join(HERE, "validate_test_matrix.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


VALIDATOR = load()
COMMIT = "a" * 40


def base_matrix() -> dict:
    """A matrix that satisfies every rule, built here rather than copied from the repository.

    Copied from the real file it would stop testing anything the moment the real file changed,
    which is the same decay the checker exists to prevent elsewhere.
    """
    return {
        "statusVocabulary": {
            "unverified": "no run",
            "verified": "a run passed",
            "known-broken": "a run failed",
            "not-applicable": "cannot apply",
        },
        "evidencePolicy": {"requiredFor": ["verified", "known-broken"]},
        "androidLevels": [
            {"label": "Android 14", "api": 34, "status": "unverified", "rationale": "targetSdk"},
            {"label": "Android 15", "api": 35, "status": "unverified", "rationale": "behaviour changes"},
            {"label": "Android 16", "api": 36, "status": "unverified", "rationale": "minor SDK scheme"},
            {"label": "Android 17", "api": 37, "status": "unverified", "rationale": "compileSdk"},
        ],
        "lsposedBuilds": [
            {"label": "LSPosed stable", "status": "unverified", "rationale": "baseline"},
            {"label": "LSPosed source", "status": "unverified", "rationale": "second implementation"},
        ],
        "targets": [
            {
                "package": "com.whatsapp",
                "trains": [
                    {"label": "stable", "status": "unverified", "rationale": "declared"},
                    {"label": "beta", "status": "unverified", "rationale": "directive"},
                ],
            },
            {
                "package": "com.whatsapp.w4b",
                "trains": [
                    {"label": "stable", "status": "unverified", "rationale": "separate package"},
                    {"label": "beta", "status": "unverified", "rationale": "separate build"},
                ],
            },
        ],
        "lifecycleScenarios": [
            {
                "id": scenario,
                "label": scenario,
                "status": "unverified",
                "asserts": "something",
                "why": "something",
            }
            for scenario in VALIDATOR.REQUIRED_SCENARIOS
        ],
        "blockingCouplings": [
            {"id": "COUPLE-01", "between": "a", "consequence": "b", "owner": "#1"}
        ],
        "evidence": {},
    }


def run(matrix: dict, commit: str = COMMIT) -> tuple[int, list[str]]:
    """Run every check and return (exit-code, failures) without the reporter's console output.

    emit() is deliberately not used for the verdict: it prints each failure to the real stderr,
    and twenty mutation cases each printing ten unrelated failures makes the one that matters
    impossible to find.
    """
    report = VALIDATOR.Report()
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        VALIDATOR.check_status(matrix, report)
        VALIDATOR.check_structure(matrix, report)
        VALIDATOR.check_evidence(matrix, report, commit)
        VALIDATOR.check_coupling(matrix, report)
        code = report.emit()
    return code, report.failures


def case(name: str, expect_pass: bool, mutate=None, commit: str = COMMIT) -> bool:
    matrix = base_matrix()
    if mutate is not None:
        mutate(matrix)
    ok, failures = run(matrix, commit)
    if expect_pass:
        if ok != 0:
            print("[fail] %s: expected a clean run, got %s" % (name, failures))
            return False
        print("[pass] %s" % name)
        return True
    if ok == 0:
        print("[fail] %s: expected a failure, got a clean run" % name)
        return False
    print("[pass] %s" % name)
    return True


def drop_scenario(scenario: str):
    def mutate(matrix: dict) -> None:
        matrix["lifecycleScenarios"] = [
            entry for entry in matrix["lifecycleScenarios"] if entry["id"] != scenario
        ]

    return mutate


def main() -> int:
    cases: list[tuple[str, bool, object]] = [
        ("a complete matrix passes", True, None),
        ("a matrix missing a required scenario is caught", False, drop_scenario("rollback")),
        ("a matrix missing safe-mode is caught", False, drop_scenario("safe-mode")),
        ("a matrix missing corrupted-cache is caught", False, drop_scenario("corrupted-cache")),
        (
            "a matrix missing WhatsApp Business is caught",
            False,
            lambda m: m["targets"].pop(1),
        ),
        (
            "a matrix missing the beta train is caught",
            False,
            lambda m: m["targets"][0]["trains"].pop(1),
        ),
        (
            "a matrix missing an Android level is caught",
            False,
            lambda m: m["androidLevels"].pop(2),
        ),
        (
            "a matrix with only one LSPosed build is caught",
            False,
            lambda m: m["lsposedBuilds"].pop(),
        ),
        (
            "a matrix with no blocking couplings is caught",
            False,
            lambda m: m.pop("blockingCouplings"),
        ),
        (
            "a scenario with no assertion is caught",
            False,
            lambda m: m["lifecycleScenarios"][0].__setitem__("asserts", ""),
        ),
        (
            "an unknown status is caught",
            False,
            lambda m: m["androidLevels"][0].__setitem__("status", "probably-fine"),
        ),
    ]

    failures = 0
    for name, expect_pass, mutate in cases:
        if not case(name, expect_pass, mutate):
            failures += 1

    # --- the evidence rules, which are the point of the file ---------------------

    def claim_without_evidence(matrix: dict) -> None:
        matrix["androidLevels"][0]["status"] = "verified"

    if not case("a verified cell with no evidence is caught", False, claim_without_evidence):
        failures += 1

    def claim_broken_without_evidence(matrix: dict) -> None:
        matrix["lifecycleScenarios"][0]["status"] = "known-broken"

    if not case(
        "a known-broken cell with no evidence is caught - pointing the other way is still a claim",
        False,
        claim_broken_without_evidence,
    ):
        failures += 1

    def evidence_from_another_commit(matrix: dict) -> None:
        matrix["androidLevels"][0]["status"] = "verified"
        matrix["evidence"]["android/Android 14"] = [
            {
                "moduleCommit": "b" * 40,
                "device": "pixel",
                "lsposedBuild": "stable",
                "apkSha256": "c" * 64,
            }
        ]

    if not case("evidence gathered at another commit is caught", False, evidence_from_another_commit):
        failures += 1

    def evidence_missing_device(matrix: dict) -> None:
        matrix["androidLevels"][0]["status"] = "verified"
        matrix["evidence"]["android/Android 14"] = [
            {"moduleCommit": COMMIT, "lsposedBuild": "stable"}
        ]

    if not case("evidence with no device is caught", False, evidence_missing_device):
        failures += 1

    def evidence_bad_sha(matrix: dict) -> None:
        matrix["androidLevels"][0]["status"] = "verified"
        matrix["evidence"]["android/Android 14"] = [
            {
                "moduleCommit": COMMIT,
                "device": "pixel",
                "lsposedBuild": "stable",
                "apkSha256": "not-a-sha",
            }
        ]

    if not case("evidence with a malformed APK hash is caught", False, evidence_bad_sha):
        failures += 1

    def complete_evidence(matrix: dict) -> None:
        matrix["androidLevels"][0]["status"] = "verified"
        matrix["evidence"]["android/Android 14"] = [
            {
                "moduleCommit": COMMIT,
                "device": "pixel 8",
                "lsposedBuild": "1.9.2",
                "apkSha256": "c" * 64,
            }
        ]

    if not case("a verified cell with complete evidence at this commit passes", True, complete_evidence):
        failures += 1

    # --- the coupling check -----------------------------------------------------

    def no_rows_above_target_sdk(matrix: dict) -> None:
        # The build targets 34, so dropping every row above it removes the coupling that
        # check_coupling exists to notice.
        matrix["androidLevels"] = [entry for entry in matrix["androidLevels"] if entry["api"] <= 34]

    if not case(
        "a matrix that stops covering Android above targetSdk is caught",
        False,
        no_rows_above_target_sdk,
    ):
        failures += 1

    # --- the real matrix --------------------------------------------------------

    real = os.path.join(ROOT, VALIDATOR.MATRIX_PATH)
    report = VALIDATOR.Report()
    try:
        with open(real, "r", encoding="utf-8") as handle:
            matrix = json.load(handle)
    except OSError as error:
        print("[fail] cannot read the real matrix: %s" % error)
        return 1

    buffer = io.StringIO()
    with contextlib.redirect_stdout(buffer), contextlib.redirect_stderr(io.StringIO()):
        VALIDATOR.check_status(matrix, report)
        VALIDATOR.check_structure(matrix, report)
        VALIDATOR.check_evidence(matrix, report, "0" * 40)
        VALIDATOR.check_coupling(matrix, report)
        code = report.emit()

    if code == 0 and not report.failures:
        print("[pass] the real matrix satisfies the checker")
    else:
        print("[fail] the real matrix violates the checker: %s" % report.failures)
        failures += 1

    total = len(cases) + 11
    if failures:
        print("%d of %d test-matrix cases failed" % (failures, total), file=sys.stderr)
        return 1
    print("all %d test-matrix cases behaved correctly" % total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())