#!/usr/bin/env python3
"""Prove that a **built APK** can be loaded by LSPosed, not only that the source can.

`check_lsposed_contract.py` reads the tree: it proves ``assets/xposed_init`` names a class,
that the class exists in the source, that the manifest declares the legacy metadata and that
the compile-time pin matches the resolved artifact. All of that was true while every release
APK shipped without the class it named, because R8 does not read ``assets/xposed_init``: the
entry point's only reference in the whole APK is that resource file, so from the shrinker's
point of view nothing refers to it, and a release build removed it - and with it the entire
injected runtime reachable only through it.

Nothing caught it. The APK installed, declared ``xposedmodule=true``, carried a correct
signature, and listed the entry class in ``assets/xposed_init``. The source-level checker
passed on the source that was never built into it, and every other gate builds the debug
variant, which is not shrunk and therefore keeps everything. The failure is only observable
on a device, at boot, in the hooked process, as a module LSPosed lists and never runs.

So this checker is the artifact-level half of the contract, and it is deliberately narrow:

* it reads the APK's **own** ``assets/xposed_init`` rather than the repository's, so the
  question asked is "can *this build* be loaded", not "is the source consistent";
* it parses the APK's dex files and asserts every declared entry class is a **class
  definition** in one of them - not that the name appears somewhere as a string;
* it fails rather than skipping when the file, the dex or the class is missing, because a
  check that can report "nothing to verify" is how this defect survived;
* ``--expect-class`` adds assertions the caller names, so a pipeline can require that the
  entry point's runtime (not only the class LSPosed reflects on) survived shrinking.

``assets/xposed_init`` is the legacy contract. If this module ever moves to the modern
``META-INF/xposed`` metadata, this checker must be re-pointed at ``java_init.list`` at the
same time - not deleted. Its absence today is a failure, not a skip, and the message says so.

Everything about the artifact that is wrong is a **failure**, never a skip and never an
invocation error: a missing entry file, an empty entry file, an APK with no dex, a dex that
cannot be parsed and a declared class that is not defined are all reasons to stop a release.
Exit code 2 is reserved for an argument that names nothing at all.

Usage:
    python3 tools/quality/check_apk_loader_contract.py --apk dist/WA-X-v1.2.0-beta.3.apk
    python3 tools/quality/check_apk_loader_contract.py --apk app.apk \\
        --expect-class com.wax.module.xposed.core.FeatureLoader --format json
Exit codes: 0 the APK can be loaded, 1 a violation, 2 the APK could not be read at all.
"""

from __future__ import annotations

import argparse
import json
import os
import struct
import sys
import zipfile

ENTRY_FILE = "assets/xposed_init"

# A class descriptor is the JVM/DEX spelling of a class name with 'L' and ';' around it.
DEX_HEADER_SIZE = 112
DEX_MAGIC_PREFIX = b"dex\n"
DEX_ENDIAN_CONSTANT = 0x12345678

# Offset of the fields this checker reads in the 112-byte DEX header. Only sizes and offsets
# are needed: the class definitions are read through them.
OFF_ENDIAN_TAG = 40
OFF_STRING_IDS_SIZE = 56
OFF_STRING_IDS_OFF = 60
OFF_TYPE_IDS_SIZE = 64
OFF_TYPE_IDS_OFF = 68
OFF_CLASS_DEFS_SIZE = 96
OFF_CLASS_DEFS_OFF = 100

# class_def_item: the only field read is class_idx, the first of its eight 4-byte fields.
CLASS_DEF_ITEM_SIZE = 32


class UnreadableArtifact(Exception):
    """The artifact could not be opened at all: an argument problem, not a contract problem."""


class ContractViolation(Exception):
    """The APK exists and is readable, and it breaks the loader contract."""


class Report:
    """Collects pass/fail checks, and prints them in the style of this repository's gates."""

    def __init__(self) -> None:
        self.checks: list[tuple[bool, str, str]] = []
        self.notes: list[str] = []

    def ok(self, check: str, detail: str) -> None:
        self.checks.append((True, check, detail))

    def fail(self, check: str, detail: str) -> None:
        self.checks.append((False, check, detail))

    def note(self, message: str) -> None:
        self.notes.append(message)

    def emit(self, fmt: str, apk: str) -> int:
        failed = [check for check in self.checks if not check[0]]
        if fmt == "json":
            print(
                json.dumps(
                    {
                        "apk": apk,
                        "checks": [
                            {"check": name, "passed": passed, "detail": detail}
                            for passed, name, detail in self.checks
                        ],
                        "notes": self.notes,
                        "result": "fail" if failed else "pass",
                    },
                    indent=2,
                    sort_keys=True,
                )
            )
            return 1 if failed else 0

        for passed, name, detail in self.checks:
            print("[%s] %s: %s" % ("pass" if passed else "fail", name, detail))
        for message in self.notes:
            print("note: %s" % message)
        if failed:
            print(
                "%d loader-contract violation(s): this APK cannot be loaded as a legacy "
                "LSPosed module" % len(failed)
            )
            return 1
        print("APK loader contract intact: every class it declares is in its dex, so LSPosed has something to load.")
        return 0


