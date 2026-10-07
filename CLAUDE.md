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
./gradlew :androidApp:assembleFossDebug           # or assemblePlayDebug
```

Needs JDK 21 and the Android SDK (`local.properties`: `sdk.dir=…`). iOS targets
compile only on macOS; on Linux Gradle skips them. The build box is small and
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
