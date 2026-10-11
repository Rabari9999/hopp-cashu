# ADR 0014 — Streaming sats: accrual and settlement

- **Status:** accepted
- **Date:** 2026-10-07
- **Refines:** ADR 0005 (wallet), ADR 0013 (`stream_ledger`)

## Context

Streaming sats are owed while someone listens, but cannot always be paid at that
moment: no network, a wallet that does not answer, amounts below one sat. ADR 0013
had a single `stream_ledger` of amounts per recipient. That fits Lightning value
splits, but not the ecash rails Sovereigncast already receives
(`CASHU_STREAMING.md` on the `demo/spilman-hackday` branch of
`nodesignal/podcasting_tools`, and the web player on the same branch of
`nodesignal/nodesignal-cashu-player`):

| Rail | Principle | Mint contacts | Offline |
|---|---|---|---|
| A: nutzaps (NIP-61) | one token per interval over Nostr relays | one per payment | relays hold it while the instance is down — but it is a public, minute-by-minute record of who listened to what |
| B: NUT-18 over HTTP | one token per interval, POSTed to the instance | one per payment | the instance must be reachable; private |
| C: Spilman channel | open once, then the listener only **signs a rising cumulative balance**, verified by the instance without the mint | **0** between open and close (measured: 5 and 8 payments) | see below |

Spilman's cumulative signature fits offline listening exactly: the newest
signature supersedes all earlier ones, so minutes listened offline need no list
of individual amounts — one tick with the new total settles them, which also
spares the prototype's limit of about 40 payments per channel (16 KB header).

State of the prototype (2026-10): the spec is an open proposal (cashubtc/nuts
PR #296, no NUT number); `cdk-spilman` is built from source with PyO3 and WASM
bindings; the server keeps channels in memory (the library's SQLite storage is
broken); NUT-09 recovery is missing; the player's channels expire after 2 hours.
A channel pays **the show's instance**, not the `podcast:value` recipients — no
split happens.

## Decision

### Two layers: accrual and settlement

1. **Accrual — independent of the rail, always offline-capable.** Listened
   seconds per episode and show, counted on **media time**: what was actually
   heard; seeks do not count (as the web player's `listening-ticker`), and at 2×
   a wall-clock minute accrues two minutes of content. Converted with the
   listener's rate.
2. **Settlement — per rail:**
   - **Lightning via NWC (phase 1):** per `podcast:value` recipient (including
     value-time-splits), batched, paid once the network is back.
   - **Spilman channel (phase 3):** one cumulative balance per channel; after an
     offline period a single tick.
   - **NUT-18 / nutzap (phase 3):** accrued tokens batched every few minutes, as
     `CASHU_STREAMING.md` recommends; NUT-18 preferred for privacy.

The data model gets an `accrual` table (episode, show, listened media seconds,
rate, accrued msats) and a `settlement` table (rail, target — recipient or
channel —, amount, state, attempts), replacing ADR 0013's single `stream_ledger`.

**Amended 2026-10-11 (device feedback: payments looked random):** the
Lightning batch runs every 5 minutes on a timer of the app, not of the
listening, and pays each recipient only whole sats — parts of one stay owed
until a whole sat has come together, so a 2 % share at 10 sats/min goes out
about every 5 minutes, a 90 % one every batch. The value bar now says what was
sent today and what is set aside; a tap opens a sheet with the next batch's
time, the rule in plain words, what this episode has set aside per recipient
(owed, or heard and not yet split) and a button that pays the whole sats now.

### Paying what accrued offline

**Automatically, within the budget**, once the network is back — followed by a
notice of what went to which episode (also in the boost log of ADR 0013). What
exceeds the budget is not paid and is shown as such.

### Rail priority

When a show offers both Lightning value splits and an ecash channel: **Lightning
splits first** — they honour the podcaster's split. Spilman only when no
Lightning wallet is connected (built-in Cashu wallet, phase 3). The app states
plainly that the ecash channel pays the show's instance as a whole, not the
individual recipients.

### Spilman channel lifetime

**3 days by default, adjustable.** Covers a weekend or a journey offline. The app
closes the channel cooperatively when its capacity is used up or after 24 hours
without listening. If a cumulative balance could not be delivered before expiry,
the listener reclaims the funds through the refund path — the podcaster receives
nothing for that span, and the app says so instead of hiding it.

### Sovereigncast side

Spilman joins the **Listener API v1** as additive endpoints under
`/api/listener/v1/shows/{guid}/…` with a capability `ecash_channel` (the
contract's `Capabilities` already allows extra keys) — not under the prototype's
own `/channel/*` paths. Show-scoping, the error shape and discovery then come for
free, and Hopp needs no second discovery mechanism. Merging the hackday branch is
a separate decision, once the library has persistent storage and NUT-09 recovery.

## Consequences

- Phase 1 builds accrual for every rail and settlement for Lightning only; the
  ecash rails plug into the settlement layer in phase 3 without touching accrual.
- The playback engine reports media-time progress and seeks (ADR 0003 interface).
- Hopp needs Kotlin and Swift bindings for `cdk-spilman` (UniFFI) in phase 3 —
  together with the F-Droid build-from-source question of ADR 0011.
