#!/usr/bin/env python3
"""Measure the dependency graph, and enforce the architecture law against the measurement.

Two things live here because they cannot honestly be separated: what the graph *is*, and what it
is *allowed* to be. A forbidden-edge list typed by hand drifts from the tree within a week, and a
ratchet whose numbers are transcribed rather than measured is a ratchet nobody trusts.

So the law declares the *edges* and the *limits*, and this tool supplies the *counts*. If the law
says `xposed.features -> SharedPreferences: at most 64 files`, the 64 comes from the tree, and a
mismatch is a failure of the law file rather than a silent pass.

## What is forbidden, and why

The forbidden edges come from the program's own Definition of Done:

    No feature -> Unobfuscator
    No feature -> SharedPreferences
    No UI -> Xposed/DexKit
    architecture dependency violations = 0

None of those are zero today, and pretending otherwise would make this gate a lie. So every one
carries a measured `limit` equal to today's value, and the gate fails the moment it goes **up**.
That is the only shape this gate can honestly have today: not "we have no violations" but "we have
N, the N is written down, and N may only fall". Each limit records the phase that owns removing
it, so the debt has a destination rather than being a permanent excuse.

## Transitive matters as much as direct

`xposed.features.listeners` does not import `Unobfuscator`. It imports `xposed.core`, which
imports `xposed.core.devkit.Unobfuscator`. A gate that only looked at direct imports would report
that edge as absent while the prohibition it exists to enforce is fully violated in practice.
Both are therefore measured, and the law can require either.

Usage:
    python3 tools/architecture/check_architecture.py --write
    python3 tools/architecture/check_architecture.py --check
    python3 tools/architecture/check_architecture.py --format json
Exit codes: 0 within the law, 1 a violation or a stale limit, 2 bad input.
"""

from __future__ import annotations

import argparse
import collections
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))

LAW_PATH = "docs/architecture/architecture-law.json"
GRAPH_PATH = "docs/architecture/baseline/a00-dependency-graph.json"

SOURCE_ROOT = "app/src/main/java"
PACKAGE = "com.wax.module"

IMPORT = re.compile(r"^\s*import\s+([A-Za-z0-9_.$]+)", re.MULTILINE)
DECLARED_PACKAGE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)", re.MULTILINE)

# Layers that run inside a hooked application process. The Manager process is everything else.
# Several forbidden edges only make sense for the injected half - a singleton in the Manager is
# ordinary, the same singleton in the injected process is the thing A02 has to contain - so the
# law scopes them rather than pretending the distinction does not exist.
RUNTIME_LAYERS = ("xposed",)


def read(path: str) -> str | None:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return None


def layer_of(package: str | None) -> str:
    if not package or package == PACKAGE:
        return "<root>"
    if not package.startswith(PACKAGE + "."):
        return "<external>"
    return package[len(PACKAGE) + 1 :]


def external_root(imported: str) -> str:
    """Classify a non-first-party import into the dependency root the law cares about."""
    if imported.startswith("de.robv.android.xposed"):
        return "xposed"
    if imported.startswith("dexkit"):
        return "dexkit"
    if imported.startswith(("android.", "androidx.", "java.", "javax.", "kotlin", "kotlinx")):
        return "platform"
    return imported.split(".")[0]


