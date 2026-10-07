#!/usr/bin/env python3
"""Self-test for tools/modernization/collect_m00_baseline.py.

This tool was written with an explicit rule in its own docstring: no machine-specific values in
the baseline, because a file that differs per machine trains everyone to ignore `--check`. The
first CI run broke that rule. `actions/checkout` configures the origin as
`https://github.com/Alaa91H/WA-X` while a local clone has `...WA-X.git`, so the gate reported
drift on a tree that had not changed.

The failure was caught by the gate the same change had added, which is the argument for having
the gate. This test is the argument for keeping the fix: it makes "the baseline means the same
thing on every machine" a checked property instead of a documented intention.

The three cases below cover the three ways this tool can stop being trustworthy: recording
something about the clone instead of the repository, failing to notice real drift, and producing
different output for the same input.

Usage:
    python3 tools/modernization/test_collect_m00_baseline.py
Exit codes: 0 every case behaved as expected, 1 a case did not.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load():
    spec = importlib.util.spec_from_file_location(
        "collect_m00_baseline", os.path.join(HERE, "collect_m00_baseline.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


BASELINE = load()


def case(name: str, ok: bool) -> bool:
    print(("[pass] " if ok else "[fail] ") + name)
    return ok


def main() -> int:
    failures = 0

    # --- the regression that actually happened ---------------------------------

    spellings = [
        "https://github.com/Alaa91H/WA-X.git",   # a local clone
        "https://github.com/Alaa91H/WA-X",       # actions/checkout
        "git@github.com:Alaa91H/WA-X.git",       # an SSH remote
        "ssh://git@github.com/Alaa91H/WA-X.git",  # an SSH remote, URL form
        "https://github.com/Alaa91H/WA-X/",      # trailing slash
    ]
    normalised = [BASELINE.canonical_remote(url) for url in spellings]
    if not case(
        "every spelling of the origin URL collapses to one repository identity",
        len(set(normalised)) == 1,
    ):
        failures += 1

    if not case(
        "a missing origin yields None rather than the string 'unknown'",
        BASELINE.canonical_remote(None) is None
        and BASELINE.canonical_remote("") is None,
    ):
        failures += 1

    # A different repository must not collapse together with ours.
    if not case(
        "two different repositories do not normalise to the same value",
        BASELINE.canonical_remote("https://github.com/Alaa91H/WA-X.git")
        != BASELINE.canonical_remote("https://github.com/other/WA-X.git"),
    ):
        failures += 1

    # --- the gate still detects real drift --------------------------------------

    root = tempfile.mkdtemp(prefix="m00-baseline-")
    try:
        path = os.path.join(root, "baseline.json")
        payload = {"repository": "https://github.com/Alaa91H/WA-X", "schema": "wax.m00.baseline/1"}
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(payload, indent=2, sort_keys=True) + "\n")

        same = json.dumps(payload, indent=2, sort_keys=True) + "\n"
        changed = json.dumps(
            {"repository": "https://github.com/Alaa91H/WA-X", "schema": "x"}, indent=2, sort_keys=True
        ) + "\n"

        if not case("an identical baseline reports no drift", BASELINE.describe_drift(same, same) == []):
            failures += 1

        drift = BASELINE.describe_drift(same, changed)
        if not case(
            "a changed field is reported by name, not as a wall of JSON",
            len(drift) == 1 and drift[0].startswith("/schema:"),
        ):
            failures += 1

        # Same shape, two fields moved. Compared against a different-shaped document this would
        # also report the removed and added fields, so the count would not isolate the property.
        two_drifted = json.dumps(
            {"repository": "https://github.com/other/WA-X", "schema": "x"}, indent=2, sort_keys=True
        ) + "\n"
        drift = BASELINE.describe_drift(same, two_drifted)
        if not case(
            "every drifted field is reported, not just the first",
            len(drift) == 2 and all(line.startswith("/") for line in drift),
        ):
            print("  got %s" % drift)
            failures += 1
    finally:
        shutil.rmtree(root, ignore_errors=True)

    # --- the baseline means the same thing twice --------------------------------

    first = BASELINE.render(BASELINE.collect())
    second = BASELINE.render(BASELINE.collect())
    if not case("collecting twice over the same tree produces identical output", first == second):
        failures += 1

    # --- and it matches what is committed ---------------------------------------

    committed_path = os.path.join(ROOT, BASELINE.BASELINE_PATH)
    try:
        with open(committed_path, "r", encoding="utf-8") as handle:
            committed = handle.read()
    except OSError:
        print("[fail] %s is missing; run with --write" % BASELINE.BASELINE_PATH)
        return 1

    drift = BASELINE.describe_drift(committed, first)
    if not drift:
        print("[pass] the committed baseline matches this tree")
    else:
        print("[fail] the committed baseline no longer matches this tree:")
        for line in drift[:20]:
            print("  %s" % line)
        failures += 1

    # The specific field that broke it is called out on purpose. A generic drift report is
    # correct but it is what turned a one-line fix into a log-reading exercise.
    if not case(
        "the repository field holds the normalised identity, not the clone's remote",
        json.loads(committed)["repository"] == "https://github.com/Alaa91H/WA-X",
    ):
        failures += 1

    if failures:
        print("%d baseline-collection cases failed" % failures, file=sys.stderr)
        return 1
    print("all baseline-collection cases behaved correctly")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())