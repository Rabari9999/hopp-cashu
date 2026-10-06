# ADR 0010 — Sovereigncast Listener API v1

- **Status:** accepted
- **Date:** 2026-10-06
- **Implemented in:** `nodesignal/podcasting_tools` (Sovereigncast). The OpenAPI
  contract there is the source of truth; this ADR records the decisions.

## Context

Hopp's Sovereigncast layer (ADR 0001) needs a stable, versioned, public API.
Today the listener-facing features sit behind `/api/community/*` in
`podcast_manager/blueprints/community.py`, built for `community.html`:
unversioned, `{"success": …}` envelopes with free-text messages, four separate
`sync-*-wallet` polling endpoints, and scoping by the *active* show (for
anonymous visitors: the default show).

Findings in the server code (2026-10-06) that the API depends on:

1. **Release Boosting and Boost2Unlock are not show-scoped.**
   `get_wallet_by_usecase('releaseboosting' | 'boost2unlock')`,
   `get_next_episode()` and `get_boost2unlock_candidates()` take no `show_id`.
   On a multi-show instance the community page already mixes shows.
   (`wallets.show_id` exists.)
2. **`shows.podcast_guid` is only generated for self-hosted shows**
   (`feed_builder.ensure_podcast_guid`). PodHome shows need it filled from their
   feed (the Nodesignal feed carries `e8df0b13-…`), or the `by-guid`
   verification of ADR 0002 cannot work.
3. **Episode mapping:** Hopp identifies episodes by the feed item `<guid>`.
   Self-hosted: item guid = `episode_id`. PodHome: item guids are UUIDs,
   presumably PodHome's episode ids, which Sovereigncast stores as
   `episode_id` — **to verify** against the production database.
4. Side finding: `GET /api/community/questions` is commented "admin use" but is
   public and returns all paid suggestions with usernames.

## Decision

### Conventions

- Base path `/api/listener/v1/`; every show-specific resource under
  `/shows/{podcast_guid}/…` — no session or active-show dependency.
- `GET /api/listener/meta` (unversioned): supported versions, instance version.
- JSON, `snake_case`, UTC ISO-8601 timestamps, amounts as integer sats
  (`…_sats`), no `success` envelope.
- Errors: HTTP status + `{"error": {"code": "<stable_code>", "message": "…"}}`.
  Codes are stable so Hopp can localise them (`feature_disabled`,
  `show_not_found`, `wallet_not_configured`, `amount_out_of_range`,
  `rate_limited`, …).
- Episodes referenced by `episode_guid` (feed item guid). Unpublished episodes
  (Release Boosting, Boost2Unlock) also carry title, image and planned date.
- Paid actions return `{payment_hash, bolt11, amount_sats, expires_at}`; one
  status endpoint `GET /v1/payments/{payment_hash}` → `pending | paid | expired`,
  which triggers the existing throttled wallet sync internally. Hopp verifies the
  invoice amount before paying (ADR 0005).
- Reads send ETag, `Cache-Control` and `poll_after_seconds`.
- Anonymous by default. An optional **NIP-98** `Authorization` header is part
  of the v1 contract from the start (ADR 0006); when present, rate limits and
  vote deduplication key on the pubkey instead of IP / voter id (mobile carrier
  NAT puts many listeners behind one IP). `429` with `Retry-After`.
- No CORS needed (native client). New prefix added to `auth.py::_ALLOWED_PREFIXES`
  and `security.py::_CSRF_EXEMPT_PREFIXES`.

### Endpoints (first release)

| Endpoint | Purpose | Replaces / based on |
|---|---|---|
| `GET /api/listener/meta` | versions | new |
| `GET /v1/shows/by-guid/{guid}` | verification (ADR 0002), feed URL, **show capabilities**, minimum amounts | new, from community toggles |
| `GET /v1/shows/{guid}/release-boosting` | next episode, goal, progress, halving clip | `release-boosting` |
| `POST /v1/shows/{guid}/release-boosting/invoices` | pay a release boost | `create-boost` |
| `GET /v1/shows/{guid}/boost2unlock` | candidates, progress, clip | `boost2unlock` |
| `POST /v1/shows/{guid}/boost2unlock/{episode_guid}/invoices` | pay Boost2Unlock | `create-boost2unlock` |
| `GET /v1/shows/{guid}/clips/{id}` | unlocked clip audio | `release-clip.mp3`, `boost2unlock-clip` |
| `GET /v1/shows/{guid}/suggestions` | open public suggestions | `open-suggestions` |
| `POST /v1/shows/{guid}/suggestions` | submit (with invoice) | `create-question` |
| `POST /v1/shows/{guid}/suggestions/{id}/votes` | free vote | `…/vote` |
| `POST /v1/shows/{guid}/suggestions/{id}/invoices` | sats boost for a suggestion | `…/boost` |
| `GET /v1/shows/{guid}/leaderboard` | top boosts / boosters / recent | `top-boosts`, `top-boosters-alltime`, `recent-boosts` |
| `GET /v1/shows/{guid}/top-episodes` | ranking with `episode_guid` | `top-episodes` |
| `GET /v1/payments/{payment_hash}` | payment status | `check-payment` + 4× `sync-*` |

Later, as additive v1 endpoints (no v2 needed): `POST …/ecash` (phase 3),
`POST /v1/push-subscriptions` (WebPush, ADR 0007).

Not in the API: value-block boosts and streaming (Hopp pays from the feed
itself, ADR 0005 — `episode-value-payments` exists only for the browser),
admin functions.

### Implementation approach

- **Shared core:** the logic is moved out of `community.py` into a shared module;
  the new `listener_api.py` blueprint uses it. `community.html` stays on
  `/api/community/*` for now and migrates to v1 in a later step.
- **Contract:** OpenAPI 3.1 file in `podcasting_tools`; contract tests validate
  responses on both database backends. Hopp may generate its Kotlin client from it.

### Order of work in Sovereigncast

1. **Show-scoping first, as its own step:** Release Boosting / Boost2Unlock
   wallets, next episode and candidates per show (also fixes the community page
   on multi-show instances).
2. Fill `podcast_guid` for PodHome shows from their feed; verify the PodHome
   item-guid ↔ `episode_id` mapping.
3. Public listener URL setting + `podcast:txt purpose="sovereigncast"` marker +
   feed check for PodHome shows (ADR 0002).
4. OpenAPI contract for v1.
5. Shared core extracted from `community.py`; `listener_api.py` with the
   endpoints above; prefixes in `auth.py` / `security.py`; contract tests.
6. Clean up `GET /api/community/questions` (admin-only or remove).
7. Later: "Open in Hopp" link + QR on the community page; community page onto v1.

## Consequences

- One code path for the business logic, two HTTP faces until the community page
  migrates.
- Everything Hopp needs in phase 2 exists once steps 1–5 ship; the API can ship
  in Sovereigncast long before Hopp does, and be exercised by the contract tests.