class Graph:
    """The measured package-level dependency graph."""

    def __init__(self) -> None:
        self.files_by_layer: dict[str, list[str]] = collections.defaultdict(list)
        self.first_party: dict[str, set[str]] = collections.defaultdict(set)
        self.external: dict[str, set[str]] = collections.defaultdict(set)
        self.shared_prefs: dict[str, set[str]] = collections.defaultdict(set)
        self.self_hook: dict[str, set[str]] = collections.defaultdict(set)
        self.files: dict[str, str] = {}
        self.locations: dict[str, str] = {}
        self.totals = {"files": 0, "lines": 0}

    def reach(self, layer: str) -> set[str]:
        """Every first-party layer `layer` reaches, following first-party edges transitively."""
        seen: set[str] = set()
        pending = list(self.first_party.get(layer, ()))
        while pending:
            current = pending.pop()
            if current in seen:
                continue
            seen.add(current)
            pending.extend(self.first_party.get(current, ()))
        return seen

    def transitive_edge(self, sources: tuple[str, ...], target: str) -> set[str]:
        """Files in `sources` that reach `target`, directly or through other first-party layers."""
        resolved = self.layers_matching(sources)
        hitting = {layer for layer in resolved if target in self.first_party.get(layer, ())}
        for layer in resolved:
            if target in self.reach(layer):
                hitting.add(layer)
        return {
            path
            for layer in hitting
            for path in self.files_by_layer.get(layer, ())
        }

    def direct_edge(self, sources: tuple[str, ...], target: str) -> set[str]:
        return {
            path
            for layer in self.layers_matching(sources)
            if target in self.first_party.get(layer, ())
            for path in self.files_by_layer.get(layer, ())
        }

    def layers_matching(self, scopes: tuple[str, ...]) -> list[str]:
        """Every declared layer that a scope names, matched as a path prefix.

        Scope names are prefixes, not exact layers. `xposed.features` is not a layer - the real
        ones are `xposed.features.general`, `.privacy` and so on - so an exact match silently
        resolves to nothing and the rule reports zero forever. A gate that measures nothing is
        worse than no gate: it reads as a clean bill of health. `test_check_architecture.py`
        asserts no scope resolves to an empty set for exactly that reason.
        """
        matched: list[str] = []
        for layer in self.files_by_layer:
            for scope in scopes:
                if scope == "<root>":
                    if layer == "<root>":
                        matched.append(layer)
                elif layer == scope or layer.startswith(scope + "."):
                    matched.append(layer)
                    break
        return sorted(set(matched))

    def files_in(self, scopes: tuple[str, ...]) -> set[str]:
        return {
            path
            for layer in self.layers_matching(scopes)
            for path in self.files_by_layer.get(layer, ())
        }

    def matches(self, paths: set[str], token: str) -> set[str]:
        return {path for path in paths if token in self.files.get(path, "")}


def build(root: str) -> Graph:
    graph = Graph()
    base = os.path.join(root, SOURCE_ROOT)
    if not os.path.isdir(base):
        print("error: %s not found" % SOURCE_ROOT, file=sys.stderr)
        raise SystemExit(2)

    for directory, _dirs, names in os.walk(base):
        for name in sorted(names):
            if not name.endswith((".kt", ".java")):
                continue
            path = os.path.join(directory, name)
            rel = os.path.relpath(path, root).replace("\\", "/")
            text = read(path)
            if text is None:
                print("error: cannot read %s" % rel, file=sys.stderr)
                raise SystemExit(2)

            declared = DECLARED_PACKAGE.search(text)
            package = declared.group(1) if declared else PACKAGE
            layer = layer_of(package)

            graph.files[rel] = text
            graph.locations[rel] = layer
            graph.files_by_layer[layer].append(rel)
            graph.totals["files"] += 1
            graph.totals["lines"] += text.count("\n") + 1

            for imported in IMPORT.findall(text):
                if imported.startswith(PACKAGE):
                    tail = imported[len(PACKAGE) :].lstrip(".")
                    target = (
                        layer_of(PACKAGE + "." + tail.rsplit(".", 1)[0])
                        if "." in tail
                        else "<root>"
                    )
                    if target not in (layer, "<external>"):
                        graph.first_party[layer].add(target)
                else:
                    graph.external[layer].add(external_root(imported))

            if "SharedPreferences" in text:
                graph.shared_prefs[layer].add(rel)
            if "isXposedEnabled" in text:
                graph.self_hook[layer].add(rel)

    return graph


# --- the law ---------------------------------------------------------------------------------
#
# `measure` names how to count it. `limit` is filled in by --write from the measurement, never
# typed by hand. `direction` is "atMost": the gate fails when the count exceeds the limit.

