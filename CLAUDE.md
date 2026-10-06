# CLAUDE.md

Guidance for Claude Code in this repository.

## What this is

**Hopp** — Honest open podcast player. A native Podcasting 2.0 player for
Android + iOS (Kotlin Multiplatform + Compose Multiplatform), the listener-side
companion of Sovereigncast (`gitlab.com/nodesignal/podcasting_tools`).

**Status: specification phase, no code yet.** Read `docs/adr/` first — the
decisions there are settled and should not be re-litigated without the
maintainer asking. Open topics and their current proposals are in
`docs/open-questions.md`.

## Conventions

- Documentation, ADRs, code comments and commit messages: **English**.
  Conversation with the maintainer: German.
- Each settled decision becomes a numbered ADR (`docs/adr/NNNN-slug.md`:
  Status, Date, Context, Decision, Consequences). Update
  `docs/open-questions.md` when a topic moves into an ADR.
- All organisational names are `hopp`; application/bundle ID and Kotlin package
  root are `space.sovereigncast.hopp` (ADR 0004).

## Related repositories

- `nodesignal/podcasting_tools` — Sovereigncast server; source of truth for the
  Listener API contract and the self-hosted feed (`podcast_manager/feed_builder.py`).
- `nodesignal/sovereigncast-space` — website on sovereigncast.space; hosts the
  `/open` deep-link fallback and the `.well-known` app-link files (ADR 0002).
