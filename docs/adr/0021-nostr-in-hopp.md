# ADR 0021 — Nostr in Hopp: comments, relays, moderation, sync

- **Status:** accepted (2026-10-15; point 8 as the maintainer revised it —
  no sign-out that promises deletion)
- **Date:** 2026-10-15
- **Details:** ADR 0006 (identity, signers, sync in principle)

## Context

ADR 0006 settled *that* Hopp gets an optional Nostr identity in phase 2, with
which signers, and what it is for. It left open *how*: which events a comment
is, where Hopp reads and writes them, how spam is kept out, what a sync event
holds. A review on 2026-10-15 listed nine such gaps; this ADR closes them.

What exists already:

- **Hopp:** secp256k1, events, NIP-44 and a relay client (`nostr/`, built for
  NWC). `podcast:socialInteract` is parsed but shown nowhere. No NIP-98 yet.
- **Sovereigncast** posts each episode as a kind-1 note. The self-hosted feed
  names it as `<podcast:socialInteract>`; a PodHome feed cannot carry that tag.
  Since 2026-10-15 (podcasting_tools) the note carries the episode's and the
  show's GUID as NIP-73 tags, its address is an `nevent` with the relays that
  took it, and the Listener API serves it for both hosting paths
  (`getEpisodeNotes`, capability `episode_notes`). The Listener API accepts
  NIP-98 for votes (one per pubkey). Sovereigncast reads no replies.
- **The ecosystem:** NIP-10 — "Kind 1 replies MUST NOT be used to reply to
  other kinds, use NIP-22 instead"; NIP-22 (kind 1111) addresses a podcast
  episode by NIP-73 (`podcast:item:guid:<guid>`). Fountain links its posts to
  episodes by NIP-73 — as kind 1 so far (nips PR #2468, Sept. 2026, where
  migrating to 1111 is recommended but not settled); likes are kind 17.

## Decision

### 1. Comments (agreed 2026-10-15)

**Writing.**
- The episode has a show note — from `socialInteract`, from `getEpisodeNotes`,
  or found on relays by `#i podcast:item:guid:<guid>` from the show's npub:
  the comment is a **kind-1 reply to that note** (NIP-10: `e` root with relay
  hint, `p` of the show). The podcaster sees it in the thread of their own
  note, in any Nostr client.
- No show note: a **kind-1111 comment** (NIP-22) with root `I`/`K` =
  `podcast:item:guid:<guid>` / `podcast:item:guid`.
- Both carry the NIP-73 `i`/`k` tags of the episode and the show
  (`podcast:guid:<guid>`), so apps that search by GUID find them either way.
- A reply to a comment follows the kind of that comment (1 under 1, 1111
  under 1111).

**Reading** is the union, de-duplicated by event id: replies to the show's
notes (`#e`), and kinds 1 and 1111 with `#i podcast:item:guid:<guid>`. So
Hopp shows what Fountain's listeners say about the same episode.

**Reactions:** kind 7 on a comment. Episode likes (kind 17, Fountain) — later.

### 2. Relays

- **Reading comments:** the relay hints of the show's `nevent`, the show
  npub's NIP-65 write relays, and a short default list
  (`relay.damus.io`, `nos.lol`, `relay.primal.net`) that the settings can
  replace. At most five relays per episode.
- **Writing:** the listener's NIP-65 write relays; a key made in Hopp
  publishes the default list as its kind 10002.
- **Privacy:** reading contacts relays, which see the IP address and which
  episode is asked about. Comments therefore load only when the listener opens
  them, never with the episode page, and the privacy page (ADR 0015) lists
  relays for reading too — not only for writing as before.

### 3. Moderation (needed for iOS: App Review guideline 1.2)

- **Mute** a person: the listener's NIP-51 mute list (kind 10000), so other
  clients honour it too; without an identity, a local list.
- **Report** a comment: NIP-56 (kind 1984), and hide it at once.
- **The podcaster moderates their thread:** comments by people on the show
  npub's mute list are hidden.
- No comment from an account muted by the listener ever shows, also not as
  "hidden".

### 4. Profile

- A key made in Hopp asks for a name (prefilled with the boost name) and
  publishes kind 0 — optionally a picture by URL, no upload.
- An existing identity: Hopp reads kind 0 and never writes it.
- The profile name replaces the boost name in the greeting and as the boost
  sender, unless the listener sets a different boost name.

### 5. Sync (last stage, opt-in)

- NIP-78 (kind 30078), NIP-44-encrypted to the listener's own key, in
  separate documents: `hopp/subscriptions`, `hopp/progress`, `hopp/settings`.
- **Merge per entry** with a timestamp inside the payload, newest wins — not
  per document, so two devices do not overwrite each other.
- **Progress:** only episodes in progress or played in the last 90 days;
  published at most every five minutes and when playback pauses or Hopp goes
  to the background. A document over 64 KB is split.
- **Never synced:** the NWC connection, keys, the wallet's budget and history.
- **External signers:** a sync needs encrypt and sign each time. With Amber,
  Hopp asks once for standing permission; a NIP-46 signer syncs only while the
  app is open and never blocks playback.

### 6. Boosts and the npub

- The boostagram carries the npub as bLIP-10 `sender_id` — to verify against
  what Sovereigncast's wallet accounting and other receivers read before
  building.
- **Public boost** (opt-in per boost, ADR 0006): a kind-1 note with the
  amount, the message and the episode's NIP-73 tags, replying to the show
  note when there is one.
