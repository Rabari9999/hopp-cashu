# Store screenshots

The screenshots F-Droid and Zapstore show (`fastlane/metadata/android/*/images/`),
made on the emulators of the device check (`../README.md`) with the beta
build, English and German.

1. Phone (`hopp_phone`, with a window) and tablet (`hopp_tablet`): a clean
   status bar (demo mode: 9:21, full battery, Wi-Fi only) and no stylus
   handwriting in Gboard:
   ```bash
   adb -s emulator-5554 shell settings put global sysui_demo_allowed 1
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command enter
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0921
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command network -e mobile hide
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4 -e fully true
   adb -s emulator-5554 shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false
   adb -s emulator-5554 shell settings put secure stylus_handwriting_enabled 0
   ```
2. `setup.yaml` — Nodesignal and the shows its podroll recommends, one
   episode begun (English UI):
   `maestro --device emulator-5554 test -e APP_ID=space.sovereigncast.hopp.beta devicetests/store/setup.yaml`
3. Phone only: connect a wallet by hand (Settings → Wallet), one with little
   in it. The flow opens the boost dialog but never sends it; afterwards
   clear the app (`adb shell pm clear space.sovereigncast.hopp.beta`).
4. `shots.yaml` once per language — `adb shell cmd locale set-app-locales
   space.sovereigncast.hopp.beta --locales de-DE` (or `en-US`) switches the
   app, give it a few seconds to restart:
   `maestro --device emulator-5554 test -e APP_ID=space.sovereigncast.hopp.beta -e WALLET=yes -e MESSAGE="Great episode, thank you!" --test-output-dir build/store-shots/raw-phone-en devicetests/store/shots.yaml`
   (`WALLET=no` on the tablet; German message "Super Folge, danke!").
5. Look at every picture — Discover and the comments are live data — then
   scale and copy them: phone to 2020 px high, tablet to 1920 px wide, JPEG
   at 80 (`sips -Z … -s format jpeg -s formatOptions 80`). The phone gets
   player, boost, home, comments, show, community, discover, library,
   privacy; the tablet player, comments, home, library (the other pages
   leave half a tablet empty).

The comments come from a V4V show with Fountain listeners (Chad and Reeds
Podcast), subscribed for the picture and unsubscribed again; a bot that
repeats every boost with a long link is muted, without an identity only on
the emulator. Nothing is published.
