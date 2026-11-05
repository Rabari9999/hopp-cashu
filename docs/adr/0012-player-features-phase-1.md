# ADR 0012 — Player features in phase 1

- **Status:** accepted
- **Date:** 2026-10-07

## Context

ADRs 0001–0011 settle infrastructure: discovery, wallet, identity, push,
directories, the Listener API, distribution. This one settles what listeners use
every day in the first release.

## Decision

### Baseline (what any serious podcast player has)

- **Playback:** speed 0.5×–3×, remembered per show; skip intervals configurable
  (default 10 s back, 30 s forward); every episode keeps its position; an episode
  counts as played when it ends. *(Amended 2026-10-10: "played from 95 %"
  dropped by the maintainer — it makes no sense for how Hopp is used.)*
  *(Amended 2026-10-19, maintainer: a default speed in the settings counts for
  every show without one of its own; choosing the default for a show in the
  player makes it follow the default again.)*
- **Lock screen, notification, headset and Bluetooth controls:** chapter title
  and chapter image in the now-playing view; previous/next jump between chapters
  when the episode has them. *(Amended 2026-10-27, maintainer: "forward" on the
  car's steering wheel jumped to the next episode — an episode without chapters
  has no next one. The media keys' next and previous — headsets, Bluetooth, the
  steering wheel — now skip forward and back by the listener's seconds, as in
  AntennaPod; the next chapter is a button of its own in the notification, on
  the lock screen, in Android Auto and on the widget.)*
- **Chapters:** list with images, tap to seek, current chapter highlighted,
  chapter links tappable.
- **Transcript:** follows playback with the current cue highlighted, tap to
  seek, searchable.
- **Episode page:** persons with image and link, soundbites as previews,
  `podcast:funding` link.
- **Queue:** "play next" / "play last", reorderable, auto-advance.
  *(Amended 2026-10-19, maintainer: as in AntennaPod, a finished download goes
  to the end of the queue — not one already played or playing, and one already
  in it keeps its place; a setting under Downloads, on by default.)*
  *(Amended 2026-11-03, maintainer: a new episode goes to the end of the queue
  when a refresh finds it — on every device, the browser too, in step with
  sync — no longer when its download finishes, which only a phone saw. Out in
  the last seven days only; one sync already knows, in the queue or taken out
  of it elsewhere, is left alone, and it is noted as of its release, so a later
  change on another device wins. The same setting, under Downloads in the apps
  and under refresh in the browser, which downloads nothing.)*
- **Sleep timer:** minutes, end of episode, and **end of chapter**.
- **Value for value in the player:** boost button with amount presets and a
  message, a view of the split (who gets how much), streaming indicator with
  sats per minute and running total (streaming itself off by default, ADR 0005).

### Start screen: inbox + library

Two tabs: **New** — the new episodes of all subscriptions, newest first — and
**Library** — the subscribed shows. Most listeners ask "what is new?" rather
than "which show?".

**Amended 2026-10-08 (device feedback):** the first tab is **Home**, the
listener's own front page rather than a social feed: the newest of "New" with
a way to all of it, continue listening, recently played, recently boosted.
Comments over Nostr join it with the identity of phase 2 (ADR 0006). "New"
stays as a page behind Home; "continue listening" moved from the Library.

### Downloads: automatic on Wi-Fi

New episodes of subscribed shows download automatically on Wi-Fi only; switchable
per show; bounded by a storage limit (oldest unplayed downloads go first when it
is reached); played episodes are deleted after 24 hours. Manual download per
episode is always available. Streaming works without downloading.

### Extras

| Extra | Phase | Why |
|---|---|---|
| **Android Auto** | 1 | Falls out of the Media3 `MediaLibraryService` the lock-screen integration needs anyway. |
| **Skip silence** | 1 on Android | Built into ExoPlayer; on iOS it needs custom audio processing — later, if asked for. |
| **Volume boost** | 3 on Android (2026-10-21) | Android's `LoudnessEnhancer` on the player's audio session, +8 dB; AVPlayer cannot go above a file's own volume, so iOS needs the same custom audio processing as skip silence. |
| **Skip start and end per show** | 0.3.1 (2026-10-26) | AntennaPod's "skip intro / ending", much liked there. Seconds per show (0–600), with its speed in "Playback for this show" on the show's menu, synced with the subscription. The start only when an episode starts from the beginning (not from a chapter, time mark or clip; Android Auto too), the end only when listening crosses into it, not a jump; episodes too short for both plus two minutes stay whole. The player says what it skipped, the start with a way back. |
| **Home-screen widget** | 0.3.1 on Android (2026-10-26) | The maintainer asked for it ahead of phase 3, looking like the lock screen: cover, show, chapter, title, time left; back, play, forward, next chapter and the quick boost (with a wallet and an amount). One resizable widget, four cells wide: two high it is the whole player, one high title, play and boost. Jetpack Glance (AndroidX, Apache 2.0, no Play services — fine for F-Droid); the playback service tells it what changed, every half minute while playing; before anything played it shows the episode last heard. "Up next" and "today" widgets were offered and not wanted. iOS later (WidgetKit). |
| **CarPlay** | 3 | Needs Apple's CarPlay audio entitlement and its own template UI. |
| **Chromecast** | 3 | Needs Google Play Services — `play` flavor only. |

The maintainer gave no preference on the extras; this split follows effort and
value and is easy to revisit.

### Not in phase 1 (decided elsewhere)

Video via `alternateEnclosure` and cross-device sync (phase 2), live items
(phase 3), home-screen widgets (phase 3; the Android player widget came with 0.3.1).

**Amendment 2026-11-03 (Dennis, maintainer): one playlist.** "Continue listening" and
"Up next" are one list, the playlist: on top "Now" — the episode the player has, not part
of the queue and not movable — then the queue in the listener's order. Home, the library,
the playlist's own page (count, length, emptying it), now playing's sheet and Android
Auto's folder show the same. "Continue listening" is gone as a section; a saved Home
order puts the playlist where it was. On the update, what was begun and not in "Up next"
joined the playlist's top once, newest first, at most 20 (`QueueRepository.adoptInProgress`,
`Settings.playlistMerged`, per device; sync joins them). The rules stay: an interrupted
episode goes back to the top, the next one plays at the end, a heard one leaves, a removed
one keeps its progress ("Recently played").

**Amendment 2026-11-04 (maintainer): what an entry offers.** An entry after "Now" could
only be played and moved; to skip one, the listener had to open its own page. Swiped to the
left it now leaves the playlist, and "Undo" puts it back where it was; a long press — a
right-click with a mouse — opens a sheet: play next, play last, heard, the download, out of
the playlist, the episode's page. In the browser a ⋮ opens it too, where a mouse does not
swipe; a screen reader has "Remove" and "More" as actions of the row. "Now" has the same on
a long press, with "Skip": it stays where it is, the next one plays. The same everywhere the
playlist shows; the playlist page's ✕ is gone. Tests: `AndroidAppFlowTest`.

## Consequences

- The playback engine interface (ADR 0003) must expose chapter boundaries, so
  that the next-chapter button and the end-of-chapter sleep timer work the
  same on both platforms.
- The download scheduler needs Wi-Fi-only constraints and a storage budget on
  both platforms (WorkManager / background `URLSession`), and the feed refresh
  (topic 12) decides when new episodes are noticed.
- Android Auto requires a browsable media tree (New, Library, Queue, Downloads).
