#!/usr/bin/env bash
#
# WA X — T00 baseline generator.
#
# Produces a machine-readable baseline (baseline.json) and a human-readable
# report (BASELINE.md) capturing the metrics we must not regress:
#   - APK sizes when APKs are available
#   - number of features registered in FeatureLoader
#   - per-feature load times (parsed from an Xposed/LSPosed log via --xposed-log)
#   - Unobfuscator risk counters (load resolvers, `!!`, throws)
#   - lint-baseline.xml size and per-issue counts
#   - source/test/version/SDK facts
#
# Usage:
#   bash tools/baseline/generate_baseline.sh [options]
#
# Options:
#   --out-dir DIR      Output directory (default: tools/baseline)
#   --xposed-log FILE  Xposed/LSPosed log to extract runtime load times from
#   --apk FILE         Register an APK (repeatable); default: auto-discover
#                      under app/build/outputs/apk
#   --no-report        Do not write BASELINE.md (JSON only)
#   -h, --help         Show this help
#
# Exit codes:
#   0 = generated   1 = usage error   2 = required file missing
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

OUT_DIR="$SCRIPT_DIR"
XPOSED_LOG=""
WRITE_REPORT=1
declare -a EXTRA_APKS=()

usage() {
    sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --out-dir)    OUT_DIR="${2:?missing value for --out-dir}"; shift 2 ;;
        --xposed-log) XPOSED_LOG="${2:?missing value for --xposed-log}"; shift 2 ;;
        --apk)        EXTRA_APKS+=("${2:?missing value for --apk}"); shift 2 ;;
        --no-report)  WRITE_REPORT=0; shift ;;
        -h|--help)    usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
    esac
done

cd "$REPO_ROOT"

FEATURE_LOADER="app/src/main/java/com/wax/module/xposed/core/FeatureLoader.kt"
UNOBFUSCATOR="app/src/main/java/com/wax/module/xposed/core/devkit/Unobfuscator.kt"
LINT_BASELINE="app/lint-baseline.xml"
GRADLE_PROPERTIES="gradle.properties"
APP_BUILD_GRADLE="app/build.gradle.kts"
ARRAYS_XML="app/src/main/res/values/arrays.xml"

for f in "$FEATURE_LOADER" "$UNOBFUSCATOR" "$LINT_BASELINE" "$GRADLE_PROPERTIES" "$APP_BUILD_GRADLE" "$ARRAYS_XML"; do
    if [[ ! -f "$f" ]]; then
        echo "error: required file not found: $f" >&2
        exit 2
    fi
done

if [[ -n "$XPOSED_LOG" && ! -f "$XPOSED_LOG" ]]; then
    echo "error: --xposed-log file not found: $XPOSED_LOG" >&2
    exit 2
fi

mkdir -p "$OUT_DIR"

# ---------------------------------------------------------------- helpers ---

count_lines() { # file
    wc -l < "$1" | tr -d ' '
}

grep_count() { # pattern file  (never fails on zero matches)
    local n
    n=$(grep -c -e "$1" "$2" 2>/dev/null || true)
    echo "${n:-0}"
}

json_escape() {
    tr -d '\r' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

human_size() { # bytes
    awk -v b="${1:-0}" 'BEGIN{
        if (b >= 1048576)      printf "%.2f MiB", b / 1048576;
        else if (b >= 1024)    printf "%.1f KiB", b / 1024;
        else                   printf "%d B", b;
    }'
}

sha256_of() { # file
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$1" | awk '{print $NF}'
    else
        echo "unavailable"
    fi
}

# Reads newline-separated items on stdin and prints a JSON string array in one
# awk pass (avoids per-item forking, which is very slow on Git Bash for Windows).
# Intended for constrained values (feature names, version strings) that never
# contain quotes or backslashes.
json_array_from_lines() {
    awk 'BEGIN { first = 1; printf "[" }
         NF {
             if (!first) printf ", "
             first = 0
             printf "\"%s\"", $0
         }
         END { printf "]" }'
}

