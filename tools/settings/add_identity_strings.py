#!/usr/bin/env python3
"""Insert the WA X identity strings and fix the maintainer attribution.

Separates the *active* maintainer (Alaa) from *historical and upstream* credit, so
no old contributor is presented as a current WA X maintainer and Alaa is never
credited with code inherited from elsewhere (§7, §91).

UTF-8 only, because these files carry Arabic, Turkish and accented Latin text.
"""
from __future__ import annotations

import io
import os
import re

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
STRINGS = os.path.join(ROOT, "app/src/main/res/values/strings.xml")

# (name, value, translatable)
NEW_STRINGS = [
    ("wax_tagline", "Advanced WhatsApp Xposed Module", False),
    ("wax_maintainer_label", "Maintained by", False),
    ("wax_maintainer_name", "Alaa", False),
    ("wax_disclaimer_title", "Disclaimer", False),
    (
        "wax_disclaimer",
        "WA X is an independent open-source project and is not affiliated with, endorsed by, "
        "sponsored by, or officially associated with WhatsApp or Meta.",
        False,
    ),
    ("wax_legal_title", "About & Legal", False),
    ("wax_open_source_notices", "Open-source notices", False),
    (
        "wax_open_source_notices_desc",
        "WA X builds on the work of earlier contributors and on open-source libraries. "
        "They are credited here for their original work. The full licence text and the "
        "commit history are part of this repository.",
        False,
    ),
    ("wax_upstream_author", "Original author of the pre-rename project", False),
    ("wax_go_to_email", "Email the maintainer", False),
    ("wax_go_to_profile", "Maintainer profile", False),
    ("wax_go_to_support", "Support development", False),
    ("wax_license_name", "GNU General Public License v3.0", False),
    (
        "wax_license_terms",
        "WA X is free software: you can redistribute it and/or modify it under the terms of "
        "the GNU General Public License as published by the Free Software Foundation, either "
        "version 3 of the License, or (at your option) any later version. Commercial "
        "distribution is permitted provided the licence conditions are met and the source "
        "is made available; the licence does not forbid commercial use by itself.",
        False,
    ),
    ("wax_version_format", "Version %1$s (%2$d)", False),
    ("wax_account_risk_title", "Account safety", False),
    (
        "wax_account_risk",
        "Xposed modules modify WhatsApp from the inside and some features may conflict with "
        "server-side checks. Use at your own risk, keep a backup of your data, and avoid "
        "features you do not need.",
        False,
    ),
]

# Existing values that must change rather than be duplicated.
REPLACE = {
    "developer": ("Maintained by Alaa", False),
    "contributors": ("Open-source notices", False),
}


def main() -> int:
    text = io.open(STRINGS, encoding="utf-8", newline="").read()

    for name, value in REPLACE.items():
        pattern = re.compile(r'(<string name="%s"[^>]*>)(.*?)(</string>)' % name, re.S)
        match = pattern.search(text)
        if not match:
            print("skip %s (not present)" % name)
            continue
        attrs = ' translatable="false"' if not value[1] else ""
        text = text[: match.start()] + '<string name="%s"%s>%s</string>' % (name, attrs, value[0]) + text[match.end():]
        print("replaced %s" % name)

    additions = []
    for name, value, translatable in NEW_STRINGS:
        if re.search(r'<string name="%s"' % name, text):
            continue
        attrs = "" if translatable else ' translatable="false"'
        escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        additions.append('    <string name="%s"%s>%s</string>' % (name, attrs, escaped))

    if additions:
        block = "\n    <!-- WA X identity (§2, §7, §73). Active maintainer is Alaa; upstream\n"
        block += "         and earlier contributors are credited separately so nobody is\n"
        block += "         presented as a current maintainer of code they did not write. -->\n"
        block += "\n".join(additions) + "\n"
        text = text.replace("</resources>", block + "</resources>")
        print("added %d identity strings" % len(additions))

    with io.open(STRINGS, "w", encoding="utf-8", newline="") as handle:
        handle.write(text)
    print("wrote %s" % os.path.relpath(STRINGS, ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
