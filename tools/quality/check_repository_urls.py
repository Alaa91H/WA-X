#!/usr/bin/env python3
"""Keep the app's outbound URLs readable and pointed at this repository.

The app makes exactly one kind of network request that a user can see the failure of: the
update check. Its failure mode is deliberately quiet — a non-2xx response turns into
"could not reach the update server" — so a typo in the endpoint is not a crash, it is a
permanent orange banner that reads like a network problem and is retried forever.

That is what happened: the endpoint was spelt

    https://api.github.com/repos/Alaa91H/WA X/releases/latest

with a space where the repository name has a hyphen. OkHttp percent-encodes the space, GitHub
answers 404, and the check failed for every user of every build. The same literal was
copy-pasted into a second call site, so the wrong spelling had two chances to look intentional.

This check reads the Kotlin sources and refuses:

  * a URL literal containing whitespace, which is never a URL a server will answer, and
  * a `api.github.com/repos/<owner>/<name>` path that does not name the canonical repository,
    after resolving the `const val` interpolations the same file declares.

The rule is on the literal, not on the constant, so a new call site that hand-writes the
endpoint is covered as well — which is the case the typo came from.

Usage:
    python3 tools/quality/check_repository_urls.py
Exit codes: 0 in step, 1 a URL is malformed or names another repository, 2 nothing readable.
"""

from __future__ import annotations

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
MAIN_SOURCES = os.path.join(REPO_ROOT, "app/src/main/java")

# The repository the app advertises its own releases from. Renaming the repository means
# changing this line as well, which is the intended reminder: the endpoint, the releases page
# and the docs all have to move together.
CANONICAL_REPOSITORY = "Alaa91H/WA-X"

# A whole absolute URL inside one Kotlin string literal. The character class stops at the
# closing quote, so a literal that holds a space is captured with the space still in it.
URL_LITERAL = re.compile(r'"(https?://[^"\n]*)"')
GITHUB_API_REPOSITORY = re.compile(r"api\.github\.com/repos/([^/\s?\"]+)/([^/\s?\"]+)")

# A `const val` the URL literals in the same file may interpolate. The endpoint is composed from
# the repository slug so the spelling lives once; the checker resolves that composition instead
# of accepting it on trust, which keeps the single definition verifiable rather than invisible.
CONSTANT = re.compile(r'const val (\w+)[^=\n]*=\s*"([^"\n]*)"')
PLACEHOLDER = re.compile(r"\$\{(\w+)\}|\$(\w+)")


def expand(url: str, constants: dict[str, str]) -> str:
    """Substitutes the file's own constants into a URL, as far as they resolve."""
    for _ in range(len(constants) + 1):
        expanded = PLACEHOLDER.sub(
            lambda match: constants.get(match.group(1) or match.group(2), match.group(0)), url
        )
        if expanded == url:
            break
        url = expanded
    return url


def inspect(source: str, path: str) -> list[str]:
    """Every problem with the URLs in one Kotlin source, as ready-to-print lines."""
    constants = dict(CONSTANT.findall(source))
    problems: list[str] = []
    for url in URL_LITERAL.findall(source):
        if any(character.isspace() for character in url):
            problems.append("%s: URL contains whitespace: %s" % (path, url))
            continue
        resolved = expand(url, constants)
        match = GITHUB_API_REPOSITORY.search(resolved)
        if match is None:
            continue
        slug = "%s/%s" % (match.group(1), match.group(2))
        if slug != CANONICAL_REPOSITORY:
            problems.append(
                "%s: GitHub API URL names %s, not %s: %s" % (path, slug, CANONICAL_REPOSITORY, url)
            )
    return problems


def kotlin_sources() -> list[str]:
    paths: list[str] = []
    for directory, _, names in os.walk(MAIN_SOURCES):
        for name in sorted(names):
            if name.endswith(".kt"):
                paths.append(os.path.join(directory, name))
    if not paths:
        print("error: no Kotlin sources under %s" % MAIN_SOURCES, file=sys.stderr)
        raise SystemExit(2)
    return sorted(paths)


def main() -> int:
    problems: list[str] = []
    urls = 0
    for path in kotlin_sources():
        try:
            with open(path, "r", encoding="utf-8") as handle:
                source = handle.read()
        except OSError as error:
            print("error: cannot read %s: %s" % (path, error), file=sys.stderr)
            raise SystemExit(2)
        urls += len(URL_LITERAL.findall(source))
        problems.extend(inspect(source, os.path.relpath(path, REPO_ROOT)))

    if problems:
        for problem in problems:
            print("error: %s" % problem, file=sys.stderr)
        return 1

    print("%d absolute URL literals name %s or no repository at all." % (urls, CANONICAL_REPOSITORY))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
