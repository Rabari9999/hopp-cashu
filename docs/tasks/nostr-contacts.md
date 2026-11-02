# What your Nostr contacts listen to

- **Implements:** [ADR 0024](../adr/0024-nostr-contacts.md); release 0.3.3.
- **Status:** stages 1–5 built (2026-11-02, branch `0.3.3`); zaps and follows of a show's npub left.

Only with a Nostr identity: without one, nothing of it is shown or done.

## Stage 1 — contacts and the contacts note

- [x] `contacts/ContactKeys`: the contacts note (kind 30078, `d` = `hopp/contacts`)
      with the share and inbox keys — public halves in its tags, private ones
      sealed to the listener; made and changed only with the listener there,
      read in the background; another device takes the keys over with one
      question to the signer. Both switches off: emptied and deleted (NIP-09).
      To the listener's write relays and Hopp's for sync.
- [x] `contacts/ContactGraph`: the listener's contact list, each contact's
      relays (NIP-65, from the indexes), and from those, per relay for all its
      contacts at once: their contact list only where it names the listener
      (`#p`, so lists of thousands never travel) and their contacts note.
- [x] `contacts/ContactsRepository`: the switches from the note, the contacts
      read at most hourly.
- [x] The Nostr page: "Nostr contacts" with the two switches, what turning
      the first one on makes public (asked before), "turned on on another
      device — take over", and a line of counts: followed, following back,
      with Hopp.
- [x] `ContactsTest` (6).

## Stage 2 — sharing, leaving shows out

- [x] `contacts/ContactShare`: the shared list (`hopp/contacts/shelf`), sealed
      by the share key to itself — as many shows as one NIP-44 message holds,
      newest first; a key per mutual contact with an inbox
      (`hopp/contacts/key/<inbox>`). Each only when changed; signed by the
      share key alone. Sent with each refresh and five minutes after the
      subscriptions last changed.
- [x] Sharing off: what the share key signed replaced by empty events and
      deleted (NIP-09).
- [x] "Share with Nostr contacts" per show in its menu, while sharing is on;
      `podcast.contacts_hidden` (migration 21 → 22), synced in
      `hopp/subscriptions` as `noShare`.
- [x] Tests: `ContactsTest` (9), `SubscriptionsSyncTest.aShowLeftOutOfSharingIsLeftOutEverywhere`.

## Stage 3 — seeing shared subscriptions

- [x] `ContactGraph.read(inbox)`: for each mutual contact whose note names a
      share key, its key for this inbox and the list it opens — a key that is
      not the one the note names opens nothing; names and pictures (kind 0)
      of those who share. Kept between starts (`contact_cache`, migration
      21 → 22); read with each feed refresh, at most hourly.
- [x] Discover "With your Nostr contacts" (what they share, most contacts
      first, shows already subscribed left out; "a subscription is no
      recommendation"); on a show's page and its preview, the contacts'
      avatars and "4 of your Nostr contacts listen to this".
- [x] Tapping the row: who, and what each did (shares the subscription,
      boosted, commented), each opening their Nostr profile. A page with all
      of one contact's shows: later, if asked for.
- [x] Tests: `ContactsTest` (11).

## Stage 4 — public signals

- [x] Notes (kind 1, `k` podcast:guid / podcast:item:guid) and comments (kind
      1111, `K`) of everyone the listener follows, the last 90 days, asked
      with the same per-relay requests; counted per show by `podcast:guid`
      — a boost when the note ends "⚡ N sats". Ranked: boost 3, comment 2,
      shared subscription 1, per contact the most, summed; shows known only
      from these looked up in the Podcast Index by guid (12 at most per read).
- [ ] Zaps to a show's npub, follows of a show's npub; comments naming an
      episode only (no `podcast:guid`): not yet — they need the show's npub or
      the episode's show.

## Stage 5 — a new share key when a mutual contact is lost

- [x] An inbox that had the key and is no longer a mutual contact's, in two
      reads in a row: a new share key, the old one's events emptied and
      deleted, the keys sent again to who is left. The signer is asked only
      with the listener there or where Hopp holds the key; otherwise the
      Nostr page offers "Renew key".
- [x] Tests: `ContactsTest` (13).

## On the real relays

`HOPP_LIVE_CONTACTS=1 ./gradlew :shared:jvmTest --tests '*ContactsLiveTest*'`: two
throwaway keys follow each other on Hopp's relays; one shares, the other looks.
2026-11-02: each found the other mutual (`#p` answered by damus, nos.lol,
primal), the key reached the inbox, and the list opened.

## When what goes out and comes in (2026-11-02)

- **The sharer's device** sends its list when it changed: five minutes after
  the subscriptions last changed, and with every refresh — opening Discover
  or the Nostr page, the feed refresh. A key for a new inbox goes out after a
  full read has shown that inbox.
- **A full read** (contact lists, notes, lists, public acts): right after a
  switch; on opening the Nostr page when older than five minutes; on Discover
  coming to the screen when older than ten; else hourly with the feed refresh.
  Nothing at all while both switches are off.
- **Looking again** between full reads, each time Discover or the Nostr page
  comes to the screen, at most every two minutes: the lists of mutual
  contacts who share, one request each — a changed list, and one whose key
  came after the read. A list that does not come keeps what was there.
- So a show the sharer subscribes to reaches the listener about seven
  minutes later at most, with Hopp open on both sides; else when both open it.
