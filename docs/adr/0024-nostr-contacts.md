# ADR 0024 — What your Nostr contacts listen to

- **Status:** accepted by the maintainer 2026-11-01 (sealed sharing, the
  public contacts note, two switches); revised 2026-11-02 (maintainer): the
  wording, public signals, "a subscription is no recommendation" — built
  on branch `0.3.3` the same day (`docs/tasks/nostr-contacts.md`); zaps and
  follows of a show's npub not yet
- **Date:** 2026-11-01, revised 2026-11-02

## Context

The maintainer asked for a way to discover shows through one's Nostr
contacts: a listener sees which shows their contacts who use Hopp have
subscribed to, and a show many of them hear ranks higher. Two ways were laid
out on 2026-11-01: a public list of one's subscriptions (simple, readable by
any client, but readable by anyone — the follow condition would only be a
filter in Hopp's view), or a list sealed so that only mutual contacts can
open it. The maintainer chose the sealed one and settled:

- the only condition is that **both follow each other** (NIP-02, kind 3);
- **sharing is each listener's own choice** — off by default, and seeing
  what others share does not depend on sharing one's own;
- **single shows can be left out** of what is shared;
- **subscriptions only** for now — no episodes, favourites or "playing now".

On 2026-11-02 a tester (Dennis) pointed out that on Nostr the people one
follows are contacts, connections — not friends: the words must say that it
is the Nostr contact list and no list of Hopp's with a choice of special
contacts. The maintainer also asked to look at Zapstore, which shows on an
app's page the people one follows who zapped it or put it into a "stack"
(a public, curated list of apps, NIP-51 kind 30267) — "Stacked or zapped
by" with their avatars, from one's contact list alone, as those acts are
public anyway; which of one's contacts follow the publisher, ranked by an
outside web-of-trust service (Vertex); and the line *"These are not
endorsements"*. Private stacks there are sealed to oneself, apart from the
public ones.

Hopp's privacy line (ADR 0006, 0015) is that listening stays private unless
the listener makes something public on purpose, and Hopp says so before. The
sync key (ADR 0021, 5, amended 2026-10-21) already shows how to seal data
with a key of its own so that the listener's signer is asked once, not for
every change and never in the background.

## Decision

### 1. The words

It is the listener's **Nostr contacts** — the people they follow on Nostr
(their contact list, kind 3) — never "friends", and never a list of Hopp's.
Where both follow each other, **mutual Nostr contacts**. The settings say it
once: *"Everyone you follow on Nostr who follows you back — no list of its
own."* For example: "Share my subscriptions with mutual Nostr contacts",
"Show what my Nostr contacts listen to", Discover's "With your Nostr
contacts", a show's "4 of your Nostr contacts listen to this". In the code
and on the relays: `contacts`, not `friends`.

### 2. Who counts

- **For shared subscriptions** (4–5): mutual contacts — the listener's
  pubkey in their latest contact list and theirs in the listener's. Both
  lists are public; Hopp reads the listener's from their write relays and
  each contact's from that contact's write relays (NIP-65, the outbox; the
  look-up relays when a contact has no relay list).
- **For public signals** (6): everyone the listener follows — one way, as
  Zapstore does: what is public is public.

Nothing in Hopp makes or breaks a contact: following someone in any Nostr
client does. A key made in Hopp starts without contacts; the feature is for
listeners who already have a Nostr identity, and the page says so.

### 3. Two keys of its own, one public note

Each listener who turns a switch on (7) gets two key pairs, made on the
first device and kept in its secret store:

- the **share key** seals and signs the listener's shared list (4), and
- the **inbox key** opens what mutual contacts seal for this listener (5).

Both travel in one kind-30078 event by the listener, `d` = `hopp/contacts`
(the **contacts note**). Its content holds the two private keys,
NIP-44-sealed to the listener's own key — as the sync keyring — so another
device takes them over with one question to the signer. Its tags name the
public halves in the open: `["share", <pubkey>]` when the listener shares,
`["inbox", <pubkey>]` when they look at what contacts share.

The contacts note is **public that the listener uses this feature of
Hopp's**, and with which keys. Hopp says so before it is published — the
same moment ADR 0021, 8 asks for before a first public action. What the
listener subscribes to is never in it.

### 4. Sharing

- **The shared list** is a kind-30078 event signed by the share key,
  `d` = `hopp/contacts/shelf`, its content NIP-44-sealed by the share key
  to itself: per show the feed's `podcast:guid` where it has one, its feed
  URL, its title, and when it was subscribed. Shows the listener left out
  (4a) are not in it. It is sent again when the subscriptions change, at
  most every five minutes, and with sync's round.
- **A key per mutual contact.** For each mutual contact whose contacts note
  names an inbox key, the share key's private half is sealed (NIP-44, share
  key → that inbox key) in a kind-30078 event signed by the share key,
  `d` = `hopp/contacts/key/<inbox pubkey>`. Relays see that one Hopp user's
  share key has a key for another's inbox — no more than both public contact
  lists and both contacts notes already tell; no gift wrap (NIP-59) is
  needed to hide it.
