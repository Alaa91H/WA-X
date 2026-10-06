# Telegram Release Publishing

WA X publishes successful development and release builds to the Telegram forum topic:

```text
Group: @WAXposed
Updates & Releases topic ID: 4
```

## Required GitHub secrets

These repository secrets must exist:

```text
TELEGRAM_BOT_TOKEN
TELEGRAM_API_ID
TELEGRAM_API_HASH
```

Do not store their values in this repository.

## Publishing model

- Pull requests: build/test only, no Telegram publishing.
- `master`: development APK artifact + Telegram development post.
- Version tags `v*`: GitHub Release + Telegram stable release.
- Test branch `ci/telegram-release-publisher-test`: full development publishing path for isolated validation before merge.

## Why a Local Bot API server is used

Telegram's Local Bot API mode supports uploads up to 2000 MB and local file paths. The CI job builds the official `tdlib/telegram-bot-api` source from a pinned upstream commit and starts it on localhost only.

The publishing helper then sends the already-built APK using a local `file://` URI, so multi-gigabyte artifacts are not copied through a second loopback upload stream.

## One-time cloud-to-local migration

Telegram documents that a bot must be logged out from the cloud Bot API before using a Local Bot API server.

The CI implementation:

1. validates the configured bot token;
2. calls the cloud `logOut` endpoint when preparing Local Bot API use;
3. starts the local server;
4. waits for local `getMe` to succeed;
5. publishes to `@WAXposed`, topic `4`;
6. logs the bot out from the local server during cleanup when possible.

After a successful cloud `logOut`, Telegram may prevent cloud Bot API login for 10 minutes. WA X publishing therefore uses the Local Bot API consistently for binary distribution instead of silently switching between cloud and local servers.

If the build bot is also used by another service through `api.telegram.org`, do not enable this publishing path until that service is migrated or a dedicated release bot is used.

## Required Telegram permissions

The release bot must be present in `@WAXposed` and able to post in the closed **Updates & Releases** forum topic.

Because the topic is admin-only/closed, give the bot the minimum Telegram administrator permissions needed to send messages/documents and operate in that topic. Do not grant unrelated permissions.

## Changelog behavior

Development builds generate a categorized changelog from Git history.

Categories include:

- Added
- Fixed
- Security
- Performance
- Improved
- Documentation
- Internal
- Other

Stable release notes continue to use the repository's authoritative `changelog.txt` release section; Telegram can reuse the same user-facing release content when production integration is enabled.

## File integrity

Before publishing, CI calculates:

- exact filename;
- byte size;
- human-readable size;
- SHA-256;
- version;
- commit SHA.

For a stable release, the intended production contract is that the GitHub Release APK and Telegram APK are byte-identical.

## Large files

Configured Telegram Local Bot API maximum:

```text
2,000,000,000 bytes
```

If a future artifact exceeds the supported Telegram maximum, CI must not split an APK into parts. It should publish the GitHub Release/download link and SHA-256 instead.

## Dry run

The publisher supports:

```bash
TELEGRAM_DRY_RUN=true tools/ci/publish_telegram.sh ...
```

Dry run renders the exact announcement, changelog chunks, target topic, checksum metadata, and document caption without any Telegram network publication.

## Test branch

The isolated validation branch is:

```text
ci/telegram-release-publisher-test
```

The branch is intentionally used to exercise the actual build, changelog, Local Bot API startup, Telegram target validation, and APK publication flow before the production workflow is merged.

## Troubleshooting

### Bot cannot post to topic

Verify:

- bot is still a member/admin of `@WAXposed`;
- topic ID is still `4`;
- the topic is not deleted;
- the bot has permission to post in the closed topic.

### Local Bot API does not start

Check the CI job log for:

- dependency installation;
- pinned upstream checkout;
- CMake build;
- port readiness;
- `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` presence.

### Local `getMe` fails

Most commonly:

- bot was not successfully logged out from the cloud Bot API first;
- token is invalid;
- the Local Bot API server has not finished authorizing the bot yet.

### APK publication fails

The GitHub Actions artifact remains the fallback. Do not publish an older APK from another run.

## Security rules

- never print Telegram secrets;
- never enable shell tracing around secret-bearing requests;
- never expose secrets to untrusted pull requests;
- never execute arbitrary commit text as shell code;
- use the exact artifact built in the current workflow run only.
