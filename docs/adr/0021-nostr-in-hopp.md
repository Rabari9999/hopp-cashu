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
- **Amended 2026-10-17 (maintainer):** a new comment stands on its own, as
  Fountain's do — a kind-1 note with the NIP-73 `i`/`k` tags of episode and
  show, no `e`. The show's note, if there is one, is quoted (`q` with its
  relay and author) and the show named (`p`), so the podcaster is still told.
  As a reply to the show's note Fountain did not show Hopp's comments (it
  likely treats them as answers to a note it does not know); a standalone
  note is what Fountain itself writes. The price: in Primal or Damus the
  comment no longer sits in the thread of the show's note but shows as a
  quote of it. Without a show note it is the same kind-1 note, not
  kind 1111. Answers to existing comments are unchanged; reading is
  unchanged (it already took both).
  The same day, after the first test comment (E298): standing alone, it had
  no visible reference in other clients — Fountain's end in a link to the
  episode. The show's note is now also mentioned at the end of the text
  (`nostr:nevent…`, NIP-18), which Primal and Damus show as the quoted
  announcement; Hopp leaves it out when showing the comment. Comments go to
  relay.fountain.fm too, and it is always asked when reading. Reading asks
  in full on every load (the kept comments show meanwhile): asking only
  since the last load lost for good what a relay too slow that once had held
  — two Fountain comments on E298.
  *(Amended 2026-10-19, maintainer:)* a show without any note (Münzweg)
  left a comment — a public boost — with no reference in Primal again. Such
  a comment now ends in "🎧 Show – Episode" and the episode's web page (the
  item's `<link>`, else the show's), as Fountain's end in theirs; Hopp
  leaves that line out when showing the comment.
