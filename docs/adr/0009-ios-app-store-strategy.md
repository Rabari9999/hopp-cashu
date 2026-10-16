# ADR 0009 — iOS App Store strategy

- **Status:** accepted
- **Date:** 2026-10-06

## Context

App Review Guidelines that matter (checked 2026-10-06 — re-check the current
text before every submission):

1. **3.1.1 In-App Purchase** — unlocking content or functionality ("access to
   premium content") requires IAP; apps may not use their own unlock mechanisms,
   explicitly including "cryptocurrencies and cryptocurrency wallets".
2. **Gifts without IAP** are allowed if (a) completely optional, (b) **100 % of
   the funds go to the receiver**, and (c) the gift is **never connected to
   receiving digital content or services**.
3. **3.1.5 Cryptocurrencies** — wallet functionality only for developers
   enrolled **as an organisation**.
4. **2.3.1** — no hidden features enabled after review (e.g. by remote flag).

Precedents: Damus (June 2023) had to drop zaps on *posts* (treated as selling
digital content) but kept zaps on *profiles*. Fountain ships boosts and
streaming sats on *episodes* in the App Store. Enforcement is inconsistent.

| Hopp feature | Risk | Why |
|---|---|---|
| Progress display, leaderboards, open suggestions, **free** votes, playing an unlocked clip | none | no payment |
| Boost / streaming to value recipients | **medium** | gift, but tied to an episode (Damus pattern); Fountain precedent |
| App split (even opt-in) | **high** | breaks "100 % to the receiver" |
| Paying for Release Boosting | **high** | payment → content available earlier |
| Boost2Unlock, halving clip | **very high** | "unlock" is literally the function |
| Paid suggestions, Q&A, vote boost | **high** | paid service inside the app |
| Built-in wallet (phase 3) | account | organisation enrolment required |

## Decision

### Two iOS builds

- **`appstore`** — conservative:
  - boosts and streaming sats **on**;
  - Sovereigncast layer **display only**: progress, leaderboards, open
    suggestions, free votes; **no payment buttons and no links to pay elsewhere**;
  - **no app split** (would break the 100 %-to-receiver condition);
  - no built-in wallet while the account is an individual one.
- **`altstore`** — full feature set, distributed through **AltStore PAL**
  (EU, Japan, Brazil). Apps there pass Apple's notarization (platform integrity,
  malware) rather than App Review's business rules.

Together with ADR 0007 the distribution flavors are:
Android `play` / `foss`, iOS `appstore` / `altstore`.

### Capabilities are fixed at build time

Each flavor compiles in its own capability set. **No remote feature flags** —
enabling features after review would violate 2.3.1 and, beyond that, needs a
server Hopp does not have.

### Boost UI identical on both platforms

Boosts are shown on the episode, as on Android. This is an **accepted risk**
(Damus pattern). Fallback if App Review objects: present the boost on iOS as a
gift to the people from `valueRecipient` ("Boost to Nodesignal & Cutting"),
with the episode only as context — the line Apple accepted for Damus.

### Apple developer account

**Individual account.** Consequences: the App Store shows the maintainer's
personal name as seller, and a built-in wallet (phase 3) cannot ship in the
`appstore` build until an organisation (legal entity + D-U-N-S number) exists.
It can ship in `altstore` and on Android.

**Amended 2026-10-16:** the first release is `altstore` only, uploaded and
notarized in a befriended developer's existing account; Nodesignal's own
account comes with the `appstore` build. That build has its own bundle ID
(ADR 0004) and the developer's name as seller. How: docs/ios-release.md.

## Consequences

- iOS release work doubles in distribution (App Store Connect + AltStore PAL
  source), not in code: one codebase, two capability sets.
- First App Store submission goes in with the conservative set and clear review
  notes; features move into `appstore` one at a time, if ever.
- AltStore PAL terms and Apple's fees for alternative distribution are to be
  checked before the first `altstore` release (fees apply only at large install
  counts as of 2026, to be verified).
