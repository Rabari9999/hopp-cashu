# ADR 0024 — What friends subscribe to

- **Status:** accepted — not built yet
- **Date:** 2026-11-01

## Context

The maintainer asked for a way to discover shows through one's Nostr
contacts: a listener sees which shows their contacts who use Hopp have
subscribed to, and a show many of them hear ranks higher. Two ways were laid
out the same day: a public list of one's subscriptions (simple, readable by
any client, but readable by anyone — the follow condition would only be a
filter in Hopp's view), or a list sealed so that only mutual contacts can
open it. The maintainer chose the sealed one and settled:

- the only condition is that **both follow each other** (NIP-02, kind 3);
- **sharing is each listener's own choice** — off by default, and seeing
  what others share does not depend on sharing one's own;
- **single shows can be left out** of what is shared;
- **subscriptions only** for now — no episodes, favourites or "playing now".

Hopp's privacy line (ADR 0006, 0015) is that listening stays private unless
the listener makes something public on purpose, and Hopp says so before. The
sync key (ADR 0021, 5, amended 2026-10-21) already shows how to seal data
with a key of its own so that the listener's signer is asked once, not for
every change and never in the background.

## Decision

### 1. Who counts as a friend

A **friend** is a contact both ways: their pubkey is in the listener's
latest contact list (kind 3) and the listener's pubkey is in theirs. Both
lists are public; Hopp reads the listener's own from their write relays and
each contact's from that contact's write relays (NIP-65, the outbox; the
look-up relays when a contact has no relay list). Nothing else — no Hopp
setting, no request, no acceptance — makes or breaks a friendship: following
someone in any Nostr client does. A key made in Hopp starts without
contacts; the feature is for listeners who already have a Nostr identity,
and the page says so.

### 2. Two keys of its own, one public note

Each listener who turns the feature on gets two key pairs, made on the
first device and kept in its secret store:

- the **share key** seals and signs the listener's shared list (2), and
- the **inbox key** opens what friends seal for this listener (3).

Both travel in one kind-30078 event by the listener, `d` = `hopp/friends`
(the **friends note**). Its content holds the two private keys,
NIP-44-sealed to the listener's own key — as the sync keyring — so another
device takes them over with one question to the signer. Its tags name the
public halves in the open: `["share", <pubkey>]` when the listener shares,
`["inbox", <pubkey>]` when they look at what friends share.

The friends note is **public that the listener uses Hopp's friends
feature**, and with which keys. Hopp says so before it is published — the
same moment ADR 0021, 8 asks for before a first public action. What the
listener subscribes to is never in it.

### 3. Sharing

- **The shared list** is a kind-30078 event signed by the share key,
  `d` = `hopp/friends/shelf`, its content NIP-44-sealed by the share key to
  itself: per show the feed's `podcast:guid` where it has one, its feed URL,
  its title, and when it was subscribed. Shows the listener left out (4)
  are not in it. It is sent again when the subscriptions change, at most
  every five minutes, and with sync's round.
- **A key per friend.** For each friend whose friends note names an inbox
  key, the share key's private half is sealed (NIP-44, share key → that
  inbox key) in a kind-30078 event signed by the share key,
  `d` = `hopp/friends/key/<inbox pubkey>`. Relays see that one Hopp user's
  share key has a key for another's inbox — no more than both public contact
  lists and both friends notes already tell; no gift wrap (NIP-59) is
  needed to hide it.
- **A friend lost, a new key.** When someone stops being a friend — either
  side unfollows, or their friends note no longer names an inbox — Hopp
  makes a new share key: a new friends note (the signer asked once), the
  list sealed again, a key for each friend left, and everything the old
  share key signed replaced by an empty event and deleted (NIP-09), signed
  by the old key — as "Renew sync key" does. Whoever was a friend keeps what
  they saw until then; they see nothing that changes after.
- **Turning sharing off** does the same without a new key: the list and its
  keys emptied and deleted, the `share` tag dropped from the friends note.

### 4. Leaving a show out

A show's page has "Not shared with friends" (in its menu, shown only while
sharing is on); the Nostr page lists the shows left out. It is a setting of
the subscription and travels with sync in `hopp/subscriptions`, so a show
left out on one device is left out on all. A show subscribed while sharing
is on is shared unless the listener leaves it out.

### 5. Seeing what friends share

With "See what friends subscribe to" on, Hopp reads, once per feed refresh
and at most every hour:

1. the listener's contact list, then each contact's contact list and friends
   note — only from contacts in the listener's list, in batches of authors;
2. for each friend whose note names a share key, the key sealed for the
   listener's inbox and the shared list it opens.

What comes back is kept on the device and shown:

- **Discover: "Your friends listen to"** — shows ranked by how many friends
  subscribe, then by how recently one did; shows the listener already has
  are left out. Each row names up to three friends with their avatars
  ("Anna, Ben and 3 more").
- **A show's page:** "4 friends listen to this" with their avatars.
- **A friend's page**, opened from those: the shows they share.

A show is matched by its `podcast:guid`, else by its feed URL. Nothing of
this leaves the device: who is a friend and what they share is never
published or synced. Turning seeing off forgets it and drops the `inbox`
tag; friends' Hopp then stops sealing a key for this listener.

### 6. Settings

Two switches on the Nostr page, both off:

- **Share my subscriptions with friends** — with the list of shows left out.
- **See what friends subscribe to.**

The first one turned on publishes the friends note after Hopp says what
becomes public (2). External signers are asked only when a friends note is
made or changed; the background asks nothing of them.

## Consequences

- **Only Hopp reads it.** The sealed list is Hopp's own format under kind
  30078 (application data); other clients cannot show it, and friends of
  friends cannot be reached. A public list for those who want one can follow
  as its own decision.
- **Many contacts, many requests.** Reading a few hundred contacts' lists
  from their own relays costs requests; it runs with the feed refresh,
  never in a standing connection, and kept answers are used in between.
  Sharing with a few hundred friends costs as many small key events, sent
  again only when the share key changes.
- **ADR 0015** gains the friends note on Nostr relays: that the listener uses
  the friends feature and its two public keys.
- **ADR 0021, 5:** a show left out of sharing travels in
  `hopp/subscriptions` (a new field of an entry; older devices keep it).
- **Built in stages:** (1) friends from the contact lists and the friends
  note; (2) sharing, leaving shows out; (3) seeing — Discover, show page,
  friend's page; (4) a new share key when a friend is lost.
