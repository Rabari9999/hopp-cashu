# Task — Sync across devices over Nostr (ADR 0021, stage 5)

- **Status:** built 2026-10-20 (packages 1–7), sync key 2026-10-21, audit fixes 2026-10-22 and two delta audits the same day (ADR 0021, 5); checked on two devices with the maintainer's key, with Amber and with a bunker, 2026-10-21
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
  back and forward, the default speed, skip silence, the volume boost (2026-10-21), the boost name, the
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

*Changed 2026-10-21 (maintainer), before release:* the documents are signed
and sealed with a **sync key** of their own, which travels once as the
keyring `hopp/keyring` sealed to the listener's key; signers keep no standing
encryption permission. Why and how: ADR 0021, stage 5, amendment of
2026-10-21. Sections 1 and 2 below describe the first build; where they say
"to the listener's own key" for the documents, read "with the sync key".

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

### 6. Restore on a new device

Asked for by the maintainer, 2026-10-20: a new phone, or Hopp installed
again, starts from the library the listener had.

- **On the first start:** the tour's Nostr page offers, below "Set up Nostr
  now", "Already using Hopp? Restore from Nostr", which leads to the ways in
  with an existing account (signer app, bunker, password manager, pasted
  key).
- **After every sign-in with an existing identity:** Hopp reads the relay
  list (as it does already), then looks for `hopp/subscriptions` there. Found,
  the Nostr page offers "Restore from your other devices — 12 shows,
  5 episodes in progress" with one button: it turns sync on and merges. On a
  fresh install there is nothing to merge with, so it is a plain restore;
  "Not now" leaves sync off and can be changed later on the same page.
- The signer is asked for the encryption permissions at sign-in already, so
  restoring does not ask again.
- A key made in Hopp comes back through its backup (password manager, nsec,
  ncryptsec); the restore follows the same way. The backup card says that
  the key also brings back the library once sync is on.
- Order of a restore: subscriptions first, then progress, "Up next" and
  settings as the feeds arrive. *As built:* the feeds are fetched four at a
  time and each show appears as its feed is in; showing the library at once
  from the document's titles and covers is left for later, if a restore
  feels slow on the device.
- *Changed 2026-10-21 (maintainer):* the link in the page fell off small
  phones and looked like an aside. Now the footer, which never scrolls,
  carries it: "Set up | Restore" side by side on the Nostr page, and a quiet
  "I already use Hopp" on the welcome page, so those who return need not
  page through the tour. The sign-in sheet then says what the key is for.

### 7. Page, privacy, docs

- The tour's Nostr page announces sync as coming
  (`onboarding_nostr_soon`, the "Soon" box): it goes, and sync becomes a
  fourth point beside comments, votes and boosts ("your library and
  progress on every device — encrypted, no Hopp server"). The "Soon" box
  then has no page left; `soon` and `onboarding_soon` go with it.

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
- Robolectric: a fresh install signed in with a key whose relays hold the
  documents offers the restore, and after it the library and "Up next"
  are there.
- On two devices with the maintainer's key: subscribe on one, it shows on
  the other; progress and "Up next" follow; with Amber and with a bunker;
  Hopp deleted and installed again comes back with its library.
  The device tests in `devicetests/` stay without an identity.
