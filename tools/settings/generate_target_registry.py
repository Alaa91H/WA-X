"""Generate the per-target setting registry from the preference screens.

The list of settings a user can override per target is derived, never hand kept: a
hand-maintained list of 150 keys drifts the first time a setting is renamed, and a
setting that silently cannot be overridden is the worst kind of bug here.

Generated into `SettingKeyRegistry.kt`. Run after changing any preference screen:

    python tools/settings/generate_target_registry.py
"""

import io
import os
import re
import sys
from xml.etree import ElementTree

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
XML_DIR = os.path.join(REPO, "app/src/main/res/xml")
OUT = os.path.join(
    REPO,
    "app/src/main/java/com/wax/module/settings/SettingKeyRegistry.kt",
)

ANDROID = "{http://schemas.android.com/apk/res/android}"
APP = "{http://schemas.android.com/apk/res-auto}"


def attr(node, name):
    """Read an attribute under either namespace.

    The preference screens in this project declare keys as pp:key, not ndroid:key,
    which a reader that only knows the android namespace silently misses: the whole
    screen then looks empty instead of failing.
    """
    return node.get(ANDROID + name) or node.get(APP + name)

# Element name -> value kind. Anything not listed is TEXT, which is the safe default:
# a target override of an unknown type is still stored and still resolves.
KIND_BY_ELEMENT = {
    "ListPreference": "TEXT",
    "MultiSelectListPreference": "SET",
    "EditTextPreference": "TEXT",
    "SeekBarPreference": "INT",
    "Preference": "TEXT",
}


def element_kind(tag: str) -> str:
    simple = tag.rsplit(".", 1)[-1]
    if "SwitchPreference" in simple:
        return "BOOLEAN"
    if simple.endswith("FloatSeekBarPreference"):
        return "FLOAT"
    if simple.endswith("SeekBarPreference"):
        return "INT"
    if simple.endswith("FileSelectPreference") or simple.endswith("FileReaderPreference"):
        return "TEXT"
    return KIND_BY_ELEMENT.get(simple, "TEXT")


def title_of(node, titles):
    """The title of a preference, resolved through the app's string resources."""
    ref = attr(node, "title")
    if not ref:
        return None
    return ref.split("/", 1)[-1] if "/" in ref else None


def collect():
    entries = {}
    for filename in sorted(os.listdir(XML_DIR)):
        if not filename.endswith(".xml"):
            continue
        screen = filename[:-4]
        try:
            tree = ElementTree.parse(os.path.join(XML_DIR, filename))
        except ElementTree.ParseError as error:
            print("skipping %s: %s" % (filename, error), file=sys.stderr)
            continue

        category_stack = []

        def walk(node, path):
            for child in node:
                tag = child.tag
                key = attr(child, "key")
                if tag == "PreferenceCategory":
                    title = title_of(child, None)
                    category_stack.append(title or category_stack[-1] if category_stack else (title or "Other"))
                    walk(child, path)
                    category_stack.pop()
                    continue
                if key:
                    kind = element_kind(tag)
                    category = " / ".join([c for c in category_stack if c]) or "Other"
                    # First declaration wins, and the screen is remembered: a key declared
                    # in two places still has exactly one override.
                    entries.setdefault(
                        key,
                        {
                            "kind": kind,
                            "category": category,
                            "screen": screen,
                            "title": title_of(child, None),
                        },
                    )
                walk(child, path)

        walk(tree.getroot(), [])
    return entries


# Keys that are the module's own behaviour or that hold a credential. Neither makes sense
# as a per-target override: a path or an API key is the same for both installs of the app,
# and duplicating it invites two places to change it.
EXCLUDED = {
    "thememode", "wae_color_mode", "wae_color_preset", "update_check", "enablelogs",
    "restartbutton", "open_wae", "bootsloader_placeholder", "bootloader_spoofer",
    "bootloader_spoofer_custom", "bootloader_spoofer_xml",
    "groq_api_key", "transcription_provider", "css_theme", "wallpaper_file",
    "call_recording_path", "tasker_auth_token",
}


