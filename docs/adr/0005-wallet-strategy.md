# ADR 0005 — Wallet strategy: NWC first

- **Status:** accepted
- **Date:** 2026-10-06

## Context

Hopp initiates four kinds of payments:

| Flow | Recipient | Mechanism | Confirmed per payment? |
|---|---|---|---|
| Boost with message | `podcast:value` recipients | keysend (bLIP-10 TLV boostagram) or Lightning address | yes |
| Streaming sats (per minute) | same, split by shares | as boost, batched every few minutes | no — runs in the background |
| Sovereigncast actions (Release Boosting, Boost2Unlock, suggestion, vote boost, Q&A) | the instance's wallet | bolt11 invoice from the Listener API | yes |
| Ecash | instance / nutzap (NIP-61) | Cashu token handed over directly | yes |

**Keysend is the constraint, not the wallet.** The self-hosted Sovereigncast
feed mostly carries `method="keysend"` recipients (a node pubkey). Many current
wallets cannot *send* keysend: Phoenix, Breez Spark, Cashu mints, and any
external wallet opened via a `lightning:` link. PodHome feeds additionally carry
`type="lnaddress"` recipients (with `customKey="lnurl_callback"`), which any
wallet that pays invoices can reach via LNURL-pay.

Options considered:

| Option | Keysend | Invoice / Lightning address | Streaming without prompts | Custody | Effort |
|---|---|---|---|---|---|
| NWC (Alby Hub, Coinos, LNbits, Zeus, …) | wallet-dependent | ✅ | ✅ | listener | low |
| External wallet via `lightning:` link | ❌ | ✅ | ❌ | listener | minimal |
| Built-in Breez Spark | ❌ (send) | ✅ | ✅ | self-custodial in Hopp | high |
| Built-in Cashu (CDK) | ❌ | ✅ via melt | ✅, cheap for micro amounts | mint | medium |
| Built-in custodial (held by us) | — | — | — | us | excluded: custody of others' funds, licensing |

## Decision

### Wallets

- **Phase 1:** **Nostr Wallet Connect** is the primary wallet. A `lightning:`
  deep link to an external wallet is the fallback for single invoices
  (Sovereigncast actions, Lightning-address recipients). Streaming sats and
  keysend boosts require NWC.
- **Phase 3:** built-in self-custodial Spark wallet (for listeners without a
  wallet) and a Cashu wallet (micro payments, nutzaps, direct ecash to
  Sovereigncast instances).
- **Never** a custodial wallet operated by us.

### Payment router (per recipient)

- keysend recipient → keysend if the NWC wallet lists `pay_keysend` in
  `get_info`; otherwise unpayable.
- `lnaddress` recipient → LNURL-pay → bolt11 → `pay_invoice` (works with any
  wallet).
- **Metadata for `lnaddress` recipients** (decided 2026-10-07 after spike 2):
  1. If the wallet can keysend and the address publishes
     `/.well-known/keysend/<user>`, pay **by keysend** with the full bLIP-10
     boostagram (and the published custom record).
  2. Otherwise LNURL-pay with a LUD-12 comment. For a **Sovereigncast show**
     the comment follows the podcast namespace's `rss::payment` convention
     (`rss::payment::boost <url> <message>`), and the URL is served by the
     show's Sovereigncast instance with the `x-rss-payment` header — the
     instance sees the show's boosts anyway. For **any other show** the comment
     is just the message: Hopp hosts nothing (ADR 0015).
- **Unpayable share → skipped and shown.** It is neither paid nor charged, and
  it is not redistributed to the other recipients: the podcaster's split is
  respected. The listener sees which recipient could not be reached.

### Quick boost outside the app (added 2026-10-08)

The media controls on the lock screen and in the notification carry a **Boost**
button (Android; iOS offers no such slot). One tap sends a fixed amount —
100 sats by default, adjustable, or off — without a dialog and without a
message, never past the daily budget; a notification says what arrived. It
appears only with a wallet connected. (Amended 2026-10-11: the notification
setting — nothing, failures only, everything — applies to every boost, also
those sent from the boost sheet inside the app.)

