#!/usr/bin/env python3
"""Prune app/lint-baseline.xml down to the entries that still match a real issue (T05).

The baseline had accumulated entries that suppress nothing: a package rename moved
`preference/` to `preference/`, the checked-in paths point at `$HOME/StudioProjects/...`
and `$GRADLE_USER_HOME/...` from the machine that created it, and nothing was ever
tidied. Those entries still counted towards the size that the T04 gate measures, which
made "baseline must not increase" a poor proxy for real debt.

This tool keeps an entry only when a currently reported issue matches it on
(id, file). Matching ignores the line number on purpose: lint tolerates line drift as
code moves, so requiring an exact line would drop entries that are still doing real
suppression work and let errors resurface. Paths are normalised before comparison
because lint writes whichever form it was given, and a fresh report uses absolute paths
while the checked-in baseline uses module-relative ones.

The document is parsed as XML rather than with a regular expression: the baseline nests a
self-closing `<location/>` inside each `<issue>`, so a non-greedy pattern stops at the
wrong `/>` and silently truncates every entry.

It never invents entries and never raises the count. Real issues that are not yet
baselined are reported, not added, because raising the count is what the gate forbids.

Usage:
    # capture every current issue by linting with no baseline in place; lint writes a
    # fresh baseline containing all of them and fails the build. That file is the input.
    python3 tools/baseline/prune_lint_baseline.py --current <fresh-baseline.xml> [--check]

Exit codes:
    0  baseline is already minimal
    1  stale entries exist (with --check), or the rewrite did not verify
    2  bad input
"""

from __future__ import annotations

import argparse
import collections
import os
import sys
import xml.etree.ElementTree as ET

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
BASELINE = os.path.join(REPO_ROOT, "app", "lint-baseline.xml")


def normalise(path: str) -> str:
    """Reduce a location path to a form comparable across machines and across runs.

    Lint writes whichever path form it was handed, so the same issue can be
    `src/main/res/menu/header_menu.xml` in the checked-in baseline and
    `D:\\WaEnhancer\\app\\src\\main\\res\\menu\\header_menu.xml` in a fresh report.
    Comparing the raw strings fails to match, which would prune entries that are still
    suppressing real issues.
    """
    path = (path or "").replace("\\", "/").strip()

    # Drop any prefix up to and including the module directory.
    marker = "/app/"
    index = path.rfind(marker)
    if index >= 0:
        path = path[index + len(marker):]
    elif path.startswith("app/"):
        path = path[len("app/"):]

    # Gradle cache paths embed a machine-local absolute prefix; keep the coordinates.
    cache = "/.gradle/caches/modules-2/files-2.1/"
    if cache in path:
        return "gradle-cache:" + path.split(cache, 1)[1]
    for prefix in ("$GRADLE_USER_HOME/caches/modules-2/files-2.1/",
                   "$HOME/.gradle/caches/modules-2/files-2.1/"):
        if path.startswith(prefix):
            return "gradle-cache:" + path[len(prefix):]

    # Home-substituted repository paths from the machine that created the baseline.
    if path.startswith("$HOME/") or path.startswith("/"):
        parts = [part for part in path.split("/") if part]
        for anchor in ("WaEnhancer", "app"):
            if anchor in parts:
                index = parts.index(anchor)
                return "/".join(parts[index + 1:])

    return path


def read_entries(path: str) -> tuple[ET.ElementTree, list[tuple[ET.Element, str, str]]]:
    """Return the parsed document and each issue element with its (id, file) key."""
    tree = ET.parse(path)
    root = tree.getroot()
    pairs: list[tuple[ET.Element, str, str]] = []
    for issue in root.findall("issue"):
        location = issue.find("location")
        file_attr = location.get("file", "") if location is not None else ""
        pairs.append((issue, issue.get("id", ""), normalise(file_attr)))
    return tree, pairs


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--current",
        required=True,
        metavar="FILE",
        help="a baseline produced by lint with no baseline in place (all current issues)",
    )
    parser.add_argument(
        "--baseline",
        default=BASELINE,
        help="the baseline to prune (default: app/lint-baseline.xml)",
    )
    parser.add_argument(
        "--check",
        action="store_true",
        help="report stale entries and exit 1 without writing",
    )
    args = parser.parse_args(argv)

    for path in (args.current, args.baseline):
        if not os.path.exists(path):
            print("missing %s" % path, file=sys.stderr)
            return 2

    try:
        _current_tree, current_pairs = read_entries(args.current)
        tree, baseline_pairs = read_entries(args.baseline)
    except ET.ParseError as error:
        print("could not parse baseline XML: %s" % error, file=sys.stderr)
        return 2

    current_keys = {(issue_id, file) for _e, issue_id, file in current_pairs}

    kept: list[ET.Element] = []
    stale: list[str] = []
    for element, issue_id, file in baseline_pairs:
        if (issue_id, file) in current_keys:
            kept.append(element)
        else:
            stale.append(issue_id)

    print("baseline entries : %d" % len(baseline_pairs))
    print("current issues   : %d" % len(current_keys))
    print("still matching   : %d" % len(kept))
    print("stale (removable): %d" % len(stale))

    if stale:
        print("\nstale by issue id:")
        for issue_id, count in collections.Counter(stale).most_common():
            print("  %-42s %d" % (issue_id, count))

    baseline_keys = {(issue_id, file) for _e, issue_id, file in baseline_pairs}
    uncovered = current_keys - baseline_keys
    if uncovered:
        print("\ncurrent issues not covered by the baseline (%d, left alone):" % len(uncovered))
        for issue_id, count in collections.Counter(k[0] for k in uncovered).most_common():
            print("  %-42s %d" % (issue_id, count))

    if not stale:
        print("\nnothing to prune")
        return 0

    if args.check:
        print("\nstale entries present", file=sys.stderr)
        return 1

    root = tree.getroot()
    for element in list(root.findall("issue")):
        root.remove(element)
    for element in kept:
        root.append(element)
    ET.indent(tree, space="    ")

    # Re-read before writing, so a bad rewrite never replaces a working baseline.
    verify_path = args.baseline + ".verify"
    tree.write(verify_path, encoding="UTF-8", xml_declaration=True)
    try:
        _t, verify_pairs = read_entries(verify_path)
    except ET.ParseError as error:
        os.unlink(verify_path)
        print("rewrite is not valid XML (%s); baseline left untouched" % error, file=sys.stderr)
        return 2
    os.unlink(verify_path)

    if len(verify_pairs) != len(kept):
        print("rewrite produced %d entries, expected %d; baseline left untouched"
              % (len(verify_pairs), len(kept)), file=sys.stderr)
        return 2

    tree.write(args.baseline, encoding="UTF-8", xml_declaration=True)
    print("\nwrote %s (%d entries, was %d)" % (
        os.path.relpath(args.baseline, REPO_ROOT), len(kept), len(baseline_pairs)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))