#!/usr/bin/env python3
"""Fail when a suite's JUnit results are missing or report a failing test.

Gradle's own exit code is not enough here: `ignoreFailures` is set for the strict unit
suite so coverage is still produced, and a crashed test executor can leave a results
directory that looks complete. Both cases are indistinguishable from success unless the
XML is read independently, so this check is the one that decides.

An empty glob is a finding, not a pass: "no results" and "all results green" must not be
the same answer.

Exit codes:
    0  results were found, parsed, and no test failed
    1  no results, an unreadable file, or at least one failing or erroring testcase
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import sys
from xml.etree import ElementTree

MAX_REPORTED = 200


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--glob", required=True, help="glob matching the suite's JUnit XML files")
    parser.add_argument("--suite", required=True, help="suite label, recorded in the artifact")
    parser.add_argument("--out", required=True, help="where to write the findings JSON")
    args = parser.parse_args()

    paths = sorted(glob.glob(args.glob, recursive=True))
    issues: list[dict[str, object]] = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}

    if not paths:
        issues.append({"type": "missing-results", "message": f"{args.suite}: no JUnit XML matched {args.glob}"})

    for path in paths:
        # Only a parse failure is caught. Anything else that goes wrong here is a bug in
        # this script, and hiding it as a finding would cost an hour of looking in the
        # wrong place.
        try:
            root = ElementTree.parse(path).getroot()
        except (ElementTree.ParseError, OSError) as error:
            issues.append(
                {"type": "parse", "file": path, "message": f"{path}: cannot parse JUnit XML: {error}"}
            )
            continue

        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            for key in totals:
                totals[key] += int(suite.get(key, "0") or 0)
            for case in suite.findall("testcase"):
                failure = case.find("failure")
                error_node = case.find("error")
                node = failure if failure is not None else error_node
                if node is None:
                    continue
                issues.append(
                    {
                        "type": "test-failure",
                        "file": path,
                        "class": case.get("classname"),
                        "test": case.get("name"),
                        "message": (
                            f"{case.get('classname')}.{case.get('name')}: "
                            f"{(node.get('message') or '').strip()}"
                        ),
                        "details": (node.text or "").strip(),
                    }
                )

    directory = os.path.dirname(args.out)
    if directory:
        os.makedirs(directory, exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump(
            {
                "suite": args.suite,
                "files": paths,
                "totals": totals,
                "issueCount": len(issues),
                "issues": issues,
            },
            handle,
            indent=2,
        )
        handle.write("\n")

    print(f"{args.suite}: {totals['tests']} test(s), {len(issues)} issue(s)")
    for issue in issues[:MAX_REPORTED]:
        print(f"::error::{issue['message']}")
    if len(issues) > MAX_REPORTED:
        print(f"::error::{len(issues) - MAX_REPORTED} additional test failures are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    sys.exit(main())
