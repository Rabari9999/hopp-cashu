#!/usr/bin/env bash
# Device check: Hopp's beta build on an emulated phone and tablet, upright
# and on its side, driven through the flows in devicetests/flows (Maestro),
# with a screenshot of every step worth looking at. Local only — the CI
# runner has no emulator. See devicetests/README.md.
#
#   scripts/device-check.sh               both devices, both orientations
#   scripts/device-check.sh phone         one device
#   BUILD=1 scripts/device-check.sh       build the beta APK first
#
# Results: build/device-check/<time>/index.html — every screenshot side by side.
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ $# -gt 0 ]]; then DEVICES=("$@"); else DEVICES=(phone tablet); fi
APP=space.sovereigncast.hopp.beta
EMU="$ANDROID_HOME/emulator/emulator"
OUT="build/device-check/$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"

# Maestro looks for iOS simulators too; while Xcode waits for its first-launch
# install that hangs (2026-10-20), so it is kept from finding Xcode at all.
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

if [[ "${BUILD:-0}" == 1 ]]; then
    ./gradlew --no-daemon -q :androidApp:assembleFossBeta || exit 1
fi
APK=$(ls -t androidApp/build/outputs/apk/foss/beta/*.apk 2>/dev/null | head -1)
[[ -n "$APK" ]] || { echo "no beta APK — run with BUILD=1" >&2; exit 1; }
echo "→ $APK"

serial_of() { [[ $1 == phone ]] && echo emulator-5554 || echo emulator-5556; }
started=()

boot() {
    local name=$1 serial; serial=$(serial_of "$1")
    if ! adb devices | grep -q "^$serial"; then
        # The phone first: each emulator takes the next free port pair.
        "$EMU" -avd "hopp_$name" -no-window -no-audio -no-boot-anim -no-snapshot-save \
            -port "${serial#emulator-}" > "$OUT/emulator-$name.log" 2>&1 &
        started+=("$serial")
    fi
    adb -s "$serial" wait-for-device
    for _ in $(seq 1 120); do
        [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
        sleep 2
    done
    adb -s "$serial" install -r "$APK" > /dev/null || { echo "install failed on $name" >&2; return 1; }
}

# 0 is the device's natural way round: upright for the phone, on its side for the tablet.
rotate() { adb -s "$1" shell settings put system accelerometer_rotation 0; adb -s "$1" shell settings put system user_rotation "$2"; sleep 2; }

failed=0
for name in "${DEVICES[@]}"; do
    boot "$name" || { failed=1; continue; }
    serial=$(serial_of "$name")
    if [[ $name == phone ]]; then ways=("portrait 0" "landscape 1"); else ways=("landscape 0" "portrait 1"); fi
    for way in "${ways[@]}"; do
        read -r label rotation <<< "$way"
        run="$name-$label"
        echo "→ $run"
        rotate "$serial" "$rotation"
        maestro --device "$serial" test --test-output-dir "$OUT/raw/$run" devicetests/flows > "$OUT/$run.log" 2>&1 || failed=1
        grep -E "^\[(Passed|Failed)\]" "$OUT/$run.log" | sed 's/^/   /'
        mkdir -p "$OUT/$run"
        find "$OUT/raw/$run" -path "*takeScreenshot*" -name "*.png" -exec cp {} "$OUT/$run/" \;
    done
    rotate "$serial" 0
done

for serial in ${started[@]+"${started[@]}"}; do adb -s "$serial" emu kill > /dev/null 2>&1; done

# One page to look through: each screen, every device and orientation beside each other.
{
    echo '<!doctype html><meta charset="utf-8"><title>Hopp device check</title>'
    echo '<style>body{font:14px system-ui;margin:16px;background:#f5f4f0}h2{margin:24px 0 8px}'
    echo '.row{display:flex;gap:12px;align-items:flex-start;overflow-x:auto}figure{margin:0}'
    echo 'img{max-height:520px;border:1px solid #ccc;border-radius:8px}figcaption{color:#666}</style>'
    echo "<h1>Hopp device check · $(basename "$OUT") · $(basename "$APK")</h1>"
    for shot in $(find "$OUT"/*-*/ -name "*.png" -exec basename {} \; 2>/dev/null | sort -u); do
        echo "<h2>${shot%.png}</h2><div class=row>"
        for dir in "$OUT"/*-*/; do
            [[ -f "$dir$shot" ]] && echo "<figure><img src=\"$(basename "$dir")/$shot\" loading=lazy><figcaption>$(basename "$dir")</figcaption></figure>"
        done
        echo "</div>"
    done
} > "$OUT/index.html"

echo "→ $OUT/index.html"
exit $failed