# ------------------------------------------------------------------- meta ---

GENERATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
GIT_COMMIT="$(git rev-parse --short=12 HEAD 2>/dev/null || echo unknown)"
GIT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)"
if [[ -n "$(git status --porcelain 2>/dev/null || true)" ]]; then
    GIT_DIRTY="true"
else
    GIT_DIRTY="false"
fi

MODULE_VERSION="$(sed -n 's/^waxVersionName=//p' "$GRADLE_PROPERTIES" | tail -n 1 | tr -d '\r')"
MODULE_VERSION_CODE="$(sed -n 's/^waxVersionCode=//p' "$GRADLE_PROPERTIES" | tail -n 1 | tr -d '\r')"

COMPILE_SDK="$(grep -m1 -o 'compileSdk *= *[0-9]*' "$APP_BUILD_GRADLE" | grep -o '[0-9]*' || true)"
TARGET_SDK="$(grep -m1 -o 'targetSdk *= *[0-9]*' "$APP_BUILD_GRADLE" | grep -o '[0-9]*' || true)"
MIN_SDK="$(grep -m1 -o 'minSdk *= *[0-9]*' "$APP_BUILD_GRADLE" | grep -o '[0-9]*' || true)"

# ----------------------------------------------------------------- source ---

KT_MAIN="$(find app/src/main/java -name '*.kt' 2>/dev/null | wc -l | tr -d ' ' || true)"
KT_XPOSED="$(find app/src/main/java/com/wax/module/xposed -name '*.kt' 2>/dev/null | wc -l | tr -d ' ' || true)"
KT_FEATURES="$(find app/src/main/java/com/wax/module/xposed/features -name '*.kt' 2>/dev/null | wc -l | tr -d ' ' || true)"
JAVA_FILES="$(find app/src/main/java -name '*.java' 2>/dev/null | wc -l | tr -d ' ' || true)"
TEST_FILES="$(find app/src/test app/src/androidTest -type f -name '*.kt' 2>/dev/null | wc -l | tr -d ' ' || true)"
TEST_COUNT="$(grep -r -c '@Test' app/src/test app/src/androidTest 2>/dev/null | awk -F: '{s+=$NF} END{print s+0}' || true)"

# ---------------------------------------------------------- test results ---

TEST_RESULTS_DIR="app/build/test-results"
TR_FILES=0; TR_TESTS=0; TR_SKIPPED=0; TR_FAILURES=0; TR_ERRORS=0
xml_attr() { # testsuite-tag attr-name
    printf '%s' "$1" | grep -o " $2=\"[0-9]*\"" 2>/dev/null | head -n 1 | grep -o '[0-9]*' || true
}
while IFS= read -r xml; do
    [[ -z "$xml" ]] && continue
    suite="$(tr '\n' ' ' < "$xml" 2>/dev/null | grep -o '<testsuite [^>]*>' | head -n 1 || true)"
    [[ -z "$suite" ]] && continue
    TR_FILES=$((TR_FILES + 1))
    v="$(xml_attr "$suite" tests)";    TR_TESTS=$((TR_TESTS + ${v:-0}))
    v="$(xml_attr "$suite" skipped)";  TR_SKIPPED=$((TR_SKIPPED + ${v:-0}))
    v="$(xml_attr "$suite" failures)"; TR_FAILURES=$((TR_FAILURES + ${v:-0}))
    v="$(xml_attr "$suite" errors)";   TR_ERRORS=$((TR_ERRORS + ${v:-0}))
done < <(find "$TEST_RESULTS_DIR" -type f -name '*.xml' 2>/dev/null | sort || true)

if [[ "$TR_FILES" -eq 0 ]]; then
    TR_JSON_TESTS=null; TR_JSON_SKIPPED=null; TR_JSON_FAILURES=null; TR_JSON_ERRORS=null
    TR_JSON_VARIANTS=null
    TR_REPORT="n/a (no JUnit XMLs — run unit tests before generating)"
