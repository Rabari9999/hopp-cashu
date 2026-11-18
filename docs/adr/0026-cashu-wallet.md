# ADR 0026 — A Cashu ecash wallet in the app

- **Status:** proposed (branch `feat/cashu-wallet`, Nut November 2026)
- **Date:** 2026-11-17

## Context

ADR 0005 plans a built-in Cashu wallet for Phase 3: "micro payments,
nutzaps, direct ecash". Until then a listener without a wallet that speaks
NWC cannot boost or stream at all (ADR 0005, "Without NWC"). Setting up
Alby Hub, LNbits or a Coinos account is a large first step for someone who
only wants to send a few sats to a show they like.

Cashu ecash is the smallest step: the listener picks a mint, pays one
Lightning invoice from any wallet app, and holds the sats in Hopp. Its
costs are known (ADR 0005's table): the mint holds the funds, and a mint
cannot send keysend.

What the wallet must keep from the rest of Hopp's payment code:

- the router, the daily budget, the spending lock, the payment journal and
  `ProvenWallet`'s preimage check (ADR 0005, Payment safety);
- secrets only in the platform key store, never the database or a backup
  (ADR 0015);
- no contact with a new server without the listener's tap (ADR 0015).

## Decision

### One mint, behind `WalletClient`

`CashuWallet` implements the same `WalletClient` interface as NWC and WebLN.
`WalletRepository.client()` wraps it in `ProvenWallet` like the others, so
boosts, streaming and Sovereigncast invoices go through unchanged.
`info()` lists `pay_invoice` and `get_balance` only. `payKeysend` answers
`NOT_IMPLEMENTED`, and the router skips keysend recipients exactly as it
does for an NWC wallet without `pay_keysend`.

The wallet holds ecash at **one mint**. A second mint would mean choosing
between mints for every payment and swapping across them through Lightning.
That is a later step if listeners ask for it.

Ecash and NWC do not mix. The ecash wallet can be chosen only while no NWC
string is stored. Connecting NWC later leaves the ecash where it is, and
it is back in use when NWC is disconnected. In the browser no wallet is
kept (ADR 0022, W5), so ecash is apps only.

### Written in Kotlin, not CDK

The Cashu Development Kit (Rust) would bring its own HTTP client, its own
storage and a native library per target, including wasm. Hopp has secp256k1
on every target already (ACINQ on JVM, Android and iOS; noble on wasm), and
the protocol parts a wallet needs are small. Built here:

| NUT | What | Where |
|---|---|---|
| 00 | BDHKE (hash_to_curve, blind, unblind); tokens `cashuA` (v3 JSON) and `cashuB` (v4 CBOR) | `Bdhke.kt`, `Token.kt`, `Cbor.kt` |
| 01, 02 | keys and keysets; keyset ids v1 and v2 checked against the keys; `input_fee_ppk` | `MintClient.kt` (`KeysetId`), `Coins.fee` |
| 03 | swap: receiving a token, making one to send | `CashuWallet.receive`, `send` |
| 04 | mint quote and mint: adding sats by Lightning | `CashuWallet.requestInvoice` |
| 05 | melt quote and melt: paying an invoice (`amountless` for invoices without an amount) | `CashuWallet.payInvoice` |
| 06 | mint info, asked once when the mint is chosen | `WalletRepository.connectEcash` |
| 07 | proof state: which inputs of a lost request are spent | `CashuWallet.releaseInputs` |
| 08 | blank outputs for the change of the fee reserve | `Coins.blankCount` |
| 09 | restore: the signatures of an answer that was lost | `CashuWallet.restore` |
| 12 | DLEQ proofs on every signature the mint returns | `Bdhke.verifyDleq`, `Coins.unblind` |

The codec and the crypto are checked against the NUT test vectors
(`BdhkeVectorsTest`, `CashuTokenTest`, `MintClientTest`).

### Proofs are money

- **Where they live.** The whole wallet (mint, proofs and operations in
  flight) is one `SecretStore` entry, `cashu_wallet`, in the Keystore or
  Keychain. One entry means moving proofs into a pending operation is a
  single write, never half done. Proofs are not in the database, not in
  sync, not in the backup.
- **A journal before every request.** Every swap, mint and melt is written
  as a `PendingOp` (its inputs, and the outputs with their blinding
  factors) before the request leaves. Its inputs leave the balance at that
  moment. An answer that never arrives is taken in on the next call: a
  melt by its quote state, a mint by its quote state and NUT-09, a swap by
  NUT-09 and otherwise NUT-07 (unspent inputs come back).
- **Unknown, not failed.** A melt with no answer (a dropped connection, a
  cancel after sending, a `PENDING` quote) is `OUTCOME_UNKNOWN`, as an NWC
  payment without an answer is: it may have paid. A refusal is
  `PAYMENT_FAILED` only once the quote says `UNPAID` and the inputs are back.
- **Nothing unchecked comes in.** Keys must hash to their keyset id, so a
  mint cannot hand one wallet its own keys to tag it. Every signature must
  be on the keyset and amount asked for, and its DLEQ proof must verify.
  A proof failing any of these is refused.
- **Privacy towards the mint.** A proof's DLEQ keeps its `r` for the
  listener's own checks; it is stripped before the proof goes to the mint,
  where `r` would link the proof to its signing.
- **Only https.** A mint must be https, or plain http to an onion service:
  proofs are bearer money and would be readable on the way.
- **Disconnect never throws money away.** While the wallet holds proofs or
  an operation is in flight, `disconnect()` refuses and the page says to
  withdraw or send them first.

### Fees

The input fee (NUT-02) is part of every selection: coins are chosen as the
best of three candidates (powers of two up to the amount, the smallest
single proof that covers it, largest first), by least fee and then least
change, each trimmed of proofs it can do without. A melt reserves the
mint's `fee_reserve` and gets the unused part back as change through blank
outputs. The Lightning fee actually paid is reported in `PaymentResult`, so
the payment history shows it as it does for NWC.

### The wallet page

No wallet yet: under the NWC form, a mint address and "Use this mint".
Only that tap contacts the mint (`/v1/info`, `/v1/keysets`), and the
mint is kept only once it answers with an active sat keyset.

With ecash in use: the balance, what the wallet reaches (Lightning address
yes, keysend no), and five actions:

- **Add by Lightning** (an invoice shown as QR, then **Check**);
- **Send ecash** (a `cashuB` token shown as QR and copied as a secret, with
  a line saying whoever holds it can take it);
- **Receive** a pasted token (from the same mint);
- **Withdraw** to a Lightning invoice;
- **Disconnect** (refused while sats are held).

Strings are in every complete language (`StringResourcesTest`).

## Consequences

- A listener without an NWC wallet can boost Lightning-address recipients
  and pay Sovereigncast invoices after one top-up. Keysend recipients stay
  skipped, as with any wallet without `pay_keysend`.
- The mint holds the listener's sats. The intro says so and advises small
  amounts. Hopp suggests no mint.
- Paying needs the mint to return the preimage (`payment_preimage`). A melt
  without one is `OUTCOME_UNKNOWN` under `ProvenWallet`'s rule. That is the
  correct outcome under Hopp's rules, but a mint that never returns
  preimages is not usable for boosts.
  Checked on 2026-11-18 against testnut (cdk-mintd 0.18.1, v2 keysets,
  `input_fee_ppk` 100): minting, sending, receiving and melting all work
  (`LiveMintTest`, run only with `HOPP_LIVE_MINT` set). A melt the mint
  settles internally (an invoice it issued itself) comes back `PAID` with
  no preimage, and `ProvenWallet` makes it `OUTCOME_UNKNOWN`: the sats are
  spent, and the history says unknown. A payment over Lightning from a
  mint with a real node is expected to carry one. Whether a mint's `PAID`
  may stand in for the preimage is the maintainer's call; this ADR keeps
  the rule as it is.
- Tests:
  - `CashuWalletTest` and `CoinsTest` run against a fake mint
    (`FakeMint`, `FakeMintKeys`) that signs with real DLEQ proofs and can
    refuse, pend, fail or drop the connection after doing the work.
  - `ValueServiceTest` covers a boost paid from ecash.
  - `EcashWalletViewModelTest` and `EcashWalletPageTest` (Robolectric)
    cover the page.
- Not done:
  - nutzaps (NIP-61) and NUT-18 payment requests;
  - several mints;
  - P2PK-locked tokens (NUT-10/11);
  - a seed-based backup (NUT-13), which means a lost device loses the
    ecash, as a lost NWC string does not.
