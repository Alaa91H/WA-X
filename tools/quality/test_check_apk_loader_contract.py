#!/usr/bin/env python3
"""Self-test for the APK loader-contract checker.

The checker exists because a release APK shipped for years with the class named in its own
``assets/xposed_init`` stripped out by R8, and no gate could see it. A gate that has never been
observed failing would repeat that mistake in a new place, so this builds APKs that are wrong in
each specific way and asserts the checker refuses them, and APKs that are right and asserts it
accepts them.

The fixtures are synthetic: a minimal, well-formed dex file is generated here (header, string
table, type table, class definitions) rather than checked in as a binary, so the test states what
it is testing and a real dex never has to be committed.

Usage: python3 tools/quality/test_check_apk_loader_contract.py
Exit codes: 0 every case behaved as declared, 1 at least one did not.
"""

from __future__ import annotations

import contextlib
import importlib.util
import io
import os
import struct
import sys
import tempfile
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(HERE, "check_apk_loader_contract.py")


def load_checker():
    spec = importlib.util.spec_from_file_location("check_apk_loader_contract", CHECKER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


CHECK = load_checker()

DEX_HEADER_SIZE = 112

# Multi-byte fields the parser reads must be little-endian, which is what a real dex is.
LE = "<"


def uleb128(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def build_dex(class_names: list[str], extra_strings: list[str] | None = None) -> bytes:
    """A minimal dex defining exactly `class_names` as classes.

    Layout: header | string_ids | type_ids | class_defs | string data. Offsets are absolute from
    the start of the file, as the format requires; padding keeps each section 4-byte aligned.
    """
    descriptors = ["L" + name.replace(".", "/") + ";" for name in class_names]
    strings = descriptors + list(extra_strings or [])

    header = bytearray(DEX_HEADER_SIZE - DEX_HEADER_SIZE)  # placeholder, sized below
    string_ids_size = len(strings)
    type_ids_size = len(descriptors)
    class_defs_size = len(descriptors)

    string_ids_off = DEX_HEADER_SIZE
    type_ids_off = string_ids_off + 4 * string_ids_size
    class_defs_off = type_ids_off + 4 * type_ids_size
    data_off = class_defs_off + 32 * class_defs_size
    data_off += (-data_off) % 4

    data = bytearray()
    string_offsets = []
    for text in strings:
        string_offsets.append(data_off + len(data))
        encoded = text.encode("utf-8")
        data += uleb128(len(encoded))
        data += encoded
        data += b"\x00"
    data += b"\x00" * ((-len(data)) % 4)

    header = bytearray(DEX_HEADER_SIZE)
    header[0:8] = b"dex\n035\x00"
    struct.pack_into(LE + "I", header, 32, data_off + len(data))  # file_size
    struct.pack_into(LE + "I", header, 36, DEX_HEADER_SIZE)  # header_size
    struct.pack_into(LE + "I", header, 40, 0x12345678)  # endian_tag
    struct.pack_into(LE + "I", header, 56, string_ids_size)
    struct.pack_into(LE + "I", header, 60, string_ids_off)
    struct.pack_into(LE + "I", header, 64, type_ids_size)
    struct.pack_into(LE + "I", header, 68, type_ids_off)
    struct.pack_into(LE + "I", header, 96, class_defs_size)
    struct.pack_into(LE + "I", header, 100, class_defs_off)
    struct.pack_into(LE + "I", header, 104, len(data))
    struct.pack_into(LE + "I", header, 108, data_off)

    blob = bytearray(header)
    blob += b"".join(struct.pack(LE + "I", offset) for offset in string_offsets)
    blob += b"".join(struct.pack(LE + "I", index) for index in range(type_ids_size))
    for index in range(class_defs_size):
        blob += struct.pack(LE + "I", index)  # class_idx
        blob += b"\x00" * (32 - 4)  # the remaining class_def_item fields
    blob += data
    return bytes(blob)


def build_apk(path: str, xposed_init: str | None, dexes: list[bytes]) -> None:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        if xposed_init is not None:
            archive.writestr("assets/xposed_init", xposed_init)
        for index, blob in enumerate(dexes):
            name = "classes.dex" if index == 0 else "classes%d.dex" % (index + 1)
            archive.writestr(name, blob)


def run(apk: str, *extra: str) -> tuple[int, str]:
    stdout = io.StringIO()
    with contextlib.redirect_stdout(stdout):
        code = CHECK.main(["--apk", apk, *extra])
    return code, stdout.getvalue()


def main() -> int:
    failures = 0
    total = 0

    def case(name: str, condition: bool, detail: str = "") -> None:
        nonlocal failures, total
        total += 1
        if condition:
            print("[pass] %s" % name)
        else:
            failures += 1
            print("[fail] %s%s" % (name, (": " + detail) if detail else ""))

    with tempfile.TemporaryDirectory() as sandbox:
        entry = "com.wax.module.ModuleEntryPoint"
        runtime = "com.wax.module.xposed.core.FeatureLoader"

        # An APK that declares its entry point and defines it: the case that must pass, because a
        # gate that refuses everything is not a gate.
        good = os.path.join(sandbox, "good.apk")
        build_apk(good, entry + "\n", [build_dex([entry, runtime])])
        code, out = run(good)
        case("a declared entry point defined in the dex passes", code == 0, out[-400:])

        # The defect this checker exists for: the class is gone from the dex, and only the dex
        # entry differs from the passing case.
        stripped = os.path.join(sandbox, "stripped.apk")
        build_apk(stripped, entry + "\n", [build_dex(["com.wax.module.activities.MainActivity"])])
        code, out = run(stripped)
        case("a declared entry point missing from the dex fails", code == 1, "exit %d" % code)
        case(
            "the failure names the class and says the build cannot be loaded",
            entry in out and "cannot be loaded" in out,
            out[-400:],
        )

        # A name present only as a string constant is not a class definition: this is the
        # distinction that makes the check meaningful rather than a substring search.
        string_only = os.path.join(sandbox, "string-only.apk")
        build_apk(string_only, entry + "\n", [build_dex(["com.wax.module.activities.MainActivity"], [entry])])
        code, out = run(string_only)
        case(
            "a class name that exists only as a string constant still fails",
            code == 1,
            "exit %d" % code,
        )

        # Multi-dex: the class may legitimately live in classes2.dex.
        second_dex = os.path.join(sandbox, "second-dex.apk")
        build_apk(second_dex, entry + "\n", [build_dex(["com.wax.module.activities.MainActivity"]), build_dex([entry])])
        code, out = run(second_dex)
        case("an entry point in classes2.dex passes", code == 0, out[-400:])
        case("the passing report names the dex that defines it", "classes2.dex" in out, out[-400:])

        # --expect-class: the extra assertion a pipeline can add, e.g. that the runtime survived.
        code, out = run(good, "--expect-class", runtime)
        case("an --expect-class that is present passes", code == 0, out[-400:])
        code, out = run(good, "--expect-class", "com.wax.module.xposed.core.Missing")
        case("an --expect-class that is absent fails", code == 1, "exit %d" % code)

        # Artifact problems are failures, not skips.
        no_entry_file = os.path.join(sandbox, "no-entry-file.apk")
        build_apk(no_entry_file, None, [build_dex([entry])])
        code, out = run(no_entry_file)
        case(
            "an APK with no assets/xposed_init fails and says what to re-point",
            code == 1 and "java_init.list" in out,
            "exit %d" % code,
        )

        empty_entry_file = os.path.join(sandbox, "empty-entry.apk")
        build_apk(empty_entry_file, "# nothing\n\n", [build_dex([entry])])
        code, _out = run(empty_entry_file)
        case("an empty assets/xposed_init fails", code == 1, "exit %d" % code)

        no_dex = os.path.join(sandbox, "no-dex.apk")
        build_apk(no_dex, entry + "\n", [])
        code, _out = run(no_dex)
        case("an APK with no dex fails", code == 1, "exit %d" % code)

        malformed = os.path.join(sandbox, "malformed.apk")
        build_apk(malformed, entry + "\n", [b"not a dex at all"])
        code, out = run(malformed)
        case("a malformed dex fails rather than crashing", code == 1, "exit %d" % code)
        case("the malformed-dex failure says nothing in the APK can be loaded", "not a readable dex" in out, out[-300:])

        truncated = os.path.join(sandbox, "truncated.apk")
        build_apk(truncated, entry + "\n", [build_dex([entry])[:40]])
        code, _out = run(truncated)
        case("a truncated dex fails", code == 1, "exit %d" % code)

        # A dex whose endian tag is not the dex constant is refused rather than misread. Every
        # real dex is little-endian, so this is the byte pattern of a file that is not one.
        wrong_endian = bytearray(build_dex([entry]))
        struct.pack_into(LE + "I", wrong_endian, 40, 0x78563412)
        be_apk = os.path.join(sandbox, "wrong-endian.apk")
        build_apk(be_apk, entry + "\n", [bytes(wrong_endian)])
        code, out = run(be_apk)
        case("a dex whose endian tag is not the dex constant fails", code == 1, "exit %d" % code)
        case("the endian failure says the dex is not readable", "not a readable dex" in out, out[-300:])

        # Comments and blank lines around a valid entry are accepted, as LSPosed accepts them.
        commented = os.path.join(sandbox, "commented.apk")
        build_apk(commented, "# entry point\n\n" + entry + "\n\n", [build_dex([entry])])
        code, _out = run(commented)
        case("comments and blank lines are ignored", code == 0, "exit %d" % code)

        # An argument that names nothing is the one invocation error (exit 2).
        code, _out = run(os.path.join(sandbox, "does-not-exist.apk"))
        case("a missing APK is an invocation error", code == 2, "exit %d" % code)

        not_a_zip = os.path.join(sandbox, "not-a-zip.apk")
        with open(not_a_zip, "wb") as handle:
            handle.write(b"definitely not a zip")
        code, _out = run(not_a_zip)
        case("an unreadable container is an invocation error", code == 2, "exit %d" % code)

        # JSON is the shape CI stores as evidence.
        code, out = run(stripped, "--format", "json")
        import json as _json

        payload = _json.loads(out)
        case("json output carries the result and the failing check", payload["result"] == "fail" and payload["checks"], out[-300:])
        case("json output names the APK", payload["apk"].endswith("stripped.apk"), payload["apk"])

        # The parser's own contract: it reads class definitions, so a dex with no classes has none.
        case("a dex with no class definitions yields no classes", CHECK.dex_class_descriptors(build_dex([])) == [])
        case("descriptors are the dex spelling of the class name", CHECK.descriptor_of("a.b.C") == "La/b/C;")

    print()
    if failures:
        print("%d apk loader-contract case(s) failed" % failures)
        return 1
    print("all %d apk loader-contract cases behaved correctly" % total)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
