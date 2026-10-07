"""Prove the runtime gate still fails when the matrix does claim support.

The rewrite made the gate conditional on there being a claim. A conditional gate is only
honest if the condition is reachable, so this plants a `supported` cell in a copy of the
compatibility matrix and checks that the gate then demands device evidence and produces the
full finding list it used to.
"""

import copy
import json
import subprocess
import sys
import tempfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CHECKER = REPO / "tools" / "quality" / "strict" / "check_latest_runtime_evidence.py"

matrix = json.loads((REPO / "tools" / "compatibility" / "compatibility.json").read_text(encoding="utf-8"))

failures = []


def run(matrix_path: Path) -> tuple[int, str]:
    with tempfile.TemporaryDirectory() as work:
        out = Path(work) / "out.json"
        proc = subprocess.run(
            [
                sys.executable,
                str(CHECKER),
                "--compat",
                str(matrix_path),
                "--commit",
                "0" * 40,
                "--out",
                str(out),
            ],
            capture_output=True,
            text=True,
        )
        return proc.returncode, proc.stdout


print("case 1: the matrix as committed claims nothing")
code, output = run(REPO / "tools" / "compatibility" / "compatibility.json")
if code != 0:
    failures.append(f"expected a pass with no claims, got {code}")
elif "no runtime evidence to prove" not in output:
    failures.append("a pass with no claims must say why it passed")
else:
    print("  pass: no claim, no demand")

print("case 2: one cell claims supported and there is no evidence")
planted = copy.deepcopy(matrix)
planted["matrix"] = {
    target: {entry["id"]: {"status": "supported"} for entry in planted["derived"]["features"]}
    for target in planted["packages"]
}
with tempfile.TemporaryDirectory() as work:
    path = Path(work) / "compatibility.json"
    path.write_text(json.dumps(planted), encoding="utf-8")
    code, output = run(path)

if code == 0:
    failures.append("a supported claim with no evidence must fail")
else:
    for expected in ("does not match newest declared train", "apkSha256 must be 64 hex characters",
                     "rooted LSPosed device evidence is required", "no E2E UI evidence"):
        if expected not in output:
            failures.append(f"missing finding: {expected}")
    print(f"  fail as it must, with {output.count('::error::')} findings")

print("case 3: a target default of supported is also treated as a claim")
planted = copy.deepcopy(matrix)
planted["packages"]["whatsapp"]["defaultStatus"] = "supported"
with tempfile.TemporaryDirectory() as work:
    path = Path(work) / "compatibility.json"
    path.write_text(json.dumps(planted), encoding="utf-8")
    code, output = run(path)
if code == 0:
    failures.append("a supported default with no evidence must fail")
else:
    print("  fail as it must")

print("case 4: an unreadable matrix is a failure, not a silent pass")
with tempfile.TemporaryDirectory() as work:
    path = Path(work) / "compatibility.json"
    path.write_text("{ this is not json", encoding="utf-8")
    code, output = run(path)
if code != 2:
    failures.append(f"unreadable matrix should exit 2, got {code}")
else:
    print("  exits 2 with the parse error")

print()
if failures:
    print("FAILURES:")
    for failure in failures:
        print("  -", failure)
    sys.exit(1)
print("all 4 runtime-evidence cases behaved correctly")