LAW: dict[str, object] = {
    "AE-01": {
        "title": "A feature must not reach Unobfuscator, directly or transitively",
        "rationale": (
            "A feature that calls a resolver itself cannot be isolated when resolution fails, and "
            "cannot be reasoned about without reading DexKit output. The dependency the program "
            "wants is Feature -> Capability -> Resolver -> DexKit."
        ),
        "measure": "feature_unobfuscator",
        "limit": None,
        "owner": "#327 M08 / #338 A04",
        "removalPhase": "M08/A04 - DexEngine and the capability boundary",
    },
    "AE-02": {
        "title": "A feature must not touch SharedPreferences",
        "rationale": (
            "The runtime reads the manager's preference file from inside another app's process. "
            "Every feature that reads it directly is a second, unmanaged path for settings, and "
            "settings are the one thing that must not be able to leak across the WhatsApp and "
            "Business targets."
        ),
        "measure": "feature_shared_preferences",
        "limit": None,
        "owner": "#342 A08",
        "removalPhase": "A08 - typed settings, immutable snapshot, no runtime SharedPreferences",
    },
    "AE-03": {
        "title": "Manager UI must not import the Xposed API or DexKit",
        "rationale": (
            "The Manager runs as an ordinary application. Anything it imports from the injected "
            "runtime is either dead code there or a class that will not resolve, and the second "
            "case fails at runtime on a device rather than at compile time."
        ),
        "measure": "ui_xposed_direct",
        "limit": None,
        "owner": "#335 A01 / #343 A09",
        "removalPhase": "A01 - explicit Manager/Runtime contracts",
    },
    "AE-04": {
        "title": "DexKit must be reached only through the resolver layer",
        "rationale": (
            "DexKit needs a resolver engine behind it so a scan cannot be repeated per feature and "
            "a failure cannot stop the runtime."
        ),
        "measure": "dexkit_outside_resolver",
        "limit": None,
        "owner": "#327 M08",
        "removalPhase": "M08 - DexEngine behind ResolverRegistry",
    },
    "AE-05": {
        "title": "Shared layers must not depend on the injected runtime",
        "rationale": (
            "`utils`, `adapter` and `views` are used by both processes. A shared layer importing "
            "the runtime makes the Manager depend on classes the framework only supplies inside a "
            "hooked process, which is how a Manager-side NoClassDefFoundError happens."
        ),
        "measure": "shared_layer_imports_runtime",
        "limit": None,
        "owner": "#335 A01 / #336 A02",
        "removalPhase": "A01/A02 - explicit Manager/Runtime split",
    },
    "AE-06": {
        "title": "The activation signal must not be a self-hook",
        "rationale": (
            "A method whose value is installed by a hook into the module's own process answers "
            "'the module is running', not 'the module is working', and the Manager used to treat "
            "the two as the same question - so a DexKit or resolver failure presented as 'LSPosed "
            "is disabled'. M02 renamed the method to say what it observes and made activation a "
            "per-target claim; the rule is at zero and stays there, because a method named for "
            "the legacy signal must not become the answer to anything again."
        ),
        "measure": "self_hook_references",
        "limit": None,
        "owner": "#335 A01",
        "removalPhase": "A01 - explicit Manager/Runtime contracts",
    },
}

MANAGER_LAYERS = ("ui", "activities", "adapter", "views", "preference", "settings")
FEATURE_LAYERS = ("xposed.features",)
SHARED_LAYERS = ("utils", "adapter", "views", "model", "preference")
# Layers permitted to touch DexKit directly: the resolver engine that owns the scans.
RESOLVER_LAYERS = ("xposed.core.devkit", "xposed.core.components")


def measure_all(graph: Graph) -> dict[str, int]:
    features = graph.files_in(FEATURE_LAYERS)
    resolver = graph.files_in(RESOLVER_LAYERS)
    values = {
        # No text filter. A feature that reaches Unobfuscator *through* another layer never
        # names the symbol, and filtering on the name threw away precisely the case the rule
        # exists to catch - the one the program calls `Feature -> Unobfuscator` while looking
        # at an import list that does not contain it.
        "feature_unobfuscator": len(graph.transitive_edge(FEATURE_LAYERS, "xposed.core.devkit")),
        "feature_shared_preferences": len(graph.matches(features, "SharedPreferences")),
        "ui_xposed_direct": len(
            graph.files_in(MANAGER_LAYERS)
            & {
                path
                for path in graph.files
                if re.search(r"^\s*import\s+de\.robv", graph.files[path], re.MULTILINE)
            }
        ),
        "dexkit_outside_resolver": len(
            {
                path
                for layer, paths in graph.files_by_layer.items()
                for path in paths
                if "dexkit." in graph.files[path] and path not in resolver
            }
        ),
        "shared_layer_imports_runtime": len(
            {
                path
                for layer in graph.layers_matching(SHARED_LAYERS)
                for path in graph.files_by_layer.get(layer, ())
                if any(
                    target.startswith("xposed")
                    for target in graph.first_party.get(layer, ())
                )
            }
        ),
        "self_hook_references": len(
            {
                path
                for paths in graph.self_hook.values()
                for path in paths
                if "isXposedEnabled" in graph.files[path]
            }
        ),
    }
    return values


