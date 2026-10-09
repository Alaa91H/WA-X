"""Emit a JSON observation manifest; no binary is downloaded or analyzed."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

COMPATIBILITY_TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(COMPATIBILITY_TOOLS))

from discovery.models import utc_timestamp  # noqa: E402
from discovery.sources import SOURCES, discover_many  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--target", action="append", choices=sorted(SOURCES),
        help="target to inspect; repeat for multiple targets (default: both)",
    )
    parser.add_argument("--timeout-seconds", type=int, default=10)
    parser.add_argument("--max-bytes", type=int, default=1_000_000)
    args = parser.parse_args(argv)
    if args.timeout_seconds <= 0 or args.max_bytes <= 0:
        parser.error("--timeout-seconds and --max-bytes must be positive")
    observations = discover_many(
        tuple(args.target) if args.target else ("whatsapp", "business"),
        timeout_seconds=args.timeout_seconds, max_bytes=args.max_bytes,
    )
    manifest = {
        "schema_version": 1,
        "generated_at": utc_timestamp(),
        "observations": [row.to_dict() for row in observations],
    }
    json.dump(manifest, sys.stdout, indent=2, sort_keys=True)
    sys.stdout.write("\n")
    # The emitted UNKNOWN/UNAVAILABLE state is data, not a green compatibility claim.
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
