# What your Nostr contacts listen to

- **Implements:** [ADR 0024](../adr/0024-nostr-contacts.md); release 0.3.3.
- **Status:** stages 1 and 2 built (2026-11-02, branch `0.3.3`).

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

- [ ] Read each mutual contact's key for this inbox, open their list, keep it.
- [ ] Discover "With your Nostr contacts", a show's row of avatars and count,
      a contact's page; "A subscription is no recommendation."

## Stage 4 — public signals

- [ ] Public boosts and comments (NIP-73 guids), zaps, follows of a show's npub.

## Stage 5 — a new share key when a mutual contact is lost