- **Amended 2026-10-17 (maintainer): the show's announcement where nothing
  names it, and zaps as comments.** Einundzwanzig announces an episode with a
  plain note (no NIP-73 tag; Fountain links it by its own page), and its feed
  names no key. Hopp now takes the show's website's NIP-05 key (`_`) when the
  feed gives none, and asks for the show's notes from two days before the
  release to a week after — in the first request when the key is known, else
  beside it as soon as the website answered (2 s at most, once per site). A
  note that names the episode, links one of its pages or gives its title
  (also without a leading number: "#336 - …" is announced as "News Episode
  336 - …") is a root, kept with the comments; the show's other notes are
  dropped. Answers to it come with the second request, as do zap receipts on
  every root: a zap whose request carries a message shows as a comment with
  its sats (request signed, amount equal to the invoice's) — one a comment
  embeds already (Fountain's boosts) is not shown twice; a zap takes no
  answers or likes. Live: Einundzwanzig #336 in 2.7 s with the website
  asked, Nodesignal E298 in 4.3 s.

**Reading** is the union, de-duplicated by event id: replies to the show's
notes (`#e`), and kinds 1 and 1111 with `#i podcast:item:guid:<guid>`. So
Hopp shows what Fountain's listeners say about the same episode.

**Reactions:** kind 7 on a comment. Episode likes (kind 17, Fountain) — later.

- **Amended 2026-10-19 (maintainer): zapping a comment.** Beside like and
  answer, ⚡ zaps a comment from the connected wallet (NIP-57): the author's
  `lud16` from their profile; its pay request must take zaps (`allowsNostr`,
  `nostrPubkey`), else Hopp says so; a zap request (kind 9734: `e` and `k` of
  the comment, `p` of its author, the amount, the relays the comments came
  from and Fountain's) goes to the address's callback, and the invoice is
  paid only if it asks for exactly that amount and its description hash
  commits to the request. Within today's budget, in the payment history as a
  zap. Public, private or anonymous, as in Amethyst (maintainer, same day;
  kept for the next zap): public is signed with the listener's identity;
  anonymous — and every zap without an identity — with a key made for that
  zap alone and the `anon` tag; private looks the same from outside, and its
  `anon` tag holds the request signed by the listener (kind 9733), encrypted
  for the author alone as Amethyst does it (AES-256-CBC under the ECDH
  secret, `pzap1…_iv1…`; the outer key derived from the listener's own where
  Hopp holds it, else random). The Lightning server sees the IP address in
  every case. Hopp reads the
  receipts on comments with their likes and shows their sum on each comment.
  A boost made public stays a note with its amount in the text, not a zap:
  a zap's receipt is signed by the server of the one address paid, and a
  boost is split among several recipients, mostly by keysend, which gives
  no receipt. Fountain's boosts show as zaps because Fountain receives the
  whole boost itself and signs the receipt; Hopp pays the recipients
  directly.

### 2. Relays

- **Reading comments:** the relay hints of the show's `nevent`, the show
  npub's NIP-65 write relays, and a short default list
  (`relay.damus.io`, `nos.lol`, `relay.primal.net`) that the settings can
  replace. At most five relays per episode.
- **Writing:** the listener's NIP-65 write relays; a key made in Hopp
  publishes the default list as its kind 10002.
- **The listener's own list (amended 2026-10-19, device feedback):** under
  Nostr → Relays the listener sees their kind 10002 and adds or removes
  relays (wss:// only, checked for an answer first). Hopp reads the newest
  list before changing it, so what another client set stays, and publishes
  it to the relays before and after the change and to the index relays. The
  listener's read relays are asked for comments too. An imported key uses
  its own list, as a signer app's identity always did. Relays that require
  NIP-42 authentication are not supported yet.
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
- *Amended 2026-10-20 (maintainer):* favourites and "Up next" travel too
  (`hopp/queue`); of the settings only the listening habits. The `d` tags
  stay readable; Hopp fetches on start and on coming to the foreground, with
  no standing relay connection. A new device, or Hopp installed again,
  offers right after sign-in to restore from the documents it finds.
  Plan: `docs/tasks/nostr-sync.md`.

### 6. Boosts and the npub

- The boostagram carries the npub as bLIP-10 `sender_id` — to verify against
  what Sovereigncast's wallet accounting and other receivers read before
  building.
- **Public boost** (opt-in per boost, ADR 0006): a kind-1 note with the
  amount, the message and the episode's NIP-73 tags, replying to the show
  note when there is one. The amount is the text's last line, "⚡ N sats",
  which every client shows. It is the listener's word: a keysend boost has
  no receipt to check. Hopp shows the amount beside the author's name, as a
  zap's, only for boosts this device paid (kept as it posts them); in any
  other note the line stays text — anyone can end a note with
  "⚡ 210000000 sats" (amended 2026-10-20, twice: first every such line went
  beside the name, until a note typed in Amethyst claimed 2.1 BTC). A
  confirmation by the receiver — Sovereigncast signing for the share it got —
  could back others' boosts later.
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

*Amended 2026-10-16 (device feedback):* the password-locked ncryptsec was the
only backup, and its text promised that any Nostr app opens it — Primal does
not, nor does Damus. Hopp's working copy stays in the device-only store
(Keystore, Keychain); the backup leaves the device by the listener's choice:

- **First: the password manager** — on Android the Credential Manager saves
  the nsec as a password under the npub (Google Password Manager, Bitwarden,
  KeePassDX …; the FOSS build uses the system's own Credential Manager,
  Android 14+, and carries no Play services); on iOS a synchronizable item
  in the iCloud Keychain. No password to remember, back in one tap.
- **The nsec in plain text**, which every app reads, after a warning; the
  clipboard is marked sensitive and cleared after a minute (on iOS it
  expires and stays off the Universal Clipboard).
- **NIP-49 ncryptsec** with a password under "more options", saying which
  apps read it.
- **An existing key** — from the password manager or pasted as nsec or
  ncryptsec — becomes the identity; its profile is read, not written.
- The reminder after the first comment made with a key from Hopp stays; a
  key saved or copied counts as backed up.

*Amended again 2026-10-16:* the page showed every way at once and read like
a manual. Now a newcomer sees one line, a name and "Get started"; the ways in
with an existing account (signer app, password manager, pasted key, bunker)
sit behind "I already have a Nostr account". Signed in: the profile, one card
with one button to back the key up (the password manager / iCloud Keychain,
else copying the nsec), the other ways behind "Other ways", then the two
switches.

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
   shown. Loaded comments are kept in the database (`comment_*` tables,
   schema 6): shown at once when opened again — after a restart, the
   download deleted — and only what is new since the last load (less ten
   minutes) is asked for, replies to old comments included. Names are looked
   up again after a week; boost amounts are kept. Everything of a show goes
   when it is unsubscribed; played or not does not matter.
   Consent (device feedback 2026-10-16): the relays are asked only on a tap,
   in the player as on the episode page — or at once behind a VPN, which
   keeps the address from them; the privacy page's VPN line says which.
   Kept comments show either way. The player's chips show only what the
   episode has (device feedback 2026-10-16).
3. **Writing comments**, with moderation (3) — before any iOS release.
   *Built 2026-10-16:* a field above the comments; without an identity the
   way to one (the draft waits). A comment answers the show's note as
   kind 1 (NIP-10 root with its relay, `p` of the show, the NIP-73 tags of
   episode and show), else a kind-1111 comment with `I`/`K` and `i`/`k` of
   the episode; an answer follows the kind of what it answers — kind 1 keeps
   the thread's root and marks the reply, kind 1111 names its parent with
   `e`/`k`/`p`. Likes (NIP-25, "+") on any comment, counted from what the
   relays hold ("-" is no like). Published to the listener's NIP-65 write
   relays (Hopp's own for a key made here) and the relays the comments came
   from, kept in the database at once. Moderation: "⋯" on a comment —
   mute (the mute list is read first and written back with all it held, the
   encrypted private part untouched; hidden here at once, and on this device
   only without an identity), report (NIP-56: spam, profanity, illegal,
   other; hidden at once; without an identity only hidden), hide. Once,
   before the first comment, like or report: "public, for good" (8); after
   the first comment with an unsaved key from Hopp: the backup reminder (9).
   Signers are asked at login for standing permission for kinds 1, 1111,
   7, 10000 and 1984 besides NIP-98; identities from before are asked per
   event by their signer.
   Device feedback 2026-10-16: an answer's field opens under the comment it
   answers, focused and in view (at the top it went unseen); a like shows at
   once while the signer and the relays work, and is taken back if it fails.
   A new comment answers the episode's announcement: Sovereigncast's
   `post_release` note, else the feed's newest — a boost to E297 had landed
   under the Release Boosting note, the first the instance lists. Above the
   keyboard: the comment lists take the IME's height, so an answer to the
   last comment is not typed blind.
4. **Public boosts**, opt-in — *built 2026-10-16* with the comments: a
   switch in the boost dialog, preset by "Boosts on Nostr too" on the Nostr
   page (off by default). Once a boost to an episode is paid, the paid
   amount goes out as a comment like any other (answering the show's note,
   NIP-73 tags), the message and "⚡ n sats" as its text — what every client
   shows; readers cannot check the amount, as with Fountain's own notes. The
   boost dialog says whether it went out; a failed post leaves the boost as
   it is. Boosts to a whole show and donations stay private.
5. **Sync.** *Planned 2026-10-20, next after stage 4 and before push
   (ADR 0007):* `docs/tasks/nostr-sync.md`.

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
