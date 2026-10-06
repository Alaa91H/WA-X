#!/usr/bin/env python3
"""Generate a concise, Telegram-safe changelog from Git history.

The output is plain UTF-8 text. It intentionally avoids Markdown parsing so
commit subjects containing punctuation cannot break Telegram publishing.
"""

from __future__ import annotations

import argparse
import re
import subprocess
from collections import OrderedDict
from pathlib import Path

RECORD_SEP = "\x1e"
FIELD_SEP = "\x1f"

CATEGORY_ORDER = (
    "important",
    "added",
    "fixed",
    "security",
    "performance",
    "improved",
    "docs",
    "internal",
    "other",
)

CATEGORY_TITLES = {
    "important": "⚠️ Important",
    "added": "✨ Added",
    "fixed": "🐛 Fixed",
    "security": "🔒 Security",
    "performance": "⚡ Performance",
    "improved": "🛠 Improved",
    "docs": "📚 Documentation",
    "internal": "🔧 Internal",
    "other": "📌 Other",
}

CONVENTIONAL_RE = re.compile(
    r"^(?P<type>feat|fix|perf|security|refactor|docs|build|ci|test|chore)"
    r"(?:\((?P<scope>[^)]+)\))?(?P<breaking>!)?:\s*(?P<title>.+)$",
    re.IGNORECASE,
)

TYPE_CATEGORY = {
    "feat": "added",
    "fix": "fixed",
    "perf": "performance",
    "security": "security",
    "refactor": "improved",
    "docs": "docs",
    "build": "internal",
    "ci": "internal",
    "test": "internal",
    "chore": "internal",
}


def run_git(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )
    return completed.stdout


def ref_exists(ref: str) -> bool:
    return (
        subprocess.run(
            ["git", "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}"],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        ).returncode
        == 0
    )


def normalize_title(value: str) -> str:
    value = " ".join(value.replace("\r", " ").replace("\n", " ").split())
    value = value.lstrip("-• ").strip()
    return value


def read_commits(base: str | None, head: str, max_commits: int) -> list[tuple[str, str, str]]:
    pretty = f"%H{FIELD_SEP}%s{FIELD_SEP}%b{RECORD_SEP}"
    if base and ref_exists(base):
        revision = f"{base}..{head}"
        args = ["log", revision, "--no-merges", f"--max-count={max_commits}", f"--pretty=format:{pretty}"]
    else:
        args = ["log", head, "--no-merges", f"--max-count={max_commits}", f"--pretty=format:{pretty}"]

    raw = run_git(*args)
    commits: list[tuple[str, str, str]] = []
    for record in raw.split(RECORD_SEP):
        record = record.strip()
        if not record:
            continue
        fields = record.split(FIELD_SEP, 2)
        if len(fields) != 3:
            continue
        sha, subject, body = fields
        subject = normalize_title(subject)
        if not subject or subject.lower().startswith("merge "):
            continue
        commits.append((sha.strip(), subject, body.strip()))
    return commits


def classify(subject: str, body: str) -> tuple[str, str, bool]:
    match = CONVENTIONAL_RE.match(subject)
    if not match:
        breaking = "BREAKING CHANGE" in body.upper()
        return ("important" if breaking else "other", subject, breaking)

    commit_type = match.group("type").lower()
    title = normalize_title(match.group("title"))
    breaking = bool(match.group("breaking")) or "BREAKING CHANGE" in body.upper()
    if breaking:
        return "important", title, True
    return TYPE_CATEGORY.get(commit_type, "other"), title, False


def generate(base: str | None, head: str, max_commits: int, max_items: int) -> str:
    commits = read_commits(base, head, max_commits)
    buckets: dict[str, list[str]] = OrderedDict((name, []) for name in CATEGORY_ORDER)
    seen: set[str] = set()

    for _sha, subject, body in commits:
        category, title, _breaking = classify(subject, body)
        dedupe_key = re.sub(r"\s+", " ", title).strip().casefold()
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        if len(buckets[category]) < max_items:
            buckets[category].append(title)

    lines: list[str] = []
    for category in CATEGORY_ORDER:
        items = buckets[category]
        if not items:
            continue
        lines.append(CATEGORY_TITLES[category])
        lines.extend(f"• {item}" for item in items)
        lines.append("")

    if not lines:
        return "📝 Development build from the latest successful commit.\n"

    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default=None)
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--output", required=True)
    parser.add_argument("--max-commits", type=int, default=100)
    parser.add_argument("--max-items", type=int, default=30)
    args = parser.parse_args()

    output = generate(args.base, args.head, args.max_commits, args.max_items)
    Path(args.output).write_text(output, encoding="utf-8")
    print(f"Wrote {args.output} ({len(output)} characters)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
