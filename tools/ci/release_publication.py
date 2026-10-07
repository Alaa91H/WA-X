#!/usr/bin/env python3
"""Decide how a release is published, and never let a beta become the repository's Latest.

GitHub has two independent things a release can be: a **pre-release**, which the releases page
marks and hides behind a link, and the repository's **latest** release, which is what
``/releases/latest`` resolves to and what a visitor is shown first. The pipeline published every
tag with ``--latest`` and without ``--prerelease``, so a build cut for testing replaced the
pointer to the last stable release, and the package-based development program cuts one beta per
development package by design.

The rule is simple and this tool exists so it is testable rather than remembered:

* a version carrying a pre-release suffix (``1.2.0-beta.2``) is published ``--prerelease`` and is
  **not** offered as latest;
* a version without one (``1.2.0``) is published ``--latest``;
* a version that is neither - anything the pipeline's own preflight would reject - is an error
  here too, so this tool cannot silently classify something the rest of the pipeline refuses.

Usage:
    python3 tools/ci/release_publication.py --version 1.2.0-beta.2     # prints --prerelease
    python3 tools/ci/release_publication.py --version 1.2.0            # prints --latest
    python3 tools/ci/release_publication.py --version 1.2.0-beta.2 --format json
Exit codes: 0 flags printed, 2 the version is not one the pipeline accepts.
"""

from __future__ import annotations

import argparse
import json
import re
import sys

# The same shape the workflow's preflight accepts for `waxVersionName`. Kept identical on purpose:
# a tool that classifies versions the pipeline would refuse is a tool that can disagree with it.
ACCEPTED_VERSION = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$")


def classify(version: str) -> str:
    """"prerelease" or "stable" for a version the pipeline accepts."""
    return "prerelease" if "-" in version else "stable"


def flags(version: str) -> list[str]:
    """The `gh release create` flags for this version: exactly one of --prerelease / --latest."""
    kind = classify(version)
    if kind == "prerelease":
        return ["--prerelease"]
    return ["--latest"]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--version", required=True, help="the value of waxVersionName")
    parser.add_argument("--format", choices=("flags", "json"), default="flags")
    args = parser.parse_args(argv)

    version = args.version.strip()
    if not ACCEPTED_VERSION.match(version):
        print(
            "error: %r is not a version the pipeline publishes; preflight accepts "
            "'MAJOR.MINOR.PATCH' with an optional '-suffix'" % args.version,
            file=sys.stderr,
        )
        return 2

    kind = classify(version)
    if args.format == "json":
        print(json.dumps({"version": version, "kind": kind, "flags": flags(version)}, indent=2, sort_keys=True))
    else:
        print(" ".join(flags(version)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