else
    TR_JSON_TESTS=$TR_TESTS; TR_JSON_SKIPPED=$TR_SKIPPED; TR_JSON_FAILURES=$TR_FAILURES; TR_JSON_ERRORS=$TR_ERRORS
    # Gradle writes one directory per variant, so the directory name is the variant.
    # Recorded so the regression gate compares tests per variant: the suite used to run
    # once per product flavor, and halving the variant count must not look like lost tests.
    TR_VARIANTS="$(find "$TEST_RESULTS_DIR" -type f -name '*.xml' -printf '%h\n' 2>/dev/null \
        | sed "s|.*/||" | sort -u | awk 'NF' | wc -l | tr -d ' ' || true)"
    [[ -z "$TR_VARIANTS" || "$TR_VARIANTS" == "0" ]] && TR_VARIANTS=1
    TR_JSON_VARIANTS=$TR_VARIANTS
    TR_REPORT="$TR_TESTS executed, $TR_FAILURES failures, $TR_ERRORS errors ($TR_FILES files, $TR_VARIANTS variant(s))"
fi

# --------------------------------------------------------------- features ---

# ktlint may split `val classes = arrayOf(` across two lines once the list is long, so
# the range is opened at the assignment and closed at the matching indentation, then the
# class references are collected from everything inside. Matching the exact one-line
# form here reported "0 features" instead of failing.
FEATURES_RAW="$(awk '
    /val[[:space:]]+classes[[:space:]]*=/ { collecting = 1 }
    collecting { print }
    collecting && /^[[:space:]]*\)/ { exit }
' "$FEATURE_LOADER" | grep -o '[A-Za-z0-9_$]*::class\.java' | sed 's/::class\.java//' || true)"
FEATURE_NAMES_JSON="$(json_array_from_lines <<< "$FEATURES_RAW")"
FEATURE_COUNT="$(printf '%s\n' "$FEATURES_RAW" | awk 'NF' | wc -l | tr -d ' ')"

# ----------------------------------------------------------- unobfuscator ---

UNOBF_LINES="$(count_lines "$UNOBFUSCATOR")"
UNOBF_LOAD_FUNCS="$(grep_count 'fun load' "$UNOBFUSCATOR")"
# Counted by check_baseline.py so the recorded figure and the enforced figure come from
# one implementation. A plain `grep -o '!!'` also matches comments and string literals,
# which both inflates the number and would let prose about the assertions change the
# ratchet. T13 is what this measures.
UNOBF_BANGS="$(python3 tools/baseline/check_baseline.py --print-non-null-assertions "$UNOBFUSCATOR" 2>/dev/null || echo null)"
UNOBF_THROWS="$(grep_count 'throw ' "$UNOBFUSCATOR")"

# ------------------------------------------------------------ lint baseline ---

# Counted by element name rather than by matching `id="` lines: the line-oriented form
# is sensitive to how the baseline happens to be serialised. A reformat that moves the
# attributes onto the <issue line made this report 0, which then propagated a false
# "no lint issues" into baseline.json and let check_baseline.py pass a growing baseline.
LINT_TOTAL="$(grep -c '<issue[ >]' "$LINT_BASELINE" 2>/dev/null || true)"
LINT_TOTAL="${LINT_TOTAL//[^0-9]/}"
LINT_IDS_JSON="$(grep -o 'id="[^"]*"' "$LINT_BASELINE" 2>/dev/null \
    | sed 's/id="//; s/"$//' | sort | uniq -c | awk '{print $1, $2}' \
    | awk 'BEGIN{first=1; printf "{"} {if (!first) printf ","; first=0; printf "\n    \"%s\": %s", $2, $1} END{if (!first) printf "\n  "; printf "}"}' \
    || echo '{}')"
if [[ -z "$LINT_IDS_JSON" ]]; then LINT_IDS_JSON='{}'; fi

# ------------------------------------------------------- supported versions ---