def snapshot(graph: Graph, values: dict[str, int]) -> dict[str, object]:
    return {
        "schema": "wax.a00.dependency-graph/1",
        "totals": graph.totals,
        "layers": {
            layer: {
                "files": len(paths),
                "first_party": sorted(graph.first_party.get(layer, ())),
                "external": sorted(graph.external.get(layer, ())),
            }
            for layer, paths in sorted(graph.files_by_layer.items())
        },
        "measurements": values,
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--write", action="store_true", help="write the graph and the measured limits")
    group.add_argument("--check", action="store_true", help="verify the law holds and limits are current")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args(argv)

    graph = build(ROOT)
    values = measure_all(graph)
    graph_path = os.path.join(ROOT, GRAPH_PATH)
    law_path = os.path.join(ROOT, LAW_PATH)

    if args.write:
        os.makedirs(os.path.dirname(graph_path), exist_ok=True)
        with open(graph_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(snapshot(graph, values), indent=2, sort_keys=True) + "\n")

        existing = None
        try:
            with open(law_path, "r", encoding="utf-8") as handle:
                existing = json.load(handle)
        except (OSError, json.JSONDecodeError):
            pass

        rules = existing.get("edges", {}) if existing else {}
        for key, rule in LAW.items():
            entry = rules.setdefault(key, {})
            entry.update(rule)
            entry["limit"] = values[rule["measure"]]
        document = {
            "schema": "wax.a00.architecture-law/1",
            "philosophy": (
                "Every forbidden edge carries a measured limit equal to today's count. The gate "
                "fails when a count rises. It does not pass because there are no violations; it "
                "passes because the violations are counted, owned and may only fall."
            ),
            "scopes": {
                "feature_layers": list(FEATURE_LAYERS),
                "manager_layers": list(MANAGER_LAYERS),
                "shared_layers": list(SHARED_LAYERS),
                "runtime_layers": list(RUNTIME_LAYERS),
            },
            "edges": dict(sorted(rules.items())),
        }
        os.makedirs(os.path.dirname(law_path), exist_ok=True)
        with open(law_path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(document, indent=2, sort_keys=True) + "\n")
        print("wrote %s and %s" % (GRAPH_PATH, LAW_PATH), file=sys.stderr)
        return 0

    content = read(law_path)
    if content is None:
        print("error: %s does not exist; run with --write" % LAW_PATH, file=sys.stderr)
        return 1
    law = json.loads(content)

    failures: list[str] = []
    notes: list[str] = []
    declared = law.get("edges", {})

    for key in sorted(LAW):
        rule = declared.get(key)
        if rule is None:
            failures.append("%s is declared in the checker but absent from the law file" % key)
            continue
        for field in ("title", "rationale", "measure", "owner", "removalPhase"):
            if not rule.get(field):
                failures.append("%s has no %s" % (key, field))
        measure = rule.get("measure")
        if measure not in values:
            failures.append("%s names an unknown measurement %r" % (key, measure))
            continue
        actual = values[measure]
        limit = rule.get("limit")
        if limit is None:
            failures.append(
                "%s has no limit, so it cannot fail. Run with --write to record today's %d."
                % (key, actual)
            )
            continue
        if actual > limit:
            failures.append(
                "%s regressed: %s is now %d files, limit %d. Owner: %s (%s)"
                % (key, measure, actual, limit, rule.get("owner"), rule.get("removalPhase"))
            )
        elif actual < limit:
            notes.append(
                "%s improved: %s is %d files, was allowed %d. Re-run with --write to record it."
                % (key, measure, actual, limit)
            )
        else:
            notes.append("%s holds at %d" % (key, actual))

    for key in sorted(set(declared) - set(LAW)):
        failures.append("%s is in the law file but not checked by this tool" % key)

    if args.format == "json":
        print(json.dumps({"violations": failures, "notes": notes}, indent=2))
        return 1 if failures else 0

    for note in notes:
        print("note: %s" % note)
    for failure in failures:
        print("error: %s" % failure, file=sys.stderr)
    if failures:
        print("\n%d architecture violation(s)" % len(failures), file=sys.stderr)
        return 1

    debt = sum(values[declared[key]["measure"]] for key in declared if key in LAW)
    print(
        "architecture law holds: %d rules at or under their limits, %d files of recorded debt "
        "across %d source files" % (len(LAW), debt, graph.totals["files"])
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))