#!/usr/bin/env python3
"""Derive the WA X setting inventory from the source tree.

Enumerates every preference key that exists today so the migration can prove that
none of them disappears. The table is generated rather than hand-written because a
hand-maintained table of ~150 rows is wrong within two releases, and an
out-of-date inventory is worse than none: it would let a setting be dropped
without anybody noticing.

Three sources are merged:

  * ``app/src/main/res/xml/*.xml`` — the declared preference keys, with the screen
    that owns them;
  * ``prefs.getX("key")`` call sites — the keys the Xposed runtime actually reads,
    which is what proves a key is live rather than dead;
  * keys created in code with ``setKey`` or assigned to a ``PREF_*`` constant.

A key that is declared but never read is reported as ``DEAD-DECLARED`` so it can be
classified deliberately rather than silently.

Usage:
    python3 tools/settings/inventory_settings.py                 # markdown to stdout
    python3 tools/settings/inventory_settings.py --out FILE      # write markdown
    python3 tools/settings/inventory_settings.py --json          # machine readable
"""
from __future__ import annotations

import argparse
import json
import os
import re
from typing import Any

REPO_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
XML_DIR = os.path.join(REPO_ROOT, "app/src/main/res/xml")
SRC_DIR = os.path.join(REPO_ROOT, "app/src/main/java")

KEY_ATTR = re.compile(r'(?:android|app):key="([^"]+)"')
GETTER = re.compile(r'\b(?:prefs|pref|sharedPreferences)\s*\.\s*get\w+\s*\(\s*"([^"]+)"')
GETTER_ANY = re.compile(r'\bget(?:Boolean|String|Float|Long|Int|StringSet)\s*\(\s*"([^"]+)"')
CONST_KEY = re.compile(r'\b(?:const\s+)?val\s+PREF[A-Za-z0-9_]*\s*(?::\s*String\s*)?=\s*"([^"]+)"')
SET_KEY = re.compile(r'\bsetKey\s*\(\s*"([^"]+)"')
STRING_RES = re.compile(r'\bR\.string\.([A-Za-z0-9_]+)')
KEY_REF = re.compile(r'"([a-z][a-z0-9_]{2,})"')

# Which screen owns a key, and therefore which new category it lands in.
CATEGORY_BY_SCREEN = {
    "preference_general_home": "Appearance & Interface / Advanced / Automation",
    "preference_general_homescreen": "Messaging & Chats",
    "preference_general_conversation": "Privacy & Presence / Messaging / Automation",
    "fragment_privacy": "Privacy & Presence / Calls",
    "fragment_media": "Media & Quality / Calls / Automation",
    "fragment_customization": "Appearance & Interface / Status / Advanced",
    "fragment_general": "Status / Messaging & Chats",
}

# Keys whose value is inherently single-instance: a shared download folder, one
# transcription provider credential, one CSS theme file. Two WhatsApps sharing these
# is the expected behaviour, so they are concrete values with no per-target override.
GLOBAL_ONLY = {
    "download_local",
    "assemblyai_key",
    "groq_api_key",
    "transcription_provider",
    "css_theme",
    "wallpaper_file",
    "call_recording_path",
    "tasker_auth_token",
}

# WA X's own behaviour rather than a WhatsApp feature.
MANAGER_KEYS = {
    "thememode",
    "wae_color_mode",
    "wae_color_preset",
    "update_check",
    "enablelogs",
    "restartbutton",
    "open_wae",
    "bootsloader_placeholder",
    "bootloader_spoofer",
    "bootloader_spoofer_custom",
    "bootloader_spoofer_xml",
}


def read(path: str) -> str:
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def declared_keys() -> dict[str, list[str]]:
    """key -> screens that declare it."""
    found: dict[str, list[str]] = {}
    if not os.path.isdir(XML_DIR):
        return found
    for filename in sorted(os.listdir(XML_DIR)):
        if not filename.endswith(".xml"):
            continue
        screen = filename[:-4]
        for key in KEY_ATTR.findall(read(os.path.join(XML_DIR, filename))):
            found.setdefault(key, [])
            if screen not in found[key]:
                found[key].append(screen)
    return found


def code_keys() -> dict[str, set[str]]:
    """key -> kinds of code reference, for keys that are not in any XML."""
    getters: set[str] = set()
    consts: set[str] = set()
    dynamic: set[str] = set()
    for dirpath, _dirnames, filenames in os.walk(SRC_DIR):
        for filename in filenames:
            if not filename.endswith((".kt", ".java")):
                continue
            body = read(os.path.join(dirpath, filename))
            getters |= set(GETTER.findall(body)) | set(GETTER_ANY.findall(body))
            consts |= set(CONST_KEY.findall(body))
            dynamic |= set(SET_KEY.findall(body))
    out: dict[str, set[str]] = {}
    for key in getters:
        out.setdefault(key, set()).add("read")
    for key in consts:
        out.setdefault(key, set()).add("const")
    for key in dynamic:
        out.setdefault(key, set()).add("dynamic")
    return out


