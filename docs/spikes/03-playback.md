# Spike 3 — playback with chapters on Android

- **Date:** 2026-10-07 — device test on a Pixel 9: all functions work
- **Question (ADR 0003, 0012, 0019):** does a Media3 player behind the shared
  `PlaybackEngine` interface give background playback, lock-screen and headset
  control, and does the chapter control of ADR 0019 work in practice?

## What was built

- `platform/PlaybackEngine` (common): load, play, pause, seek, speed, a state
  flow. `PositionStore` for resuming. `AppInfo.userAgent` (ADR 0015).
- Android: `PlaybackService` (MediaSessionService + ExoPlayer: audio focus,
  pause on headphones unplugged, network wake lock, Hopp's User-Agent on the
  audio requests) and `AndroidPlaybackEngine` (MediaController).
- `feed/Chapters`: JSON chapters parser and `ChapterIndex` — current chapter,
  next/previous (back within 3 s goes to the previous chapter), remaining time,
  boundaries for the progress bar; `toc: false` chapters change the image but
  are no stops.
- Spike UI (common Compose): episode list from the live Nodesignal feed and the
  now-playing screen of ADR 0019 — cover/chapter image with the chapter label
  `‹ ›`, swipe on the cover, long-press on −15/+30, progress bar with chapter
  gaps, speed, value bar (not wired), chapter sheet. Resumes at the last
  position.

## Device checklist (Pixel 9)

- [ ] Episode list loads; an episode with chapters plays
- [ ] Chapter label: ‹ › jump, middle opens the list, remaining time counts down
- [ ] Swipe left/right on the cover changes the chapter
- [ ] Long-press −15 / +30 jumps by chapter
- [ ] Progress bar: gaps at chapter starts, tap and drag seek
- [ ] Speed cycles 1 → 1,2 → 1,5 → 1,8 → 2 → 0,8
- [ ] Screen off / app in background: keeps playing; notification and lock screen controls work
- [ ] Bluetooth / headset play-pause; unplugging headphones pauses
- [ ] Phone call or other audio: pauses and resumes (audio focus)
- [ ] Close and reopen the app: same episode, same position

## Device test feedback (Pixel 9, 2026-10-07)

All checklist items work. Two look-and-feel findings, fixed:

1. Now playing could only be closed with the button, not swiped down; and
   opening the playing episode again reloaded it from the last *saved*
   position (saved every 5 s), so it jumped back a few seconds. Now: swipe
   down / back minimises to a mini player, and the loaded episode is never
   reloaded.
2. The progress bar's track had 1.09:1 contrast (1.25:1 dark), so the chapter
   gaps were barely visible. Now a dedicated `progressTrack` colour with
   ≥ 3:1, a 6 dp track and 3 dp gaps (ADR 0019).
