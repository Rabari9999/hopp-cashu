# ADR 0001 — Universal player with a Sovereigncast layer

- **Status:** accepted
- **Date:** 2026-10-05

## Context

Sovereigncast offers listener-facing features that no other podcast app can
use, because they are not expressed in the RSS feed: Release Boosting,
Boost2Unlock (incl. the halving-clip unlock), topic suggestions with free
votes and sats boosts, paid Q&A, top-booster leaderboards, ecash redemption.
Today they only exist on each instance's public `/community` web page.

Two shapes were considered:

1. **Companion app** — plays only shows managed by a Sovereigncast instance.
   Small scope, but listeners rarely switch players; a second app next to the
   main player tends to go unused.
2. **Universal player** — plays any feed; Sovereigncast features switch on
   per show when the feed is recognised as Sovereigncast-managed.

## Decision

Hopp is a **universal Podcasting 2.0 player with a Sovereigncast layer, and
Sovereigncast shows in focus**:

- Any feed can be subscribed (URL, Podcast Index search, OPML import/export).
- The Sovereigncast layer activates automatically for any show whose feed
  carries the discovery marker (see [ADR 0002](0002-discovery-via-podcast-txt.md)),
  regardless of which instance manages it.
- Sovereigncast shows are put forward: a "Open in Hopp" deep link / QR code on
  each instance's community page is the primary acquisition path; an opt-in
  directory of instances can follow later.

## Consequences

- The player must handle the **union** of Podcasting 2.0 tags that Sovereigncast
  self-hosted feeds and PodHome feeds emit, not the intersection
  (see [feature matrix](../feature-matrix.md)).
- Robust parsing of arbitrary, often malformed feeds, background refresh of many
  feeds, OPML and Podcast Index search are in scope.
- Positioning against existing P2.0 players (Fountain, Podverse, Castamatic,
  Podcast Guru) rests on: the Sovereigncast features no one else has; no
  account, no tracking, own wallet, open source; the most complete P2.0 tag
  coverage.
- The Sovereigncast server needs a **public, versioned Listener API** separate
  from the endpoints that serve its `community.html` page today
  (`/api/community/*`, unversioned, shaped for that page).
