# WA X upstream runtime and toolchain review — 2026-10-09

This file separates **published framework releases** from **dependencies consumed by WA X**. A version pin in a Gradle catalog is not a framework upgrade on an Android device, and a successful Gradle build is not evidence that WhatsApp hooks work.

| Component | Published upstream | Repository change / status | Primary source |
| --- | --- | --- | --- |
| Vector Xposed framework | **v2.2 stable**; **v2.2 canary 3112** latest published preview (2026-10-01) | Not bundled with WA X. User must update the installed root framework separately. Do not auto-flash from an app. | https://github.com/JingMatrix/Vector/releases |
| Modern libxposed API | **102.0.0** | Add `compileOnly` API pin for M06 migration work; **current shipped entry still uses legacy API 93**. | https://central.sonatype.com/artifact/io.github.libxposed/api/102.0.0 |
| Modern libxposed service | **102.0.0** | Verified available, **not included until a real modern service integration exists**. | https://central.sonatype.com/artifact/io.github.libxposed/service/102.0.0 |
| Legacy Xposed API | Maven artifact **82**, runtime API level **93** | Remains the active loading contract. 82 and 93 are different version spaces; do not invent legacy artifact 102. | https://github.com/LSPosed/LSPosed/wiki/Develop-Xposed-Modules-Using-Modern-Xposed-API |
| Spotless Gradle plugin | **8.10.4** already pinned | No change necessary to this plugin; CI builds with it. | https://github.com/diffplug/spotless |
| Gradle | **9.8.1**, security patch | Wrapper distribution moved from 9.7.0 to 9.8.1; SHA-256 pinned from Gradle official checksums. | https://gradle.org/release-checksums/ |
| Android Gradle Plugin | **9.4.1** latest stable; **9.5.0-alpha08** latest preview tested here | Trying preview 9.5.0-alpha08 because AGP 9.4.1 calls deprecated `Configuration.setVisible` under Gradle 9.8.1 and strict CI fails. Must pass all gates before acceptance. | https://developer.android.google.cn/reference/tools/gradle-api |
| DexKit | **2.3.0** latest upstream at review | WA X embeds a local `app/libs/dexkit-android.aar`; its binary version/hash must be independently verified before claiming it was upgraded. | https://github.com/LuckyPray/DexKit/releases |

## Deliberate boundaries

1. **No dual loader:** Current signed APK still owns `assets/xposed_init`, manifest `xposedminversion=93`, and the legacy hook implementation. Merely adding a compile-only 102 API does not activate modern `XposedModule`. Do not add `META-INF/xposed/java_init.list` until M06 has a tested entry point and legacy/modern packaging is mutually exclusive.
2. **M06 is substantive engineering:** migrate entry, module service, preference transport, hook interface, resource handling and all feature groups in dependency order. Enforce a package check against the **built signed APK**, not the source directory. See #325; #323 and #324 are prerequisites. The old legacy API is not obsolete just because the modern one exists.
3. **Root framework is not app content:** Vector stable v2.2 is the safer baseline; canary 3112 is a test lane only. Updating it requires the authorized device, a rollback path and a compatible root manager. The WA X build/release pipeline must never silently flash a system module.
4. **Real device behavior:** log the exact framework build, Android SDK, WhatsApp build and WA X APK hash; verify injection, signed bridge health, refresh to Manager, and one reversible feature. A red self-hook banner does not override target-process evidence. A version marked supported is not proof of every feature.
5. **No suppressed CI failures:** preserve R8, Spotless, strict lint, CodeQL and the legacy single-loader packaging check. Release evidence remains unverified until a real rooted-device run.

## Upgrade gate

For this staging change, verify Gradle 9.8.1 checksum, dependency resolution, unit tests, code quality and APK build. **Do not merge staging as a completed M06 migration**. Complete M06 only after both WhatsApp and Business are demonstrably hooked through modern API 102 on a compatible framework and preferences/hooks round-trip correctly.
