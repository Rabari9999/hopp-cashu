# Spike 2 — NWC in pure Kotlin, keysend and Lightning addresses

- **Date:** 2026-10-07
- **Questions (ADR 0003, ADR 0005):** can Nostr Wallet Connect run in common
  Kotlin without a Rust library? How do boosts reach keysend and
  Lightning-address recipients, and how does the boost metadata travel?
- **Answer:** NWC works in common Kotlin with one native dependency
  (ACINQ's `secp256k1-kmp`, also used by Phoenix). Keysend boosts carry the
  full bLIP-10 metadata. Lightning-address boosts work with any wallet but
  carry metadata only by a community convention that needs a hosted URL —
  a decision for the maintainer (below).

## What was built

| Package | Content | Tests |
|---|---|---|
| `crypto/` | SHA-256, HMAC-SHA256, HKDF, ChaCha20, hex, secure random (expect/actual) | RFC 6234, 4231, 5869, 8439 vectors |
| `nostr/` | keys + Schnorr (secp256k1-kmp), NIP-01 events with exact id escaping, NIP-44 v2, relay transport (Ktor WebSockets) | all official NIP-44 vectors (35 conversation keys, 10 round trips, invalid payloads) |
| `value/nwc/` | NWC URI, client: info event, `pay_invoice`, `pay_keysend`, `get_balance`; NIP-44 only | end to end against an in-memory relay and a fake wallet service with real crypto |
| `value/` | splits (fee off the top, largest remainder), bLIP-10 boostagram + TLV records, BOLT11 checks, LNURL-pay + `.well-known/keysend` | BOLT11 spec vectors (valid and invalid), mocked LNURL endpoints |

## Findings

1. **No Rust needed for phase 1.** secp256k1-kmp ships JNI builds for
   JVM/Android and a cinterop build for iOS; everything else is common Kotlin.
   ChaCha20 and the hashes are ~200 lines, pinned to published vectors.
2. **NIP-44 only.** The client reads the wallet's `encryption` tag and refuses
   NIP-04-only wallets with a clear error instead of downgrading. Alby Hub and
   current wallets advertise `nip44_v2`; whether that holds for every wallet the
   community uses is checked with real connections (below).
3. **The invoice check of ADR 0005 is implementable without a full decoder:**
   amount from the HRP, description hash from the tagged fields, bech32
   checksum. A wrong amount or an invoice not committing to the LNURL metadata
   is refused before the wallet sees it.
4. **Fee recipients: Sovereigncast differs from the spec.** Its
   `_split_recipients` treats `fee="true"` recipients as ordinary weights; the
   value spec takes their split as a percentage off the top. Hopp follows the
   spec. → server todo in podcasting_tools.
5. **Lightning-address metadata** (the open question of ADR 0005). The podcast
   namespace now documents two routes:
   - `/.well-known/keysend/<user>` — a Lightning address may publish a node
     pubkey + custom record; a keysend-capable wallet can then send the **full
     bLIP-10 boostagram**.
   - **`rss::payment` metadata** (`docs/examples/value/metadata.md`): the LUD-12
     comment carries `rss::payment::{action} {url} {message}`, and the URL answers
     with an `x-rss-payment` header holding the full JSON. Works with any wallet —
     but **someone has to host that URL**. Fountain hosts its own. Hopp runs no
     server (ADR 0015).

   **Decided (ADR 0005):** keysend via the `.well-known/keysend` route when the
   wallet can; otherwise LNURL with an `rss::payment` comment whose URL the
   show's Sovereigncast instance serves, or a plain comment for other shows.

## Live test with a real wallet (2026-10-07)

Alby Hub via `relay.getalby.com`, 100 sats, from the build box (JVM,
`NwcLiveTest`, only runs with `HOPP_NWC_URI` set — never in CI):

- Info event: `nip44_v2 nip04`; `pay_keysend`, `pay_invoice`, `multi_pay_*`,
  `get_balance`, `make_invoice`, hold invoices, notifications.
- A real 21-sat Hopp boost on "Nodesignal-Recap – September 2026" through
  `BoostSender` with the feed's split: **16.8 sats keysend to Nodesignal's
  node** (bLIP-10 boostagram, `696969 = 2`) and **4.2 sats to Karsten's
  Lightning address via its `.well-known/keysend` route** (`696969 = 12`).
- 5 sats to the same address **by LNURL-pay** (`pay_invoice`), forcing the
  route a wallet without keysend takes; the invoice passed the amount and
  description-hash checks.
- All paid, 0 fees, balance 100 → 74 sats.

`BoostSender` is the router of ADR 0005: node → keysend; lnaddress → keysend
route if the wallet can, else LNURL with an `rss::payment` or plain comment;
unpayable shares skipped and reported. Unit-tested against the fake wallet
and mocked LNURL endpoints.

## Still to verify

- Relay behaviour in the wild over longer sessions: auth (NIP-42) prompts,
  rate limits, slow `OK`.
- ~~That the boost shows up in Sovereigncast as a boostagram on the episode~~
  — confirmed by the maintainer: Nodesignal's Sovereigncast assigned it to the
  right episode (invoice → episode linking by GUID works end to end).

## Server side, done after the spike (podcasting_tools)

- `_split_recipients` takes fee recipients off the top, like Hopp.
- Listener API: `POST /v1/shows/{guid}/payment-metadata` and
  `GET /v1/payment-metadata/{id}` (capability `payment_metadata`) — the URL for
  `rss::payment` comments, with the `x-rss-payment` header.
