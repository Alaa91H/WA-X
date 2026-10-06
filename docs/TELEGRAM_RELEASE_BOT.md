# Telegram Release Publishing

WA X publishes successful development and stable builds to the Telegram forum topic:

```text
Group: @WAXposed
Updates & Releases topic ID: 4
```

## Required GitHub secrets

These repository secrets already exist and are consumed only at publish time:

```text
TELEGRAM_BOT_TOKEN
TELEGRAM_API_ID
TELEGRAM_API_HASH
```

Never store their values in the repository, build artifacts, workflow summaries, or logs.

## Architecture

Telegram distribution uses **direct MTProto bot authorization through Kurigram**.

The pipeline does **not**:

- compile `tdlib/telegram-bot-api`;
- run a Local Bot API daemon;
- call Bot API `logOut`;
- switch the bot between cloud and local Bot API servers;
- persist a Telegram session file.

The CI runner creates an in-memory MTProto session, authenticates with:

```text
TELEGRAM_API_ID
TELEGRAM_API_HASH
TELEGRAM_BOT_TOKEN
```

uploads the current-run APK directly to Telegram, sends the changelog to forum topic `4`, then disconnects. The ephemeral CI runner is destroyed after the job.

## Why MTProto

The normal HTTP Bot API upload path is too restrictive for the requested large-file distribution.

Direct MTProto provides the same Telegram transport used by clients and supports the large-file upload RPCs used by bots. Kurigram 2.2.26 currently uses 512 KiB parts and a multi-worker pipeline for files larger than 10 MiB.

The CI limit is aligned to the non-Premium/bot limit used by Kurigram:

```text
2000 MiB
```

If Telegram or Kurigram changes the supported limit in the future, update the pinned dependency and the CI guard together after verification.

## Pinned publisher dependency

The publisher uses:

```text
Kurigram[fast]==2.2.26
```

from:

```text
tools/ci/telegram-requirements.txt
```

The `fast` extra enables TgCrypto and uvloop where supported.

The workflow pins Python through the official `actions/setup-python` action.

## Publishing model

- Pull requests: build/test only; no secret-bearing Telegram step.
- `master`: development APK artifact + Telegram development publication.
- Version tags `v*`: GitHub Release + Telegram stable publication.
- `ci/telegram-release-publisher-test`: isolated live test of the same development publishing path before merge.
- `workflow_dispatch`: supports normal publication and explicit force republish.

## Telegram target

All release/build messages use:

```text
chat_id = @WAXposed
message_thread_id = 4
```

The publisher uses Kurigram's native `message_thread_id` support, so messages and documents stay inside the **Updates & Releases** topic.

## Required Telegram permissions

The bot must be present in `@WAXposed` and be able to post messages/documents in the closed **Updates & Releases** topic.

Give only the minimum administrator permissions required for that topic. Do not grant unrelated moderation permissions.

## Artifact integrity

Before publication CI calculates:

- exact APK filename;
- byte size;
- human-readable size;
- SHA-256;
- version;
- commit SHA;
- workflow URL;
- GitHub Release URL for tagged releases.

For stable releases, GitHub Release and Telegram must use the **same downloaded artifact from the same workflow run**.

Do not rebuild separately for Telegram.

## Changelog

Stable releases continue to use the current version section from the repository's authoritative `changelog.txt`.

Development builds generate a concise changelog from Git history using:

```text
tools/ci/generate_telegram_changelog.py
```

The generated sections include:

- Added
- Fixed
- Security
- Performance
- Improved
- Documentation
- Internal
- Other

Long text is split into safe message chunks before publishing.

## Publication order

To avoid leaving a release announcement with no attached binary, the MTProto publisher uploads the APK first.

After successful document creation, it posts the changelog as replies in the same topic.

The APK caption contains the essential metadata:

- release/development label;
- file name;
- size;
- short commit;
- CI status;
- SHA-256;
- GitHub Release or workflow link when it fits.

## Large files

Configured maximum:

```text
2000 MiB
```

If an APK exceeds the verified Telegram/Kurigram bot limit, CI must not split the APK. The publisher sends a link/checksum fallback instead.

## Upload progress

Large uploads print coarse CI progress only, approximately every 5 percentage points or 20 seconds.

The log includes transferred MiB and approximate throughput without exposing secrets.

## Duplicate protection

After a successful Telegram publication the workflow stores a small GitHub Actions marker artifact keyed by:

```text
channel + commit SHA
```

A normal rerun skips a duplicate publication.

A maintainer may intentionally republish through `workflow_dispatch` with:

```text
force_publish=true
```

## Dry run

The publisher has a network-free validation mode:

```bash
python tools/ci/publish_telegram_mtproto.py \
  --dry-run \
  --file <apk> \
  --type development \
  --version <version> \
  --commit <sha> \
  --branch <branch> \
  --sha256 <hash> \
  --size <size> \
  --changelog-file <file>
```

Dry run validates local inputs and renders:

- target;
- topic;
- document caption;
- changelog chunks.

It does not import or require Kurigram until real publication begins.

## Local tests

Run:

```bash
python3 tools/ci/test_telegram_changelog.py
python3 tools/ci/test_publish_telegram_mtproto.py
python3 -m py_compile \
  tools/ci/generate_telegram_changelog.py \
  tools/ci/publish_telegram_mtproto.py
```

## Test branch

Live validation is performed on:

```text
ci/telegram-release-publisher-test
```

That branch exercises the actual Android build, artifact validation, changelog generation, MTProto authorization, forum-topic upload, checksum metadata, and duplicate marker without touching `master`.

## Troubleshooting

### Authorization fails

Check that all three GitHub secrets exist and belong to the intended Telegram application/bot.

Do not print their values.

### Bot can resolve the group but cannot send

Verify:

- the bot is still in `@WAXposed`;
- topic ID is still `4`;
- the topic was not deleted/recreated with another ID;
- the bot can post in the closed topic.

### Upload fails near the start

Check:

- the APK exists and is not empty;
- the runner has outbound Telegram connectivity;
- the bot is allowed to post documents;
- the current file is within the supported bot size limit.

### FloodWait

Kurigram handles short flood waits automatically up to the configured threshold. A larger rate limit is surfaced as a CI failure instead of sleeping indefinitely.

### Telegram fails after GitHub release succeeds

Do not delete the valid GitHub Release.

The GitHub Artifact/Release remains the distribution fallback. Re-run only the Telegram publication path through the normal workflow after fixing permissions/connectivity.

## Security rules

- never print Telegram secrets;
- no Telegram publishing on untrusted pull requests;
- use an in-memory MTProto session only;
- do not commit `.session` files;
- treat commit/changelog text as data, never shell;
- publish only the APK downloaded from the current workflow run;
- keep the Kurigram version pinned;
- do not use third-party Telegram uploader Actions for the secret-bearing step.
