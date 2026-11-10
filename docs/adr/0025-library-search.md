# ADR 0025 — Searching the library

- **Status:** accepted by the maintainer 2026-11-10 (way B and its design); built on branch `0.4.2` the same day
- **Date:** 2026-11-10

## Context

Hopp searched in two places: the directories in Discover (ADR 0008), and one
show's episodes on its page (title and show notes, matched in memory). The
maintainer asked for a search across the whole library, as Sovereigncast's
site has one — limited to what the app's database holds, so that it answers
at once and asks no one.

What the database holds: every episode of every subscribed show with its full
show notes (as HTML inside `meta_json`), the persons the feeds name, chapter
files once loaded (`chapter_cache`) and the comments read (`comment_event`).
Transcripts are fetched when shown and not kept.

Three ways were laid out on 2026-11-10:

- **A** — the per-show search, run over every show: no schema change, but
  every search decodes all stored episode JSON (tens of MB in a large
  library), and in the browser across the worker.
- **B** — the text each kind is searched by, kept beside it in the database,
  folded ahead of time, and found with SQL `LIKE`.
- **C** — SQLite's full-text index (FTS). Fastest, with ranking, but FTS5 is
  not guaranteed in Android's own SQLite, and FTS matches word starts only:
  "bibliothek" would not find "Bitcoinbibliothek", nor "muller" "Müller".

The maintainer chose B, and the design with it (canvas "Hopp globale
Suche"): in Discover, above what the directories find, "In your library" with
its first shows, episodes and chapters; a page with every hit, a kind at a
time, newest or oldest first; on a wide screen the library beside the
directories.

## Decision

1. **Folded text, kept.** `podcast.search_text` and `episode.search_text`
   (version 25) hold title, author or show notes as text, categories and
   persons, folded: lower case, "ä" "ö" "ü" "ß" as "ae" "oe" "ue" "ss" —
   SQLite's `LIKE` folds only ASCII. Each word of a search is folded the same
   way and must be in the text (`LIKE '%word%'`, up to four words, `%` and
   `_` escaped). "Müller", "mueller" and "MUELLER" find the same; a word is
   found inside a compound.
2. **Written with the feed.** The texts go in with the statements that store
   a feed (`insertIfNew`, both `updateFromFeed`): no extra writes on a
   refresh.
3. **Chapters** (`search_chapter`): those the feed gives (Podlove, or a list
   in the notes), and a chapter file's once it is loaded — then the file's
   only, as the player shows. `episode.chapter_sig` notes the feed's chapters
   as a number; a refresh that changes them sets it to null, and the
   background pass writes them again.
4. **Comments** (`search_comment`): the notes and replies kept for an episode,
   written whenever they are kept — without the people muted then and
   without the show's own notes on the episode. Only what was read: Hopp asks
   no relay for a search.
5. **Catching up** (`LibrarySearch.catchUp`): at the start and after each
   subscription or refresh, in the background, in batches — rows without a
   text (from before version 25), episodes whose chapters are pending, and
   once the chapter files and comments kept before.
6. **Snippets** come from the show notes of the hits shown, found again in
   the original text, so the words are marked as they are written there.

## Consequences

- The show notes are kept twice: as HTML in `meta_json`, as folded text
  (capped at 40 000 characters an episode). A few MB in a large library.
- No ranking: hits are by date. A word typed is found anywhere, so a short
  one finds much; the counts say how much.
- Chapters of a chapter file not loaded yet are not found. Transcripts are
  not searched.
- The per-show search on a show's page stays as it was.
