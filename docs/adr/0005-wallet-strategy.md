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
- **Unpayable share → skipped and shown.** It is neither paid nor charged, and
  it is not redistributed to the other recipients: the podcaster's split is
  respected. The listener sees which recipient could not be reached.

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
- **Phase 3:** Apple requires developers offering wallet functionality to be
  enrolled as an organisation (to be verified before Phase 3).
