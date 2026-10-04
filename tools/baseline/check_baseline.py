#!/usr/bin/env python3
"""T04 baseline regression gate for WaEnhancer.

Compares the current build's metrics against a reference baseline and fails
when any of them regress:

  * lint baseline entries must not increase (0% tolerance)
  * unit tests must not fail/error and fewer tests must not execute
  * every APK listed in the baseline must exist and must not grow more
    than the allowed percentage (default +2%)

Reference selection (anti self-exemption):
  By default the gate reads tools/baseline/baseline.json from the working
  tree. In CI the GitHub context is passed so the reference baseline comes
  from the base revision instead of the change's own copy - a change cannot
  raise the limits it is checked against by editing baseline.json:

      --event-name pull_request --base-ref master [--pr-base-sha SHA]
      --event-name push [--before-sha SHA]
      --base-rev REV

  Candidate order: --base-rev (must resolve), origin/<base-ref>,
  <pr-base-sha>, <before-sha> (unless it is all zeroes), then HEAD^ - the
  fallbacks are only used when a CI hint is given. The first candidate that
  resolves as a commit is the base. When it has no baseline file yet
  (bootstrap), or when nothing resolves, the gate falls back to the
  working-tree baseline with an explicit warning.

Legacy baselines:
  A baseline without testResults.tests (schema from before T04) disables
  only the executed-test-count check, with an explicit warning; test
  failures/errors are still enforced.

Usage:
    python3 tools/baseline/check_baseline.py [options]

Options:
    --baseline FILE        Explicit baseline JSON (no base-rev resolution)
    --base-rev REV         Git revision the reference baseline is read from
    --event-name NAME      GitHub event name (pull_request / push)
    --base-ref BRANCH      github.base_ref (base branch of a pull request)
    --pr-base-sha SHA      github.event.pull_request.base.sha
    --before-sha SHA       github.event.before (tip of the ref before a push)
    --max-apk-growth PCT   Allowed APK growth in percent (default: 2.0)
    --lint-file FILE       lint-baseline.xml (default: app/lint-baseline.xml)
    --test-results DIR     JUnit XML root (default: app/build/test-results)
    --apk-root DIR         APK search root (default: app/build/outputs/apk)

Exit codes: 0 = pass, 1 = regression found, 2 = invalid inputs.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
BASELINE_REL = "tools/baseline/baseline.json"

LINT_ID_RE = re.compile(r'^\s*id="')
TESTSUITE_RE = re.compile(r"<testsuite\b[^>]*>", re.DOTALL)
ZERO_SHA_RE = re.compile(r"^0+$")


class InputError(Exception):
    """Fatal input problem; the gate exits with code 2."""


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--baseline",
        default=None,
        metavar="FILE",
        help="explicit baseline JSON (disables base-revision resolution)",
    )
    parser.add_argument("--base-rev", default=None, metavar="REV")
    parser.add_argument("--event-name", default=None, metavar="NAME")
    parser.add_argument("--base-ref", default=None, metavar="BRANCH")
    parser.add_argument("--pr-base-sha", default=None, metavar="SHA")
    parser.add_argument("--before-sha", default=None, metavar="SHA")
    parser.add_argument("--max-apk-growth", type=float, default=2.0, metavar="PCT")
    parser.add_argument("--lint-file", default="app/lint-baseline.xml")
    parser.add_argument("--test-results", default="app/build/test-results")
    parser.add_argument("--apk-root", default="app/build/outputs/apk")
    return parser.parse_args()


def resolve(path_str: str) -> Path:
    return (REPO_ROOT / path_str).resolve()


def git(*git_args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(REPO_ROOT), *git_args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )


def peel_commit(rev: str) -> str | None:
    proc = git("rev-parse", "--verify", "--quiet", rev + "^{commit}")
    if proc.returncode == 0:
        return proc.stdout.strip() or None
    return None


def base_candidates(args: argparse.Namespace) -> list[tuple[str, bool]]:
    """(revision, required) candidates in priority order."""
    candidates: list[tuple[str, bool]] = []

    def add(rev: str, required: bool = False) -> None:
        if rev and all(existing != rev for existing, _ in candidates):
            candidates.append((rev, required))

    add(args.base_rev or "", required=True)
    if args.event_name == "pull_request":
        if args.base_ref:
            add("origin/" + args.base_ref)
        add(args.pr_base_sha or "")
    elif args.event_name == "push":
        if args.before_sha and not ZERO_SHA_RE.match(args.before_sha):
            add(args.before_sha)
    if args.base_rev or args.event_name:
        add("HEAD^")
    return candidates


def select_baseline(args: argparse.Namespace, notices: list[str]) -> tuple[str, str]:
    """Return (baseline JSON text, source label) for this run."""
    if args.baseline:
        path = resolve(args.baseline)
        if not path.is_file():
            raise InputError("baseline not found: %s" % path)
        return path.read_text(encoding="utf-8"), str(path)

    missing: list[str] = []
    try:
        for rev, required in base_candidates(args):
            sha = peel_commit(rev)
            if sha is None:
                if required:
                    raise InputError("base revision cannot be resolved: %s" % rev)
                missing.append(rev)
                continue
            proc = git("show", "%s:%s" % (sha, BASELINE_REL))
            if proc.returncode == 0 and proc.stdout.strip():
                return proc.stdout, "git %s (%s)" % (rev, sha[:8])
            notices.append(
                "base revision %s (%s) has no %s; falling back to the working-tree "
                "baseline (bootstrap)" % (rev, sha[:8], BASELINE_REL)
            )
            break
        else:
            if missing:
                notices.append(
                    "none of the base revisions resolved (%s); falling back to the "
                    "working-tree baseline (bootstrap)" % ", ".join(missing)
                )
    except OSError as exc:
        notices.append(
            "git is not available (%s); falling back to the working-tree baseline "
            "(bootstrap)" % exc
        )

    path = resolve(BASELINE_REL)
    if not path.is_file():
        raise InputError("baseline not found: %s" % path)
    return path.read_text(encoding="utf-8"), "%s (working tree)" % path


def suite_attrs(text: str) -> dict[str, int]:
    match = TESTSUITE_RE.search(text)
    if not match:
        return {}
    attrs: dict[str, int] = {}
    for name in ("tests", "skipped", "failures", "errors"):
        found = re.search(r'\b%s="(\d+)"' % name, match.group(0))
        attrs[name] = int(found.group(1)) if found else 0
    return attrs


def human_size(num_bytes: int) -> str:
    if num_bytes >= 1048576:
        return "%.2f MiB" % (num_bytes / 1048576)
    if num_bytes >= 1024:
        return "%.1f KiB" % (num_bytes / 1024)
    return "%d B" % num_bytes


def main() -> int:
    args = parse_args()
    checks: list[tuple[bool, str, str]] = []
    notices: list[str] = []

    try:
        text, source = select_baseline(args, notices)
    except InputError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 2
    except OSError as exc:
        print("error: cannot read baseline: %s" % exc, file=sys.stderr)
        return 2
    try:
        baseline = json.loads(text)
    except json.JSONDecodeError as exc:
        print("error: baseline from %s is not valid JSON: %s" % (source, exc), file=sys.stderr)
        return 2

    # --- schema ----------------------------------------------------------
    base_lint = baseline.get("lintBaseline", {}).get("totalIssues")
    if not isinstance(base_lint, int):
        print(
            "error: baseline from %s has no lintBaseline.totalIssues" % source,
            file=sys.stderr,
        )
        return 2
    base_apks = baseline.get("apks") or []
    if not base_apks:
        print("error: baseline from %s has no APK entries" % source, file=sys.stderr)
        return 2
    base_tests = (baseline.get("testResults") or {}).get("tests")
    if not isinstance(base_tests, int):
        base_tests = None
        notices.append(
            "baseline from %s has no testResults.tests (legacy schema); the "
            "executed-test count check is disabled for this run - regenerate the "
            "baseline to enable it" % source
        )

    # --- lint baseline entries ------------------------------------------
    lint_path = resolve(args.lint_file)
    if not lint_path.is_file():
        print("error: lint baseline not found: %s" % lint_path, file=sys.stderr)
        return 2
    current_lint = sum(
        1
        for line in lint_path.read_text(encoding="utf-8", errors="replace").splitlines()
        if LINT_ID_RE.match(line)
    )
    if current_lint > base_lint:
        checks.append(
            (
                False,
                "lint baseline entries",
                "%d > baseline %d (+%d) - fix new findings or justify them in review"
                % (current_lint, base_lint, current_lint - base_lint),
            )
        )
    else:
        checks.append((True, "lint baseline entries", "%d (baseline %d)" % (current_lint, base_lint)))

    # --- unit tests ------------------------------------------------------
    results_dir = resolve(args.test_results)
    xml_files = sorted(results_dir.rglob("*.xml")) if results_dir.is_dir() else []
    if not xml_files:
        checks.append(
            (
                False,
                "unit tests",
                "no JUnit XMLs under %s - run testWhatsappDebugUnitTest/testBusinessDebugUnitTest first"
                % args.test_results,
            )
        )
    else:
        total = skipped = failures = errors = 0
        for xml_file in xml_files:
            attrs = suite_attrs(xml_file.read_text(encoding="utf-8", errors="replace"))
            total += attrs.get("tests", 0)
            skipped += attrs.get("skipped", 0)
            failures += attrs.get("failures", 0)
            errors += attrs.get("errors", 0)
        detail = "%d executed (%d skipped), %d failures, %d errors" % (
            total,
            skipped,
            failures,
            errors,
        )
        if base_tests is not None:
            detail += " (baseline %d)" % base_tests
        else:
            detail += " (no baseline count)"
        if failures or errors:
            checks.append((False, "unit tests", detail))
        elif base_tests is not None and total < base_tests:
            checks.append(
                (
                    False,
                    "unit tests",
                    detail + " - fewer tests executed than in the baseline (%d < %d)" % (total, base_tests),
                )
            )
        else:
            checks.append((True, "unit tests", detail))

    # --- APKs ------------------------------------------------------------
    apk_root = resolve(args.apk_root)
    found: dict[tuple[str, str], Path] = {}
    if apk_root.is_dir():
        for apk in sorted(apk_root.rglob("*.apk")):
            posix = apk.as_posix()
            flavor = (
                "business"
                if "/business/" in posix
                else ("whatsapp" if "/whatsapp/" in posix else None)
            )
            build = (
                "release"
                if "/release/" in posix
                else ("debug" if "/debug/" in posix else None)
            )
            if flavor and build:
                found[(flavor, build)] = apk
    for entry in base_apks:
        key = (str(entry.get("flavor", "?")), str(entry.get("buildType", "?")))
        label = "APK %s/%s" % key
        base_size = int(entry.get("sizeBytes", 0) or 0)
        apk = found.get(key)
        if apk is None:
            checks.append(
                (False, label, "expected artifact not found under %s" % args.apk_root)
            )
            continue
        if base_size <= 0:
            checks.append((False, label, "baseline size is invalid"))
            continue
        size = apk.stat().st_size
        growth = (size - base_size) / base_size * 100.0
        detail = "%s (baseline %s, %+.2f%%, max +%.1f%%)" % (
            human_size(size),
            human_size(base_size),
            growth,
            args.max_apk_growth,
        )
        if growth > args.max_apk_growth:
            checks.append((False, label, detail))
        else:
            checks.append((True, label, detail))

    # --- report ----------------------------------------------------------
    print("WaEnhancer baseline gate (T04)")
    print("Baseline source: %s" % source)
    for notice in notices:
        print("  [WARN] %s" % notice)
    for ok, label, detail in checks:
        print("  [%s] %s: %s" % ("PASS" if ok else "FAIL", label, detail))
    failed = [check for check in checks if not check[0]]
    if failed:
        print("Result: FAIL - %d regression(s) against the baseline" % len(failed))
        return 1
    suffix = " (%d warning(s))" % len(notices) if notices else ""
    print("Result: PASS - no regressions against the baseline%s" % suffix)
    return 0


if __name__ == "__main__":
    sys.exit(main())
