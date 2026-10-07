#!/usr/bin/env python3
"""Fail when a coverage counter drops below the recorded floor.

Absolute 100% is not a target this module can reach, and a gate that can never pass is not
a gate. WA X is an Xposed module: its largest classes resolve obfuscated members of
WhatsApp's own classes through Xposed reflection, run inside a hooked Android process and
are exercised by a device, not by a JVM test. Requiring 100% of instructions, branches,
methods and classes therefore asks for a test suite that reimplements WhatsApp.

What this gate does instead is the two things that are both achievable and useful:

  * the *covered count* of every counter must not fall, which catches a removed or
    weakened test;
  * the *percentage* of every counter must not fall, which catches production code added
    without a test for it.

A change that adds tests passes. A change that adds untested code fails the second check. A
change that deletes a test without deleting the code fails the first. Either way the fix is
the same: add the test, or regenerate the floor deliberately with `--update` and say so in
the commit that did it.

Findings and totals are written to `--out` before the verdict is returned, so the artifact
exists even when the report could not be read.

Exit codes:
    0  every counter is at or above its floor
    1  a counter fell, or the report is missing/unreadable
    2  bad input
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from xml.etree import ElementTree

COUNTERS = ("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS")
MAX_REPORTED = 200

# A gate that fails on the fourth decimal place is a gate that fails on floating point, not
# on the code. The tolerance is far below the smallest real regression - the narrowest
# counter here moves in whole instructions - and exists only so a regenerate/check pair
# cannot disagree over rounding.
PERCENT_TOLERANCE = 0.01


def read_totals(path: str) -> tuple[dict[str, dict[str, int]], list[str]]:
    """Counter totals from a JaCoCo XML, plus the counters the report did not contain."""
    root = ElementTree.parse(path).getroot()
    totals: dict[str, dict[str, int]] = {}
    for node in root.findall("counter"):
        name = node.get("type", "")
        if name in COUNTERS:
            covered = int(node.get("covered", "0"))
            missed = int(node.get("missed", "0"))
            total = covered + missed
            totals[name] = {
                "covered": covered,
                "missed": missed,
                "percent": round(covered / total * 100.0, 4) if total else 100.0,
            }
    return totals, [name for name in COUNTERS if name not in totals]


def load_floor(path: str, block: str) -> tuple[dict[str, dict[str, float]] | None, list[str]]:
    """Read one suite's floors.

    Returns ``(floors, problems)``. ``floors`` is None when the block is absent, which is
    the bootstrap case: the suite has no recorded measurement yet, so the check records one
    instead of demanding a number nobody has taken. ``problems`` carries a real schema
    problem, which is a failure either way.
    """
    document = json.load(open(path, encoding="utf-8"))
    if block not in document:
        return None, []
    counters = document[block]
    if not isinstance(counters, dict):
        return None, [f"{path}: `{block}` is not an object"]
    missing = [name for name in COUNTERS if name not in counters]
    if missing:
        return None, [f"{path}: `{block}` has no floor recorded for {', '.join(missing)}"]
    floor: dict[str, dict[str, float]] = {}
    for name in COUNTERS:
        entry = counters[name]
        if not isinstance(entry, dict) or "covered" not in entry:
            return None, [f"{path}: `{block}`/{name} has no covered count"]
        floor[name] = {
            "covered": int(entry["covered"]),
            "percent": float(entry.get("percent", 0.0)),
        }
    return floor, []


def suite_block(suite: str) -> str:
    """Which key of the floor document holds this suite's numbers."""
    return "counters" if suite == "unit" else f"{suite}Counters"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--xml", required=True, help="JaCoCo XML report to read")
    parser.add_argument(
        "--floor",
        default=os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))),
            "quality",
            "coverage-baseline.json",
        ),
        help="recorded floors to compare against (default: quality/coverage-baseline.json)",
    )
    parser.add_argument("--suite", required=True, help="suite label, recorded in the artifact")
    parser.add_argument("--out", required=True, help="where to write the findings JSON")
    parser.add_argument(
        "--update",
        metavar="FILE",
        help="write the measured counters into FILE as the new floor instead of checking",
    )
    parser.add_argument("--commit", default="", help="commit recorded alongside the floor")
    parser.add_argument("--tests", default="", help="number of executed tests recorded alongside the floor")
    args = parser.parse_args()

    issues: list[dict[str, object]] = []
    totals: dict[str, dict[str, float]] = {}
    block = suite_block(args.suite)

    try:
        floor, schema_problems = load_floor(args.floor, block)
    except (OSError, json.JSONDecodeError) as error:
        print(f"::error::cannot read the coverage floor: {error}")
        return 2
    for problem in schema_problems:
        print(f"::error::{problem}")
        return 2

    if not os.path.isfile(args.xml):
        issues.append({"type": "missing-report", "message": f"missing JaCoCo XML: {args.xml}"})
        return emit(args, issues, totals, floor or {name: {"covered": 0, "percent": 0.0} for name in COUNTERS})

    try:
        totals, absent = read_totals(args.xml)
    except (ElementTree.ParseError, OSError) as error:
        issues.append(
            {"type": "unreadable-report", "message": f"cannot read JaCoCo XML {args.xml}: {error}"}
        )
        return emit(args, issues, totals, floor or {name: {"covered": 0, "percent": 0.0} for name in COUNTERS})

    for name in absent:
        issues.append(
            {"type": "missing-counter", "message": f"{args.suite}: JaCoCo counter {name} is absent"}
        )

    if args.update:
        # The counters are written the same way the check reads them, so a regenerated floor
        # can never disagree with the comparison by a rounding error. Everything else in the
        # document - the rationale, the regeneration command - is preserved.
        document = json.load(open(args.update, encoding="utf-8"))
        document[block] = {name: totals[name] for name in COUNTERS if name in totals}
        if args.commit:
            document["measuredOnCommit"] = args.commit
        if args.tests:
            document[f"{args.suite}TestMethodsExecuted"] = int(args.tests)
        with open(args.update, "w", encoding="utf-8", newline="\n") as handle:
            json.dump(document, handle, indent=2, ensure_ascii=False)
            handle.write("\n")
        print(f"updated {args.update} [{block}]")
        for name in COUNTERS:
            if name in totals:
                print(
                    f"  {name:<13} {totals[name]['covered']:>7} covered  "
                    f"{totals[name]['percent']:6.2f}%"
                )
        return 0 if not issues else 1

    if floor is None:
        # Bootstrap. The run happened and produced a report, so the measurement is real; what
        # is missing is a number someone chose to hold the line at. Record it in the
        # artifact and say so, rather than failing on a threshold that was never set.
        print(f"{args.suite}: no floor recorded yet - this run is the baseline, not a regression")
        for name in COUNTERS:
            entry = totals.get(name)
            if entry is None:
                print(f"  {name:<13} absent from the report")
                continue
            print(f"  {name:<13} {entry['covered']:>7} covered  {entry['percent']:6.2f}%")
        print(
            "Pin it with --update when the suite is worth holding a line at; until then this "
            "gate reports and cannot fail."
        )
        return emit(args, issues, totals, {name: {"covered": 0, "percent": 0.0} for name in COUNTERS})

    for name in COUNTERS:
        measured = totals.get(name)
        if measured is None:
            continue
        recorded = floor[name]
        lost = recorded["covered"] - measured["covered"]
        if lost > 0:
            issues.append(
                {
                    "type": "coverage-count-regression",
                    "counter": name,
                    "floor": recorded["covered"],
                    "covered": measured["covered"],
                    "message": (
                        f"{args.suite}: {name} covered count fell from {recorded['covered']} to "
                        f"{measured['covered']} (-{lost}); a test was lost or weakened"
                    ),
                }
            )
        drift = round(recorded["percent"] - measured["percent"], 4)
        if drift > PERCENT_TOLERANCE:
            issues.append(
                {
                    "type": "coverage-percent-regression",
                    "counter": name,
                    "floor": recorded["percent"],
                    "measured": measured["percent"],
                    "message": (
                        f"{args.suite}: {name} coverage fell from {recorded['percent']:.2f}% to "
                        f"{measured['percent']:.2f}% (-{drift:.2f}); production code was added "
                        f"without a test"
                    ),
                }
            )

    return emit(args, issues, totals, floor)


