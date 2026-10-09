#!/usr/bin/env python3
"""Publish a validated WA X APK to Telegram over MTProto using Kurigram.

The script is intentionally self-contained and uses an in-memory bot session:
no Bot API proxy, no Local Bot API daemon, no cloud/local logout dance, and no
persistent Telegram session file on the CI runner.
"""

from __future__ import annotations

import argparse
import asyncio
import os
import sys
import time
from dataclasses import dataclass
from pathlib import Path

MAX_TELEGRAM_BOT_BYTES = 2000 * 1024 * 1024
TEXT_CHUNK_LIMIT = 3500


@dataclass(frozen=True)
class PublishArgs:
    file: Path
    publish_type: str
    version: str
    commit: str
    branch: str
    sha256: str
    size: str
    changelog_file: Path
    release_url: str
    workflow_url: str
    chat_id: str
    thread_id: int
    variant: str = "legacy"


def require_env(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def split_text(text: str, limit: int = TEXT_CHUNK_LIMIT) -> list[str]:
    text = text.strip()
    if not text:
        return []

    chunks: list[str] = []
    current = ""

    for line in text.splitlines():
        candidate = line if not current else f"{current}\n{line}"
        if len(candidate) <= limit:
            current = candidate
            continue

        if current:
            chunks.append(current)
            current = ""

        remaining = line
        while len(remaining) > limit:
            cut = remaining.rfind(" ", 0, limit)
            if cut < limit // 2:
                cut = limit
            chunks.append(remaining[:cut].rstrip())
            remaining = remaining[cut:].lstrip()
        current = remaining

    if current:
        chunks.append(current)

    return chunks


def title_for(args: PublishArgs) -> str:
    if args.variant == "modern-main":
        return "🧪 WA X API 102 — Experimental Main APK"
    if args.variant == "modern-canary":
        return "🧪 WA X API 102 — Separate Canary APK"
    if args.publish_type == "stable":
        return f"🚀 WA X v{args.version}"
    return "🧪 WA X Beta Testing Build"


def build_caption(args: PublishArgs) -> str:
    short_sha = args.commit[:8]
    lines = [
        title_for(args),
        "",
        f"📦 {args.file.name}",
        f"📏 {args.size}",
        f"🔖 {short_sha}",
        "✅ CI Passed",
        "",
        f"🔒 SHA-256: {args.sha256}",
    ]

    if args.variant == "modern-main":
        lines.extend(["", "⚠️ DEBUG-SIGNED: NOT an in-place update to WA X. Do not uninstall WA X.",
                      "⚠️ Migration incomplete: legacy features are not yet ported."])
    elif args.variant == "modern-canary":
        lines.extend(["", "⚠️ Separate testing app. Does not replace WA X.",
                      "⚠️ Hooks are experimental; on-device compatibility is unverified."])
    if args.release_url:
        lines.extend(["", f"GitHub Release: {args.release_url}"])
    elif args.workflow_url:
        lines.extend(["", f"Workflow: {args.workflow_url}"])

    caption = "\n".join(lines)
    if len(caption) > 1024:
        # Keep the document caption within Telegram's caption limit. Full links
        # and changelog remain available in the following text messages.
        caption = "\n".join(lines[:8])
    return caption


def build_changelog_intro(args: PublishArgs) -> str:
    if args.variant != "legacy":
        return "📝 Experimental API 102 snapshot — not a feature-complete release"
    if args.publish_type == "stable":
        return "📝 Changelog"
    return "📝 Changes since the latest stable release"


def validate_local_inputs(args: PublishArgs) -> int:
    if args.publish_type not in {"development", "stable"}:
        raise ValueError(f"Unsupported publish type: {args.publish_type}")
    if args.variant not in {"legacy", "modern-main", "modern-canary"}:
        raise ValueError(f"Unsupported APK variant: {args.variant}")
    if args.variant != "legacy" and args.publish_type == "stable":
        raise ValueError("Experimental API 102 variants cannot be called stable")
    if not args.file.is_file():
        raise FileNotFoundError(args.file)
    if not args.changelog_file.is_file():
        raise FileNotFoundError(args.changelog_file)

    file_bytes = args.file.stat().st_size
    if file_bytes <= 0:
        raise ValueError("APK is empty")
    return file_bytes


class UploadProgress:
    def __init__(self) -> None:
        self.started = time.monotonic()
        self.last_print = 0.0
        self.last_percent = -1

    def __call__(self, current: int, total: int) -> None:
        now = time.monotonic()
        percent = int((current * 100) / total) if total else 0
        should_print = (
            percent >= 100
            or percent >= self.last_percent + 5
            or now - self.last_print >= 20
        )
        if not should_print:
            return

        elapsed = max(now - self.started, 0.001)
        mib_sent = current / (1024 * 1024)
        mib_total = total / (1024 * 1024)
        speed = mib_sent / elapsed
        print(
            f"Telegram upload: {percent:3d}% "
            f"({mib_sent:.1f}/{mib_total:.1f} MiB, {speed:.1f} MiB/s)",
            flush=True,
        )
        self.last_print = now
        self.last_percent = percent


async def publish_mtproto(args: PublishArgs, file_bytes: int) -> tuple[int | None, int | None]:
    # Imported lazily so local unit tests and --dry-run need no Telegram package.
    from pyrogram import Client, enums, types
    from pyrogram.errors import FloodWait, RPCError

    api_id = int(require_env("TELEGRAM_API_ID"))
    api_hash = require_env("TELEGRAM_API_HASH")
    bot_token = require_env("TELEGRAM_BOT_TOKEN")

    # Kurigram currently uploads files >10 MiB using a four-worker 512 KiB
    # MTProto part pipeline. The client is ephemeral and never writes a .session.
    client = Client(
        "wax_release_ci",
        api_id=api_id,
        api_hash=api_hash,
        bot_token=bot_token,
        in_memory=True,
        no_updates=True,
        skip_updates=True,
        sleep_threshold=30,
        max_concurrent_transmissions=1,
    )

    document_message_id: int | None = None
    first_text_message_id: int | None = None

    try:
        await client.start()
        me = await client.get_me()
        chat = await client.get_chat(args.chat_id)
        print(
            f"MTProto authorized as @{me.username or me.id}; "
            f"target={getattr(chat, 'title', None) or args.chat_id}; "
            f"topic={args.thread_id}",
            flush=True,
        )

        if file_bytes > MAX_TELEGRAM_BOT_BYTES:
            if not args.release_url and not args.workflow_url:
                raise ValueError(
                    "APK exceeds the Telegram MTProto bot upload limit and no fallback URL is available"
                )

            fallback = (
                f"{title_for(args)}\n\n"
                f"📦 {args.file.name}\n"
                f"📏 {args.size}\n"
                "⚠️ The APK exceeds Telegram's bot upload limit.\n\n"
                f"Download: {args.release_url or args.workflow_url}\n"
                f"🔒 SHA-256: {args.sha256}"
            )
            message = await client.send_message(
                args.chat_id,
                fallback,
                message_thread_id=args.thread_id,
                parse_mode=enums.ParseMode.DISABLED,
            )
            first_text_message_id = message.id
            return document_message_id, first_text_message_id

        progress = UploadProgress()
        document = await client.send_document(
            args.chat_id,
            str(args.file),
            caption=build_caption(args),
            parse_mode=enums.ParseMode.DISABLED,
            file_name=args.file.name,
            force_document=True,
            message_thread_id=args.thread_id,
            progress=progress,
        )
        if document is None:
            raise RuntimeError("Telegram upload was stopped before the document message was created")

        document_message_id = document.id
        print(f"Telegram document message id: {document_message_id}", flush=True)

        changelog = args.changelog_file.read_text(encoding="utf-8").strip()
        full_text = f"{build_changelog_intro(args)}\n\n{changelog}" if changelog else build_changelog_intro(args)
        chunks = split_text(full_text)

        for index, chunk in enumerate(chunks):
            message = await client.send_message(
                args.chat_id,
                chunk,
                message_thread_id=args.thread_id,
                parse_mode=enums.ParseMode.DISABLED,
                reply_parameters=types.ReplyParameters(message_id=document_message_id),
                disable_web_page_preview=True,
            )
            if index == 0:
                first_text_message_id = message.id

        print(
            f"Telegram publication complete: document={document_message_id}, "
            f"changelog={first_text_message_id or 'none'}",
            flush=True,
        )
        return document_message_id, first_text_message_id

    except FloodWait as exc:
        raise RuntimeError(f"Telegram rate limit requires waiting {exc.value} seconds") from exc
    except RPCError as exc:
        raise RuntimeError(f"Telegram MTProto RPC failed: {type(exc).__name__}: {exc}") from exc
    finally:
        if client.is_connected:
            await client.stop()


def write_github_outputs(document_id: int | None, text_id: int | None) -> None:
    output_path = os.environ.get("GITHUB_OUTPUT")
    if not output_path:
        return

    with open(output_path, "a", encoding="utf-8") as output:
        output.write(f"document_message_id={document_id or ''}\n")
        output.write(f"announcement_message_id={text_id or document_id or ''}\n")


def parse_args() -> tuple[PublishArgs, bool]:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", required=True, type=Path)
    parser.add_argument("--type", dest="publish_type", choices=("development", "stable"), required=True)
    parser.add_argument("--variant", choices=("legacy", "modern-main", "modern-canary"), default="legacy")
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--branch", required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--size", required=True)
    parser.add_argument("--changelog-file", required=True, type=Path)
    parser.add_argument("--release-url", default="")
    parser.add_argument("--workflow-url", default="")
    parser.add_argument("--chat-id", default=os.environ.get("TELEGRAM_CHAT_ID", "@WAXposed"))
    parser.add_argument(
        "--thread-id",
        type=int,
        default=int(os.environ.get("TELEGRAM_THREAD_ID", "4")),
    )
    parser.add_argument("--dry-run", action="store_true")
    ns = parser.parse_args()

    args = PublishArgs(
        file=ns.file.resolve(),
        publish_type=ns.publish_type,
        version=ns.version,
        commit=ns.commit,
        branch=ns.branch,
        sha256=ns.sha256,
        size=ns.size,
        changelog_file=ns.changelog_file.resolve(),
        release_url=ns.release_url,
        workflow_url=ns.workflow_url,
        chat_id=ns.chat_id,
        thread_id=ns.thread_id,
        variant=ns.variant,
    )
    return args, ns.dry_run


def main() -> int:
    args, dry_run = parse_args()
    file_bytes = validate_local_inputs(args)

    if dry_run:
        print("Telegram MTProto dry run")
        print(f"Target: {args.chat_id} / topic {args.thread_id}")
        print(f"File: {args.file} ({file_bytes} bytes)")
        print("--- Caption ---")
        print(build_caption(args))
        print("--- Changelog chunks ---")
        changelog = args.changelog_file.read_text(encoding="utf-8").strip()
        full_text = f"{build_changelog_intro(args)}\n\n{changelog}" if changelog else build_changelog_intro(args)
        for index, chunk in enumerate(split_text(full_text), start=1):
            print(f"[chunk {index}]")
            print(chunk)
        return 0

    try:
        import uvloop
    except ImportError:
        document_id, text_id = asyncio.run(publish_mtproto(args, file_bytes))
    else:
        document_id, text_id = uvloop.run(publish_mtproto(args, file_bytes))

    write_github_outputs(document_id, text_id)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"Telegram publisher failed: {exc}", file=sys.stderr)
        raise
