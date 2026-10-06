# Telegram Build & Release Publishing

WA X publishes validated APKs from GitHub Actions to the `@WAXposed` Telegram forum by using **direct MTProto bot authorization through Kurigram**.

## Routing policy

There are exactly two publishing destinations:

```text
GitHub Release / version tag
→ Updates & Releases
→ https://t.me/WAXposed/4
→ message_thread_id = 4

Build without a GitHub Release
→ Beta Testing
→ https://t.me/WAXposed/18
→ message_thread_id = 18
```

A non-release build must never be posted to **Updates & Releases**.

A tagged build is posted to **Updates & Releases** only after the GitHub Release job has succeeded.

## Required GitHub secrets

These secrets are required and are read only by the Telegram publishing job:

```text
TELEGRAM_BOT_TOKEN
TELEGRAM_API_ID
TELEGRAM_API_HASH
```

Never write their values to the repository, workflow summaries, artifacts, changelogs, or logs.

## Transport architecture

The pipeline uses direct MTProto.

It does **not**:

- compile `tdlib/telegram-bot-api`;
- run a Local Bot API server;
- call Bot API `logOut`;
- move the bot between cloud and local Bot API servers;
- persist a Telegram session file;
- use a third-party GitHub Action for Telegram upload.

The CI runner creates an in-memory Kurigram session, authenticates the bot with the existing API credentials, uploads the APK directly to Telegram, publishes the changelog in the selected forum topic, and disconnects.

## Pinned dependency

The publisher uses:

```text
Kurigram[fast]==2.2.26
```

from:

```text
tools/ci/telegram-requirements.txt
```

The `fast` extra enables TgCrypto and uvloop on the Linux runner.

## Event behavior

### Pull requests

```text
Build
Test
Lint
Static checks
No Telegram
No GitHub Release
```

### Push to master

```text
Build
Test
Validate APK
Upload GitHub Actions artifact
Publish to Beta Testing / topic 18
```

### Test branch

```text
ci/telegram-release-publisher-test
→ same non-release path
→ Beta Testing / topic 18
```

This allows live Telegram validation without touching `master`.

### Version tag

```text
Build release APK
Verify tag/version/signature
Create GitHub Release
Publish the exact same APK to Updates & Releases / topic 4
```

The Telegram stable publication depends on the GitHub Release job. If the Release job fails, nothing is posted to topic 4.

## Artifact integrity

Before publication CI computes:

- filename;
- byte size;
- human-readable size;
- SHA-256;
- version;
- full commit SHA;
- short commit SHA;
- workflow URL;
- GitHub Release URL for release tags.

The APK sent to Telegram is downloaded from the artifact produced by the current workflow run.

For a tagged release, the GitHub Release asset and Telegram APK must therefore originate from the same validated build artifact.

## Changelog

### Stable releases

The Telegram changelog is extracted from the current WA X version section in:

```text
changelog.txt
```

This is the same authoritative release text used by the GitHub Release.

### Beta builds

The changelog is generated from Git history with:

```text
tools/ci/generate_telegram_changelog.py
```

It categorizes relevant changes into sections such as:

- Added
- Fixed
- Security
- Performance
- Improved
- Documentation
- Internal
- Other

The publisher splits long text into safe Telegram-sized chunks.

## Telegram presentation

### Updates & Releases — topic 4

Release title:

```text
🚀 WA X v<VERSION>
```

Destination:

```text
https://t.me/WAXposed/4
```

Only builds that are actually published as GitHub Releases belong here.

### Beta Testing — topic 18

Non-release title:

```text
🧪 WA X Beta Testing Build
```

Destination:

```text
https://t.me/WAXposed/18
```

Normal branch builds, test-branch builds, and manual non-tag builds belong here.

## File-size support

The MTProto publisher targets the Telegram bot limit used by the pinned Kurigram runtime:

```text
2000 MiB
```

Kurigram uploads large files in 512 KiB pieces and uses a multi-worker upload pipeline for large media.

If an APK exceeds the supported bot limit, the workflow must not split the APK. It posts a download/checksum fallback instead.

## Publication order

The APK is uploaded first.

After Telegram confirms the document message, the changelog is sent into the same topic as replies to that document.

This avoids posting a release announcement when the APK upload itself failed.

## Duplicate protection

After a successful Telegram publication, CI stores a small marker artifact keyed by:

```text
publish type + topic ID + commit SHA
```

This means:

- a beta publication to topic 18 does not collide with a stable publication to topic 4;
- a normal rerun does not spam the same topic;
- an explicit manual `force_publish=true` can intentionally republish.

## Dry run

The MTProto publisher can render the exact output without making a Telegram connection:

```bash
python3 tools/ci/publish_telegram_mtproto.py \
  --dry-run \
  --file <apk> \
  --type development \
  --version <version> \
  --commit <sha> \
  --branch <branch> \
  --sha256 <hash> \
  --size <size> \
  --changelog-file <file> \
  --chat-id @WAXposed \
  --thread-id 18
```

For release dry-runs use:

```text
--type stable
--thread-id 4
```

## Local tests

Run:

```bash
python3 -m py_compile \
  tools/ci/generate_telegram_changelog.py \
  tools/ci/publish_telegram_mtproto.py

python3 tools/ci/test_telegram_changelog.py
python3 tools/ci/test_publish_telegram_mtproto.py
```

## Telegram permissions

The bot must be able to post files/messages in both forum topics:

```text
Updates & Releases — topic 4
Beta Testing       — topic 18
```

If **Updates & Releases** is closed/admin-only, the bot needs the appropriate administrator/topic permission to publish there.

## Troubleshooting

### Release went to Beta Testing

This is incorrect. Verify that the workflow's `IS_RELEASE` output is true only for a version-tag run and that the GitHub Release job completed successfully.

### Beta build went to Updates & Releases

This is incorrect. Non-tag runs must resolve:

```text
TYPE=development
TELEGRAM_THREAD_ID=18
```

### Bot authorizes but cannot post

Verify:

- bot is still in `@WAXposed`;
- topic IDs are still `4` and `18`;
- the topics were not deleted/recreated;
- bot permissions allow messages and documents in both topics.

### Telegram upload fails

The GitHub artifact/release remains the fallback.

Do not publish an older APK from another workflow run.

## Security rules

- never print Telegram secrets;
- never publish from untrusted pull requests;
- use an in-memory MTProto session;
- never commit Telegram `.session` files;
- treat Git commit/changelog text as data, not shell;
- publish only the APK from the current workflow run;
- keep Kurigram pinned;
- do not use third-party Telegram uploader Actions for the secret-bearing step.


## Project identity and contact

WA X is a fork and continuation of [Dev4Mod/WaEnhancer](https://github.com/Dev4Mod/WaEnhancer) and is currently developed/maintained by [Alaa](https://github.com/Alaa91H).

- Community: https://t.me/WAXposed
- Developer Telegram: https://t.me/Alaa91h
- Email: alahus2591@gmail.com
- Voluntary support: https://ko-fi.com/alaa91h

The release bot is a distribution tool only. It does not change project licensing, attribution or release provenance.
