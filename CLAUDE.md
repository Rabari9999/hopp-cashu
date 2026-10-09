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

Menus and bottom sheets are popups: a Robolectric test only finds them with
`@GraphicsMode(NATIVE)` — without it the click lands and nothing opens. And
give the test a phone's screen (`qualifiers = "en-w411dp-h891dp"`): on
Robolectric's default 320×470 dp the value bar of now playing lies below the
edge, and a tap there silently goes nowhere.

The database has a schema version. A change to a `CREATE TABLE` in a `.sq`
file needs the matching `migrations/<n>.sqm` too, and installed apps run it
on update; `DownloadsAndQueueTest.aVersionOneDatabaseIsMigrated` shows how to
check it against the old schema.

Search needs the Podcast Index key (ADR 0008), which is compiled in but not in
git: `podcastindex.key` / `podcastindex.secret` in `~/.gradle/gradle.properties`
on the build box, `PODCASTINDEX_KEY` / `PODCASTINDEX_SECRET` as masked CI
variables. Without them the app builds and Discover says search is
unavailable. `HOPP_LIVE_DIRECTORY=1 ./gradlew :shared:jvmTest --tests
'*DirectoryLiveTest*'` checks the real directories with it. Never print it.

Judge how Hopp *feels* only on the `beta` build (`assembleFossBeta`: release
code, debug key, installs over a debug build). A debug build is debuggable,
mostly interpreted and carries no baseline profile; Compose in it runs
several times slower and its animations stutter — that is not a bug to chase.
Two things that did make the app itself heavy (2026-10-08): a theme value
built in a composable without `remember` (a new Typography restyles and
re-lays out every text whenever the frame recomposes), and frame-level state
read in `App` (download progress) that recomposes the whole frame — collect
such state in the smallest composable that needs it.

The Sovereigncast layer (`sovereigncast/`, ADR 0002/0010/0018) speaks the
Listener API whose contract is `podcast_manager/api_specs/listener-v1.yaml` in
`nodesignal/podcasting_tools` — read it there, do not guess field names.
`HOPP_LIVE_SOVEREIGNCAST=1 ./gradlew :shared:jvmTest --tests '*SovereigncastLiveTest*'`
reads Nodesignal's real instance (read only). An invoice is paid only when it
asks for exactly the chosen amount; votes use one random voter id per install.

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
