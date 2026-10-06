#!/usr/bin/env python3
"""Fail the build when an internal access gate appears in the repository.

WA X has no paywall: no Premium or Pro tier, no supporter-only feature, no donation
that unlocks anything, and no licence server standing between a user and a feature the
project owns. A rule that exists only in a document stops being true the first time
somebody adds ``if (isPremiumUser)`` in a hurry, so this scanner turns it into a gate.

What it looks for
-----------------

Gate-shaped *identifiers*: ``premiumOnly``, ``isPremium``, ``hasLicense``,
``paywall``, ``upgradeToUnlock``, ``hasPaidEntitlement`` and the rest of the list in
``GATE_TOKENS`` / ``GATE_PAIRS``. Those two sets are the same sets
``NoPaywallContract`` uses at runtime, and ``test_scan_access_gates.py`` asserts that
they cannot drift apart: the Kotlin audit and this scanner have to agree about what a
gate is, or one of them is checking something the other is not.

What it deliberately does not look for
--------------------------------------

Ordinary support wording. A Ko-fi link, a thank-you screen, a help page about donations
and a mention of "supported" are all fine and would otherwise dominate the output. The
distinction is the whole reason the token list is gate-shaped rather than a list of
suspicious words.

Comments are ignored. A comment saying "this feature is not gated behind a payment" must
not fail the check, and doc comments describing the policy are how the policy survives.

Third-party entitlements are respected, not bypassed. WhatsApp's, Meta's and any
provider's own paid capability is read from the client; the allowlist records those
places explicitly, with a reason, rather than by widening the token list.

Usage
-----

    python3 tools/quality/scan_access_gates.py                 # scan and report
    python3 tools/quality/scan_access_gates.py --format json   # machine-readable
    python3 tools/quality/scan_access_gates.py --root DIR      # scan a different tree

Exit codes: 0 clean, 1 at least one gate found, 2 the invocation itself was wrong
(missing allowlist, unreadable root, stale allowlist entry).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Any, Iterable

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEFAULT_ALLOWLIST = os.path.join(os.path.dirname(__file__), "access_gate_allowlist.json")
CONTRACT_SOURCE = os.path.join(
    REPO_ROOT, "app/src/main/java/com/wax/module/platform/NoPaywallContract.kt"
)

# Scanned by default. Deliberately source and build inputs, not the whole tree: a scanner
# that walks binary artifacts and generated output finds whatever a dependency shipped and
# buries the findings that are ours.
SCAN_SUFFIXES = (".kt", ".kts", ".java", ".xml", ".toml", ".gradle", ".pro", ".aidl", ".py")
SKIP_DIRECTORIES = {
    ".git",
    ".gradle",
    ".idea",
    ".kotlin",
    "build",
    ".cxx",
    "__pycache__",
    "node_modules",
}

# Git submodules. Third-party code that ships with the project is not WA X's feature
# access, and a scanner that reads it reports whatever its upstream happened to license.
VENDORED_PREFIXES = (
    "app/src/main/cpp/opus/",
    "app/src/main/cpp/ogg/",
    "app/src/main/cpp/libopusenc/",
)

# Single tokens that only ever appear in an access gate. Mirrored from
# NoPaywallContract.GATE_TOKENS; test_scan_access_gates.py enforces the mirror.
GATE_TOKENS = {
    "premium",
    "donor",
    "donate",
    "donation",
    "supporter",
    "paywall",
    "entitlement",
    "billingclient",
    "playbilling",
    "license",
    "licensed",
    "licence",
    "subscription",
}

# Adjacent-token pairs that name a gate even though each half is harmless alone.
# Mirrored from NoPaywallContract.GATE_PAIRS.
# Three-token names that are a gate even though no two adjacent tokens are. Mirrored from
# NoPaywallContract.GATE_TRIPLES. "upgradeToUnlock" is why this set exists: as two tokens it
# is "upgradeto" + "tounlock", neither of which is damning on its own.
GATE_TRIPLES = {
    "upgradetounlock",
    "unlockupgrade",
    "unlockpremium",
    "premiumunlock",
    "haspaidaccess",
    "subscribetounlock",
    "donatetounlock",
}

GATE_PAIRS = {
    "proonly",
    "premiumonly",
    "donoronly",
    "donationonly",
    "supporteronly",
    "viponly",
    "ispro",
    "ispremium",
    "isdonor",
    "issupporter",
    "haslicense",
    "hasentitlement",
    "licensekey",
    "licensekeys",
    "licenseserver",
    "licensedonly",
    "paidonly",
    "featurelocked",
    "lockedfeature",
    "unlockfeature",
    "unlockfor",
    "upgradetounlock",
    "upgradetopro",
    "unlockby",
    "unlockwith",
    "premiumtier",
    "goldtier",
    "donortier",
    "supportertier",
    "premiumfeature",
    "paidfeature",
    "purchasefeature",
    "querypurchases",
    "launchbillingflow",
    "activationcode",
}

BOUNDARY = re.compile(r"[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])")


# --- comment stripping --------------------------------------------------------------------


def strip_comments(text: str, suffix: str) -> str:
    """Return ``text`` with comments blanked out, preserving line structure.

    Blanking rather than deleting keeps line numbers honest, which matters because the
    report points a reviewer at a line. The state machine tracks string literals so a
    ``//`` inside a URL is left alone, and tracked quotes so a brace inside a string
    cannot desynchronise it.
    """
    if suffix == ".py":
        return _strip_hash_comments(text)
    if suffix == ".xml":
        return _strip_xml_comments(text)
    return _strip_c_like_comments(text)


def _strip_xml_comments(text: str) -> str:
    """Blank out ``<!-- ... -->`` blocks.

    Worth handling separately because almost every resource file in the tree carries a
    licence header, and a scan that reports the project's own GPL/Apache headers as an
    access gate is a scan nobody will read.
    """
    out: list[str] = []
    index = 0
    length = len(text)
    while index < length:
        start = text.find("<!--", index)
        if start == -1:
            out.append(text[index:])
            break
        out.append(text[index:start])
        end = text.find("-->", start + 4)
        end = length if end == -1 else end + 3
        out.append(_blank_preserving_newlines(text[start:end]))
        index = end
    return "".join(out)


def _strip_hash_comments(text: str) -> str:
    return "\n".join(_blank_hash_line(line) for line in text.split("\n"))


def _blank_hash_line(line: str) -> str:
    in_single = False
    in_double = False
    for index, char in enumerate(line):
        if char == "'" and not in_double:
            in_single = not in_single
        elif char == '"' and not in_single:
            in_double = not in_double
        elif char == "#" and not in_single and not in_double:
            return line[:index]
    return line


def _strip_c_like_comments(text: str) -> str:
    out: list[str] = []
    index = 0
    length = len(text)
    while index < length:
        char = text[index]
        two = text[index : index + 2]
        three = text[index : index + 3]
        if three == '"""':
            end = text.find('"""', index + 3)
            end = length if end == -1 else end + 3
            out.append(_blank_preserving_newlines(text[index:end]))
            index = end
        elif two == "/*":
            end = text.find("*/", index + 2)
            end = length if end == -1 else end + 2
            out.append(_blank_preserving_newlines(text[index:end]))
            index = end
        elif two == "//":
            end = text.find("\n", index)
            if end == -1:
                out.append(" " * (length - index))
                index = length
            else:
                out.append(" " * (end - index))
                index = end
        elif char == '"':
            end = index + 1
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                if text[end] == '"':
                    end += 1
                    break
                end += 1
            out.append(text[index:end])
            index = end
        elif char == "'":
            end = index + 1
            while end < length:
                if text[end] == "\\":
                    end += 2
                    continue
                if text[end] == "'":
                    end += 1
                    break
                end += 1
            out.append(text[index:end])
            index = end
        else:
            out.append(char)
            index += 1
    return "".join(out)