def emit(
    args: argparse.Namespace,
    issues: list[dict[str, object]],
    totals: dict[str, dict[str, float]],
    floor: dict[str, dict[str, float]],
) -> int:
    directory = os.path.dirname(args.out)
    if directory:
        os.makedirs(directory, exist_ok=True)

    summary = {}
    for name in COUNTERS:
        entry = totals.get(name)
        recorded = floor.get(name)
        if entry is None or recorded is None:
            continue
        summary[name] = {
            "floorCovered": recorded["covered"],
            "floorPercent": recorded["percent"],
            "covered": entry["covered"],
            "missed": entry["missed"],
            "percent": entry["percent"],
        }

    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(
            {
                "suite": args.suite,
                "report": args.xml,
                "counters": summary,
                "issueCount": len(issues),
                "issues": issues,
            },
            handle,
            indent=2,
        )
        handle.write("\n")

    if summary:
        print(f"{args.suite}: coverage against the recorded floor")
        for name in COUNTERS:
            entry = summary.get(name)
            if entry is None:
                print(f"  {name:<13} absent from the report")
                continue
            print(
                f"  {name:<13} {entry['covered']:>7} covered (floor {entry['floorCovered']:>7}) "
                f"{entry['percent']:6.2f}% (floor {entry['floorPercent']:6.2f}%)"
            )
    print(f"{args.suite}: {len(issues)} issue(s)")
    for issue in issues[:MAX_REPORTED]:
        print(f"::error::{issue['message']}")
    if len(issues) > MAX_REPORTED:
        print(f"::error::{len(issues) - MAX_REPORTED} additional issues are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())