### Paying oneself (added 2026-10-08, corrected the same day)

A share whose recipient is the paying wallet itself is skipped as "paying
yourself" — wallets refuse such payments, and podcasters are often recipients
of their own shows. **Only the wallet's Lightning address** (`lud16` in the
connection string) identifies it: a node key does not, because sub-wallets on
one node (Alby Hub, LNbits …) share it and are told apart by the recipient's
`customKey`/`customValue`. A share to the wallet's own node is therefore paid
like any other, with its custom record; if it really is this wallet, the
wallet refuses and the listener sees its reason. A Lightning address whose
keysend route fails is paid by LNURL after all.

### Whole sats, fees on top (added 2026-10-09)

A boost is split in **whole sats** (largest remainder), so the recipients
together receive exactly the amount the listener chose: 21 sats at 80/20 are
17 + 4. Splitting to the millisat gave 16.8 + 4.2, and custodial accounts that
credit whole sats only (Alby's) received 16 + 4. **Routing fees are paid by
the listener's wallet on top**, never taken from the shares; the boost result
and the history show them apart. This holds for every boost — episode, show,
lock screen. Streaming splits its accruals to the millisat and pays whole sats
per recipient, carrying the rest.

### App split

**Opt-in, off by default.** "Support Hopp" is a voluntary, visible split in the
settings. No hidden fee, in line with "Honest".

### Streaming sats

**Off until the listener turns it on**, with a rate and a budget. Hopp
proposes the feed's `suggested` rate. Sub-sat shares accumulate locally and
are paid once they reach a whole sat; payments are batched.

### Safety

- Every invoice from a Sovereigncast instance is **decoded and paid only if its
  amount exactly matches** what the listener chose — a malicious instance must
  not be able to charge more.
- The NWC connection string is stored in Android Keystore / iOS Keychain.
- When connecting, Hopp recommends setting a spending limit on the NWC
  connection in the wallet itself; Hopp enforces its own budget on top.
- Boostagrams carry podcast GUID and episode GUID (and `app_name: Hopp`), which
  Sovereigncast's invoice→episode auto-linking relies on.

## Consequences

- **Spike:** how boostagram metadata travels with Lightning-address payments.
  A LUD-12 `comment` carries the message text but not the structured TLV fields;
  check what Fountain/Podverse/CurioCaster do, and the spec's alternative
  `/.well-known/keysend/<user>` resolution.
- **Server work (podcasting_tools):** Sovereigncast feeds should also emit
  `lnaddress` recipients — the Spark integration already gives an instance a
  Lightning address — so boosts reach the podcaster from wallets without keysend.
  *Checked 2026-10-07:* `lnaddress` recipients were already possible on the
  self-hosted path (both V4V editors offer the type and resolve the LNURL
  callback); a recipient stored without a type is now published as
  `lnaddress` when its address is `name@domain`. Defaulting the show's own
  recipient to a Spark Lightning address waits for the Spark wallet, which is
  not on `main` yet.
- **Server work (podcasting_tools), from spike 2:** a Listener API endpoint
  that stores a boost's metadata and serves it at a URL with the
  `x-rss-payment` header; and `_split_recipients` should treat `fee="true"`
  recipients as a percentage off the top, as the value spec (and Hopp) does.
- **Phase 3:** Apple requires developers offering wallet functionality to be
  enrolled as an organisation (to be verified before Phase 3).

### Without NWC (added 2026-10-12)

Boosts and streaming need a connected wallet: a boost is split among the feed's
recipients, some reachable only by keysend, and a wallet app reached through
`lightning:` only ever takes one invoice. Release Boosting and Boost2Unlock at
Sovereigncast shows are single invoices from the instance and work with any
wallet app. The boost sheet without a wallet says so in one paragraph, with the
NWC explainer link when it answers; the website (hopp.sovereigncast.space)
says the same since 2026-10-12 — it had promised boosts "with an external
wallet".

### Payment safety (added 2026-10-12, security report of 2026-10-10)

An external review (Zettbot, commit `ca3811b`) rated four findings high; all
four held and are fixed, each with a regression test:

- **H-01 — the exported media session offered the boost to any app.** The
  session must stay exported (Android Auto, System UI, Bluetooth), so the boost
  command is offered — and checked again when it arrives — only for Hopp
  itself, its media notification, Android Auto and controllers the system
  trusts with media control; everything else gets playback controls only and a
  refused command (`PlaybackService.mayBoost`, `PlaybackServiceTest`). A second
  quick boost while one is paying is ignored.
- **H-02 — fee recipients could take more than the boost.** Fees claiming
  100 % or more together now share the total by weight, splits are capped as
  weights so nothing overflows, and every division checks that the shares never
  add up to more than the total (`SplitsTest`, 5 000 random blocks).
- **H-03 — the daily budget was not atomic.** Boosts, quick boosts and
  streaming batches run one at a time under one lock, from the budget check to
  the logged payment (`ValueServiceTest.parallelQuickBoostsStayWithinTheBudget`).
- **H-04 — an unanswered payment counted as failed.** Once a request has gone
  to the wallet, only the wallet's answer — a result or an error it reports —
  decides; a timeout, a dropped connection, an unreadable answer or (since
  2026-10-18) the relay's refusal is `OUTCOME_UNKNOWN`. Such a share is not followed by an
  invoice after a keysend, not retried by the next streaming batch, and shows
  as "outcome unknown" in the history. Clearing it up through NIP-47
  `lookup_invoice` (with a preimage Hopp chooses for keysends) is a later step.

The medium findings, 2026-10-12:

- **M-01** — the NWC connection string travelled as a navigation argument and in
  `rememberSaveable`, i.e. in the activity's saved state. It now goes from a
  wallet link to the wallet page in memory only (`IncomingLinks.handOverWallet`),
  the field is plain `remember`, masked with a "show" toggle, cleared once
  connected, and the wallet page sets `FLAG_SECURE`.
- **M-02** — feeds, OPML files, chapters, transcripts, directory answers, the
  Listener API and LNURL were read whole, whatever their size. All go through
  `getLimited` (`net/Http.kt`, `Limits`): refused when Content-Length says more,
  stopped when a stream goes past it — 32 MiB for a feed down to 512 KiB for
  LNURL. Chapters and transcripts that answer with an error are not cached.
- **M-03** — downloads stop at 4 GiB or the storage limit, whichever is
  smaller, announced or not; a web page (`text/html`, JSON) is refused; a
  refused download leaves no `.part`. HTTPS→HTTP redirects stay allowed: podcast
  trackers do that, and an audio file changed in transit pays no one.
- **M-04** — kept as it is, on purpose: an LNURL invoice without a description
  hash is still accepted (npub.cash, a recipient of Nodesignal's splits, sends
  none, and an empty description). The metadata and the invoice come from the
  same server over the same TLS connection, so the hash binds the invoice to
  what that server itself chose; the amount check is what protects the payment.
- **M-05** — streaming settlement rows are keyed by type, address *and* custom
  record, so sub-wallets on one node are no longer merged and paid to one of
  them. Older rows keep their key and pay out to the recipient they stored.
- **M-06** — a Sovereigncast invoice is refused unless its BOLT11 payment hash
  is the one the instance names, which Hopp then waits on.
- **M-07** — betas show as "Hopp β" and sign with a key of their own once CI
  has one (docs/signing.md); until then, the public debug key.
- **M-08** — "run fork pipelines in the parent project" is off for hopp,
  sovereigncast-space and hopp.sovereigncast.space, so no maintainer click
  can put a fork's code on the self-hosted runner. Making the runner protected
  (main and tags only) is left to decide: pipelines of other branches would then
  wait for a runner.

The low findings are still open.

### Payment safety, audit of 2026-10-18 (Zetti, Astra audit of Hopp 0.2.0)

The three payment findings held; fixed with regression tests that fail on
the code before:

- **H-01 — a relay's refusal counted as "not paid".** A relay could pass the
  request on to the wallet and still answer `OK false`; Hopp then paid the
  share again (an invoice after a keysend, the next streaming batch). The
  refusal is not signed by the wallet, so it is `OUTCOME_UNKNOWN` now
  (`NwcClientTest`, `BoostSenderTest`). The cost: a share an honest relay
  refused is not tried again and shows as unknown. A cancellation while the
  request goes out is unknown too, not "nothing sent".
- **M-02 — any answer with a `result` counted as paid.** A payment needs a
  `result_type` that is the method asked, a 32-byte preimage — for an invoice
  the one its payment hash commits to — and fees that are not negative;
  anything less is unknown. Wallets that leave the preimage out would show
  every payment as unknown: to check with the wallets of the device tests.
- **M-03 — unknown outcomes cost no budget, fees were counted afterwards.**
  An unknown outcome may have left the wallet: its amount goes into
  `payment.unknown_msat` (schema 9) and counts against the daily budget, also
  for an instance's invoice and a share left in flight. A payment leaves room
  for its fee first (`ValueService.feeReserve`: 1 %, at least a sat) — a
  reserve, not a cap; the wallet's own budget on the connection is the cap.
  An instance payment is logged even when its page closes mid-payment.

### Payment safety, audit of 2026-10-20 (Zettbot, Hopp 0.2.2 RC)

- **H-01 — a send that failed after the relay took the request counted as
  "not paid".** `NwcClient` turned only a cancellation during `send` into an
  unknown outcome; an ordinary I/O error (the socket closing as the frame
  left) came back as a plain failure, and the LNURL fallback paid the share
  again. Every exception from the moment the EVENT is sent is
  `OUTCOME_UNKNOWN` now; a failure before it (the REQ) stays a plain failure,
  nothing was out (`NwcClientTest`, `BoostSenderTest`).
- **M-01 — an authentic answer of the wrong shape threw past the unknown
  path.** A scalar `result`, or an `error` whose `code` is no string, raised a
  JSON exception that counted as "not paid". Every field of the answer is read
  as "if it has this shape"; what has not is unknown.
- **Instance payments past the budget and the lock (found in the same
  review).** Release Boosting, Boost2Unlock and suggestions paid their invoice
  straight from `NwcClient`: no daily budget, no fee reserve, not under the
  spending lock that keeps boosts, zaps and streaming batches apart, and in
  the history only afterwards. They go through `ValueService.payInvoice` now,
  as zaps do: one at a time, within the budget — checked before the instance
  is asked for an invoice, so it keeps no suggestion that is never paid — and
  **journalled before the wallet is asked** (audit M-09, for single
  invoices): the row is written as an unknown outcome that counts against
  the budget, and the wallet's answer settles it; a process killed in
  between leaves a trace, not nothing. Boosts with several shares still log
  after the last share; streaming has its in-flight marker.
- **A secret store that did not answer read as "nothing stored" (found in
  the same review).** `KeystoreSecretStore.get` turned every failure — the
  Keystore not answering after a boot, say — into null; Hopp then showed no
  identity and no wallet, and making a new identity wrote over the key that
  was only unreadable for the moment. `SecretStore` now tells
  [`SecretUnreadable`] from "none stored", on Android and iOS; nothing is
  written over such an entry, the wallet and identity pages say so and offer
  only "forget" (which removes it), and the entry is back once the store
  answers (`IdentityTest`, `ValueServiceTest`).
- **M-04 / M-08 — a mute list or relay list that could not be read was
  replaced.** `RelayPublisher.latest` answered null both for "no relay has
  it" and "the relays could not be read"; `CommentWriter.mute` and
  `IdentityRepository.changeRelays` then published a new list over the one
  that was there — the mute list with its private part gone, the relay list
  from Hopp's local copy over what another app had set. The read is
  three-valued now (`latestChecked`: found / none, and every relay said so /
  not known): found nowhere *and* a relay not answering throws
  `ListNotRead`, nothing is published, the page says so and the listener
  tries again (`RelayPublisherTest`, `CommentsTest`, `IdentityTest`).