def _blank_preserving_newlines(block: str) -> str:
    return "".join("\n" if char == "\n" else " " for char in block)


# --- detection ----------------------------------------------------------------------------


def tokens_of(text: str) -> list[str]:
    """Split an identifier or text into lower-case tokens, camel case included."""
    return [token.lower() for token in BOUNDARY.split(text) if token]


def gate_tokens_in(text: str) -> list[str]:
    """Every gate-shaped token in ``text``, in order, de-duplicated."""
    tokens = tokens_of(text)
    found: list[str] = []
    for index, token in enumerate(tokens):
        if token in GATE_TOKENS and token not in found:
            found.append(token)
        if index + 1 < len(tokens):
            pair = token + tokens[index + 1]
            if pair in GATE_PAIRS and pair not in found:
                found.append(pair)
            if index + 2 < len(tokens):
                triple = pair + tokens[index + 2]
                if triple in GATE_TRIPLES and triple not in found:
                    found.append(triple)
    return found


def donation_unlock_phrasing(text: str) -> list[str]:
    """Phrases that tie a donation to unlocking a feature.

    Checked separately because it is the one gate that is a *sentence* rather than an
    identifier: "donate to unlock" cannot be caught by token matching without also
    flagging ordinary support wording.
    """
    lowered = text.lower()
    found: list[str] = []
    for match in re.finditer(r"\b(donat\w*|support\w*|subscrib\w*)\b[^.\n]{0,60}?\bunlock\w*", lowered):
        found.append(match.group(0).strip())
    for match in re.finditer(r"\bunlock\w*\b[^.\n]{0,60}?\b(donat\w*|subscrib\w*)\b", lowered):
        found.append(match.group(0).strip())
    return found


