# Task — Sync across devices over Nostr (ADR 0021, stage 5)

- **Status:** planned 2026-10-20 — before push (maintainer, same day)
- **ADR:** 0021 §5 (sync), 0006 (identity), 0015 (privacy), 0013 (offline)

## Why

A listener with two devices — a phone and a tablet, or later an iPhone —
keeps subscriptions, progress, favourites and "Up next" by hand today, or
through OPML. With a Nostr identity Hopp can keep them in step without an
account and without a server of its own: encrypted documents on the
listener's relays that only their key opens.

## Decided (maintainer, 2026-10-20)

- **What travels:** subscriptions (with the show's own speed and
  auto-download), progress (position, played, last played), **favourites**
  and **"Up next"**, and the **listening habits** among the settings: skip
  back and forward, the default speed, skip silence, the boost name, the
  quick-boost amount, Home's order, hidden and folded sections, the
  library's order and view.
- **Never:** the NWC connection, keys, the daily budget, streaming and its
  rate, "Support Hopp", storage and refresh rules, downloads, the voter id,
  local mutes and hidden comments.
- **Readable `d` tags** as the ADR has them. Relays see that someone uses
  Hopp and when a document changes — not what is in it. The privacy page
  says so.
- **Fetch** on start, on coming to the foreground (at most every five
  minutes) and on pull-to-refresh in the library. No standing relay
  connection.

## What to build

### 1. Encryption through the signer

`NostrSigner` gains `nip44Encrypt(plaintext)` and `nip44Decrypt(payload)`
to the listener's own key:

- **Local key:** `Nip44` with the conversation key of the own key pair.
- **Amber (NIP-55):** `NIP44_ENCRYPT` / `NIP44_DECRYPT` through the content
  resolver; the permissions `nip44_encrypt` and `nip44_decrypt` join
  `ExternalSigner.PERMISSIONS`, kind 30078 joins `SIGNED_KINDS`. Without a
  standing permission sync does not open the signer by itself — it waits
  and says so on the Nostr page.
- **Bunker (NIP-46):** the `nip44_encrypt` / `nip44_decrypt` requests; the
  perms of `BUNKER_PERMS` grow to match. Only while the app is open, never
  in the way of playback.
- Identities from before are asked for the new permissions when sync is
  turned on, as for stage 3.

### 2. The sync core (shared)

- **Documents:** kind 30078, `d` = `hopp/subscriptions`, `hopp/progress`,
  `hopp/queue`, `hopp/settings`; content NIP-44-encrypted to the own key:

  ```json
  {"v": 1, "entries": {"<key>": {"t": 1760000000000, "del": false, "...": "..."}}}
  ```

- **Keys:** a show by its `podcast:guid`, else `url:<feed URL>`; an episode
  by the show's key and the item's guid. A feed that moved keeps its key.
- **Merge per entry**, newest `t` wins; `del` marks a removal (an
  unsubscribed show, a favourite taken back, an episode out of "Up next") so
  the other device does not bring it back. Markers older than 90 days go.
  The merge is a pure function with its own tests: two devices, reordering,
  a clock several minutes off, an entry changed on both.
- **"Up next"** as entries with a sort key (the queue's own `sort_key`) and
  `t`; a move is a new sort key, so a reorder on one device and an addition
  on the other both survive.
- **Size:** a document over 64 KB is split (`hopp/progress#2` …); the first
  part names how many there are.
- **Local state:** a table `sync_entry(doc, key, json, updated_at, deleted)`
  (migration 12) — when this device last changed each entry, removal
  markers, and progress for episodes this device does not know yet.
  Repositories write it with every change while sync is on.
- **Relays:** the listener's NIP-65 write relays; for a key made in Hopp,
  Hopp's own. Read from the same.

### 3. Subscriptions

Entries from the other device subscribe or unsubscribe here; a new show
loads its feed as a subscription by hand does. The first sync on a new
device therefore fills the library.

### 4. Progress and favourites

- Episodes in progress or played in the last 90 days, and favourites.
- Published at most every five minutes, on pause and when Hopp goes to the
  background.
- An entry for an episode the feed has not brought yet waits in
  `sync_entry` and is applied when the feed is in.
- An episode playing here is not moved under the listener's ears: a newer
  position from elsewhere applies once it is paused.

### 5. "Up next" and settings

As above; settings only from the list of listening habits.

### 6. Page, privacy, docs

- The Nostr page: "Sync across devices", off by default; the first time on,
  what is here and on the relays is merged — nothing is overwritten. Below
  it, when it last synced, or why not (the signer, no relay reached).
- The privacy page: what is synced, to which relays, what relays can see.
- ADR 0021 stage 5 amended with what was built; CHANGELOG(.de).

## Done when

- `jvmTest`: the merge, the document format, splitting, the three signers'
  encryption (local against the NIP-44 test vectors, the external two
  against fakes), pending progress.
- Robolectric: turning sync on, a library filled from a document, an
  unsubscription that stays, a position that waits while playing.
- On two devices with the maintainer's key: subscribe on one, it shows on
  the other; progress and "Up next" follow; with Amber and with a bunker.
  The device tests in `devicetests/` stay without an identity.
