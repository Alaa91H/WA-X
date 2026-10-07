#!/usr/bin/env python3
"""Self-test for tools/ci/release_publication.py.

The defect this protects against is not a crash: it is a beta tag quietly becoming the
repository's Latest release, which is invisible in CI output and only shows up as the wrong build
offered to whoever opens the releases page. So the cases below assert both directions - a beta is
never latest, a stable release always is - and assert that the classifier refuses a version the
pipeline's preflight would refuse, because a classifier that accepts more than the pipeline
publishes can disagree with it.

Usage: python3 tools/ci/test_release_publication.py
Exit codes: 0 every case behaved as declared, 1 at least one did not.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))


def load():
    spec = importlib.util.spec_from_file_location(
        "release_publication", os.path.join(HERE, "release_publication.py")
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


MODULE = load()


def run(*argv: str) -> tuple[int, str]:
    out = io.StringIO()
    err = io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = MODULE.main(list(argv))
    return code, (out.getvalue() + err.getvalue()).strip()


def main() -> int:
    failures = 0
    total = 0

    def case(name: str, condition: bool, detail: str = "") -> None:
        nonlocal failures, total
        total += 1
        if condition:
            print("[pass] %s" % name)
        else:
            failures += 1
            print("[fail] %s%s" % (name, (": " + detail) if detail else ""))

    # A beta is published as a pre-release and is not offered as latest. Every spelling the
    # project's own versioning has used or can use.
    for version in ("1.2.0-beta.1", "1.2.0-beta.2", "1.2.0-beta.10", "1.2.0-rc.1", "2.0.0-alpha", "1.0.0-beta.x.y"):
        code, out = run("--version", version)
        case(
            "%s is published as a pre-release" % version,
            code == 0 and out == "--prerelease",
            "exit %d, output %r" % (code, out),
        )
        case(
            "%s is never passed to --latest" % version,
            "--latest" not in out,
            out,
        )

    # A stable release keeps the behaviour it had, so the fix cannot be mistaken for removing the
    # Latest marker from everything.
    for version in ("1.2.0", "1.0.0", "10.20.30"):
        code, out = run("--version", version)
        case(
            "%s is published as latest" % version,
            code == 0 and out == "--latest",
            "exit %d, output %r" % (code, out),
        )
        case("%s is not marked pre-release" % version, "--prerelease" not in out, out)

    # Whitespace off a properties file is not a reason to misclassify.
    code, out = run("--version", " 1.2.0-beta.2\n")
    case("surrounding whitespace is ignored", code == 0 and out == "--prerelease", "exit %d %r" % (code, out))

    # Anything preflight would refuse is refused here, rather than classified by guesswork.
    for version in ("1.2", "1.2.0.1", "v1.2.0", "1.2.0+meta", "", "beta.2", "1.2.0-", "nightly"):
        code, out = run("--version", version)
        case(
            "%r is rejected rather than classified" % version,
            code == 2 and "preflight" in out,
            "exit %d, output %r" % (code, out),
        )

    # The workflow consumes the flags directly, so exactly one flag must be printed, and the JSON
    # form has to agree with it.
    code, out = run("--version", "1.2.0-beta.2", "--format", "json")
    payload = json.loads(out)
    case("json agrees with the flags form", code == 0 and payload["flags"] == ["--prerelease"], out)
    case("json names the kind", payload["kind"] == "prerelease" and payload["version"] == "1.2.0-beta.2", out)

    code, out = run("--version", "1.2.0", "--format", "json")
    payload = json.loads(out)
    case("json for a stable version says latest", payload["flags"] == ["--latest"] and payload["kind"] == "stable", out)

    # The classifier and the pipeline must agree on what a version looks like. If the workflow's
    # pattern ever changes, this is where the two are compared.
    workflow = os.path.join(HERE, "..", "..", ".github", "workflows", "ci.yml")
    try:
        with open(workflow, encoding="utf-8") as handle:
            content = handle.read()
    except OSError as exc:  # pragma: no cover - the repository always has it
        print("[fail] could not read the workflow to compare patterns: %s" % exc)
        failures += 1
        total += 1
        content = ""
    if content:
        case(
            "the workflow's accepted version pattern matches this tool's",
            "[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?" in content,
            "the workflow no longer contains the pattern this tool mirrors",
        )
        case(
            "the release step consumes this tool rather than hardcoding a flag",
            "release_publication.py" in content and "--latest \\" not in content,
            "the release step still passes --latest unconditionally",
        )

    print()
    if failures:
        print("%d release-publication case(s) failed" % failures)
        return 1
    print("all %d release-publication cases behaved correctly" % total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
