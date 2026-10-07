# Loader integrity: the entry point R8 was removing

Package: the inserted blocker package (`docs/PACKAGE_EXECUTION_PLAN.md`, package 3), built on
development package 2 while verifying its Beta artifact.
Owners: #324 (M05 — "Add/verify R8 entry preservation", "Verify final APK contents, not only
source tree") and #361 (release hygiene). Measurements are recorded on #324 and #318.

## The defect

`assets/xposed_init` in every published APK names `com.wax.module.ModuleEntryPoint`. That file is
the legacy contract: LSPosed reads it and instantiates the class it names
(`docs/XPOSED_SCOPE.md`). In a **release** build the class was not there. R8 removed it, and with
it the entire injected runtime reachable only through it.

R8's own reports say so. From a local `assembleRelease`, in `usage.txt` (a class printed without a
colon was removed; one with a colon survived with some members removed):

```
com.wax.module.ModuleEntryPoint                   <- removed
com.wax.module.xposed.core.devkit.Unobfuscator    <- removed
com.wax.module.activities.MainActivity:           <- kept
```

`mapping.txt` contains `MainActivity` and no `ModuleEntryPoint`; `seeds.txt` contains neither the
entry point nor any `com.wax.module.xposed.**` class. `app/proguard-rules.pro` sets
`-dontobfuscate` and `-dontoptimize`, so these are real names: the shrinker removed the code
because nothing reachable from a seed refers to it. The only reference to the entry point anywhere
in the APK is the class name written in `assets/xposed_init`, and **R8 does not read assets**.

Measured across the shipped artifacts (dex string tables; a class must have its descriptor in the
string table to exist):

| APK | `ModuleEntryPoint` | `Unobfuscator` | `MainActivity` |
| --- | --- | --- | --- |
| `WaEnhancer-1.8.0.apk` (pre-rename stable) | 0 (old name) | **499** | 10 |
| `WA-X-v1.1.0.apk` | **0** | **0** | present |
| `WA-X-v1.2.0-beta.1.apk` | **0** | **0** | present |
| `WA-X-v1.2.0-beta.2.apk` | **0** | **0** | present |

So the APK installed, declared `xposedmodule=true`, carried the WA X update signature, listed the
entry class in `assets/xposed_init`, and could not be loaded as a module. Every gate passed,
because every gate builds the **debug** variant, which is not shrunk, and the source-level
contract checker reads the source, which was never the problem.

## The fix

One keep rule, in `app/proguard-rules.pro`, in the file's own idiom (a comment that states the
failure mode next to the rule that prevents it):

```
-keep class com.wax.module.ModuleEntryPoint { *; }
```

Keeping the class as a root restores the runtime by reachability: the feature registry holds
direct class references, so nothing inside the injected code needs a rule of its own. Measured
after the fix, on the release APK: the entry point, `FeatureLoader` **and** `RuntimeHealth` are all
defined in `classes.dex`, and the APK defines 11,005 classes where the broken one defined 9,803 -
the M01 runtime, and 1.2 MB of APK, had been missing from every shippable build.

## The two gates

A keep rule alone would leave the same hole open for the next person, so the contract is now
checked at both levels, and each half is insufficient alone.

| Gate | Reads | Runs in | Catches |
| --- | --- | --- | --- |
| `tools/quality/check_lsposed_contract.py` (`entry.keep`) | the tree: `assets/xposed_init` and the ProGuard rules | every PR (`strict-static`) | the rule being removed, weakened, or scoped to another class |
| `tools/quality/check_apk_loader_contract.py` | a **built APK**: its own `assets/xposed_init` and the class definitions in its dex | `Compile debug APK` (always) and the release step before publishing | the class missing from the artifact that will actually be installed |

Both have self-tests that build deliberately broken fixtures and assert a failure:
`tools/quality/test_check_lsposed_contract.py` (57 cases) and
`tools/quality/test_check_apk_loader_contract.py` (23 cases, generating minimal dex files and APKs
rather than committing binaries). The artifact checker resolves class **definitions** through the
dex `class_defs`/`type_ids`/`string_ids` tables rather than searching for a substring, so a name
that survives inside a string constant cannot be mistaken for loadable code - and that case is one
of the fixtures.

The `entry.keep` check understands modifiers rather than grepping for `-keep`: `-keepnames`,
`-keepclassmembers` and `-keep,allowshrinking` are all correctly treated as *not* holding a class
against the shrinker, each with its own case. Accepting any of them is how a check like this ends
up satisfied by the line that causes the defect.

## Release hygiene (#361)

The release step published every tag with `--latest` and without `--prerelease`, so each
development-package beta became the repository's **Latest** release and demoted the last stable
one; `v1.2.0-beta.2` is currently in that position. The decision now lives in
`tools/ci/release_publication.py`, which is tested by `tools/ci/test_release_publication.py`
(32 cases, including two that compare it against `ci.yml` so the two cannot drift): a version
with a pre-release suffix is published `--prerelease` and never `--latest`, a version without
one is published `--latest`, and a version preflight would reject is refused here too.

## Verified, and not verified

Verified on the build machine and in CI for this package:

- the two new gates fail on the artifacts that shipped before this change (the published
  `v1.2.0-beta.2` APK is rejected by name) and pass on the release APK built with the fix;
- 27 static contracts, the unit suite and coverage floors, `spotlessCheck`/`detekt`/`lintDebug`
  under `--warning-mode=fail`, `assembleDebug` + `assembleRelease`, and the baseline ratchet;
- the M00 evidence lock is re-derived in this package because the workflow hash it records moved.

**Device-lab runtime evidence: none, and none is claimed.** This package proves that the installed
APK now contains the class LSPosed loads, and that the runtime it needs is in the same dex. It does
not prove the module hooks anything on a real device: no cell of the compatibility or test matrix
moves out of `unknown` here, and the first device evidence will come from the phase whose gate
calls for it.

## What this does not do

The remaining #324 M05 tasks stay open and are unaffected: replacing the blanket `META-INF/**`
packaging exclusion, proving the modern `java_init.list`/`scope.list`/`module.prop` metadata
survives packaging, splitting the contract checker into explicit legacy and modern modes,
rejecting ambiguous mixed-loader packages, and running the metadata check against signed
distributed APKs. This package fixes the legacy entry the program's next phases depend on, and
leaves the migration to the modern entry where the program put it.