def scan_text(text: str, path: str, suffix: str) -> list[dict[str, Any]]:
    """Findings in one file's contents."""
    stripped = strip_comments(text, suffix)
    findings: list[dict[str, Any]] = []
    for number, line in enumerate(stripped.split("\n"), start=1):
        for token in gate_tokens_in(line):
            findings.append({"path": path, "line": number, "token": token, "excerpt": line.strip()[:160]})
        for phrase in donation_unlock_phrasing(line):
            findings.append({"path": path, "line": number, "token": phrase, "excerpt": line.strip()[:160]})
    return findings


def _is_scanned(relative: str) -> bool:
    normalised = relative.replace("\\", "/")
    if normalised.startswith(VENDORED_PREFIXES):
        return False
    parts = normalised.split("/")
    if any(part in SKIP_DIRECTORIES for part in parts[:-1]):
        return False
    return relative.endswith(SCAN_SUFFIXES)


def iter_files(root: str) -> Iterable[str]:
    for base, directories, names in os.walk(root):
        directories[:] = sorted(d for d in directories if d not in SKIP_DIRECTORIES)
        for name in sorted(names):
            absolute = os.path.join(base, name)
            relative = os.path.relpath(absolute, root)
            if _is_scanned(relative):
                yield relative


# --- allowlist ----------------------------------------------------------------------------


def load_allowlist(path: str) -> tuple[list[dict[str, Any]], list[str]]:
    """Load and validate the allowlist. Returns (entries, problems)."""
    if not os.path.isfile(path):
        return [], ["allowlist not found: %s" % path]
    try:
        with open(path, "r", encoding="utf-8") as handle:
            document = json.load(handle)
    except (OSError, ValueError) as error:
        return [], ["allowlist is not readable JSON: %s" % error]
    entries = document.get("allow")
    if not isinstance(entries, list):
        return [], ["allowlist must contain an 'allow' array"]
    problems: list[str] = []
    for index, entry in enumerate(entries):
        if not isinstance(entry, dict):
            problems.append("allow[%d] is not an object" % index)
            continue
        for key in ("path", "reason", "category"):
            if not entry.get(key):
                problems.append("allow[%d] is missing '%s'" % (index, key))
        tokens = entry.get("tokens")
        if tokens is not None:
            if not isinstance(tokens, list) or not tokens or not all(isinstance(item, str) for item in tokens):
                problems.append("allow[%d].tokens must be a non-empty array of strings" % index)
    return entries, problems


