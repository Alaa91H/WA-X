#!/usr/bin/env bash
set -euo pipefail
set +x

usage() {
  cat <<'EOU'
Usage: publish_telegram.sh --file APK --type development|stable --version VERSION \
  --commit SHA --branch BRANCH --sha256 HASH --size HUMAN_SIZE \
  --changelog-file FILE [--release-url URL] [--workflow-url URL]
EOU
}

require_value() {
  local name="$1"
  local value="${2:-}"
  if [[ -z "$value" ]]; then
    echo "Missing required value: $name" >&2
    exit 2
  fi
}

FILE=""
TYPE=""
VERSION=""
COMMIT=""
BRANCH=""
SHA256=""
SIZE=""
CHANGELOG_FILE=""
RELEASE_URL=""
WORKFLOW_URL=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --file) FILE="${2:-}"; shift 2 ;;
    --type) TYPE="${2:-}"; shift 2 ;;
    --version) VERSION="${2:-}"; shift 2 ;;
    --commit) COMMIT="${2:-}"; shift 2 ;;
    --branch) BRANCH="${2:-}"; shift 2 ;;
    --sha256) SHA256="${2:-}"; shift 2 ;;
    --size) SIZE="${2:-}"; shift 2 ;;
    --changelog-file) CHANGELOG_FILE="${2:-}"; shift 2 ;;
    --release-url) RELEASE_URL="${2:-}"; shift 2 ;;
    --workflow-url) WORKFLOW_URL="${2:-}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

require_value "--file" "$FILE"
require_value "--type" "$TYPE"
require_value "--version" "$VERSION"
require_value "--commit" "$COMMIT"
require_value "--branch" "$BRANCH"
require_value "--sha256" "$SHA256"
require_value "--size" "$SIZE"
require_value "--changelog-file" "$CHANGELOG_FILE"

if [[ ! -f "$FILE" ]]; then
  echo "APK not found: $FILE" >&2
  exit 2
fi
if [[ ! -f "$CHANGELOG_FILE" ]]; then
  echo "Changelog file not found: $CHANGELOG_FILE" >&2
  exit 2
fi
if [[ "$TYPE" != "development" && "$TYPE" != "stable" ]]; then
  echo "Unsupported publish type: $TYPE" >&2
  exit 2
fi

: "${TELEGRAM_CHAT_ID:=@WAXposed}"
: "${TELEGRAM_THREAD_ID:=4}"
: "${TELEGRAM_BOT_API_BASE:=http://127.0.0.1:8081}"
: "${TELEGRAM_MAX_UPLOAD_BYTES:=2000000000}"
: "${TELEGRAM_DRY_RUN:=false}"

if [[ "$TELEGRAM_DRY_RUN" != "true" ]]; then
  for name in TELEGRAM_BOT_TOKEN TELEGRAM_CHAT_ID TELEGRAM_THREAD_ID TELEGRAM_BOT_API_BASE; do
    if [[ -z "${!name:-}" ]]; then
      echo "Missing required environment variable: $name" >&2
      exit 2
    fi
  done
fi

ABS_FILE="$(realpath "$FILE")"
FILE_NAME="$(basename "$FILE")"
FILE_BYTES="$(stat -c '%s' "$FILE")"
SHORT_SHA="${COMMIT:0:8}"

if [[ "$TYPE" == "stable" ]]; then
  TITLE="🚀 WA X $VERSION"
  BUILD_NOTE="Stable release"
else
  TITLE="🧪 WA X Development Build"
  BUILD_NOTE="Development build — experimental changes may be present."
fi

ANNOUNCEMENT="$(cat <<EOA
$TITLE

📦 File: $FILE_NAME
📦 Version: $VERSION
🔖 Commit: $SHORT_SHA
🌿 Branch: $BRANCH
📏 Size: $SIZE
✅ CI: Passed

$BUILD_NOTE
EOA
)"

if [[ -n "$RELEASE_URL" ]]; then
  ANNOUNCEMENT+=$'\n\nGitHub Release:\n'
  ANNOUNCEMENT+="$RELEASE_URL"
elif [[ -n "$WORKFLOW_URL" ]]; then
  ANNOUNCEMENT+=$'\n\nWorkflow:\n'
  ANNOUNCEMENT+="$WORKFLOW_URL"
fi

DOCUMENT_CAPTION="$(cat <<EOC
$TITLE
📦 $FILE_NAME
📏 $SIZE
🔖 $SHORT_SHA
🔒 SHA-256: $SHA256
EOC
)"

