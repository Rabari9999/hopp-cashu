# ADR 0004 — Naming and identifiers

- **Status:** accepted
- **Date:** 2026-10-06

## Decision

| What | Value |
|---|---|
| App name | **Hopp** |
| Tagline | Honest open podcast player |
| Endorsement | Hopp — by Sovereigncast |
| Android application ID / iOS bundle ID | `space.sovereigncast.hopp` |
| iOS bundle ID of the AltStore PAL build (2026-10-16) | `space.sovereigncast.hopp.pal` |
| Kotlin package root | `space.sovereigncast.hopp` |
| Repository | `gitlab.com/nodesignal/hopp` |
| Custom URL scheme | `hopp://` |
| Link / fallback domain | `sovereigncast.space` (see [ADR 0002](0002-discovery-via-podcast-txt.md)) |
| Documentation language | English |

Every organisational name (repository, directories, project names) is **hopp**,
so the source is easy to find.

## Rationale

- "Hopp!" — German/Nordic call for "let's go / jump"; neutral in both languages.
  It also matches the logo direction ("the hop", a payment hopping from listener
  to podcaster — Lightning payments travel in hops).
- The bundle ID cannot be changed after the first store upload; it is anchored
  on `sovereigncast.space`, a domain we control.
- The first iOS release goes to AltStore PAL through a befriended developer's
  Apple account (2026-10-16, docs/ios-release.md). An explicit bundle ID
  belongs to the team that registers it first, so that build has its own,
  `.pal`; `space.sovereigncast.hopp` stays free for an App Store build in
  Nodesignal's own account. Moving from one to the other means a new install
  (and the NWC connection again: Keychain items belong to the team).

## Rejected names

- **Japp** — pronounced like an English ethnic slur; unsuitable for international stores.
- **Hoppr** — taken several times (fitness booking app in both stores,
  ride-hailing, a game studio paying creators in stablecoins); sounds identical
  to **Hopper**, a large travel app with a hopping-bunny logo.
- **Drip / Dripp** — "drip." is an existing FOSS app on GitLab/F-Droid; "Dripp"
  is used by several store apps.
- **Zapp, Boostr, Satcast, Plebcast** — taken (Lightning wallet / payment apps,
  a podcast ad platform, existing podcast names).

## Open

- A proper trademark search for "Hopp" in the target markets before the first
  public store release (only a web search has been done so far).
- Logo: see [logo brief](../brand/logo-brief.md).
