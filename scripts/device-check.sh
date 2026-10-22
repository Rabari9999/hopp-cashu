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
    if wants phone || wants tablet; then ./gradlew --no-daemon -q :androidApp:assembleFossBeta -Phopp.beta.deviceCheck=true || exit 1; fi
    if wants iphone || wants ipad; then
        # The simulator wants arm64 only: a generic destination asks for x86_64 too,
        # which the Kotlin framework is not built for. Signed ad hoc, not unsigned: an
        # unsigned app gets no Keychain, and the Nostr key would last only while it runs.
        xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Debug \
            -sdk iphonesimulator -destination "platform=iOS Simulator,name=$IPHONE" \
            ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGN_IDENTITY=- CODE_SIGN_STYLE=Manual DEVELOPMENT_TEAM= PROVISIONING_PROFILE_SPECIFIER= \
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

# ── sync between two devices (ADR 0021 stage 5) ───────────────────────────
# On what the phone's runs left: listening habits, a favourite and "Up next"
# added, a Nostr key made for the test and sync on; the key's backup read from
# the screen; Hopp installed anew on the iPhone, restored from Nostr with that
# key and checked; two changes there found on the phone again. The only run
# that publishes: a throwaway key's profile, relay list and sync documents.
# SYNC=0 leaves it out.
sync_step() { # device id, app id, flow, run name, extra env…
    local device=$1 app=$2 flow=$3 name=$4; shift 4
    maestro --device "$device" test -e APP_ID="$app" "$@" --test-output-dir "$OUT/raw/$name" "devicetests/sync/$flow" > "$OUT/$name.log" 2>&1 || { failed=1; echo "   [Failed] $name — $OUT/$name.log"; return 1; }
    echo "   [Passed] $name"
    mkdir -p "$OUT/sync"
    find "$OUT/raw/$name" -path "*takeScreenshot*" -name "*.png" -exec cp {} "$OUT/sync/" \;
}
if [[ "${SYNC:-1}" == 1 ]] && wants phone && wants iphone; then
    echo "→ sync phone → iphone → phone"
    phone=$(serial_of phone); iphone=$(udid_of "$IPHONE")
    password="hopp-device-check-$RANDOM"
    rotate_android "$phone" 0
    if sync_step "$phone" "$ANDROID_APP" 1-source.yaml sync-1-source -e PASSWORD="$password"; then
        # The backup on screen, read from the view hierarchy — kept with this run only.
        key=$(maestro --device "$phone" hierarchy 2>/dev/null | grep -o 'ncryptsec1[02-9ac-hj-np-z]\{20,\}' | head -1)
        if [[ -z "$key" ]]; then echo "   no key backup on the phone's screen" >&2; failed=1
        else
            printf '%s\n%s\n' "$key" "$password" > "$OUT/sync/test-key.txt"
            # The second device as after a fresh install — the Keychain too, which outlives
            # deleting the app and would bring a key from an earlier run back.
            xcrun simctl uninstall "$iphone" "$IOS_APP" > /dev/null 2>&1
            xcrun simctl keychain "$iphone" reset
            xcrun simctl install "$iphone" "$IOS_APP_PATH"
            rotate_maestro "$iphone" PORTRAIT
            sync_step "$iphone" "$IOS_APP" 2-restore.yaml sync-2-restore -e KEY="$key" -e PASSWORD="$password" \
                && sync_step "$phone" "$ANDROID_APP" 3-back.yaml sync-3-back
        fi
    fi
fi

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
    # Sync: the steps in order, phone and iPhone alternating.
    if ls "$OUT"/sync/*.png > /dev/null 2>&1; then
        echo "<h2>sync phone → iphone → phone</h2><div class=row>"
        for f in "$OUT"/sync/*.png; do echo "<figure><img src=\"sync/$(basename "$f")\" loading=lazy><figcaption>$(basename "$f" .png)</figcaption></figure>"; done
        echo "</div>"
    fi
} > "$OUT/index.html"

echo "→ $OUT/index.html"
exit $failed
