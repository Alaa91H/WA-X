#!/usr/bin/env python3
"""Generate injected UI label lookups from the WA-X Manager's shipped translations.

Runtime cannot reference Manager R.string from within WhatsApp. This builds a
data-only, locale-specific fallback catalog without AndroidX or reflection.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
import json

root = Path(__file__).resolve().parents[1]
res = root / "app/src/main/res"
dst = root / "modern-runtime/src/main/java/com/wax/module/modern/ControlCenterLocaleCatalog.kt"
name_by_id = {
    "freeze_last_seen": "freezelastseen",
    "view_once": "viewonce",
    "anti_revoke": "antirevoke",
    "receipt_privacy_read": "hideread",
    "receipt_privacy_delivery": "hidereceipt",
    "status_seen_hidden": "hidestatusview",
    "typing_privacy": "ghostmode",
    "dnd_mode": "dnd_mode_title",
    "tasker": "enable_tasker_automation",
}
ui_keys = {
    "ui.search": "search_features_hint",
    "ui.empty": "search_no_results",
    "ui.restart": "restart_whatsapp",
    "ui.all": "mode_all",
    "category.privacy": "privacy",
    "category.media": "media",
    "category.tools": "tools",
    "category.appearance": "custom_appearance",
}
all_keys = {**name_by_id, **ui_keys}
def values(locale):
    data = {}
    for file in (res / locale).glob("*.xml"):
        try:
            root = ET.parse(file).getroot()
            for e in root:
                if e.tag == "string" and "name" in e.attrib and e.text is not None:
                    data[e.attrib["name"]] = "".join(e.itertext()).strip()
        except ET.ParseError:
            pass
    return data
def kotlin(s):
    s = s.replace("\\'", "'").replace('\\"', '"')
    return json.dumps(s, ensure_ascii=False).replace("$", "\\u0024")
locales = ["values-ar", "values-de", "values-es", "values-fr", "values-in", "values-it", "values-iw",
    "values-pt", "values-ru", "values-tr", "values-zh"]
out = [
    "package com.wax.module.modern",
    "",
    "/** Generated from app/src/main/res/locale-specific strings.xml. Run tools/generate_control_center_locales.py. */",
    "internal object ControlCenterLocaleCatalog {",
    "    fun values(locale: String): Map<String, String> = when (locale) {",
]
for loc in locales:
    items = values(loc)
    mapped = [(id, items[k]) for id, k in all_keys.items() if items.get(k)]
    normalized = {"in":"id", "iw":"he"}.get(loc[7:],loc[7:])
    out.append("        " + kotlin(normalized) + " -> mapOf(")
    for id, val in mapped:
        out.append("            " + kotlin(id) + " to " + kotlin(val) + ",")
    out.append("        )")
out += [
    "        else -> emptyMap()",
    "    }",
    "}",
]
dst.write_text("\n".join(out)+"\n", encoding="utf-8", newline="\n")
print(f"Generated {dst.name} for {len(locales)} shipped locales")
