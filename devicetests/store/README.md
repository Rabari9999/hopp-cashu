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
2. `setup.yaml` — Nodesignal and the shows its podroll recommends, each by
   its feed address (`subscribe.yaml`: since 0.4.2 Discover's search shows
   the library's hits above the directories, and the shows' notes name each
   other), and Nodesignal's own playback (1.3×, intro 30 s) (English UI):
   `maestro --device emulator-5554 test -e APP_ID=space.sovereigncast.hopp.beta devicetests/store/setup.yaml`
   Then `listen.yaml` plays an episode of three shows in turn — each show opened through
   Discover's search (its row "In your library") and the episode through the show's own (since 0.4.0 the library's playlist and
   downloads name the shows above them), for the
   stats and the playlist — Nodesignal's E298 last, the player's
   picture; each left playing a few minutes, then paused with
   `adb shell input keyevent KEYCODE_MEDIA_PAUSE`:
   ```bash
   maestro … -e SHOW="Sound Money Bitcoin Podcast" -e EPISODE="#350 .*" -e QUERY="#350" devicetests/store/listen.yaml       # 5 min
   maestro … -e SHOW="Münzweg - der Bitcoin-Podcast" -e EPISODE="Münzweg #[0-9]+.*" -e QUERY="Münzweg #" devicetests/store/listen.yaml  # 6 min
   maestro … -e SHOW="Nodesignal - Deine Bitcoin-Frequenz" -e EPISODE="Nodesignal-Talk - E298.*" -e QUERY="E298" devicetests/store/listen.yaml  # 10 min
   ```
3. `shots.yaml` once per language and device — `adb shell cmd locale set-app-locales
   space.sovereigncast.hopp.beta --locales de-DE` (or `en-US`) switches the
   app, give it a few seconds to restart:
   `maestro --device emulator-5554 test -e APP_ID=space.sovereigncast.hopp.beta -e WALLET=no -e MESSAGE="Great episode, thank you!" --test-output-dir build/store-shots/raw-phone-en devicetests/store/shots.yaml`
   (German message "Super Folge, danke!"). Since 0.4.0 no boost picture
   (maintainer 2026-11-05): `WALLET=yes` takes one, with a wallet connected
   by hand first — one with little in it; the dialog is never sent.
   An emulator in a morning time zone greets "Good morning"
   (`adb shell cmd alarm set-timezone Asia/Tokyo` late in the evening); its
   clock stays right, as the Podcast Index checks it.
4. Look at every picture — Discover and the comments are live data — then
   scale and copy them: phone to 2020 px high, tablet to 1920 px wide, JPEG
   at 80 (`sips -Z … -s format jpeg -s formatOptions 80`). Six of them, the
   same on both and in the same order (maintainer 2026-11-05): 1-home,
   2-player, 3-comments (from 9-comments), 4-library, 5-discover,
   6-privacy — the stores show the phone's first, then the tablet's;
   `zapstore.yaml` lists them in that order.

The comments come from a V4V show with Fountain listeners (Chad and Reeds
Podcast), subscribed for the picture and unsubscribed again; a bot that
repeats every boost with a long link is muted, without an identity only on
the emulator. Nothing is published.