tmp_dir="$(mktemp -d)"
cleanup() {
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

python3 - "$CHANGELOG_FILE" "$tmp_dir" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8").strip()
out_dir = Path(sys.argv[2])
limit = 3500

if not source:
    source = "📝 Development build from the latest successful commit."

lines = source.splitlines()
chunks = []
current = ""

for line in lines:
    candidate = line if not current else current + "\n" + line
    if len(candidate) <= limit:
        current = candidate
        continue

    if current:
        chunks.append(current)
        current = ""

    while len(line) > limit:
        cut = line.rfind(" ", 0, limit)
        if cut < limit // 2:
            cut = limit
        chunks.append(line[:cut].rstrip())
        line = line[cut:].lstrip()
    current = line

if current:
    chunks.append(current)

for index, chunk in enumerate(chunks):
    (out_dir / f"chunk-{index:03d}.txt").write_text(chunk, encoding="utf-8")
PY

if [[ "$TELEGRAM_DRY_RUN" == "true" ]]; then
  echo "Telegram dry run"
  echo "Target: ${TELEGRAM_CHAT_ID} / topic ${TELEGRAM_THREAD_ID}"
  echo "File: $ABS_FILE ($FILE_BYTES bytes)"
  echo "--- Announcement ---"
  printf '%s\n' "$ANNOUNCEMENT"
  echo "--- Changelog chunks ---"
  for chunk in "$tmp_dir"/chunk-*.txt; do
    [[ -e "$chunk" ]] || continue
    cat "$chunk"
    echo
    echo "---"
  done
  echo "--- Document caption ---"
  printf '%s\n' "$DOCUMENT_CAPTION"
  exit 0
fi

API_ROOT="${TELEGRAM_BOT_API_BASE%/}/bot${TELEGRAM_BOT_TOKEN}"

telegram_form() {
  local method="$1"
  shift
  local response_file="$tmp_dir/response.json"
  local http_code

  http_code="$(
    curl --silent --show-error \
      --connect-timeout 30 \
      --max-time 7200 \
      --retry 3 \
      --retry-delay 3 \
      --retry-all-errors \
      --output "$response_file" \
      --write-out '%{http_code}' \
      "$@" \
      "$API_ROOT/$method"
  )"

  if [[ "$http_code" -lt 200 || "$http_code" -ge 300 ]]; then
    echo "Telegram $method failed with HTTP $http_code" >&2
    if jq -e '.description' "$response_file" >/dev/null 2>&1; then
      jq -r '.description' "$response_file" >&2
    fi
    return 1
  fi

  if ! jq -e '.ok == true' "$response_file" >/dev/null 2>&1; then
    echo "Telegram $method returned ok=false" >&2
    jq -r '.description // "Unknown Telegram error"' "$response_file" >&2
    return 1
  fi

  cat "$response_file"
}

send_text() {
  local text_file="$1"
  telegram_form sendMessage \
    --form-string "chat_id=$TELEGRAM_CHAT_ID" \
    --form-string "message_thread_id=$TELEGRAM_THREAD_ID" \
    --form-string "text=$(cat "$text_file")" \
    --form-string "disable_notification=false"
}

printf '%s' "$ANNOUNCEMENT" > "$tmp_dir/announcement.txt"
announcement_json="$(send_text "$tmp_dir/announcement.txt")"
announcement_id="$(jq -r '.result.message_id' <<<"$announcement_json")"
echo "Announcement message id: $announcement_id"

for chunk in "$tmp_dir"/chunk-*.txt; do
  [[ -e "$chunk" ]] || continue
  send_text "$chunk" >/dev/null
done

if (( FILE_BYTES > TELEGRAM_MAX_UPLOAD_BYTES )); then
  echo "APK is larger than Telegram Local Bot API configured maximum; skipping binary upload."
  if [[ -n "$RELEASE_URL" ]]; then
    printf 'File exceeds Telegram upload limit.\nDownload: %s\nSHA-256: %s\n' \
      "$RELEASE_URL" "$SHA256" > "$tmp_dir/over-limit.txt"
    send_text "$tmp_dir/over-limit.txt" >/dev/null
  fi
  exit 0
fi

DOCUMENT_URI="file://$ABS_FILE"
document_json="$(
  telegram_form sendDocument \
    --form-string "chat_id=$TELEGRAM_CHAT_ID" \
    --form-string "message_thread_id=$TELEGRAM_THREAD_ID" \
    --form-string "document=$DOCUMENT_URI" \
    --form-string "caption=$DOCUMENT_CAPTION"
)"
document_id="$(jq -r '.result.message_id' <<<"$document_json")"
echo "Document message id: $document_id"

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "announcement_message_id=$announcement_id"
    echo "document_message_id=$document_id"
  } >> "$GITHUB_OUTPUT"
fi
