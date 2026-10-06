#!/usr/bin/env python3
"""Prove the update endpoint checker can fail before it is trusted to pass.

A checker that only ever sees the current tree is a checker with an unknown failure mode. Each
case here is a source file the checker must reject — including the exact spelling that shipped,
with the space in the repository name — and a clean file it must accept.

Usage:
    python3 tools/quality/test_check_repository_urls.py
Exit codes: 0 every case behaved, 1 a case did not.
"""

from __future__ import annotations

import importlib.util
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(HERE, "check_repository_urls.py")

SPEC = importlib.util.spec_from_file_location("check_repository_urls", CHECKER)
if SPEC is None or SPEC.loader is None:
    print("error: cannot load %s" % CHECKER, file=sys.stderr)
    raise SystemExit(2)
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)

# (name, source, must be rejected)
CASES: list[tuple[str, str, bool]] = [
    (
        "the spelling that shipped",
        'private const val API = "https://api.github.com/repos/Alaa91H/WA X/releases/latest"',
        True,
    ),
    (
        "the percent-encoded form of the same mistake",
        'const val API = "https://api.github.com/repos/Alaa91H/WA%20X/releases/latest"',
        True,
    ),
    (
        "another owner's repository",
        'const val API = "https://api.github.com/repos/Dev4Mod/WaEnhancer/releases/latest"',
        True,
    ),
    (
        "a whitespace-only difference in the slug",
        'const val API = "https://api.github.com/repos/Alaa91H/WA-X /releases/latest"',
        True,
    ),
    (
        "the correct endpoint",
        'const val API = "https://api.github.com/repos/Alaa91H/WA-X/releases/latest"',
        False,
    ),
    (
        "the endpoint composed from the repository constant",
        'const val REPOSITORY = "Alaa91H/WA-X"\n'
        'const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"',
        False,
    ),
    (
        "the same composition over a misspelt slug",
        'const val REPOSITORY = "Alaa91H/WA X"\n'
        'const val API = "https://api.github.com/repos/${REPOSITORY}/releases/latest"',
        True,
    ),
    (
        "an interpolation nothing in the file declares",
        'const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"',
        True,
    ),
    (
        "the releases page, which is not an API path",
        'const val PAGE = "https://github.com/Alaa91H/WA-X/releases"',
        False,
    ),
    (
        "another host entirely",
        'const val DOCS = "https://developer.android.com/reference/java/net/URI"',
        False,
    ),
    (
        "no absolute URL at all",
        'const val KEY = "wae.presence.alert."',
        False,
    ),
]


def main() -> int:
    failures: list[str] = []
    for name, source, must_reject in CASES:
        problems = checker.inspect(source, "Fixture.kt")
        rejected = bool(problems)
        if rejected != must_reject:
            expectation = "rejected" if must_reject else "accepted"
            failures.append("%s: expected the source to be %s, problems=%s" % (name, expectation, problems))

    if failures:
        for failure in failures:
            print("error: %s" % failure, file=sys.stderr)
        return 1

    print("%d endpoint cases behave as declared." % len(CASES))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