def entry_classes(archive: zipfile.ZipFile) -> list[str]:
    """The class names the APK itself declares in ``assets/xposed_init``."""
    try:
        raw = archive.read(ENTRY_FILE)
    except KeyError:
        raise ContractViolation(
            "%s is missing from the APK. This checker verifies the legacy entry contract, in "
            "which LSPosed finds the entry point by reading that file; if the module has moved "
            "to the modern META-INF/xposed metadata, re-point this checker at java_init.list "
            "instead of removing it." % ENTRY_FILE
        )
    text = raw.decode("utf-8", "replace")
    lines = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            lines.append(stripped)
    if not lines:
        raise ContractViolation("%s names no class, so there is nothing for LSPosed to load" % ENTRY_FILE)
    return lines


def dex_entries(archive: zipfile.ZipFile) -> list[str]:
    names = [name for name in archive.namelist() if name.endswith(".dex") and "/" not in name]
    if not names:
        raise ContractViolation("the APK contains no classes*.dex, so it carries no loadable code")
    return sorted(names)


def u32(buf: bytes, offset: int, what: str) -> int:
    try:
        return struct.unpack_from("<I", buf, offset)[0]
    except struct.error as exc:
        raise UnreadableArtifact("a dex header is truncated (%s): %s" % (what, exc))


