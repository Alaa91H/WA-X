#!/usr/bin/env python3
"""Fail when mutable global runtime state gains a new writer.

A02's job is not to delete the globals - that is #342 and the rest of the roadmap - it is to stop
them growing while they are being removed. And "stop them growing" cannot be a count, because a
count moves for good reasons: deleting one writer and adding another leaves the number identical
while the module has got strictly worse.

So this checks **write sites**, not state. A write site is a place that assigns a legacy global, and
each one is allowed exactly where it is recorded in `legacy_global_writes.json`, keyed by owner
rather than by line number - line numbers rot on every unrelated edit, and a rule that has to be
re-approved each time it shifts trains people to re-approve it without reading.

Three things are checked:

1. **No new writer.** An assignment to a legacy global outside its recorded owner fails, and the
   message names the symbol, the owner phase it belongs to, and the file that broke the rule.
2. **No new global.** A legacy symbol gaining a *declaration* - a second place that owns it - fails
   for the same reason: state with two owners is state that cannot be released.
3. **No second graph holder.** `RuntimeGraphs` exists so that exactly one thing holds the process
   graph. A second one would reintroduce the global this package removed, with a nicer name.

A missing owner is not a failure, and that asymmetry is deliberate. Legacy state is meant to
disappear, so a writer that is gone leaves a stale entry and the gate says nothing - which is the
correct direction for a ratchet to fail in. The recorded owners are visible in
`docs/modernization/RUNTIME_SURFACE.md` and are re-derived with `--report`.

Usage:
    python3 tools/quality/check_legacy_global_writes.py
    python3 tools/quality/check_legacy_global_writes.py --format json
    python3 tools/quality/check_legacy_global_writes.py --report

Exit codes:
    0 no legacy global gained a writer
    1 at least one new write site, new declaration, or second graph holder
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
LEDGER = os.path.join(REPO, "tools", "quality", "legacy_global_writes.json")
SOURCE_ROOT = os.path.join(REPO, "app", "src", "main", "java")

#: The mutable globals A02 contains. Each entry is the symbol as it is written, and the phase that
#: owns removing it - the message says this, so the answer is in the build output rather than in a
#: search through the roadmap.
LEGACY_GLOBALS = {
    "FeatureLoader.mApp": "#342 A08 / #336 A02",
    "FeatureLoader.moduleContext": "#342 A08",
    "Utils.xprefs": "#342 A08",
    "Utils.appClassLoader": "#336 A02",
    "Feature.isDebug": "#342 A08",
    "mCurrentActivity": "#336 A02",
    "ModuleEntryPoint.pref": "#336 A02",
}

#: The declaration form of each symbol, used for the "no new owner" rule. A declaration is a second
#: holder of the same state, which is the failure the graph exists to prevent.
DECLARATIONS = {
    "mApp": r"var\s+mApp\s*:\s*Application\?",
    "moduleContext": r"(?:lateinit\s+)?var\s+moduleContext\s*:\s*Context",
    "xprefs": r"lateinit\s+var\s+xprefs\s*:",
    "appClassLoader": r"lateinit\s+var\s+appClassLoader\s*:",
    # Declared as an inferred Boolean here rather than a typed one, so the rule has to match
    # the shape the code actually has instead of the shape it would be tidier with.
    "isDebug": r"var\s+isDebug\b",
    "mCurrentActivity": r"var\s+mCurrentActivity\s*:",
}

#: A write site. Deliberately not `==`, deliberately not a declaration: this is an assignment of a
#: value to the state, which is what makes a global a global.
#:
#: Each entry is (pattern, simple name). The simple name is what makes `val moduleContext = ...` in
#: an unrelated file a declaration rather than a write to the loader's field, and `val pref = ...`
#: a local rather than a write to the entry point's - both of which exist in this tree today.
WRITE_PATTERNS = {
    "FeatureLoader.mApp": (r"\bmApp\s*=(?!=)", "mApp"),
    "FeatureLoader.moduleContext": (r"\bmoduleContext\s*=(?!=)", "moduleContext"),
    "Utils.xprefs": (r"\bUtils\.xprefs\s*=(?!=)|\bxprefs\s*=(?!=)", "xprefs"),
    "Utils.appClassLoader": (r"\bUtils\.appClassLoader\s*=(?!=)|\bappClassLoader\s*=(?!=)", "appClassLoader"),
    "Feature.isDebug": (r"\bFeature\.isDebug\s*=(?!=)|\bisDebug\s*=(?!=)", "isDebug"),
    "mCurrentActivity": (r"\bmCurrentActivity\s*=(?!=)", "mCurrentActivity"),
    "ModuleEntryPoint.pref": (r"\bModuleEntryPoint\.pref\s*=(?!=)|(?<![\w.])pref\s*=(?!=)", "pref"),
}


def is_declaration(line: str, simple_name: str) -> bool:
    """True when a line declares the symbol rather than writing to an existing one."""
    return bool(re.search(r"\b(?:val|var)\s+%s\b" % re.escape(simple_name), line))

#: Where the process graph is allowed to be held. One place, or it is a global again.
GRAPH_HOLDER = "app/src/main/java/com/wax/module/xposed/graph/RuntimeGraphs.kt"

#: Where a graph is allowed to be built. The holder, the pure type itself, and the loader, which is
#: the only thing that knows what the process is.
GRAPH_BUILDERS = (
    GRAPH_HOLDER,
    "app/src/main/java/com/wax/module/graph/RuntimeGraph.kt",
    "app/src/main/java/com/wax/module/xposed/core/FeatureLoader.kt",
)

#: A property whose type is a graph. This is the shape a second global would take.
GRAPH_PROPERTY = re.compile(r":\s*RuntimeGraph\??\b")


def kotlin_sources() -> list[tuple[str, list[str]]]:
    """Every production Kotlin file, paired with its lines, as repository-relative paths."""
    found = []
    for directory, _, names in os.walk(SOURCE_ROOT):
        for name in names:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(directory, name)
            relative = os.path.relpath(path, REPO).replace("\\", "/")
            with open(path, encoding="utf-8", errors="replace") as handle:
                found.append((relative, handle.read().splitlines()))
    return sorted(found)


def load_ledger() -> dict:
    if not os.path.exists(LEDGER):
        return {"schema": "wax.legacy_global_writes/1", "symbols": {}}
    with open(LEDGER, encoding="utf-8") as handle:
        return json.load(handle)


def strip_noise(line: str) -> str:
    """Remove the parts of a line that cannot be an assignment of state."""
    without_comment = re.sub(r"//.*$", "", line)
    without_comment = re.sub(r"/\*.*?\*/", "", without_comment)
    return without_comment


def find_sites() -> dict:
    """Every write site and declaration of a legacy global, keyed by symbol."""
    sites: dict = {}
    for relative, lines in kotlin_sources():
        for number, raw in enumerate(lines, start=1):
            line = strip_noise(raw)
            if not line.strip():
                continue
            for symbol, (pattern, simple_name) in WRITE_PATTERNS.items():
                if is_declaration(line, simple_name):
                    continue
                if re.search(pattern, line):
                    sites.setdefault(symbol, []).append({"file": relative, "line": number, "text": line.strip()})
            for name, pattern in DECLARATIONS.items():
                if re.search(pattern, line):
                    sites.setdefault("declare:%s" % name, []).append(
                        {"file": relative, "line": number, "text": line.strip()}
                    )
    return sites


def check() -> list[dict]:
    ledger = load_ledger()
    recorded = ledger.get("symbols", {})
    sites = find_sites()
    findings = []

    for symbol, entry in WRITE_PATTERNS.items():
        owner = LEGACY_GLOBALS[symbol]
        allowed = set(recorded.get(symbol, {}).get("writers", []))
        for site in sites.get(symbol, []):
            if site["file"] in allowed:
                continue
            findings.append(
                {
                    "type": "new-writer",
                    "symbol": symbol,
                    "file": site["file"],
                    "line": site["line"],
                    "text": site["text"],
                    "message": "%s is written at %s:%d, which is not its recorded owner. #336 A02 "
                    "contains mutable global state by giving it an owner and a lifetime; a new "
                    "writer is new state with no end. Either publish it through RuntimeGraph, or "
                    "record the owner in tools/quality/legacy_global_writes.json if this write is "
                    "genuinely the one that replaces an existing one. Removal phase: %s."
                    % (symbol, site["file"], site["line"], owner),
                }
            )

    for name in DECLARATIONS:
        symbol = "declare:%s" % name
        allowed = set(recorded.get(symbol, {}).get("writers", []))
        for site in sites.get(symbol, []):
            if site["file"] in allowed:
                continue
            findings.append(
                {
                    "type": "new-owner",
                    "symbol": name,
                    "file": site["file"],
                    "line": site["line"],
                    "text": site["text"],
                    "message": "%s is declared at %s:%d, giving that state a second owner. The "
                    "graph exists so this state has exactly one holder that can be released; a "
                    "second declaration is the failure it was built to prevent."
                    % (name, site["file"], site["line"]),
                }
            )

    for relative, lines in kotlin_sources():
        body = "\n".join(strip_noise(line) for line in lines)
        if GRAPH_PROPERTY.search(body) and relative != GRAPH_HOLDER:
            findings.append(
                {
                    "type": "second-graph-holder",
                    "symbol": "RuntimeGraph",
                    "file": relative,
                    "line": 1,
                    "text": relative,
                    "message": "%s holds a RuntimeGraph in a field. %s is the single holder; a "
                    "second one reintroduces the global this package removed under a new name."
                    % (relative, GRAPH_HOLDER),
                }
            )
        if "RuntimeGraph(" in body and relative not in GRAPH_BUILDERS:
            findings.append(
                {
                    "type": "unexpected-graph-builder",
                    "symbol": "RuntimeGraph",
                    "file": relative,
                    "line": 1,
                    "text": relative,
                    "message": "%s constructs a RuntimeGraph. Only %s may build one: a graph is a "
                    "process identity, and a file that makes its own has one per call."
                    % (relative, ", ".join(GRAPH_BUILDERS)),
                }
            )

    return findings


def report() -> str:
    """The current write sites, for reviewing the ledger."""
    sites = find_sites()
    lines = []
    for symbol in sorted(sites):
        owners = sorted({site["file"] for site in sites[symbol]})
        lines.append("%s (%d site(s) in %d file(s))" % (symbol, len(sites[symbol]), len(owners)))
        for owner in owners:
            lines.append("    %s" % owner)
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--format", choices=("text", "json"), default="text")
    parser.add_argument("--report", action="store_true", help="print the write sites found and exit")
    args = parser.parse_args()

    if args.report:
        print(report())
        return 0

    findings = check()
    if args.format == "json":
        print(json.dumps({"findingCount": len(findings), "findings": findings}, indent=2, sort_keys=True))
        return 1 if findings else 0

    for finding in findings:
        print("%s: %s" % (finding["type"], finding["message"]))
    if findings:
        print("\n%d finding(s)." % len(findings))
    else:
        ledger = load_ledger()
        sites = find_sites()
        print(
            "legacy global writes hold: %d symbol(s), %d recorded owner(s), no new writer"
            % (
                len(LEGACY_GLOBALS),
                sum(len(entry.get("writers", [])) for entry in ledger.get("symbols", {}).values()),
            )
        )
        del sites
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())