def main() -> int:
    entries = collect()
    offered = {key: value for key, value in entries.items() if key not in EXCLUDED}
    skipped = sorted(set(entries) - set(offered))
    by_kind = {}
    for value in offered.values():
        by_kind[value["kind"]] = by_kind.get(value["kind"], 0) + 1

    lines = []
    lines.append("package com.wax.module.settings")
    lines.append("")
    lines.append("/**")
    lines.append(" * Every setting a user can override per target, with its value kind.")
    lines.append(" *")
    lines.append(" * Generated by tools/settings/generate_target_registry.py from the preference")
    lines.append(" * screens. Do not hand-edit: a setting that is missing here cannot be")
    lines.append(" * overridden, and one that is listed but no longer exists would show an empty")
    lines.append(" * row in the interface. Regenerate instead.")
    lines.append(" *")
    lines.append(" * %d settings, of which %d are toggles." % (len(offered), by_kind.get("BOOLEAN", 0)))
    lines.append(" *")
    lines.append(
        " * %d settings are excluded on purpose: the module's own appearance and diagnostics,"
        % len(skipped)
    )
    lines.append(" * and anything holding a credential or a file path.")
    lines.append(" */")
    lines.append("object SettingKeyRegistry {")
    lines.append("    /** One setting, as the interface needs it. */")
    lines.append("    data class Entry(")
    lines.append("        val key: String,")
    lines.append("        val kind: Kind,")
    lines.append("        val category: String,")
    lines.append("        /** The preference screen the setting is edited on. */")
    lines.append("        val screen: String,")
    lines.append("        /** Title string resource id, or 0 when the screen declares no title. */")
    lines.append("        val titleRes: Int,")
    lines.append("    ) {")
    lines.append("        /** Whether this setting can be resolved to a plain on/off value. */")
    lines.append("        val isToggle: Boolean get() = kind == Kind.BOOLEAN")
    lines.append("    }")
    lines.append("")
    lines.append("    /** The value kinds a preference can hold. */")
    lines.append("    enum class Kind {")
    lines.append("        BOOLEAN,")
    lines.append("        TEXT,")
    lines.append("        INT,")
    lines.append("        FLOAT,")
    lines.append("        SET,")
    lines.append("    }")
    lines.append("")
    lines.append("    private val ALL: List<Entry> =")
    lines.append("        listOf(")
    for key in sorted(offered):
        value = offered[key]
        title = value["title"]
        title_expr = "com.wax.module.R.string.%s" % title if title else "0"
        lines.append(
            '            Entry("%s", Kind.%s, "%s", "%s", %s),'
            % (key, value["kind"], value["category"], value["screen"], title_expr)
        )
    lines.append("        )")
    lines.append("")
    lines.append("    /** Every overrideable setting. */")
    lines.append("    val entries: List<Entry> = ALL")
    lines.append("")
    lines.append("    private val BY_KEY: Map<String, Entry> = ALL.associateBy { it.key }")
    lines.append("")
    lines.append("    /** The setting for [key], or null when it cannot be overridden. */")
    lines.append("    fun find(key: String): Entry? = BY_KEY[key]")
    lines.append("")
    lines.append("    /** Whether [key] can carry a per-target override. */")
    lines.append("    fun isOverrideable(key: String): Boolean = BY_KEY.containsKey(key)")
    lines.append("")
    lines.append("    /** The settings grouped by category, categories in alphabetical order. */")
    lines.append("    fun byCategory(): Map<String, List<Entry>> =")
    lines.append("        ALL.groupBy { it.category }.toSortedMap()")
    lines.append("")
    lines.append("    /** The toggles, which is what the interface offers as a three-way control. */")
    lines.append("    fun toggles(): List<Entry> = ALL.filter { it.isToggle }")
    lines.append("}")

    io.open(OUT, "w", encoding="utf-8", newline="\n").write("\n".join(lines) + "\n")
    print(
        "wrote %s: %d settings (%d toggles, %d other)"
        % (
            os.path.relpath(OUT, REPO).replace("\\", "/"),
            len(offered),
            by_kind.get("BOOLEAN", 0),
            len(offered) - by_kind.get("BOOLEAN", 0),
        )
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())