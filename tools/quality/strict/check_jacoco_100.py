#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import os
import sys
from xml.etree import ElementTree

COUNTERS = ("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD", "CLASS")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--xml", required=True)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    issues: list[dict[str, object]] = []
    if not os.path.exists(args.xml):
        issues.append({"type": "missing-report", "message": f"missing JaCoCo XML: {args.xml}"})
        return emit(args, issues, {})

    root = ElementTree.parse(args.xml).getroot()
    totals: dict[str, dict[str, int]] = {}
    for node in root.findall("counter"):
        name = node.get("type", "")
        if name in COUNTERS:
            missed = int(node.get("missed", "0"))
            covered = int(node.get("covered", "0"))
            totals[name] = {"missed": missed, "covered": covered}
            if missed != 0:
                issues.append({
                    "type": "coverage-counter",
                    "counter": name,
                    "missed": missed,
                    "covered": covered,
                    "message": f"{args.suite}: {name} coverage is not 100% ({missed} missed)",
                })

    for counter in COUNTERS:
        if counter not in totals:
            issues.append({"type": "missing-counter", "message": f"{args.suite}: JaCoCo counter {counter} is absent"})

    for package in root.findall("package"):
        package_name = package.get("name", "")
        for source in package.findall("sourcefile"):
            source_name = source.get("name", "")
            for line in source.findall("line"):
                nr = int(line.get("nr", "0"))
                mi = int(line.get("mi", "0"))
                mb = int(line.get("mb", "0"))
                if mi > 0 or mb > 0:
                    issues.append({
                        "type": "uncovered-line",
                        "file": f"{package_name}/{source_name}",
                        "line": nr,
                        "missedInstructions": mi,
                        "missedBranches": mb,
                        "message": f"{package_name}/{source_name}:{nr}: missed instructions={mi}, branches={mb}",
                    })

    return emit(args, issues, totals)


def emit(args, issues, totals) -> int:
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    payload = {"suite": args.suite, "report": args.xml, "totals": totals, "issueCount": len(issues), "issues": issues}
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, indent=2)
        handle.write("\n")

    print(f"{args.suite}: {len(issues)} strict coverage issue(s)")
    for issue in issues[:200]:
        location = ""
        if issue.get("file"):
            location = f" file={issue['file']},line={issue.get('line', 1)}"
        print(f"::error{location}::{issue['message']}")
    if len(issues) > 200:
        print(f"::error::{len(issues) - 200} additional coverage issues are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