def read_uleb128(buf: bytes, offset: int) -> tuple[int, int]:
    result = 0
    shift = 0
    while True:
        if offset >= len(buf):
            raise UnreadableArtifact("a dex string is truncated while reading its length")
        byte = buf[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return result, offset
        shift += 7
        if shift > 28:
            raise UnreadableArtifact("a dex string length is not a valid ULEB128")


def dex_strings(buf: bytes) -> list[str]:
    size = u32(buf, OFF_STRING_IDS_SIZE, "string_ids_size")
    offset = u32(buf, OFF_STRING_IDS_OFF, "string_ids_off")
    if size == 0:
        return []
    if offset < DEX_HEADER_SIZE or offset + 4 * size > len(buf):
        raise UnreadableArtifact("string_ids point outside the dex file")
    strings = []
    for index in range(size):
        data_offset = u32(buf, offset + 4 * index, "string_id[%d]" % index)
        if data_offset >= len(buf):
            raise UnreadableArtifact("string_id[%d] points outside the dex file" % index)
        _length, cursor = read_uleb128(buf, data_offset)
        end = buf.find(b"\x00", cursor)
        if end < 0:
            raise UnreadableArtifact("string %d is not NUL-terminated" % index)
        strings.append(buf[cursor:end].decode("utf-8", "replace"))
    return strings


def dex_class_descriptors(buf: bytes) -> list[str]:
    """Every class **definition** in one dex file, as type descriptors.

    A name in the string table is not a class: a class is defined by a ``class_def_item``, and
    this reads that structure rather than searching for a substring, so a name that survives
    only inside a string constant cannot be mistaken for loadable code.
    """
    if len(buf) < DEX_HEADER_SIZE:
        raise UnreadableArtifact("the file is smaller than a dex header")
    if not buf.startswith(DEX_MAGIC_PREFIX):
        raise UnreadableArtifact("the file is not a dex file (magic %r)" % buf[:8])
    endian = u32(buf, OFF_ENDIAN_TAG, "endian_tag")
    if endian != DEX_ENDIAN_CONSTANT:
        raise UnreadableArtifact(
            "the dex declares endian_tag 0x%08x; only little-endian dex files are supported"
            % endian
        )

    strings = dex_strings(buf)
    type_size = u32(buf, OFF_TYPE_IDS_SIZE, "type_ids_size")
    type_offset = u32(buf, OFF_TYPE_IDS_OFF, "type_ids_off")
    class_size = u32(buf, OFF_CLASS_DEFS_SIZE, "class_defs_size")
    class_offset = u32(buf, OFF_CLASS_DEFS_OFF, "class_defs_off")
    if class_size and (class_offset < DEX_HEADER_SIZE or class_offset + CLASS_DEF_ITEM_SIZE * class_size > len(buf)):
        raise UnreadableArtifact("class_defs point outside the dex file")
    if type_size and (type_offset < DEX_HEADER_SIZE or type_offset + 4 * type_size > len(buf)):
        raise UnreadableArtifact("type_ids point outside the dex file")

    descriptors = []
    for index in range(class_size):
        class_idx = u32(buf, class_offset + CLASS_DEF_ITEM_SIZE * index, "class_def[%d]" % index)
        if class_idx >= type_size:
            raise UnreadableArtifact("class_def[%d] names type %d, past type_ids" % (index, class_idx))
        descriptor_idx = u32(buf, type_offset + 4 * class_idx, "type_id[%d]" % class_idx)
        if descriptor_idx >= len(strings):
            raise UnreadableArtifact(
                "class_def[%d] names string %d, past string_ids" % (index, descriptor_idx)
            )
        descriptors.append(strings[descriptor_idx])
    return descriptors


def descriptor_of(class_name: str) -> str:
    return "L" + class_name.replace(".", "/") + ";"


def collect_class_descriptors(archive: zipfile.ZipFile, dexes: list[str]) -> dict[str, set[str]]:
    """Class descriptors per dex entry; a malformed dex is a contract failure, not a crash."""
    by_dex: dict[str, set[str]] = {}
    for name in dexes:
        try:
            buf = archive.read(name)
        except KeyError as exc:  # pragma: no cover - the name came from the same archive
            raise ContractViolation("could not read %s: %s" % (name, exc))
        try:
            by_dex[name] = set(dex_class_descriptors(buf))
        except UnreadableArtifact as exc:
            raise ContractViolation(
                "%s is not a readable dex file, so nothing in this APK can be loaded: %s" % (name, exc)
            )
    return by_dex


def check(args: argparse.Namespace, report: Report) -> None:
    path = os.path.abspath(args.apk)
    if not os.path.isfile(path):
        raise UnreadableArtifact("no such APK: %s" % path)

    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as exc:
        raise UnreadableArtifact("%s is not a readable zip/APK: %s" % (args.apk, exc))

    with archive:
        try:
            declared = entry_classes(archive)
            dexes = dex_entries(archive)
            by_dex = collect_class_descriptors(archive, dexes)
        except ContractViolation as exc:
            report.fail("apk.readable", str(exc))
            return

    total_classes = sum(len(descriptors) for descriptors in by_dex.values())
    report.note(
        "%s declares %d entry class(es) %s; %d dex file(s) define %d classes"
        % (os.path.basename(path), len(declared), ", ".join(declared), len(dexes), total_classes)
    )

    missing = []
    for class_name in declared:
        descriptor = descriptor_of(class_name)
        holder = next((name for name, descriptors in sorted(by_dex.items()) if descriptor in descriptors), None)
        if holder is None:
            missing.append(class_name)
            report.fail(
                "entry.present",
                "%s is declared in the APK's %s but is not defined in any of its dex files (%s). "
                "LSPosed reads that file and instantiates that class, so this build cannot be "
                "loaded: it will install, be listed and run nothing. If a release build is "
                "involved, the entry point is being removed by the shrinker - it is referenced "
                "only from %s, which R8 does not read."
                % (class_name, ENTRY_FILE, ", ".join(dexes), ENTRY_FILE),
            )
        else:
            report.ok(
                "entry.present",
                "%s is declared in %s and defined as %s in %s" % (class_name, ENTRY_FILE, descriptor, holder),
            )

    for class_name in args.expect_class:
        descriptor = descriptor_of(class_name)
        holder = next((name for name, descriptors in sorted(by_dex.items()) if descriptor in descriptors), None)
        if holder is None:
            report.fail(
                "expect.present",
                "%s was required by --expect-class and is not defined in any dex of this APK (%s)"
                % (class_name, ", ".join(dexes)),
            )
        else:
            report.ok("expect.present", "%s is defined in %s" % (class_name, holder))

    if missing:
        report.note(
            "this is the failure mode that no other gate in this repository can see: the debug "
            "variant keeps the class, the source-level contract checker reads the source, and an "
            "APK that cannot be loaded still installs and still declares xposedmodule=true"
        )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--apk", required=True, help="path to the built APK to verify")
    parser.add_argument(
        "--expect-class",
        action="append",
        default=[],
        metavar="FQCN",
        help=(
            "a class that must also be defined in the APK's dex, for example the entry point's "
            "runtime. Repeatable; every class given must be present"
        ),
    )
    parser.add_argument("--format", choices=("text", "json"), default="text", help="report format")
    args = parser.parse_args(argv)

    report = Report()
    try:
        check(args, report)
    except UnreadableArtifact as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 2
    return report.emit(args.format, args.apk)


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
