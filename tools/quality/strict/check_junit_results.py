#!/usr/bin/env python3
from __future__ import annotations

import argparse
import glob
import json
import os
from xml.etree import ElementTree


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--glob", required=True)
    parser.add_argument("--suite", required=True)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    paths = sorted(glob.glob(args.glob, recursive=True))
    issues = []
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}

    if not paths:
        issues.append({"type":"missing-results","message":f"{args.suite}: no JUnit XML matched {args.glob}"})
    for path in paths:
        try:
            root = ElementTree.parse(path).getroot()
        except Exception as exc:
            issues.append({"type":"parse","file":path,"message":f"{path}: cannot parse JUnit XML: {exc}"})
            continue
        suites = [root] if root.tag == "testsuite" else list(root.findall("testsuite"))
        for suite in suites:
            for key in totals:
                totals[key] += int(suite.get(key, "0") or 0)
            for case in suite.findall("testcase"):
                failure = case.find("failure")
                error = case.find("error")
                node = failure if failure is not None else error
                if node is not None:
                    issues.append({
                        "type":"test-failure",
                        "file":path,
                        "class":case.get("classname"),
                        "test":case.get("name"),
                        "message":f"{case.get('classname')}.{case.get('name')}: {(node.get('message') or '').strip()}",
                        "details":(node.text or "").strip(),
                    })

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump({"suite":args.suite,"files":paths,"totals":totals,"issueCount":len(issues),"issues":issues}, handle, indent=2)
        handle.write("\n")

    print(f"{args.suite}: {totals['tests']} test(s), {len(issues)} issue(s)")
    for issue in issues[:200]:
        print(f"::error::{issue['message']}")
    if len(issues) > 200:
        print(f"::error::{len(issues)-200} additional test failures are in {args.out}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