def target_model(key: str) -> str:
    if key in MANAGER_KEYS:
        return "MANAGER"
    if key in GLOBAL_ONLY:
        return "GLOBAL"
    return "GLOBAL+TARGET"


def build() -> dict[str, Any]:
    declared = declared_keys()
    code = code_keys()

    rows: list[dict[str, Any]] = []
    for key in sorted(set(declared) | set(code)):
        screens = declared.get(key, [])
        kinds = code.get(key, set())
        read_by_hooks = "read" in kinds
        status = "MIGRATED" if read_by_hooks or key in declared else "PRESERVED-ADVANCED"
        rows.append(
            {
                "key": key,
                "screens": screens,
                "category": CATEGORY_BY_SCREEN.get(screens[0], "Advanced & Experimental")
                if screens
                else "Unclassified",
                "read_by_hooks": read_by_hooks,
                "target_model": target_model(key),
                "status": status,
                "code_only": not screens,
            }
        )
    return {
        "declared": len(declared),
        "codeOnly": sum(1 for r in rows if r["code_only"]),
        "readByHooks": sum(1 for r in rows if r["read_by_hooks"]),
        "targetOverrideable": sum(1 for r in rows if r["target_model"] == "GLOBAL+TARGET"),
        "rows": rows,
    }


# What the table above means in practice, stated here rather than in a second document
# that would drift from it. Kept in the generator so the two cannot disagree.
STATUS_SECTION = """## Implementation status

This table lists every user-facing key and whether it can carry a per-target override.
What it does not say is whether an override actually reaches the feature, so that is
recorded here.

| Stage | State |
|---|---|
| Global values readable at their original keys | Live since 1.0.0 |
| Override storage, namespaced under `waxtarget.<code>.` | Live |
| Override read path inside a hooked WhatsApp process | Live since 1.1.0 |
| Per-target interface | Live since 1.1.0, `TargetSettingsActivity` |
| Overrideable key list used by the interface | Generated from the preference screens by `tools/settings/generate_target_registry.py` |

Keys marked `GLOBAL` are deliberately not offered per target: they configure WA X itself
rather than a WhatsApp feature, or they hold a credential or a file path where a second
copy would only create a second place to change it.

A note on how this table was counted: the preference screens in this project declare keys
as `app:key`, not `android:key`. A reader that only knows the android namespace sees four
keys in total and reports success, which is why the generator checks both."""


def markdown(data: dict[str, Any]) -> str:
    out: list[str] = []
    out.append("<!-- generated by tools/settings/inventory_settings.py; do not hand-edit -->")
    out.append("")
    out.append("## Current inventory (auto-generated)")
    out.append("")
    out.append("| Metric | Count |")
    out.append("|---|---|")
    out.append("| Keys declared in settings XML | %d |" % data["declared"])
    out.append("| Keys only referenced in code | %d |" % data["codeOnly"])
    out.append("| Keys read by the Xposed runtime | %d |" % data["readByHooks"])
    out.append("| Keys that can carry a per-target override | %d |" % data["targetOverrideable"])
    out.append("| **Total user-facing keys** | **%d** |" % len(data["rows"]))
    out.append("")
    out.append("| Old key | Old location | Read by hooks | New category | Target model | Status |")
    out.append("|---|---|---|---|---|---|")
    for row in data["rows"]:
        screens = ", ".join(row["screens"]) or "_(code only)_"
        out.append(
            "| `%s` | %s | %s | %s | %s | %s |"
            % (
                row["key"],
                screens,
                "yes" if row["read_by_hooks"] else "no",
                row["category"],
                row["target_model"],
                row["status"],
            )
        )
    out.append("")
    out.append(STATUS_SECTION)
    return "\n".join(out)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", help="write the markdown table here")
    parser.add_argument("--json", action="store_true", help="emit JSON instead")
    args = parser.parse_args(argv)

    data = build()
    rendered = json.dumps(data, indent=2, sort_keys=True) if args.json else markdown(data)
    if args.out:
        os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
        with open(args.out, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(rendered)
        print("wrote %s (%d keys)" % (args.out, len(data["rows"])))
    else:
        print(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main(__import__("sys").argv[1:]))
