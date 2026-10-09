# Spike 5 — minimal iOS (ADR 0018)

- **Question:** does the shared core compile for iOS, and does an AVPlayer
  engine play an episode behind the same `PlaybackEngine` as Android?
- **Status:** Kotlin side compiles for `iosArm64` and `iosSimulatorArm64` on
  the Linux build box (and in CI). **Runs on the maintainer's Mac** (iPhone 17
  simulator, 2026-10-09): app starts and is fully usable; playback, resume at
  the saved position and chapter jumps work. **Open:** no Now Playing entry on
  the lock screen or in Control Center in the simulator, even with
  `beginReceivingRemoteControlEvents` — the simulator is unreliable here;
  check on a real iPhone when iOS work starts (after phase 2, ADR 0018).

Met on the way: `xcode-select` pointed at the Command Line Tools (Kotlin/Native
needs full Xcode: `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`),
and the static framework needs `-lsqlite3` in the app target for SQLDelight's
native driver.

## Run it on a Mac

1. Xcode from the App Store; open it once, accept the licence, install an iOS
   simulator runtime (Settings → Components).
2. JDK 21 (`brew install --cask temurin@21`).
3. Android SDK — Gradle configures the Android targets even for an iOS build.
   Easiest: Android Studio, then `local.properties` in the repo root:
   `sdk.dir=/Users/<you>/Library/Android/sdk`.
4. `git clone git@gitlab.com:nodesignal/hopp.git && cd hopp`
5. Quick check in the terminal: `./gradlew :shared:linkDebugFrameworkIosSimulatorArm64`
   (first run downloads Kotlin/Native for macOS: 10–20 min).
6. `open iosApp/iosApp.xcodeproj`, pick an iPhone simulator, Run (⌘R). The
   Xcode build phase calls Gradle (`embedAndSignAppleFrameworkForXcode`).
7. On a real iPhone: set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig`
   (a free personal team works; the app then runs 7 days).

## Checklist

- [ ] Episode list loads (Ktor/Darwin, xmlutil, Coil on iOS)
- [ ] An episode with chapters plays; chapter label, swipe, long-press, progress gaps
- [ ] Speed works; position is kept after closing and reopening the app
- [ ] Home / lock: audio keeps playing; lock screen and Control Center show
      title and work (play/pause, ±15/30 s, scrubbing)
- [ ] AirPods / headphones play-pause
- [ ] Swipe down minimises to the mini player; tap reopens without a jump