def match_allowlist(entries: list[dict[str, Any]], relative: str) -> dict[str, Any] | None:
    """The first allowlist entry covering ``relative``, or None."""
    normalised = relative.replace("\\", "/")
    for entry in entries:
        pattern = str(entry.get("path", "")).replace("\\", "/")
        if not pattern:
            continue
        if pattern.endswith("/") and normalised.startswith(pattern):
            return entry
        if normalised == pattern:
            return entry
        # A bare directory name such as "docs/" covers the subtree; a name without a slash
        # is matched as a path suffix so a moved package does not silently stop being exempt.
        if "/" not in pattern and normalised.endswith(pattern):
            return entry
    return None


def apply_allowlist(
    entries: list[dict[str, Any]],
    relative: str,
    findings: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Split findings into (violations, exempted) for one file.

    An entry may narrow itself to specific tokens. That matters where a file is part of the
    contract rather than a user of it: the feature catalog legitimately mentions the audit
    test's name, and exempting the whole catalog would stop the scanner from noticing a
    gated feature id added to the one file that declares every feature.
    """
    entry = match_allowlist(entries, relative)
    if entry is None:
        return findings, []
    allowed_tokens = entry.get("tokens")
    if allowed_tokens is None:
        return [], findings
    normalised = {str(token).lower() for token in allowed_tokens}
    violations = [finding for finding in findings if finding["token"].lower() not in normalised]
    exempted = [finding for finding in findings if finding["token"].lower() in normalised]
    return violations, exempted


def allowlist_is_stale(entries: list[dict[str, Any]], root: str) -> list[str]:
    """Allowlist entries that cover nothing.

    A stale exemption is how an allowlist rots into a blanket permission, so an entry that
    matches no file in the tree is an error rather than a note.
    """
    problems: list[str] = []
    candidates = list(iter_files(root))
    for entry in entries:
        pattern = str(entry.get("path", "")).replace("\\", "/")
        if pattern.endswith("/") or "/" not in pattern:
            matched = any(match_allowlist([entry], candidate) for candidate in candidates)
        else:
            matched = any(candidate.replace("\\", "/") == pattern for candidate in candidates)
        if not matched:
            problems.append("allowlist entry '%s' matches nothing" % pattern)
    return problems


# --- contract mirror ----------------------------------------------------------------------


def kotlin_set(name: str, source: str | None = None) -> set[str]:
    """Read one string set out of NoPaywallContract.kt.

    Used by the self-test to prove that the Kotlin audit and this scanner share one
    definition of a gate. Without it the two drift and each quietly checks a different
    subset of the contract.
    """
    text = source if source is not None else _read(CONTRACT_SOURCE)
    if text is None:
        return set()
    marker = "val %s: Set<String> =" % name
    start = text.find(marker)
    if start == -1:
        marker = "val %s: List<String> =" % name
        start = text.find(marker)
        if start == -1:
            return set()
    end = text.find("\n    )", start)
    if end == -1:
        end = text.find(")\n", start)
    block = text[start : end if end != -1 else len(text)]
    return {value.lower() for value in re.findall(r'"([^"]+)"', block)}


def _read(path: str) -> str | None:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return handle.read()
    except OSError:
        return None


# --- entry point --------------------------------------------------------------------------


def scan_repository(root: str, allowlist_path: str) -> tuple[list[dict[str, Any]], list[str], list[str]]:
    """Scan ``root``. Returns (violations, suppressed, problems)."""
    entries, problems = load_allowlist(allowlist_path)
    if problems:
        return [], [], problems
    problems.extend(allowlist_is_stale(entries, root))
    violations: list[dict[str, Any]] = []
    suppressed: list[str] = []
    for relative in iter_files(root):
        text = _read(os.path.join(root, relative))
        if text is None:
            continue
        findings = scan_text(text, relative, os.path.splitext(relative)[1])
        if not findings:
            continue
        remaining, exempted = apply_allowlist(entries, relative, findings)
        violations.extend(remaining)
        if exempted:
            entry = match_allowlist(entries, relative) or {}
            suppressed.append(
                "%s [%s] (%s: %s)"
                % (relative, ", ".join(sorted({item["token"] for item in exempted})), entry.get("category"), entry.get("reason"))
            )
    return violations, sorted(set(suppressed)), problems


def render_text(violations: list[dict[str, Any]], suppressed: list[str]) -> str:
    lines: list[str] = []
    if suppressed:
        lines.append("Allowlisted (reason recorded in access_gate_allowlist.json):")
        lines.extend("  - %s" % item for item in suppressed)
        lines.append("")
    if not violations:
        lines.append("No internal access gate found. WA X owns no paid feature tier.")
        return "\n".join(lines)
    lines.append("Internal access gates found: %d" % len(violations))
    for violation in violations:
        lines.append(
            "  %s:%d  %s   %s"
            % (violation["path"], violation["line"], violation["token"], violation["excerpt"])
        )
    lines.append("")
    lines.append(
        "Every WA X feature is free. Only technical, safety, capability, version, root or "
        "experimental state may limit one."
    )
    lines.append(
        "If this is documentation or a test, add it to access_gate_allowlist.json with a reason. "
        "If it is a reference to WhatsApp's or a provider's own paid capability, say so in the category."
    )
    return "\n".join(lines)


def _configure_output() -> None:
    """Make the report readable whatever encoding the console was started with.

    The tree contains Arabic and other non-Latin text, and a finding quotes the line it
    came from. On a Windows console that defaults to a legacy code page this would raise
    instead of reporting, which is the one failure mode a diagnostic tool must not have.
    """
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is None:
            continue
        try:
            reconfigure(encoding="utf-8", errors="replace")
        except (ValueError, OSError):
            pass


def main(argv: list[str] | None = None) -> int:
    _configure_output()
    parser = argparse.ArgumentParser(description="Scan for internal WA X access gates.")
    parser.add_argument("--root", default=REPO_ROOT, help="repository root to scan")
    parser.add_argument("--allowlist", default=DEFAULT_ALLOWLIST, help="allowlist file")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    parser.add_argument("--check-mirror", action="store_true", help="also verify the Kotlin token sets match")
    args = parser.parse_args(argv)

    if not os.path.isdir(args.root):
        print("error: --root is not a directory: %s" % args.root, file=sys.stderr)
        return 2

    if args.check_mirror:
        drift = mirror_drift()
        if drift:
            for problem in drift:
                print("error: %s" % problem, file=sys.stderr)
            return 2

    violations, suppressed, problems = scan_repository(args.root, args.allowlist)
    if problems:
        for problem in problems:
            print("error: %s" % problem, file=sys.stderr)
        return 2

    if args.format == "json":
        print(json.dumps({"violations": violations, "allowlisted": suppressed}, indent=2, sort_keys=True))
    else:
        print(render_text(violations, suppressed))
    return 1 if violations else 0


def mirror_drift() -> list[str]:
    """Differences between this scanner's token sets and the Kotlin contract's."""
    problems: list[str] = []
    for name, local in (("GATE_TOKENS", GATE_TOKENS), ("GATE_PAIRS", GATE_PAIRS), ("GATE_TRIPLES", GATE_TRIPLES)):
        declared = kotlin_set(name)
        if not declared:
            problems.append("could not read %s from %s" % (name, CONTRACT_SOURCE))
            continue
        missing = sorted(declared - local)
        extra = sorted(local - declared)
        if missing:
            problems.append("%s is missing from the scanner: %s" % (name, missing))
        if extra:
            problems.append("%s in the scanner is not in the contract: %s" % (name, extra))
    return problems


if __name__ == "__main__":
    sys.exit(main())
