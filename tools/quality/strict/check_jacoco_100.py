#!/usr/bin/env python3
"""Fail unless every JaCoCo counter for a suite is at 100%.

The counters named in `quality/strict-policy.json` are all enforced, including the ones a
percentage hides: a suite can report 100% line coverage while a class, a method or a branch
is entirely unexecuted. The report is also required to exist and to carry all six counters,
because "no report" and "a report that omits a counter" are both indistinguishable from
success if they are treated as an empty set of findings.

Findings are written to `--out` before the verdict is returned, so the artifact explaining
a failure exists even when the failure is that the report could not be read at all.

Exit codes:
    0  every counter is at 100% and no line is missed
    1  at least one counter, counter or line is not fully covered
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from xml.etree import ElementTree

COUNTERS = ("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS")
MAX_REPORTED = 200


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--xml", required=True, help="JaCoCo XML report to read")
    parser.add_argument("--suite", required=True, help="suite label, recorded in the artifact")
    parser.add_argument("--out", required=True, help="where to write the findings JSON")
    args = parser.parse_args()

    issues: list[dict[str, object]] = []
    totals: dict[str, dict[str, int]] = {}

    if not os.path.isfile(args.xml):
        issues.append({"type": "missing-report", "message": f"missing JaCoCo XML: {args.xml}"})
        return emit(args, issues, totals)

    # A truncated or empty report raises here. Letting it propagate would lose the artifact
    # and print a traceback instead of the finding, so the parse failure is recorded as the
    # issue it is.
    try:
        root = ElementTree.parse(args.xml).getroot()
    except (ElementTree.ParseError, OSError) as error:
        issues.append({"type": "unreadable-report", "message": f"cannot read JaCoCo XML {args.xml}: {error}"})
        return emit(args, issues, totals)

    for node in root.findall("counter"):
        name = node.get("type", "")
        if name not in COUNTERS:
            continue
        missed = int(node.get("missed", "0"))
        covered = int(node.get("covered", "0"))
        totals[name] = {"missed": missed, "covered": covered}
        if missed != 0:
            issues.append(
                {
                    "type": "coverage-counter",
                    "counter": name,
                    "missed": missed,
                    "covered": covered,
                    "message": f"{args.suite}: {name} coverage is not 100% ({missed} missed)",
                }
            )

    for counter in COUNTERS:
        if counter not in totals:
            issues.append(
                {"type": "missing-counter", "message": f"{args.suite}: JaCoCo counter {counter} is absent"}
            )

    for package in root.findall("package"):
        package_name = package.get("name", "")
        for source in package.findall("sourcefile"):
            source_name = source.get("name", "")
            for line in source.findall("line"):
                missed_instructions = int(line.get("mi", "0"))
                missed_branches = int(line.get("mb", "0"))
                if missed_instructions == 0 and missed_branches == 0:
                    continue
                number = int(line.get("nr", "0"))
                issues.append(
                    {
                        "type": "uncovered-line",
                        "file": f"{package_name}/{source_name}",
                        "line": number,
                        "missedInstructions": missed_instructions,
                        "missedBranches": missed_branches,
                        "message": (
                            f"{package_name}/{source_name}:{number}: "
                            f"missed instructions={missed_instructions}, branches={missed_branches}"
                        ),
                    }
                )

    return emit(args, issues, totals)


def emit(
    args: argparse.Namespace,
    issues: list[dict[str, object]],
    totals: dict[str, dict[str, int]],
) -> int:
    directory = os.path.dirname(args.out)
    if directory:
        os.makedirs(directory, exist_ok=True)
    payload = {
        "suite": args.suite,
        "report": args.xml,
        "totals": totals,
        "issueCount": len(issues),
        "issues": issues,
    }
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")

    print(f"{args.suite}: {len(issues)} strict coverage issue(s)")
    for issue in issues[:MAX_REPORTED]:
        location = ""
        if issue.get("file"):
            location = f" file={issue['file']},line={issue.get('line', 1)}"
        print(f"::error{location}::{issue['message']}")
    if len(issues) > MAX_REPORTED:
        print(f"::error::{len(issues) - MAX_REPORTED} additional coverage issues are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
