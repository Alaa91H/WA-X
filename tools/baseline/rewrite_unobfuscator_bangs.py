#!/usr/bin/env python3
"""Rewrite `findFirst*UsingStrings(...)!!` into a named failure (T13 batch 3).

Every one of these sites has the same shape: a nullable DexKit lookup asserted non-null
with `!!`. Asserting turns a missing target into a bare null-cast NullPointerException
that names neither the resolver nor what it looked for, which is exactly the failure T15
has to explain to a user.

The rewrite routes each one through `requireClass` / `requireMethod`, which throw the same
exception type on the same condition but with a message naming the resolver and the string
literal it was matching on. Control flow is preserved deliberately: the full typed-result
migration is T13's later batches and T14, and doing it here would touch every caller.

Idempotent: running it twice makes no further changes.

Usage:
    python3 tools/baseline/rewrite_unobfuscator_bangs.py [--check]
"""

from __future__ import annotations

import argparse
import os
import re
import sys

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
TARGET = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wax/module/xposed/core/devkit/Unobfuscator.kt"
)

OPENER_RE = re.compile(r"^(\s*)(findFirst(?:Class|Method)UsingStrings)\($")
CLOSER_RE = re.compile(r"^\s*\)[!?]*$")
STRING_RE = re.compile(r'"([^"]*)"')


def enclosing_function(lines: list[str], index: int) -> str:
    """The nearest preceding `fun loadXxx` declaration."""
    for i in range(index, -1, -1):
        match = re.match(r"\s*fun (load\w+)", lines[i])
        if match:
            return match.group(1)
    return "Unobfuscator"


def hint_between(lines: list[str], start: int, end: int) -> str:
    """The first string literal in the call, which is what the lookup matched on."""
    for i in range(start, end + 1):
        match = STRING_RE.search(lines[i])
        if match:
            return match.group(1)
    return "?"


def rewrite(text: str) -> tuple[str, int]:
    lines = text.split("\n")
    out: list[str] = []
    i = 0
    changed = 0

    while i < len(lines):
        opener = OPENER_RE.match(lines[i])
        if not opener:
            out.append(lines[i])
            i += 1
            continue

        indent, fn_name = opener.group(1), opener.group(2)
        # Find the closing line and confirm it carried the assertion.
        end = None
        for j in range(i + 1, min(i + 12, len(lines))):
            if CLOSER_RE.match(lines[j]):
                end = j
                break
        if end is None or not lines[end].strip().startswith(")"):
            out.append(lines[i])
            i += 1
            continue
        assertion = re.match(r"^\s*\)([!?]+)$", lines[end])
        if not assertion:
            out.append(lines[i])
            i += 1
            continue

        is_class = fn_name.startswith("findFirstClass")
        helper = "requireClass" if is_class else "requireMethod"
        kind = "class" if is_class else "method"
        resolver = enclosing_function(lines, i)
        hint = hint_between(lines, i, end)

        body = ["    " + line if line.strip() else line for line in lines[i + 1:end]]

        out.append("%s%s(" % (indent, helper))
        out.append('%s    "%s",' % (indent, resolver))
        out.append('%s    "%s",' % (indent, hint))
        out.append("%s    %s(" % (indent, fn_name))
        out.extend(body)
        out.append("%s    )" % indent)
        out.append("%s)" % indent)
        changed += 1
        i = end + 1

    return "\n".join(out), changed


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--check",
        action="store_true",
        help="report how many sites would change and exit non-zero, without writing",
    )
    args = parser.parse_args(argv)

    if not os.path.exists(TARGET):
        print("missing %s" % TARGET, file=sys.stderr)
        return 2

    original = open(TARGET, encoding="utf-8").read()
    rewritten, changed = rewrite(original)

    if args.check:
        print("sites that would be rewritten: %d" % changed)
        return 1 if changed else 0

    if changed == 0:
        print("nothing to rewrite (already applied)")
        return 0

    with open(TARGET, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(rewritten)
    print("rewrote %d sites" % changed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
