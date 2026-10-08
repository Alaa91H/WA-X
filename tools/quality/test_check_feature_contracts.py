#!/usr/bin/env python3
"""Prove the feature-contract checker can still fail.

A gate that has never rejected anything is not a gate. This writes deliberately broken contract
packages into a temporary copy and asserts that each defect is reported, then asserts that the
real repository is clean. Run it first in CI, exactly as the other contract checkers are run,
because a checker that passes because it looks in the wrong directory is worse than no checker.
"""

from __future__ import annotations

import io
import os
import shutil
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import check_feature_contracts as checker  # noqa: E402

CONTRACT_HEADER = "package com.wax.module.contract\n\n"


#: Set for the duration of a mutation that needs the fixture's own paths rather than the
#: contract directory it is handed.
CHECKER_HOLDER: dict[str, str] = {}


def with_repository(mutate) -> list[dict[str, object]]:
    """Run the checker against a throwaway repository that `mutate` has damaged."""
    original_contract_dir = checker.CONTRACT_DIR
    original_adapter_dir = checker.ADAPTER_DIR
    original_repo = checker.REPO
    root = tempfile.mkdtemp(prefix="wax-contract-check-")
    try:
        contract_dir = os.path.join(root, "app", "src", "main", "java", "com", "wax", "module", "contract")
        adapter_dir = os.path.join(root, "app", "src", "main", "java", "com", "wax", "module", "xposed", "contract")
        os.makedirs(contract_dir)
        os.makedirs(adapter_dir)
        # Every required contract is declared as a stub, so a case that damages one of them sees
        # exactly the finding it caused rather than a pile of "missing" noise on top of it.
        for required in checker.REQUIRED_CONTRACTS:
            if required in checker.EXTERNAL_CONTRACTS:
                continue
            with io.open(os.path.join(contract_dir, "%s.kt" % required), "w", encoding="utf-8") as handle:
                handle.write(CONTRACT_HEADER + "interface %s\n" % required)
        with io.open(os.path.join(adapter_dir, "RuntimeFeatureContext.kt"), "w", encoding="utf-8") as handle:
            handle.write("package com.wax.module.xposed.contract\n")

        checker.REPO = root
        checker.CONTRACT_DIR = contract_dir
        checker.ADAPTER_DIR = adapter_dir
        CHECKER_HOLDER["contract"] = contract_dir
        CHECKER_HOLDER["adapter"] = adapter_dir
        mutate(contract_dir)
        return checker.check()
    finally:
        checker.CONTRACT_DIR = original_contract_dir
        checker.ADAPTER_DIR = original_adapter_dir
        checker.REPO = original_repo
        shutil.rmtree(root, ignore_errors=True)


def write(directory: str, name: str, body: str) -> None:
    with io.open(os.path.join(directory, name), "w", encoding="utf-8") as handle:
        handle.write(CONTRACT_HEADER + body)


def main() -> int:
    cases: list[tuple[str, bool, list[dict[str, object]]]] = []

    def finding_types(findings: list[dict[str, object]]) -> list[str]:
        return sorted({str(f["type"]) for f in findings})

    # 1. The real repository is clean.
    cases.append(("the repository satisfies its own contracts", checker.check() == [], []))

    # 2. An imported Android type is reported.
    cases.append(
        (
            "an imported android type is rejected",
            finding_types(
                with_repository(
                    lambda d: write(d, "Leak.kt", "import android.content.Context\n\ninterface Leak\n")
                )
            )
            == ["leak"],
            [],
        )
    )

    # 3. A fully qualified platform type in a signature is reported even with no import.
    cases.append(
        (
            "a fully qualified platform type is rejected without an import",
            finding_types(
                with_repository(
                    lambda d: write(d, "Leak.kt", "interface Leak {\n    fun context(): android.content.Context?\n}\n")
                )
            )
            == ["leak"],
            [],
        )
    )

    # 4. Each forbidden platform is caught, not just Android.
    for prefix in ("androidx.compose.runtime", "de.robv.android.xposed.XposedBridge", "org.luckypray.dexkit.query"):
        cases.append(
            (
                "the %s package is rejected" % prefix,
                finding_types(with_repository(lambda d, p=prefix: write(d, "Leak.kt", "import %s.Foo\n\ninterface Leak\n" % p)))
                == ["leak"],
                [],
            )
        )

    # 5. A contract the issue requires but nobody declared is reported.
    cases.append(
        (
            "a required contract that is not declared is reported",
            "missing" in finding_types(with_repository(lambda d: os.remove(os.path.join(d, "FeatureContext.kt")))),
            [],
        )
    )

    # 6. No production adapter is reported.
    def remove_adapter(directory: str) -> None:
        for name in os.listdir(directory):
            os.remove(os.path.join(directory, name))

    cases.append(
        (
            "a contract set with no production adapter is reported",
            "missing-adapter" in finding_types(with_repository(lambda _: remove_adapter(CHECKER_HOLDER["adapter"]))),
            [],
        )
    )

    # 7. Documentation that *names* a platform package is not a violation.
    cases.append(
        (
            "a documented platform package is not a violation",
            finding_types(
                with_repository(
                    lambda d: write(
                        d,
                        "Documented.kt",
                        "/**\n * The point is that `android.content.SharedPreferences` never appears here.\n */\ninterface Documented\n",
                    )
                )
            )
            == [],
            [],
        )
    )

    # 8. A similarly named package in an unrelated namespace is not a violation.
    cases.append(
        (
            "a package whose name merely contains a forbidden word is accepted",
            finding_types(
                with_repository(
                    lambda d: write(d, "Lookalike.kt", "import com.wax.module.xposed.bridge.HookBinder\n\ninterface Lookalike\n")
                )
            )
            == [],
            [],
        )
    )

    failed = 0
    for name, held, _ in cases:
        if held:
            print("[pass] %s" % name)
        else:
            print("[fail] %s" % name)
            failed += 1

    print("\n%d/%d contract-checker cases behaved correctly" % (len(cases) - failed, len(cases)))
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())