VERSIONS_WPP="$(sed -n '/name="supported_versions_wpp"/,/<\/string-array>/p' "$ARRAYS_XML" \
    | grep -o '<item>[^<]*</item>' | sed 's/<item>//; s/<\/item>//' || true)"
VERSIONS_BUSINESS="$(sed -n '/name="supported_versions_business"/,/<\/string-array>/p' "$ARRAYS_XML" \
    | grep -o '<item>[^<]*</item>' | sed 's/<item>//; s/<\/item>//' || true)"
VERSIONS_WPP_JSON="$(json_array_from_lines <<< "$VERSIONS_WPP")"
VERSIONS_BUSINESS_JSON="$(json_array_from_lines <<< "$VERSIONS_BUSINESS")"

# -------------------------------------------------------------------- APKs ---

declare -a APK_FILES=()
if [[ ${#EXTRA_APKS[@]} -gt 0 ]]; then
    APK_FILES=("${EXTRA_APKS[@]}")
else
    while IFS= read -r f; do
        [[ -n "$f" ]] && APK_FILES+=("$f")
    done < <(find app/build/outputs/apk -type f -name '*.apk' 2>/dev/null | sort || true)
fi

APKS_JSON="["
apk_first=1
APK_SUMMARY_LINES=""
for apk in ${APK_FILES[@]+"${APK_FILES[@]}"}; do
    if [[ ! -f "$apk" ]]; then
        echo "warning: APK not found, skipping: $apk" >&2
        continue
    fi
    apk_bytes="$(wc -c < "$apk" | tr -d ' ')"
    apk_human="$(human_size "$apk_bytes")"
    apk_sha="$(sha256_of "$apk")"
    # Build type only: WA X builds one APK with no flavor dimension, so recording a
    # flavor here would invent a distinction that no longer exists in the output tree.
    apk_build="unknown"
    case "$apk" in
        */release/*) apk_build="release" ;;
        */debug/*)   apk_build="debug" ;;
    esac
    [[ $apk_first -eq 0 ]] && APKS_JSON+=","
    APKS_JSON+="$(printf '\n    {"path": "%s", "buildType": "%s", "sizeBytes": %s, "sizeHuman": "%s", "sha256": "%s"}' \
        "$(printf '%s' "$apk" | json_escape)" "$apk_build" "$apk_bytes" "$apk_human" "$apk_sha")"
    apk_first=0
    APK_SUMMARY_LINES+="| \`$apk\` | $apk_build | $apk_human ($apk_bytes B) | \`${apk_sha:0:16}…\` |"$'\n'
done
APKS_JSON+=$'\n  ]'

# ----------------------------------------------------------------- runtime ---

RUNTIME_SOURCE="none"
TOTAL_HOOK_LOAD_MS="null"
FEATURE_LOAD_TIMES_JSON="{}"
FEATURE_LOADED_COUNT="null"
RUNTIME_LOG_LABEL="null"
if [[ -n "$XPOSED_LOG" ]]; then
    RUNTIME_SOURCE="xposed_log"
    RUNTIME_LOG_LABEL="\"$(printf '%s' "$XPOSED_LOG" | json_escape)\""
    total="$(grep -o 'Loaded Hooks in [0-9]*ms' "$XPOSED_LOG" | tail -n 1 | grep -o '[0-9]*' || true)"
    [[ -n "$total" ]] && TOTAL_HOOK_LOAD_MS="$total"

    times_raw="$(grep -o '\* Loaded Plugin [A-Za-z0-9_$]* in [0-9]*ms' "$XPOSED_LOG" \
        | sed 's/^\* Loaded Plugin //; s/ in / /; s/ms$//' || true)"
    FEATURE_LOAD_TIMES_JSON="$(printf '%s\n' "$times_raw" \
        | awk 'NF>=2 {if (!first) printf ","; first=0; printf "\n    \"%s\": %s", $1, $2} BEGIN{printf "{"; first=1} END{if (!first) printf "\n  "; printf "}"}')"
    if [[ -z "$FEATURE_LOAD_TIMES_JSON" ]]; then FEATURE_LOAD_TIMES_JSON="{}"; fi
    FEATURE_LOADED_COUNT="$(printf '%s\n' "$times_raw" | awk 'NF>=2' | wc -l | tr -d ' ')"
fi

# -------------------------------------------------------------- write JSON ---

BASELINE_JSON="$OUT_DIR/baseline.json"
cat > "$BASELINE_JSON" <<EOF
{
  "schemaVersion": 1,
  "generatedAtUtc": "$GENERATED_AT",
  "module": {
    "name": "WaEnhancer",
    "versionName": "${MODULE_VERSION:-unknown}",
    "versionCode": ${MODULE_VERSION_CODE:-0}
  },
  "git": {
    "commit": "$GIT_COMMIT",
    "branch": "$GIT_BRANCH",
    "dirtyWorktree": $GIT_DIRTY
  },
  "sdk": {
    "compileSdk": ${COMPILE_SDK:-null},
    "targetSdk": ${TARGET_SDK:-null},
    "minSdk": ${MIN_SDK:-null}
  },
  "source": {
    "kotlinFilesMain": $KT_MAIN,
    "kotlinFilesXposed": $KT_XPOSED,
    "kotlinFilesFeatures": $KT_FEATURES,
    "javaFilesRemaining": $JAVA_FILES,
    "testFiles": $TEST_FILES,
    "testMethods": $TEST_COUNT
  },
  "testResults": {
    "files": $TR_FILES,
    "tests": $TR_JSON_TESTS,
    "skipped": $TR_JSON_SKIPPED,
    "failures": $TR_JSON_FAILURES,
    "errors": $TR_JSON_ERRORS,
    "variants": $TR_JSON_VARIANTS
  },
  "features": {
    "registeredCount": $FEATURE_COUNT,
    "names": $FEATURE_NAMES_JSON
  },
  "unobfuscator": {
    "fileLines": $UNOBF_LINES,
    "loadResolvers": $UNOBF_LOAD_FUNCS,
    "nonNullAssertions": $UNOBF_BANGS,
    "throwStatements": $UNOBF_THROWS,
    "runtimeErrorCount": null,
    "runtimeErrorCountNote": "requires on-device structured failure reports (T03)"
  },
  "lintBaseline": {
    "file": "$LINT_BASELINE",
    "totalIssues": $LINT_TOTAL,
    "byIssueId": $LINT_IDS_JSON
  },
  "supportedVersions": {
    "whatsapp": $VERSIONS_WPP_JSON,
    "business": $VERSIONS_BUSINESS_JSON
  },
  "apks": $APKS_JSON,
  "runtime": {
    "source": "$RUNTIME_SOURCE",
    "xposedLog": $RUNTIME_LOG_LABEL,
    "featuresLoaded": $FEATURE_LOADED_COUNT,
    "totalHookLoadMs": $TOTAL_HOOK_LOAD_MS,
    "featureLoadTimesMs": $FEATURE_LOAD_TIMES_JSON
  }
}
EOF

# ---------------------------------------------------------- write report ---

BASELINE_MD="$OUT_DIR/BASELINE.md"
if [[ "$WRITE_REPORT" -eq 1 ]]; then
    if [[ ${#APK_FILES[@]} -eq 0 ]]; then
        APK_SECTION="No APKs found under \`app/build/outputs/apk\`. Run a build (e.g. \`./gradlew assembleDebug\`) and re-run the generator to capture sizes."
    else
        APK_SECTION="| APK | Build type | Size | SHA-256 |"$'\n'"|---|---|---|---|"$'\n'"$APK_SUMMARY_LINES"
    fi

    if [[ "$RUNTIME_SOURCE" == "none" ]]; then
        RUNTIME_SECTION="No Xposed log supplied. Capture a device log and re-run with \`--xposed-log <file>\` to record per-feature load times."
    else
        RUNTIME_SECTION="Source log: \`$XPOSED_LOG\` — total hook load: ${TOTAL_HOOK_LOAD_MS} ms — features with timing: ${FEATURE_LOADED_COUNT}."$'\n\n'"\`\`\`json"$'\n'"$FEATURE_LOAD_TIMES_JSON"$'\n'"\`\`\`"
    fi

    LINT_TABLE="$(printf '%s' "$LINT_IDS_JSON" | sed -n 's/^ *"\([^"]*\)": \([0-9]*\),\?$/- `\1`: \2/p')"

    cat > "$BASELINE_MD" <<EOF
# WA X Baseline Report (T00)

Auto-generated by \`tools/baseline/generate_baseline.sh\` — do not edit by hand.
Regenerate after every phase and compare against \`baseline.json\`.

WA X is a fork/continuation of Dev4Mod/WaEnhancer. Current developer/maintainer: Alaa · Telegram: https://t.me/Alaa91h · Community: https://t.me/WAXposed · Email: alahus2591@gmail.com · Voluntary support: https://ko-fi.com/alaa91h

- Generated: $GENERATED_AT
- Module: $MODULE_VERSION (code $MODULE_VERSION_CODE)
- Git: \`$GIT_COMMIT\` on \`$GIT_BRANCH\` (dirty worktree: $GIT_DIRTY)

## Key metrics

| Metric | Value |
|---|---|
| Features registered in FeatureLoader | $FEATURE_COUNT |
| Unit test files / methods | $TEST_FILES / $TEST_COUNT |
| Unit tests executed (last run) | $TR_REPORT |
| Kotlin files (main / xposed / features) | $KT_MAIN / $KT_XPOSED / $KT_FEATURES |
| Java files remaining | $JAVA_FILES |
| Unobfuscator lines | $UNOBF_LINES |
| Unobfuscator load resolvers | $UNOBF_LOAD_FUNCS |
| Unobfuscator \`!!\` assertions | $UNOBF_BANGS |
| Unobfuscator throw statements | $UNOBF_THROWS |
| lint-baseline issues | $LINT_TOTAL |
| compileSdk / targetSdk / minSdk | ${COMPILE_SDK:-?} / ${TARGET_SDK:-?} / ${MIN_SDK:-?} |

## APK sizes

$APK_SECTION

## lint baseline breakdown

$LINT_TABLE

## Supported WhatsApp versions

- WhatsApp: ${VERSIONS_WPP//$'\n'/, }
- Business: ${VERSIONS_BUSINESS//$'\n'/, }

## Runtime load times

$RUNTIME_SECTION

## Feature list ($FEATURE_COUNT)

$(printf '%s\n' "$FEATURES_RAW" | awk 'NF {printf "%s%s", (n++ ? ", " : ""), $0} END {printf "\n"}')
EOF
fi

# -------------------------------------------------------------- validate ---

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
    python3 -m json.tool "$BASELINE_JSON" > /dev/null && echo "JSON valid (python3)"
elif command -v node >/dev/null 2>&1; then
    node -e "JSON.parse(require('fs').readFileSync(process.argv[1], 'utf8'))" "$BASELINE_JSON" && echo "JSON valid (node)"
else
    echo "warning: neither python3 nor node available; skipped JSON validation" >&2
fi

# ----------------------------------------------------------------- summary ---

echo ""
echo "WA X baseline ($MODULE_VERSION @ $GIT_COMMIT)"
echo "  features:        $FEATURE_COUNT"
echo "  tests:           $TEST_COUNT methods in $TEST_FILES files"
echo "  test results:    $TR_REPORT"
echo "  unobfuscator:    $UNOBF_LINES lines, $UNOBF_LOAD_FUNCS load resolvers, $UNOBF_BANGS '!!'"
echo "  lint baseline:   $LINT_TOTAL issues"
echo "  APKs:            ${#APK_FILES[@]}"
echo "  runtime source:  $RUNTIME_SOURCE"
echo ""
echo "  wrote: $BASELINE_JSON"
if [[ "$WRITE_REPORT" -eq 1 ]]; then
    echo "  wrote: $BASELINE_MD"
fi
