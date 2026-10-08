# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

**Hopp** — Honest open podcast player. A native Podcasting 2.0 player for
Android + iOS (Kotlin Multiplatform + Compose Multiplatform), the listener-side
companion of Sovereigncast (`gitlab.com/nodesignal/podcasting_tools`).

**Status: phase 0 (ADR 0018) — project skeleton and spikes.** Read `docs/adr/`
first — the decisions there are settled and should not be re-litigated without
the maintainer asking. Spike results are in `docs/spikes/`.

## Build

```bash
./gradlew :shared:jvmTest                         # shared core tests (JVM)
./gradlew :androidApp:testFossDebugUnitTest       # the app on Android, no device (Robolectric)
./gradlew :androidApp:assembleFossDebug           # or assemblePlayDebug
```

`androidApp/src/test` drives the whole app through Android's own Compose,
lifecycle, main looper and SQLite — the layer where device-only bugs live.
The first round of device feedback (2026-10-08) was invisible to every
repository test: data and flows were right, the *navigation* was not (a page
pushed onto the start tab came back when that tab was tapped). Reproduce a
device report there before guessing. Tabs keep their own stacks; open a tab
only through `selectTab` in `App.kt`, never with a plain `navigate`.

Debug builds are signed with the checked-in `androidApp/debug.keystore`, so
each CI APK installs over the last one; release signing is `docs/signing.md`.

Needs JDK 21 and the Android SDK (`local.properties`: `sdk.dir=…`). The iOS
Kotlin code compiles on Linux too (`./gradlew :shared:compileKotlinIosSimulatorArm64`,
Apple klibs cross-compile); linking the framework and running the app need a
Mac with Xcode. K/N gotchas met so far: `AVAudioSession` lives in
`platform.AVFAudio`, and Objective-C category methods (`setActive`, AVPlayer's
`currentTime`, `seekToTime` …) are extensions that need an explicit import. The build box is small and
shared — run one Gradle build at a time and prefer `--no-daemon`: on
2026-10-07 a long-lived Gradle + Kotlin daemon pair stalled an APK build for
40 minutes that took 2.5 minutes without them.

## Conventions

- Documentation, ADRs, code comments and commit messages: **English**.
  Conversation with the maintainer: German.
- Each settled decision becomes a numbered ADR (`docs/adr/NNNN-slug.md`:
  Status, Date, Context, Decision, Consequences). Update
  `docs/open-questions.md` when a topic moves into an ADR.
- All organisational names are `hopp`; application/bundle ID and Kotlin package
  root are `space.sovereigncast.hopp` (ADR 0004).

## Related repositories

- `nodesignal/podcasting_tools` — Sovereigncast server; source of truth for the
  Listener API contract and the self-hosted feed (`podcast_manager/feed_builder.py`).
- `nodesignal/sovereigncast-space` — website on sovereigncast.space; hosts the
  `/open` deep-link fallback and the `.well-known` app-link files (ADR 0002).
