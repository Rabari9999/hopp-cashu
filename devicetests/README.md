# Device check

Hopp on an emulated Android phone (Pixel 9) and tablet (Pixel Tablet) and a
simulated iPhone 17 and iPad Air 11", each upright and on its side, driven through the flows in `flows/` with
[Maestro](https://maestro.mobile.dev). Every flow takes screenshots of what is
worth looking at; `scripts/device-check.sh` puts them side by side in one page.

It finds what the Robolectric tests in `androidApp/src/test` cannot: real
playback and buffering, layout on real screen sizes (a button label cut short,
a list off the edge), text in the device's language, live data from the
Podcast Index and the relays.

```bash
scripts/device-check.sh                  # all four devices, both orientations (~30 min)
scripts/device-check.sh phone iphone     # some of them: phone tablet iphone ipad
BUILD=1 scripts/device-check.sh          # build the beta APK and the simulator app first
open build/device-check/*/index.html  # the newest result
```

## What it needs (once)

- Emulator and image: `sdkmanager "emulator" "system-images;android-36;google_apis;arm64-v8a"`
- The two devices, made with the SDK's own avdmanager
  (`$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager`; Homebrew's looks
  elsewhere): `hopp_phone` (`-d pixel_9`) and `hopp_tablet` (`-d pixel_tablet`).
- Maestro: `brew install mobile-dev-inc/tap/maestro`. The script turns its
  analytics off.
- iOS: Xcode with the iOS simulator runtime; after an Xcode update once
  `sudo xcodebuild -runFirstLaunch` (until then `simctl`, and Maestro with
  it, hang). The script sets each simulator to English and builds the app
  for it (arm64 only) with `BUILD=1`.

The script starts the emulators without a window, and the simulators, when
they are not running, and stops the ones it started. It runs on a Mac with Apple Silicon only: the
CI runner is Linux in Docker, without an emulator.

## The flows

| Flow | What it does |
| --- | --- |
| `01-tour` | First start with nothing kept: every page of the tour, then Home empty. |
| `02-discover` | Categories, new ⚡ Value shows and what is popular, live. |
| `03-subscribe-play` | Nodesignal found by search, subscribed, an episode streamed; the player, the speed, the comments (read only). |
| `04-library` | The library and Home with that show. |
| `05-settings` | The settings pages reachable without a wallet or an identity. |

They build on each other in that order (`flows/config.yaml`); `03` starts
clean through `common/fresh.yaml`. Nothing is ever published: the flows make
no Nostr identity and connect no wallet.

Labels are matched as the English UI shows them (the emulators run in
English). The flows take the app's id from `APP_ID` (the Android beta is
`space.sovereigncast.hopp.beta`, iOS `space.sovereigncast.hopp`); they go
back with the app's own arrow (`tapOn: "Back"`), as iOS has no back key.
Where iOS reports a control's description instead of its text (the speed),
the flows use the description. A flow fails where a label it waits for does not come; the log of
each run lies beside the screenshots.