- **Zaps (NIP-57)** to a podcaster's npub are not a payment path in phase 2:
  the value block stays the one way money flows (ADR 0005).

### 7. Sovereigncast

- With an identity, votes and suggestions are signed with NIP-98; a setting
  keeps them anonymous.
- **"Your suggestion became an episode"** needs server work: the instance
  stores the pubkey of a signed suggestion and sends a DM when it is
  published. Not part of the Listener API yet.

### 8. What is published stays

Nothing published on Nostr can be taken back — deletion requests (NIP-09)
are only a request, and relays keep copies. Hopp therefore offers no
"delete my comments" or sync wipe that would promise otherwise. Signing out
of Hopp only removes the key from the device, after a reminder to export it.
Instead, Hopp says it **before** the first public action (comment, public
boost, profile): this stays public and permanent.

### 9. Key backup

- The export is **NIP-49 (`ncryptsec`)** with a password; the bare `nsec` only
  behind a second confirmation.
- A reminder after the first comment or boost made with a key from Hopp.
- The key stays out of system backups (ADR 0015) — so without an export, a
  lost phone means a lost identity. The onboarding says so.

### Stages

1. **Identity:** a key made in Hopp, Amber (NIP-55), bunker (NIP-46); the
   profile name in the greeting; NIP-98 to Sovereigncast.
   *1a built 2026-10-15:* the key made in Hopp (secret store), its profile
   (kind 0) and relay list (kind 10002) on the default relays, the name as
   boost name when none is set, NIP-49 export (scrypt and XChaCha20-Poly1305
   in shared code, checked against the spec's vector), NIP-98 on votes and
   suggestions with a switch to stay anonymous, the relays on the privacy
   page. Sovereigncast verifies NIP-98 since the same day — before, the
   contract promised it and the server ignored the header.
   *1b built 2026-10-15:* "Sign in with Amber" where a NIP-55 signer is
   installed. Hopp keeps the public key and the signer's package, asks at
   login for standing permission to sign NIP-98 (kind 27235), signs through
   the content resolver in the background and opens the signer only when
   that is not allowed; whatever comes back is checked (the listener's key,
   the event asked for, a valid signature). The profile name is read from
   the relays, never written; no backup is offered for a key Hopp does not
   hold. Profiles are looked up as clients do (device feedback 2026-10-16:
   the maintainer's was only on an index): Hopp's relays and two indexes
   (purplepag.es, user.kindpag.es), then the person's NIP-65 write relays.
   *1c built 2026-10-16:* a remote signer (NIP-46) on both platforms —
   a pasted or scanned `bunker://` address, or a `nostrconnect://` code
   Hopp shows (copy, share, open in the signer; no QR drawn yet). Hopp keeps
   a conversation key of its own, asks for standing NIP-98 permission,
   honours `switch_relays` and `auth_url`, checks the connect secret of a
   code, signs out with `logout`. NIP-44 only; signers that speak only
   NIP-04 are not supported.
2. **Reading comments**, with the relay entry on the privacy page.
   *Built 2026-10-16:* a Comments chip in the player (a sheet, loaded on
   opening) and a Comments tab on the episode page (loaded on a tap). The
   anchor: the feed's `socialInteract`, `getEpisodeNotes`, and the show's
   keys from `accountId`, the notes' authors and `podcast:txt
   purpose="nostr"`. One REQ for kinds 1/1111 by `#i`, `#I` and `#e` of
   the show's notes, a second for answers to what it found (Fountain
   replies name only the comment). The relays: two hints of the notes, the
   default list, the show's NIP-65 write relays — five at most. Muted:
   the public entries of the show's and the listener's kind 10000; a muted
   comment goes with everything below it. One level of replies; a deeper
   one names whom it answers. Names: the comment relays, the two indexes
   and relay.fountain.fm — Fountain listeners' profiles are often only
   there (live on 2026-10-16: 5 of 10 commenters). Embedded `nostr:nevent`
   references are not shown, nor the Fountain episode link Fountain ends
   its comments with. Speed (device feedback 2026-10-16: 30–40 s): each
   step waited for its slowest relay, and a silent one cost the 10-s
   timeout every time. Now the relays are asked at once, the show's own as
   soon as its list is in, and the others get 1 s once the first has
   answered (3 s for names — Fountain's relay is slower than the indexes);
   names are kept while the app runs. Live on E297: comments in 3.7 s,
   every name in 6.9 s.
   Fountain's boosts: Fountain posts each as a comment embedding its zap
   receipt (kind 9735). Hopp fetches the receipts and shows "⚡ n sats" when
   the zap request inside is by the comment's author, names the invoice's
   amount, and — where the invoice has one — matches its description hash
   (Fountain's have none). A comment with neither text nor sats is not
   shown. The player's chips show only what the
   episode has (device feedback 2026-10-16).
3. **Writing comments**, with moderation (3) — before any iOS release.
4. **Public boosts**, opt-in.
5. **Sync.**

## Consequences

- **Server (podcasting_tools):** done 2026-10-15 — NIP-73 tags on episode
  notes, `nevent` addresses, `getEpisodeNotes`. Open — pubkey on signed
  suggestions and the DM on publication (7).
- ADR 0015 gains relays as a party for *reading* comments.
- Hopp's sender name gets a source with precedence: the explicit boost name,
  then the profile name.
- The default relay list is a decision Hopp makes for listeners without a
  Nostr history; it is kept short and replaceable.
- Fountain's choice between kind 1 and 1111 for comments is still open
  upstream; reading both keeps Hopp compatible either way.
