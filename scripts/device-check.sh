#!/usr/bin/env bash
# Device check: Hopp on an emulated Android phone and tablet and a simulated
# iPhone and iPad, each upright and on its side, driven through the flows in
# devicetests/flows (Maestro), with a screenshot of every step worth looking
# at. Local only, on the Mac — the CI runner has neither emulator nor
# simulator. See devicetests/README.md.
#
#   scripts/device-check.sh                  all four devices, both orientations
#   scripts/device-check.sh phone iphone     some of them (phone tablet iphone ipad)
#   BUILD=1 scripts/device-check.sh          build the apps first
#
# Results: build/device-check/<time>/index.html — every screenshot side by side.
set -uo pipefail
cd "$(dirname "$0")/.."

if [[ $# -gt 0 ]]; then DEVICES=("$@"); else DEVICES=(phone tablet iphone ipad); fi
ANDROID_APP=space.sovereigncast.hopp.beta
IOS_APP=space.sovereigncast.hopp
IOS_BUILD=build/ios-sim
IPHONE="iPhone 17"
IPAD="iPad Air 11-inch (M4)"
EMU="$ANDROID_HOME/emulator/emulator"
OUT="build/device-check/$(date +%Y%m%d-%H%M)"
mkdir -p "$OUT"
export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

wants() { for d in "${DEVICES[@]}"; do [[ $d == "$1" ]] && return 0; done; return 1; }

# ── builds ────────────────────────────────────────────────────────────────
if [[ "${BUILD:-0}" == 1 ]]; then
    if wants phone || wants tablet; then ./gradlew --no-daemon -q :androidApp:assembleFossBeta || exit 1; fi
    if wants iphone || wants ipad; then
        # The simulator wants arm64 only: a generic destination asks for x86_64 too,
        # which the Kotlin framework is not built for.
        xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
            -sdk iphonesimulator -destination "platform=iOS Simulator,name=$IPHONE" \
            ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO \
            -derivedDataPath "$IOS_BUILD" build > "$OUT/ios-build.log" 2>&1 || { echo "iOS build failed: $OUT/ios-build.log" >&2; exit 1; }
    fi
fi
APK=$(ls -t androidApp/build/outputs/apk/foss/beta/*.apk 2>/dev/null | head -1)
IOS_APP_PATH="$IOS_BUILD/Build/Products/Debug-iphonesimulator/Hopp.app"
if (wants phone || wants tablet) && [[ -z "$APK" ]]; then echo "no beta APK — run with BUILD=1" >&2; exit 1; fi
if (wants iphone || wants ipad) && [[ ! -d "$IOS_APP_PATH" ]]; then echo "no simulator app — run with BUILD=1" >&2; exit 1; fi

# ── Android ───────────────────────────────────────────────────────────────
serial_of() { [[ $1 == phone ]] && echo emulator-5554 || echo emulator-5556; }
emulators=()

boot_android() {
    local name=$1 serial; serial=$(serial_of "$1")
    if ! adb devices | grep -q "^$serial"; then
        "$EMU" -avd "hopp_$name" -no-window -no-audio -no-boot-anim -no-snapshot-save \
            -port "${serial#emulator-}" > "$OUT/emulator-$name.log" 2>&1 &
        emulators+=("$serial")
    fi
    adb -s "$serial" wait-for-device
    for _ in $(seq 1 120); do
        [[ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]] && break
        sleep 2
    done
    adb -s "$serial" install -r "$APK" > /dev/null || { echo "install failed on $name" >&2; return 1; }
}

# 0 is the device's natural way round: upright for the phone, on its side for the tablet.
rotate_android() { adb -s "$1" shell settings put system accelerometer_rotation 0; adb -s "$1" shell settings put system user_rotation "$2"; sleep 2; }

# ── iOS ───────────────────────────────────────────────────────────────────
simulators=()
udid_of() { xcrun simctl list devices available | grep -F "    $1 (" | grep -o '[0-9A-F-]\{36\}' | head -1; }

boot_ios() {
    local model=$1 udid; udid=$(udid_of "$model")
    [[ -n "$udid" ]] || { echo "no simulator \"$model\"" >&2; return 1; }
    if ! xcrun simctl list devices | grep "$udid" | grep -q Booted; then
        xcrun simctl boot "$udid"; simulators+=("$udid")
    fi
    xcrun simctl bootstatus "$udid" -b > /dev/null
    # The flows read the English labels; a simulator follows the Mac's language.
    if ! xcrun simctl spawn "$udid" defaults read -g AppleLanguages 2>/dev/null | head -2 | grep -q '"\?en'; then
        xcrun simctl spawn "$udid" defaults write -g AppleLanguages -array en
        xcrun simctl spawn "$udid" defaults write -g AppleLocale en_US
        xcrun simctl shutdown "$udid"; xcrun simctl boot "$udid"; xcrun simctl bootstatus "$udid" -b > /dev/null
    fi
    xcrun simctl install "$udid" "$IOS_APP_PATH" || { echo "install failed on $model" >&2; return 1; }
    echo "$udid" > "$OUT/.udid"
}

rotate_maestro() {
    printf 'appId: %s\n---\n- setOrientation: %s\n' "$IOS_APP" "$2" > "$OUT/.rotate.yaml"
    maestro --device "$1" test "$OUT/.rotate.yaml" > /dev/null 2>&1
}

# ── the runs ──────────────────────────────────────────────────────────────
failed=0
suite() { # device id, app id, run name
    echo "→ $3"
    maestro --device "$1" test -e APP_ID="$2" --test-output-dir "$OUT/raw/$3" devicetests/flows > "$OUT/$3.log" 2>&1 || failed=1
    grep -E "^\[(Passed|Failed)\]" "$OUT/$3.log" | sed 's/^/   /'
    mkdir -p "$OUT/$3"
    find "$OUT/raw/$3" -path "*takeScreenshot*" -name "*.png" -exec cp {} "$OUT/$3/" \;
}

[[ -n "$APK" ]] && echo "→ $APK"
for name in "${DEVICES[@]}"; do
    case $name in
        phone|tablet)
            boot_android "$name" || { failed=1; continue; }
            serial=$(serial_of "$name")
            if [[ $name == phone ]]; then ways=("portrait 0" "landscape 1"); else ways=("landscape 0" "portrait 1"); fi
            for way in "${ways[@]}"; do
                read -r label rotation <<< "$way"
                rotate_android "$serial" "$rotation"
                suite "$serial" "$ANDROID_APP" "$name-$label"
            done
            rotate_android "$serial" 0 ;;
        iphone|ipad)
            [[ $name == iphone ]] && model=$IPHONE || model=$IPAD
            boot_ios "$model" || { failed=1; continue; }
            udid=$(cat "$OUT/.udid")
            for way in "portrait PORTRAIT" "landscape LANDSCAPE_LEFT"; do
                read -r label orientation <<< "$way"
                rotate_maestro "$udid" "$orientation"
                suite "$udid" "$IOS_APP" "$name-$label"
            done
            rotate_maestro "$udid" PORTRAIT ;;
        *) echo "unknown device $name (phone tablet iphone ipad)" >&2; failed=1 ;;
    esac
done

for serial in ${emulators[@]+"${emulators[@]}"}; do adb -s "$serial" emu kill > /dev/null 2>&1; done
for udid in ${simulators[@]+"${simulators[@]}"}; do xcrun simctl shutdown "$udid" > /dev/null 2>&1; done

# One page to look through: each screen, every device and orientation beside each other.
{
    echo '<!doctype html><meta charset="utf-8"><title>Hopp device check</title>'
    echo '<style>body{font:14px system-ui;margin:16px;background:#f5f4f0}h2{margin:24px 0 8px}'
    echo '.row{display:flex;gap:12px;align-items:flex-start;overflow-x:auto}figure{margin:0}'
    echo 'img{max-height:520px;border:1px solid #ccc;border-radius:8px}figcaption{color:#666}</style>'
    echo "<h1>Hopp device check · $(basename "$OUT")</h1>"
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
