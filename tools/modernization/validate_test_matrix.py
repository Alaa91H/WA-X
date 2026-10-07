#!/usr/bin/env python3
"""Keep the release and device test matrix honest.

`compatibility.json` answers "does this feature's resolver match on this WhatsApp build". It does
not answer "does the module load on Android 16", "does it survive a reboot", or "does it work on
LSPosed from source". Those are the dimensions this file governs, and they are separate on purpose:
mixing them would break compatibility.json's derived-freshness check and merge two evidence models
that answer different questions.

A matrix is a claim, so the only interesting property of this one is that it cannot start
asserting anything it has not earned. Three rules, in the order they are checked:

1. **Structure.** Every required dimension is present. A matrix that quietly stops listing a
   lifecycle scenario cannot regress; it just stops mentioning it.
2. **Evidence.** A cell may not say `verified` or `known-broken` without an evidence entry whose
   `moduleCommit` is the commit under test. Both are claims, and `known-broken` is a claim that
   points the other way but is just as unsupported without a run.
3. **Coupling.** The Android rows are cross-checked against what the build actually targets, so the
   matrix cannot imply a support position the build configuration contradicts. `compileSdk 37`
   with `targetSdk 34` does not mean Android 17 is supported; it means Android 17 behaviour changes
   are opted out, and the matrix has to say so rather than let the numbers imply otherwise.

The gate is deliberately not demanding evidence today. Every cell starts `unverified`, and
`unverified` asserts nothing, so there is nothing to prove - exactly the reasoning already in force
for `check_latest_runtime_evidence.py`. It is not a gate nobody had to build; it is the same trade,
recorded in a second place.

Usage:
    python3 tools/modernization/validate_test_matrix.py
    python3 tools/modernization/validate_test_matrix.py --check
    python3 tools/modernization/validate_test_matrix.py --commit SHA
Exit codes: 0 valid, 1 a check failed, 2 bad input.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

MATRIX_PATH = "docs/modernization/test-matrix.json"
MODULE_BUILD = "app/build.gradle.kts"

STATUSES = ("unverified", "verified", "known-broken", "not-applicable")
CLAIMED = ("verified", "known-broken")

# Scenarios the program directive requires. Held here rather than derived from the matrix, because
# the whole failure mode this file guards against is the matrix dropping a row that nobody notices.
REQUIRED_SCENARIOS = (
    "clean-install",
    "update",
    "process-restart",
    "device-reboot",
    "scope-remove-add",
    "module-disable-enable",
    "safe-mode",
    "corrupted-cache",
    "rollback",
)

REQUIRED_PACKAGES = ("com.whatsapp", "com.whatsapp.w4b")
REQUIRED_TRAINS = ("stable", "beta")
REQUIRED_ANDROID = (34, 35, 36, 37)

SHA256_RE = re.compile(r"^[0-9a-f]{64}$")


class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.notes: list[str] = []
        self.claims = 0
        self.cells = 0

    def fail(self, message: str) -> None:
        self.failures.append(message)

    def note(self, message: str) -> None:
        self.notes.append(message)

    def emit(self) -> int:
        for message in self.notes:
            print("note: %s" % message)
        for message in self.failures:
            print("FAIL: %s" % message, file=sys.stderr)
        if self.failures:
            print("\n%d check(s) failed" % len(self.failures), file=sys.stderr)
            return 1
        print(
            "test-matrix.json: %d cells, %d of them claiming something; every claim is backed."
            % (self.cells, self.claims)
        )
        return 0


def load(path: str) -> dict:
    if not os.path.isfile(path):
        print("missing %s" % path, file=sys.stderr)
        raise SystemExit(2)
    with open(path, "r", encoding="utf-8") as handle:
        try:
            return json.load(handle)
        except json.JSONDecodeError as error:
            print("%s is not valid JSON: %s" % (path, error), file=sys.stderr)
            raise SystemExit(2)


def head_commit() -> str | None:
    try:
        result = subprocess.run(
            ("git", "rev-parse", "HEAD"),
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
    except OSError:
        return None
    return result.stdout.strip() if result.returncode == 0 else None


def build_targets() -> dict[str, int | None]:
    """minSdk / targetSdk / compileSdk as the build actually declares them."""
    path = os.path.join(ROOT, MODULE_BUILD)
    try:
        with open(path, "r", encoding="utf-8") as handle:
            build = handle.read()
    except OSError:
        return {}
    found: dict[str, int | None] = {}
    for key in ("minSdk", "targetSdk", "compileSdk"):
        match = re.search(r"%s\s*=\s*(\d+)" % key, build)
        found[key] = int(match.group(1)) if match else None
    return found


def check_status(matrix: dict, report: Report) -> None:
    vocabulary = matrix.get("statusVocabulary", {})
    for status in ("unverified", "verified", "known-broken", "not-applicable"):
        if status not in vocabulary:
            report.fail("statusVocabulary does not define %r" % status)
    for status in STATUSES:
        if status not in vocabulary:
            report.fail("%r is used as a status but is not defined" % status)


def check_structure(matrix: dict, report: Report) -> None:
    levels = matrix.get("androidLevels", [])
    apis = {entry.get("api") for entry in levels}
    for api in REQUIRED_ANDROID:
        if api not in apis:
            report.fail(
                "the matrix does not list Android API %d. A matrix that stops listing a level "
                "cannot regress on it, it just stops mentioning it." % api
            )
    for entry in levels:
        if entry.get("api") is None:
            report.fail("an androidLevels entry has no api: %r" % entry.get("label"))
        if not entry.get("rationale"):
            report.fail("android level %r has no rationale" % entry.get("api"))
        report.cells += 1

    builds = matrix.get("lsposedBuilds", [])
    if len(builds) < 2:
        report.fail(
            "at least two LSPosed builds are required. One baseline implementation proves the "
            "module works on that implementation, not that it works on the Xposed API contract."
        )
    for entry in builds:
        if not entry.get("rationale"):
            report.fail("LSPosed build %r has no rationale" % entry.get("label"))
        report.cells += 1

    packages = matrix.get("targets", [])
    seen_packages = set()
    for target in packages:
        package = target.get("package")
        seen_packages.add(package)
        trains = {train.get("label") for train in target.get("trains", [])}
        for train in REQUIRED_TRAINS:
            if train not in trains:
                report.fail(
                    "%s does not list the %s train. Stable and beta are different builds; a run "
                    "on one says nothing about the other." % (package, train)
                )
        for train in target.get("trains", []):
            if not train.get("rationale"):
                report.fail("%s/%s has no rationale" % (package, train.get("label")))
            report.cells += 1
    for package in REQUIRED_PACKAGES:
        if package not in seen_packages:
            report.fail(
                "the matrix does not list %s. WhatsApp Business is a separately signed package "
                "with its own process and its own settings; a matrix that omits it hides exactly "
                "the drift it should surface." % package
            )

    scenarios = matrix.get("lifecycleScenarios", [])
    ids = [entry.get("id") for entry in scenarios]
    for scenario in REQUIRED_SCENARIOS:
        if scenario not in ids:
            report.fail("the lifecycle scenario %r is missing from the matrix" % scenario)
    if len(ids) != len(set(ids)):
        report.fail("the lifecycle scenarios contain a duplicate id")
    for entry in scenarios:
        for field in ("label", "status", "asserts", "why"):
            if not entry.get(field):
                report.fail(
                    "lifecycle scenario %r has no %s; every cell has to say what it proves and "
                    "why it matters" % (entry.get("id"), field)
                )
        report.cells += 1

    couplings = matrix.get("blockingCouplings", [])
    if not couplings:
        report.fail(
            "the matrix declares no blocking couplings. The Android rows above are not independent "
            "of the build configuration, and a matrix that does not say so will be read as if they "
            "were."
        )
    for entry in couplings:
        for field in ("id", "between", "consequence", "owner"):
            if not entry.get(field):
                report.fail("a blocking coupling has no %s" % field)


def check_evidence(matrix: dict, report: Report, commit: str | None) -> None:
    evidence = matrix.get("evidence", {})
    policy = matrix.get("evidencePolicy", {})
    if not policy.get("requiredFor"):
        report.fail("evidencePolicy declares no statuses that require evidence")

    cells: list[tuple[str, str]] = []
    for entry in matrix.get("androidLevels", []):
        cells.append(("android/%s" % entry.get("label"), str(entry.get("status"))))
    for entry in matrix.get("lsposedBuilds", []):
        cells.append(("lsposed/%s" % entry.get("label"), str(entry.get("status"))))
    for target in matrix.get("targets", []):
        for train in target.get("trains", []):
            cells.append(
                ("%s/%s" % (target.get("package"), train.get("label")), str(train.get("status")))
            )
    for entry in matrix.get("lifecycleScenarios", []):
        cells.append(("scenario/%s" % entry.get("id"), str(entry.get("status"))))

    for cell, status in cells:
        if status not in STATUSES:
            report.fail("cell %s has unknown status %r" % (cell, status))
            continue
        if status == "not-applicable":
            continue
        if status not in CLAIMED:
            continue
        report.claims += 1
        records = evidence.get(cell)
        if not records:
            report.fail(
                "cell %s claims %r with no evidence. Both directions are claims: an unsupported "
                "'this does not work' is as unfounded as an unsupported 'this works'." % (cell, status)
            )
            continue
        for record in records:
            if not record.get("moduleCommit"):
                report.fail("evidence for %s records no moduleCommit" % cell)
            elif commit and record["moduleCommit"] != commit:
                report.fail(
                    "evidence for %s was gathered at %s but this is %s. Evidence from another "
                    "commit certifies that commit." % (cell, record["moduleCommit"][:8], commit[:8])
                )
            if not record.get("device"):
                report.fail("evidence for %s records no device" % cell)
            if not record.get("lsposedBuild"):
                report.fail("evidence for %s records no lsposedBuild" % cell)
            if record.get("apkSha256") and not SHA256_RE.match(str(record["apkSha256"])):
                report.fail("evidence for %s has a malformed apkSha256" % cell)

    if report.claims == 0:
        report.note(
            "no cell claims verified or known-broken, so this gate has nothing to prove today. "
            "That is the same trade check_latest_runtime_evidence.py makes: demanding a rooted "
            "LSPosed device with both WhatsApp builds on every run would block every release on "
            "hardware no runner has, while proving nothing about an all-unverified matrix."
        )


def check_coupling(matrix: dict, report: Report) -> None:
    """The Android rows must not imply a support position the build contradicts."""
    targets = build_targets()
    if not targets:
        report.fail("could not read %s, so the matrix cannot be checked against the build" % MODULE_BUILD)
        return

    target_sdk = targets.get("targetSdk")
    compile_sdk = targets.get("compileSdk")
    listed = {entry.get("api") for entry in matrix.get("androidLevels", [])}

    if target_sdk is not None and target_sdk not in listed:
        report.fail(
            "targetSdk is %d but no Android row lists it. The level the module declares "
            "behaviour for is the one row that cannot be missing." % target_sdk
        )

    if target_sdk is not None and compile_sdk is not None and target_sdk < compile_sdk:
        above = sorted(api for api in listed if api is not None and api > target_sdk)
        if not above:
            report.fail(
                "compileSdk %d exceeds targetSdk %d but no Android row above targetSdk records the "
                "coupling. Rows above targetSdk test a build that has opted out of that "
                "platform's behaviour changes, and the matrix has to say so."
                % (compile_sdk, target_sdk)
            )
        else:
            report.note(
                "compileSdk %d > targetSdk %d: %s are covered while opted out of that platform's "
                "behaviour changes (blockingCouplings COUPLE-01)."
                % (compile_sdk, target_sdk, ", ".join(str(api) for api in above))
            )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--matrix", default=None)
    parser.add_argument(
        "--commit",
        default=None,
        help="the module commit under test; defaults to git HEAD. Evidence gathered elsewhere does "
        "not count for it.",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="also assert that the matrix is the one this tree describes (structure and coupling "
        "against the build file); without it only claims are checked",
    )
    args = parser.parse_args(argv)

    path = args.matrix or os.path.join(ROOT, MATRIX_PATH)
    matrix = load(path)
    commit = args.commit or head_commit()

    report = Report()
    check_status(matrix, report)
    check_structure(matrix, report)
    check_evidence(matrix, report, commit)
    if args.check:
        check_coupling(matrix, report)
    return report.emit()


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))