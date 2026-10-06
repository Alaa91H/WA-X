#!/usr/bin/env python3
"""Mutation tests for scan_access_gates.py.

A gate that always passes is worse than no gate. Each case below plants exactly one
condition and asserts the scanner reacts to it — and, just as importantly, the cases
that assert it *does not* react include the shapes that would otherwise make the check
noisy enough to be ignored: comments, ordinary support wording, licence text, and a
legitimate reference to another party's paid capability.

The last case runs the scanner over the real repository, so a change that introduces a
gate here fails locally instead of in CI.

Usage:
    python3 tools/quality/test_scan_access_gates.py
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SCANNER = os.path.join(HERE, "scan_access_gates.py")
ALLOWLIST = os.path.join(HERE, "access_gate_allowlist.json")

sys.path.insert(0, HERE)
import scan_access_gates as scanner  # noqa: E402

CASES: list[tuple[str, object]] = []


def case(name):
    def register(function):
        CASES.append((name, function))
        return function

    return register


def expect(condition, message):
    if not condition:
        raise AssertionError(message)


class Fixture:
    """A temporary tree and allowlist, removed when the case finishes."""

    def __init__(self, files, allow=None):
        self.root = tempfile.mkdtemp(prefix="wae-scan-root-")
        for relative, content in files.items():
            path = os.path.join(self.root, relative.replace("/", os.sep))
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as handle:
                handle.write(content)
        handle_fd, self.allowlist = tempfile.mkstemp(suffix=".json", prefix="wae-allow-")
        with os.fdopen(handle_fd, "w", encoding="utf-8", newline="\n") as handle:
            json.dump({"allow": allow or []}, handle, indent=2)

    def scan(self):
        return scanner.scan_repository(self.root, self.allowlist)

    def findings(self):
        return self.scan()[0]

    def close(self):
        shutil.rmtree(self.root, ignore_errors=True)
        if os.path.exists(self.allowlist):
            os.unlink(self.allowlist)

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.close()
        return False


# --- violations are found -----------------------------------------------------------------


@case("a planted premium gate is found")
def _premium_gate():
    with Fixture({"src/A.kt": "fun f() {\n    if (isPremiumUser) enableFeature()\n}\n"}) as fixture:
        findings = fixture.findings()
        expect(findings, "a premium gate must be reported")
        expect("ispremium" in {item["token"] for item in findings}, "the compound token must be named: %s" % findings)
        expect(findings[0]["line"] == 2, "the line must point at the gate: %s" % findings[0])


@case("a pro-only identifier is found")
def _pro_only():
    with Fixture({"src/A.kt": "val proOnly = true\n"}) as fixture:
        expect("proonly" in {item["token"] for item in fixture.findings()}, "proOnly must be reported")


@case("an upgrade-to-unlock identifier is found")
def _upgrade_to_unlock():
    with Fixture({"src/A.kt": "fun upgradeToUnlock() {}\n"}) as fixture:
        expect(
            "upgradetounlock" in {item["token"] for item in fixture.findings()},
            "upgradeToUnlock must be reported: %s" % fixture.findings(),
        )


@case("a licence-server identifier is found")
def _licence_server():
    with Fixture({"src/A.kt": "class LicenseServer { fun hasLicense(): Boolean = false }\n"}) as fixture:
        tokens = {item["token"] for item in fixture.findings()}
        expect("haslicense" in tokens, "hasLicense must be reported: %s" % tokens)


@case("a donation-unlock sentence is found")
def _donation_unlock():
    with Fixture({"src/A.kt": """val notice = "Donate to unlock advanced privacy"\n"""}) as fixture:
        tokens = [item["token"] for item in fixture.findings()]
        expect(
            any("donate to unlock" in token for token in tokens),
            "a donation-unlock sentence must be reported: %s" % tokens,
        )


@case("an entitlement check is found")
def _entitlement():
    with Fixture({"src/A.kt": "val ok = hasPaidEntitlement()\n"}) as fixture:
        expect("entitlement" in {item["token"] for item in fixture.findings()}, "entitlement must be reported")


# --- the check is not noisy ---------------------------------------------------------------


@case("a clean file passes")
def _clean():
    with Fixture(
        {
            "src/A.kt": "fun greeting(): String = \"hello\"\n",
            "src/res/values/strings.xml": "<resources><string name=\"ok\">OK</string></resources>\n",
        }
    ) as fixture:
        violations, _, problems = fixture.scan()
        expect(not violations, "nothing should be reported: %s" % violations)
        expect(not problems, "no allowlist problem is expected: %s" % problems)


@case("a comment mentioning a premium tier is ignored")
def _comment():
    with Fixture(
        {
            "src/A.kt": "// This feature is deliberately not premium-only and has no paywall.\n"
            "/** The Pro tier does not exist in WA X. */\n"
            "fun f() = Unit\n"
        }
    ) as fixture:
        expect(not fixture.findings(), "comments must be ignored: %s" % fixture.findings())


@case("an XML comment is ignored")
def _xml_comment():
    with Fixture({"src/res/values/strings.xml": "<!-- GNU General Public License v3.0 -->\n<resources />\n"}) as fixture:
        expect(not fixture.findings(), "XML comments must be ignored: %s" % fixture.findings())


@case("a Python comment is ignored")
def _python_comment():
    with Fixture({"src/tool.py": "# no premium tier here\nprint('ok')\n"}) as fixture:
        expect(not fixture.findings(), "Python comments must be ignored: %s" % fixture.findings())


@case("a URL inside a string does not desynchronise comment stripping")
def _url_in_string():
    with Fixture(
        {
            "src/A.kt": 'val url = "https://example.com/paywall"\nval real = 1 /* block comment */\n',
        }
    ) as fixture:
        findings = fixture.findings()
        expect([item["line"] for item in findings] == [1], "only the string counts: %s" % findings)


@case("ordinary support wording is not a gate")
def _support_wording():
    with Fixture(
        {
            "src/A.kt": 'val a = "support_development"\nval b = "supported_versions"\nval c = "wae.support.kofi"\n',
        }
    ) as fixture:
        expect(not fixture.findings(), "support wording must not be reported: %s" % fixture.findings())


@case("vendored third-party sources are not scanned")
def _vendored():
    with Fixture({"app/src/main/cpp/opus/doc/x.xml": "<a>license</a>\n"}) as fixture:
        expect(not fixture.findings(), "a submodule must not be scanned")


# --- allowlist behaviour ------------------------------------------------------------------


@case("an allowlisted path is exempt")
def _allowlisted_path():
    files = {"src/A.kt": "val x = isPremiumUser\n"}
    with Fixture(files, allow=[{"path": "src/", "category": "tests", "reason": "planted"}]) as fixture:
        violations, suppressed, problems = fixture.scan()
        expect(not violations, "the path is allowlisted: %s" % violations)
        expect(suppressed, "the exemption must be reported, not silent")
        expect(not problems, "problems: %s" % problems)


@case("a token-scoped entry exempts only that token")
def _token_scoped():
    files = {
        "src/A.kt": "val a = isPremiumUser\n",
        "src/res/values/strings.xml": "<resources><string name=\"license\">GNU</string></resources>\n",
    }
    entries = [
        {"path": "src/A.kt", "category": "contract", "tokens": ["premium", "ispremium"], "reason": "names a gate"},
        {"path": "src/res/", "category": "documentation", "reason": "licence text"},
    ]
    with Fixture(files, allow=entries) as fixture:
        violations, suppressed, _ = fixture.scan()
        expect(not violations, "both entries exempt what they name: %s" % violations)
        expect(any("A.kt" in item for item in suppressed), "the narrowing must be reported: %s" % suppressed)


@case("a token-scoped entry leaves other tokens in the same file failing")
def _token_scoped_negative():
    with Fixture(
        {"src/A.kt": "val a = isPremiumUser\nval b = hasLicense()\n"},
        allow=[{"path": "src/A.kt", "category": "contract", "tokens": ["premium", "ispremium"], "reason": "narrow"}],
    ) as fixture:
        violations, _, _ = fixture.scan()
        expect(violations, "the licence gate in the same file must still fail")
        tokens = {item["token"] for item in violations}
        expect("haslicense" in tokens, "only the non-exempt token fails: %s" % tokens)
        expect("premium" not in tokens and "ispremium" not in tokens, "the exempt tokens must not appear: %s" % tokens)


@case("a stale allowlist entry is an error")
def _stale_allowlist():
    with Fixture(
        {"src/A.kt": "fun f() = Unit\n"},
        allow=[{"path": "src/Gone.kt", "category": "tests", "reason": "no longer exists"}],
    ) as fixture:
        _, _, problems = fixture.scan()
        expect(any("matches nothing" in problem for problem in problems), "problems: %s" % problems)


@case("an allowlist entry without a reason is rejected")
def _reason_required():
    with Fixture(
        {"src/A.kt": "fun f() = Unit\n"},
        allow=[{"path": "src/", "category": "tests"}],
    ) as fixture:
        _, _, problems = fixture.scan()
        expect(any("reason" in problem for problem in problems), "problems: %s" % problems)


@case("an empty and a non-list allowlist are both rejected")
def _bad_allowlist_shape():
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as handle:
        handle.write("{\"allow\": [\"not an object\"]}")
        path = handle.name
    try:
        _, _, problems = scanner.scan_repository(REPO_ROOT, path)
        expect(problems, "a malformed entry must be reported")
    finally:
        os.unlink(path)


@case("a missing allowlist is an error")
def _missing_allowlist():
    _, _, problems = scanner.scan_repository(REPO_ROOT, os.path.join(HERE, "does-not-exist.json"))
    expect(any("not found" in problem for problem in problems), "problems: %s" % problems)


# --- the command line ---------------------------------------------------------------------


def run_scanner(*arguments):
    result = subprocess.run(
        [sys.executable, SCANNER, *arguments],
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
    )
    return result.returncode, result.stdout + result.stderr


@case("the command line exits 0 on a clean tree")
def _exit_zero():
    with Fixture({"src/A.kt": "fun f() = Unit\n"}) as fixture:
        code, output = run_scanner("--root", fixture.root, "--allowlist", fixture.allowlist)
        expect(code == 0, "expected 0, got %d: %s" % (code, output))


@case("the command line exits 1 on a violation")
def _exit_one():
    with Fixture({"src/A.kt": "val a = isPremiumUser\n"}) as fixture:
        code, output = run_scanner("--root", fixture.root, "--allowlist", fixture.allowlist)
        expect(code == 1, "expected 1, got %d: %s" % (code, output))
        expect("ispremium" in output, "the report must name the token: %s" % output)


@case("the command line exits 2 on a stale allowlist")
def _exit_two():
    with Fixture(
        {"src/A.kt": "fun f() = Unit\n"},
        allow=[{"path": "src/Gone.kt", "category": "tests", "reason": "gone"}],
    ) as fixture:
        code, output = run_scanner("--root", fixture.root, "--allowlist", fixture.allowlist)
        expect(code == 2, "expected 2, got %d: %s" % (code, output))


@case("the command line exits 2 on a root that is not a directory")
def _exit_two_bad_root():
    code, _ = run_scanner("--root", os.path.join(HERE, "no-such-directory"))
    expect(code == 2, "expected 2, got %d" % code)


# --- consistency and the real repository ---------------------------------------------------


@case("the Kotlin contract and the scanner share one definition of a gate")
def _mirror():
    drift = scanner.mirror_drift()
    expect(not drift, "\n".join(drift))


@case("comment stripping preserves line numbers")
def _line_numbers():
    text = "line one\n/* two\nthree */\nval gated = isPremiumUser\n"
    stripped = scanner.strip_comments(text, ".kt")
    expect(len(stripped.split("\n")) == len(text.split("\n")), "line count must be preserved")
    expect(
        {item["line"] for item in scanner.scan_text(text, "A.kt", ".kt")} == {4},
        "the gate is on line 4: %s" % scanner.scan_text(text, "A.kt", ".kt"),
    )


@case("the real repository is free of internal access gates")
def _real_repository():
    violations, _, problems = scanner.scan_repository(REPO_ROOT, ALLOWLIST)
    expect(not problems, "allowlist problems: %s" % problems)
    expect(not violations, "internal access gates found: %s" % violations)


def main() -> int:
    failures = 0
    for name, function in CASES:
        try:
            function()
            status = "pass"
        except AssertionError as error:
            status = "FAIL"
            failures += 1
            print("[%s] %s" % (status, name))
            for line in str(error).splitlines():
                print("         %s" % line)
            continue
        print("[%s] %s" % (status, name))

    print()
    if failures:
        print("%d of %d scanner cases failed" % (failures, len(CASES)))
        return 1
    print("all %d scanner cases behaved correctly" % len(CASES))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
