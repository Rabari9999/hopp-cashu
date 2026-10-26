# ADR 0023 — Listening statistics

- **Status:** accepted
- **Date:** 2026-10-26

## Context

The maintainer asked for listening statistics in the listener's profile, after
Pocket Casts': time listened, the favourite shows, and — Hopp's own — what was
given to each. Until then Hopp kept only each episode's position; the time
heard was counted nowhere but in the streaming accruals, and only with
streaming on. The maintainer also asked for the statistics to follow the
listener from device to device, as everything else does with sync.

## Decision

1. **Counted as it plays.** The player already measures, every report of the
   engine, the media time heard since the last one, a jump left out (ADR 0014).
   From 0.3.1 it also takes the clock's time for it. Per day, per month and
   show, and per episode, both are added up: the clock's time is the time
   listened, the media time beyond it the time saved by speed and skipped
   silence; a show's skipped start and end (ADR 0012) count as saved too.
   Chapters jumped to, transcripts opened and comments posted on Nostr are
   counted per month. The sats given come from the payment log, per month and
   show, split into boosts, streaming and zaps.
2. **Per device.** Each install has a random name, kept with the secrets —
   which no Android backup takes along, so a phone set up from another's
   backup counts as a device of its own instead of overwriting the other's
   counts. A device writes only its own counts.
3. **Synced as `hopp/stats`** (ADR 0021, stage 5): days kept 400 days, an
   episode a year after it was last heard; the months per show stay. Per
   device about 50–80 KB, growing by some 30 KB a year — far inside a
   document's 16 parts of 60 KB. What another device sends is taken only in
   Hopp's shape and bounds. Noted at most every 30 minutes while listening,
   on a pause after 5, and when the app goes to the background — it rides
   with the rounds that go anyway.
4. **Before 0.3.1, an estimate:** the length of the episodes played to their
   end by listening before the first day counted — the same on every device,
   since their progress is synced — shown apart and marked as estimated.
5. **The page** sits in the settings beside the wallet and the payment history,
   and is linked from the Nostr profile: this month, this year, all time; the
   time and its bars, the time saved, the top shows and episodes, Value for
   Value per show (with sats per hour heard and how many of the shows heard
   were supported), Podcasting 2.0. It says that nothing of it leaves the
   listener's devices but encrypted to their own key; "Share" hands a line of
   text to the system's share sheet.

## Consequences

- A database migration (version 16) with four tables; the payment log and the
  episodes are only read.
- Statistics start empty on the update; the estimate fills the time before.
- The counts are the listener's own word and Hopp's: nothing checks them, and
  nothing needs to — they never leave the listener's keys.
- Not counted (yet): the time of day, the people reached through the splits,
  songs paid in episodes — each would need more than the payment log keeps.
