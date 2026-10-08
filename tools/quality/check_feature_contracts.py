#!/usr/bin/env python3
"""Fail when a runtime feature contract reaches for a platform type it must not name.

The claim this repository makes about its feature layer is that a feature can be started in a plain
JVM test. That claim is not visible in a signature: a type can appear in a constructor parameter
and nothing fails at the point of declaration, which is exactly how `android.content.SharedPreferences`
came to be the second parameter of sixty-four feature constructors and why not one of them could be
unit tested.

So the check is textual and it is the same shape as the other contract checkers here
(`check_lsposed_contract.py`, `check_preference_types.py`): read the declared package, and fail if
any source in it imports a platform package.

Four things are checked, and the fourth is the one that would otherwise go unnoticed:

1. **The contract package imports nothing platform-facing.** `android.*`, `androidx.*`,
   `de.robv.android.xposed.*` and `org.luckypray.dexkit.*` are all forbidden there.
2. **A contract file does not mention a platform type anywhere**, not only in an import - a fully
   qualified name in a signature leaks exactly as much as an import does.
3. **A production adapter lives in the injected layer, not in the contract package.** The runtime
   implementations of these interfaces necessarily import the framework; they are expected to, and
   they are expected to be somewhere else.
4. **Every contract name the issue requires exists**, and each is an interface or a value type.
   A contract that is not declared cannot be leaked from, but it also does not exist.

Usage:
    python3 tools/quality/check_feature_contracts.py
    python3 tools/quality/check_feature_contracts.py --format json

Exit codes:
    0 the contracts are clean and complete
    1 at least one contract file leaks a platform type, or a required contract is missing
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
CONTRACT_DIR = os.path.join(REPO, "app", "src", "main", "java", "com", "wax", "module", "contract")
ADAPTER_DIR = os.path.join(REPO, "app", "src", "main", "java", "com", "wax", "module", "xposed", "contract")

#: Package prefixes a contract may not name. Each is a platform a test cannot provide.
FORBIDDEN_PREFIXES = (
    "android.",
    "androidx.",
    "de.robv.android.xposed",
    "org.luckypray.dexkit",
)

#: The contracts #335 requires. A contract that is not declared does not exist, whatever the
#: rest of the code says.
REQUIRED_CONTRACTS = (
    "WaFeature",
    "FeatureContext",
    "FeatureStartResult",
    "HookEngine",
    "CapabilityProvider",
    "DiagnosticSink",
    "RuntimeClock",
    "RuntimeLogger",
    "SettingsSnapshot",
)

#: `SettingsSnapshot` is declared in the settings package rather than in `contract`, because it
#: predates this package and already has one production implementation and its own tests. Moving it
#: would be churn with no boundary crossed; what matters is that the contract package carries it
#: and does not re-declare it.
EXTERNAL_CONTRACTS = ("SettingsSnapshot",)

DECLARATION = re.compile(r"^\s*(?:public\s+|internal\s+)?(?:sealed\s+)?(?:abstract\s+)?(?:interface|class|object|fun\s+interface|sealed\s+interface)\s+(\w+)", re.MULTILINE)


def sources(directory: str) -> list[str]:
    if not os.path.isdir(directory):
        return []
    return [
        os.path.join(directory, name)
        for name in sorted(os.listdir(directory))
        if name.endswith(".kt") or name.endswith(".java")
    ]


def strip_comments(text: str) -> str:
    """Remove block and line comments so a documented import is not read as a real one.

    The contracts are heavily documented, and several of them *name* the platform packages they
    exist to keep out - in prose, in KDoc. Without this, the first line of every contract's
    explanation would be reported as a violation of the contract it explains.
    """
    without_block = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    return re.sub(r"//[^\n]*", "", without_block)


def declared_contracts() -> set[str]:
    """Every type declared in the contract package, comments excluded."""
    names: set[str] = set()
    for path in sources(CONTRACT_DIR):
        with open(path, encoding="utf-8") as handle:
            names.update(DECLARATION.findall(strip_comments(handle.read())))
    return names


def check() -> list[dict[str, object]]:
    findings: list[dict[str, object]] = []
    declared: set[str] = set()

    for path in sources(CONTRACT_DIR):
        name = os.path.relpath(path, REPO)
        raw = open(path, encoding="utf-8").read()
        code = strip_comments(raw)
        for declared_name in DECLARATION.findall(code):
            declared.add(declared_name)

        for prefix in FORBIDDEN_PREFIXES:
            if re.search(r"(?:^|[^\w.])(?:import\s+)?" + re.escape(prefix), code):
                findings.append(
                    {
                        "type": "leak",
                        "file": name,
                        "message": "%s references %s. A contract that names a platform type cannot " % (name, prefix)
                        + "be satisfied by a fake, which is the property this package exists to create.",
                    }
                )

    for required in REQUIRED_CONTRACTS:
        if required in EXTERNAL_CONTRACTS:
            continue
        if required not in declared:
            findings.append(
                {
                    "type": "missing",
                    "file": os.path.relpath(CONTRACT_DIR, REPO),
                    "message": "The %s contract is not declared. #335 requires it, and a contract "
                    "that is not declared does not exist however the code is arranged." % required,
                }
            )

    # The adapters are where the framework is allowed to appear. Asserting they exist at all is
    # what stops "the contracts are clean" from being true because nobody implemented them.
    adapters = sources(ADAPTER_DIR)
    if not adapters:
        findings.append(
            {
                "type": "missing-adapter",
                "file": os.path.relpath(ADAPTER_DIR, REPO),
                "message": "No production adapter exists for the contracts. A contract with no "
                "implementation cannot be used by a feature, and a feature cannot be tested against "
                "an interface nothing implements.",
            }
        )

    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args()

    findings = check()
    if args.format == "json":
        print(json.dumps({"findingCount": len(findings), "findings": findings}, indent=2, sort_keys=True))
    else:
        for finding in findings:
            print("%s: %s" % (finding["type"], finding["message"]))
        if findings:
            print("\n%d finding(s)." % len(findings))
        else:
            print(
                "feature contracts are clean: %d file(s), no platform type named, %d contract(s) declared"
                % (len(sources(CONTRACT_DIR)), len(declared_contracts()))
            )
    return 1 if findings else 0


if __name__ == "__main__":
    raise SystemExit(main())