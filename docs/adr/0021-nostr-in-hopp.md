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
- **Amended 2026-10-24 (maintainer): the show's post above the comments.**
  Fountain shows the show's post on an episode among its comments; Hopp
  showed only what answers it, and the post itself seemed missing (E299).
  The announcement new comments answer (`post_release`, else the feed's
  newest note; before the release the Release Boosting note) — or, without
  one, the newest show note found about the episode — now sits above the
  comments, marked as the show's: its text folded to five lines, its likes
  and zaps as on a comment. It takes no Reply of its own: a new comment
  answers it already. It is asked for by id too, since a note without
  NIP-73 tags is found by nothing else, and its likes come with the second
  request.

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
  app is open and never blocks playback. *Superseded 2026-10-21 by the sync
  key, below.*
- *Amended 2026-10-20 (maintainer):* favourites and "Up next" travel too
  (`hopp/queue`); of the settings only the listening habits — since
  2026-10-23 with downloads, the background refresh and what boosts and zaps
  report (`docs/tasks/nostr-sync.md` lists them). The `d` tags
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
   *Built 2026-10-20:* a switch on the Nostr page, off by default. Four
   documents (`hopp/subscriptions`, `hopp/progress`, `hopp/queue`,
   `hopp/settings`), each a JSON of entries with a time in milliseconds and a
   removal mark, NIP-44-sealed to the listener's key in kind-30078 parts
   under 40 KB that share a revision. Every round reads and merges per entry
   before it sends; a change made here is always later than what it
   replaces, so a clock behind does not lose it. A position and a favourite
   of one episode are separate entries; positions older than 90 days are
   dropped everywhere alike, favourites stay; a place in "Up next" is the
   sort key it was given, so a reorder and an addition elsewhere both
   survive. A device's settings and queue order carry no time and join only
   where the relays have nothing. From another device: applied without being
   noted again; an entry whose show or episode is not here yet, or whose
   episode is playing, waits for the next round. Rounds on start, back in the
   foreground (at most every five minutes), on pull-to-refresh, ten seconds
   after a change — a position while playing at most every five minutes,
   a pause sooner — and what is left on leaving. In the background the signer
   app is never opened; sync waits and the page says so. Signers are asked
   at login for kind 30078 and NIP-44 encryption besides the rest. After a
   sign-in with an existing identity the page offers the library found on
   the relays, with what it holds; turning sync on merges, on a new device
   a restore. Turning it off forgets this device's notes; the relays keep
   theirs (8). A document of a newer format is left alone.
   *Amended 2026-10-21 (maintainer), before any release carried sync — the
   sync key:* a standing `nip44_decrypt` permission opens everything ever
   encrypted to the listener, NIP-17 messages included, to whoever holds
   Hopp's signer session; for a web client (planned) that session lives in a
   browser. So the documents are no longer sealed to the listener's key. A
   key pair of its own, the **sync key**, signs and seals them (NIP-44 to
   itself); it travels once as the **keyring**, a kind-30078 event by the
   listener with `d` = `hopp/keyring`, NIP-44-sealed to the listener's key
   (`{"v":1,"key":"<hex>"}`). A device opens the keyring once — the only time
   sync asks the signer — and keeps the key in its secret store; turning sync
   off forgets it. The first device makes the keyring, and only when at least
   half of the write relays answer the read to its end with none: a keyring
   out of reach is never replaced by a new one (the library of a lost phone
   would stay under a key nobody has), nor is one that does not open. A
   device looks at most once a minute whether the keyring changed — two
   devices that made one at the same moment both end with the one the
   relays keep, and the loser's entries follow, since every device keeps its
   own. Signers are no longer asked for standing encryption or kind-30078
   permissions (`ExternalSigner.PERMISSIONS`, `BUNKER_PERMS`); Amber or a
   bunker asks the listener at that one moment, and in the background
   nothing waits for a signer any more. Relays see the keyring under the
   listener's npub, the documents under the sync key; they can still link
   the two by IP and time. *Built the same day:* "Renew sync key" on the
   Nostr page (identities in a signer only — a lost phone that held the key
   itself has it anyway): a round, a new keyring, the documents out under
   the new key, then everything the old key signed replaced by an empty
   event and deleted (NIP-09), signed with the old key itself. A device that
   moves off a key — renewed elsewhere, or two made at once — retires it the
   same way, so a device that sent its copy once more before it noticed
   cleans up after itself. Other devices ask their signer once; a session
   that keeps the old key gets nothing new without the listener.
   Tests: `SyncKeysTest`.
   *Amended 2026-10-22 (external sync audit, before any release carried
   sync):* what the relays answer is never taken for more than it is.
   - **Reads and writes meet.** A document goes out only when a majority
     of the write relays took every part; it counts as absent only when a
     majority answered to the end without it. A read with a part missing,
     of another revision, under another part's address or not opening is
     *incomplete*: what arrived is applied, nothing is sent over it. Too
     few relays answering is *not known*: nothing happens.
   - **The keyring.** Made only by the listener turning sync on (never by a
     round in the background), and only when *every* write relay answered
     to the end without one (a majority since the delta audit, below). A device keeps the keyring event with the key
     and sends it once a run to the relays again, so one that lost it gets
     it back; a keyring older than the one it holds is never taken (NIP-01
     order: later, or the same second and the lower id).
   - **Local changes.** Entries from the relays are written only where they
     win over what is here at that moment (compare and set), and checked
     again right before they are applied: a change the listener makes while
     a round is on its way survives it. What is still to send is kept per
     entry, not by the newest time.
   - **Time.** A version goes out later than the newest one read; an entry
     dated more than a day ahead of this device is not taken. A device away
     longer than removal markers are kept (90 days) removes what it holds
     unchanged and no longer finds in a complete document, instead of
     bringing it back.
   - **Size and values.** A document that does not fit its 16 parts is not
     sent at all (it was cut before); a show's title and image are kept
     short; speeds, positions and settings from elsewhere are taken only
     within what Hopp itself could set. "Up next" breaks a tie in sort keys
     by the show and episode as sync names them, so every device shows one
     order. A show restored without downloads fetches nothing.
   - **Relays and signers.** Only answers to Hopp's own subscription count,
     only events its filters ask for, at most 5,000 events or 16 MiB a
     relay. A bunker is not asked in the background, as Amber is not opened;
     its confirmation page reaches the Nostr page from any request.
   What stays: relays can hold back or show a stale view — signatures prove
   who wrote, not that it is the newest; "none found" from relays that all
   answered is still not proof against relays that lie; a changed relay
   list breaks the overlap of reads and writes until the documents went out
   again. Tests: `SyncAuditTest`, `SyncCoreTest`, `BunkerTest`.
   *Amended again 2026-10-22 (delta audit of the same day, on 94687cc):*
   - **Majorities, both ways.** A complete read counts as a base to write
     over only when a majority answered to the end — one old relay alone is
     never written over the newer rest; a document with any part but its
     first is *incomplete*, not absent. The first keyring follows the same
     rule instead of waiting for every relay (one relay answering 503 had
     stopped every first device): made when a majority answered without
     one, and counted as out only once a majority took it. Until then the
     device keeps it and sends it again — it never makes a second one; a
     resend counts only once a majority took it. A key copy from before
     keyring times were kept moves only on a read every relay answered.
   - **Nothing local is lost in between.** What a device had without a time
     when sync was turned on (settings, the queue's order) is kept in the
     store as a seed until the relays hold a value or took it — a failed
     first round or an app closed meanwhile loses nothing, and a seed always
     gives way to a value from elsewhere. Removals and rebases delete an
     entry only as it was and once sent; a change made while another
     device's entry was applied goes back into the app. A removal marker is
     pruned only once the relays took it. An entry dated far ahead is
     carried along in what goes out, not dropped. A round stopped while
     applying applies again next time — the store moves only after the app.
   - **Renewing the key** happens in order, each step only after the last
     went through: a round with nothing waiting or unsent; the new keyring;
     every document under it; a second round that finds them all there;
     only then are the old key's documents emptied — by any device that
     moved off a key, after its own round went through. The page says
     whether it finished, or that it finishes on the next syncs.
   - The Nostr page names what the relays did ("2 of 3 relays answered"),
     not "no relay" for every failure. Tests: `SyncAuditTest`, `SyncKeysTest`,
     `QueueAndSettingsSyncTest`, `SyncMigrationTest`.
   *Amended a third time 2026-10-22 (second delta audit, on 346f002):*
   - **The newest revision, not the newest complete one.** A later part as
     new as the first, or newer, that is not of its revision makes the read
     incomplete: a larger revision whose first part is missing must not hide
     behind an older, smaller one still there. Parts a document left behind
     when it became smaller are always older than its new first part.
   - **A removal counts from when it went out.** Each removal carries the
     time it first went out (`out`) besides the time it was made; it is
     kept 90 days from then. One made offline and sent months later is not
     too old the moment it arrives.
   - **The app and the store agree, or the store says so.** Before another
     device's entries are applied, their keys are noted (`sync_unapplied`);
     the note goes once the app holds what the store does. A round cut off
     halfway, or a change the listener kept making under it, is put right
     by the next round. A show is given up — its episodes, progress and
     place in "Up next" with it — only while the removal still wins, asked
     in the same transaction; a show's settings change with their note for
     sync in one transaction. An entry dated far ahead is kept in the store
     and goes into the app once its time has come, so it goes along to a
     new key too.
   - **Retiring an old key needs the new documents read back.** It happens
     only after a round under the new key, all of it, read every document
     complete with nothing to send — not after a round that only sent them.
     A failed read of the old key's documents is not an empty one. The keys
     still to retire are kept with the key, so a restart finishes the job.
   - **A first keyring is kept before it goes out and used once a majority
     holds it.** A relay may keep it without saying so; a later round sends
     the same one again instead of making another, and nothing is sealed
     with it before a majority took it. A key copy from before keyring times
     were kept is not moved at all — which keyring is newer cannot be told;
     turning sync off and on takes the relays' one.
   - Migration 13 → 14 marks again as unsent what the old mark said was not
     out, on devices that ran the first version-13 migration. "Up next" is
     numbered afresh before a place at its edge. Tests: `SyncAuditTest`,
     `SyncKeysTest`, `SubscriptionsSyncTest`, `QueueAndSettingsSyncTest`,
     `SyncMigrationTest`.
   *Amended a fourth time 2026-10-22 (third delta audit, on 309dd16 —
   approved for a controlled beta):*
   - **Every keyring is committed by a majority before anything is sealed
     with it** — one read from the relays (another device's, or the winner
     of two made at once) too: one relay may be all that has it, so this
     device sends it on and waits. A renewal keeps its new keyring before it
     goes out; no relay saying it took it is no proof none did, so it is
     "unfinished" and finished by later rounds, never "nothing changed".
   - **The player follows a position from elsewhere when the episode is
     only shown after a restart,** and plays from what the database has
     when it is first loaded — it had played from the old place and saved
     that over the new one.
   - **Home's tick means nothing waits:** a change made here shows as
     waiting at once, as does the time before the first round.
   *Amended a fifth time 2026-10-23 (fourth delta audit, on 45b136e —
   approved for a controlled beta):*
   - **A keyring an earlier beta called committed is confirmed once more.**
     Before the third delta audit a keyring read from the relays was kept as
     committed at once, even from one relay alone. A copy now carries a mark
     that it was committed under the majority rule; one without it sends its
     own keyring to a majority before anything is sealed with it — the key
     stays the same. One from before keyring events were kept takes the
     relays' event and does the same; with another keyring on the relays it
     stays as it was (MN-03).
   - **The sync sheet fits a small phone and larger text:** it opens whole,
     and its buttons keep one line, smaller where they must. Its labels were
     whole before; the audit's "overflow" was Compose's measure — a label's
     layout is as wide as the row it may take — not what is seen. Test:
     `HomeLayoutTest` (320 dp and 360 dp at 1.3× text, German and English).
   *Amended a sixth time 2026-10-23 (device check):*
   - **One connection per relay for a round.** Hopp opened a connection for
     every read and every event: a first round with a library came to 13 a
     relay in under four seconds, and relays turn that away by address —
     relay.damus.io answered 503 from the third, nos.lol 429 from the
     twelfth; one relay of three took the round, and the next went through.
     A round, turning sync on, the restore offer and publishing a profile
     now share one connection to each relay (`withRelayPool`); one that drops
     is opened again by the next request, and a relay that cannot be reached
     fails only its own requests.
   - **"Too many" is asked again:** a connection refused with 429 or 503 is
     tried again after 0.8 s and 1.6 s. A request waits for it no longer
     than its own ten seconds; the opening itself, shared by the round, may
     go on a little past that and is ended with the round.
     Test: `RelayPoolTest`.
   *Amended a seventh time 2026-10-24 (fifth delta audit, on 9799fcb —
   approved for a controlled beta):*
   - **A shared connection's end reaches every request on it.** Each request
     listens on a channel of its own, closed when the connection closes —
     after what it still holds, however full (RP-01).
   - **A request's subscription ends with it,** answered, timed out or
     cancelled: `CLOSE` goes out briefly in any case, so a relay stops
     answering it on the round's connection (RP-03).
   - **Each request counts only its own answers** against its limits; the
     others' are passed over unread (RP-02).
   - **"Automatic downloads off" from another device cleans up here
     whenever it is applied,** not only when it was on before — an apply cut
     off halfway does it the next time (SS-01).
   - Downloads, background refresh and what boosts and zaps report travel
     too (maintainer 2026-10-23); the texts on sync and restore say so.
     Tests: `RelayPoolTest`, `SyncAuditTest` (delta audit 5).
   *Amended an eighth time 2026-10-24 (sixth delta audit, on 0591ee5 — fix
   before a general beta):*
   - **A request knows its answers by what JSON reads in them,** not by its
     subscription somewhere in the first 100 characters: an answer padded
     with spaces or with the subscription escaped was passed over, the EOSE
     after it made the read complete and empty, and a new device made a
     second keyring. Type and subscription are read as JSON reads them
     (`RelayFrame`); one of its own too large to read makes the read
     incomplete instead of being passed over (RP-02).
   - **Shows wait for the settings on a new device:** until the settings
     were read to the end once, a round leaves the shows for later. Taken
     first is not enough when their read fails — the shows came under the
     fresh default and downloaded although automatic downloads were off.
     Tests: `RelayFrameTest`, `RelayPoolTest`, `SyncAuditTest` (delta audit 6).

   *Amended a ninth time 2026-10-26 (0.3.1):* two more things in step.
   - **A show's skipped start and end** ride with its subscription entry
     (`intro`, `outro` in seconds, 0–600; left out when 0) — taken from
     elsewhere only in those bounds, anything else counts as none. A device
     still on 0.3.0 rebuilds the entry without them when it notes the show.
   - **A fifth document, `hopp/stats`:** the listening statistics (ADR 0023),
     each device writing only keys with its own random name, so the counts add
     up across devices and are never merged over each other. Noted at most
     every 30 minutes while listening, on a pause after 5 and when the app
     goes to the background; never a round of its own.
   *Amended a tenth time 2026-10-30 (0.3.2, maintainer: relays refusing a long
   progress document):*
   - **"Heard up to" per show** (`upto\n<show>`: `before`, `at` in seconds):
     "this and older" and "all heard" go as one entry instead of one per
     episode — 324 entries of the test feed were 2 events, sent again with
     every change. An episode that sync already held an entry for, or one
     without a date, still gets its own. Applied before the episode
     entries of the same read, so a later "unheard" of one episode wins;
     it marks only what was not listened to since `at`. Kept in
     `podcast.played_through` (version 17), never expired. Hopp before
     0.3.2 passes over it: there those episodes stay unheard.
   - **Gentler sending:** half a second between the parts of a document,
     and a relay that answers `rate-limited` gets the event once more
     after 3 s. What each relay answered is kept per round and shown on
     the sync sheet.
     Tests: `ProgressSyncTest`, `RelayReportTest`, `SyncMigrationTest`.

   *Amended an eleventh time 2026-10-30 (0.3.2, maintainer: "Incomplete" with
   every relay answering, four changes that never went out):*
   - **Parts of at most 40 000 bytes:** a 60 000-byte part became an event of
     ~88 KB, over strfry's default of 64 KiB per event (nos.lol and many
     others); now ~55 KB.
   - **A gap that stays is written over.** A version that went out only in
     part (a relay refused a part) leaves a revision with a gap; read as
     incomplete, it was never sent over (H-02) — and so never mended. Now,
     with a majority read to its end, a whole new version goes out over it,
     with every entry of every part that arrived, when the newest revision
     is one this device failed to send whole (`sync_doc.broken_rev`,
     version 18), or when every missing part is there in an older version
     only (a relay that had taken the newer one would hold it instead). A
     part that is simply missing still stops the round: a relay may hold it
     and not have given it (HN-02) — for three minutes (ten at first): a gap every relay has
     shown that long, the same revision each time, is written over too
     (`sync_doc.stuck_rev`/`stuck_since`, version 19). The revision a device
     sends is kept *before* its first part goes and cleared once all went, so
     a round ended any way between — refused, timed out, the tab closed —
     leaves a gap its device knows. Seen on the maintainer's progress
     document: nos.lol slows to no answer after ~1.5 MB in a minute, and each
     failed round sent the parts again. The report names the document that
     came in part.
     Tests: `SyncCoreTest`, `SyncAuditTest`, `SyncMigrationTest`.
   - **Single entries folded into "heard up to".** Episodes heard one by
     one before 0.3.2 kept an entry each — the maintainer's progress was
     ~560 KB. Once per start each show heard from its oldest episode on
     without a gap (at least ten) gets its mark, dated when the last of them
     was heard (a device that listened to one since keeps that). An episode
     entry the mark says already — heard, at its start, no newer than the
     mark, out before it by this device's feed — is left out of the store,
     the round and what goes out, without a removal marker; applied again it
     would change nothing, as the mark goes first. With 50 such entries on
     the relays a smaller version goes out though nothing changed. A Hopp
     before 0.3.2 puts its copies back when it sends; 0.3.2 drops them again.
     Tests: `ProgressSyncTest`.
   - **"Write anew from this device"** on the sync page, under a document
     read in part: this device's copy, with all that arrived, whole, at once
     — the listener's word over the wait; what only the missing parts held
     comes back when the device that has it syncs. The page says when Hopp
     would do it by itself, and the report what the read found (which parts
     of the newest version, how many relays answered), with names that a
     release build does not shorten.
   - **A smaller version leaves no large parts behind.** Passed over as
     older, the larger one's later parts were still read every round (the
     maintainer's progress: one part of 8 KB after the fold, 13 old ones of
     ~700 KB beside it). Each is overwritten by an empty part of the new
     version — same revision and time, past its part count, so every Hopp
     passes it over — a few hundred bytes each; best effort.

   *Amended a twelfth time 2026-10-31 (0.3.2, review of the branch: "heard up
   to" passed over what the single entries had kept apart):*
   - **A mark found from a device's feed starts where that feed does**
     (`from` in seconds, `podcast.played_through_from`, version 20). Feeds
     give their newest 50 or 100: a device subscribed for years holds older
     episodes the run never saw, and the mark made them heard there. Applied
     and counted as covering only from `from` on; a mark without it (the
     listener's "this and older" or "all") from the first episode. One found
     joins the show's mark only where the two meet.
   - **An entry newer than the mark stands.** Taking an episode back leaves
     no time in the episode, so a mark made elsewhere before it, read
     after, made it heard again — and only that device. Where sync holds an
     entry for the episode newer than the mark's `at`, the mark passes it
     over.
   - **"This and older" short of the mark leaves the mark as it was.** It
     says *everything* up to it as of its time; re-dated, an episode taken
     back since was heard again on the other devices, not on this one. The
     episodes heard now go out one by one instead.
   - **A smaller version for its own sake at most every six hours.** A
     Hopp before 0.3.2 puts the covered entries back each round, and the
     0.3.2 device took them out again: both sent a version every round for
     as long as both were in use. With something else to send it still
     goes along at once.
     Tests: `ProgressSyncTest`, `SyncMigrationTest`, `SyncAuditTest`.

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