- **A mutual contact lost, a new key.** When someone stops being a mutual
  contact — either side unfollows, or their contacts note no longer names an
  inbox — Hopp makes a new share key: a new contacts note (the signer asked
  once), the list sealed again, a key for each mutual contact left, and
  everything the old share key signed replaced by an empty event and
  deleted (NIP-09), signed by the old key — as "Renew sync key" does.
  Whoever was one keeps what they saw until then; they see nothing that
  changes after.
- **Turning sharing off** does the same without a new key: the list and its
  keys emptied and deleted, the `share` tag dropped from the contacts note.

**4a. Leaving a show out.** A show's page has "Not shared with Nostr
contacts" (in its menu, shown only while sharing is on); the Nostr page
lists the shows left out. It is a setting of the subscription and travels
with sync in `hopp/subscriptions`, so a show left out on one device is left
out on all. A show subscribed while sharing is on is shared unless the
listener leaves it out.

### 5. Seeing what mutual contacts share

With "Show what my Nostr contacts listen to" on, Hopp reads, once per feed
refresh and at most every hour: the listener's contact list, then each
contact's contact list and contacts note — only from contacts in the
listener's list, in batches of authors; for each mutual contact whose note
names a share key, the key sealed for the listener's inbox and the shared
list it opens.

### 6. Public signals

What the listener's contacts did in public already counts too, without any
sharing — read with the contact lists, under the same switch:

- **Boosts made public and comments** on an episode or show: notes and
  NIP-22 comments carrying its `podcast:item:guid` or `podcast:guid`
  (NIP-73), as Hopp (ADR 0021, 6) and Fountain write them;
- **zaps** to a show's npub or to its episode notes (receipts, kind 9735,
  whose zap request a contact signed);
- **following a show's npub**, where the show names one (ADR 0021, 3): in
  the contacts' lists Hopp reads anyway.

### 7. What the listener sees

What comes back is kept on the device and shown:

- **Discover: "With your Nostr contacts"** — shows ranked by what contacts
  did: a boost or zap weighs most, then a comment, then a subscription
  shared, then following the show; newer before older; shows the listener
  already has are left out. Each row names up to three contacts with their
  avatars ("Anna, Ben and 3 more").
- **A show's page:** a row of avatars, "Heard, boosted or commented on by
  …" — as Zapstore's "Stacked or zapped by" — and its count ("4 of your
  Nostr contacts listen to this").
- **A contact's page**, opened from those: the shows they share and what
  they boosted or commented on.
- Wherever shared subscriptions show, one line says what they are: **"A
  subscription is no recommendation."** — as Zapstore says of follows, so
  nobody feels judged for what they listen to.

A show is matched by its `podcast:guid`, else by its feed URL. Nothing of
this leaves the device: who is a contact and what they share or did is
never published or synced.

### 8. Settings

**Only with a Nostr identity** (maintainer, 2026-11-02): without an npub in
Hopp, nothing of the feature is shown or done — no switches, no section in
Discover, no row on a show's page.

Two switches on the Nostr page, both off:

- **Share my subscriptions with mutual Nostr contacts** — with the list of
  shows left out.
- **Show what my Nostr contacts listen to** — shared subscriptions and
  public signals.

The first one turned on publishes the contacts note after Hopp says what
becomes public (3). External signers are asked only when a contacts note is
made or changed; the background asks nothing of them. Turning seeing off
forgets what was read and drops the `inbox` tag; contacts' Hopp then stops
sealing a key for this listener.

### 9. Not taken

- **A web-of-trust service** (Zapstore's Vertex) to rank which contacts
  matter: each look-up tells a third party whom the listener is curious
  about. Hopp ranks from the contact lists alone.

### 10. An idea for later: public recommendation lists

Zapstore's "stacks" for podcasts: lists a listener puts together on purpose
and publishes — NIP-51 sets naming shows by `podcast:guid` — readable by
any client, and a strong public signal (6). It would draw the line clearly:
all subscriptions private, shared only with mutual contacts; what one
recommends, public. Not decided (maintainer, 2026-11-02);
`docs/open-questions.md`, 20.

## Consequences

- **Only Hopp reads the shared lists.** They are Hopp's own format under
  kind 30078 (application data); other clients cannot show them, and
  contacts of contacts cannot be reached. Public signals, by contrast, come
  from what any client publishes.
- **Many contacts, many requests.** Reading a few hundred contacts' lists
  and notes from their own relays costs requests; it runs with the feed
  refresh, never in a standing connection, and kept answers are used in
  between. Sharing with a few hundred mutual contacts costs as many small
  key events, sent again only when the share key changes.
- **ADR 0015** gains the contacts note on Nostr relays: that the listener
  uses the feature and its two public keys; and the relays asked for the
  contacts' lists and signals learn whose they are asked for.
- **ADR 0021, 5:** a show left out of sharing travels in
  `hopp/subscriptions` (a new field of an entry; older devices keep it).
- **Built in stages:** (1) contacts from the contact lists and the contacts
  note; (2) sharing, leaving shows out; (3) seeing shared subscriptions —
  Discover, show page, contact's page; (4) public signals; (5) a new share
  key when a mutual contact is lost.
