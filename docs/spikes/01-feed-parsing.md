# Spike 1 — XML parsing in common code

- **Date:** 2026-10-07
- **Question (ADR 0003):** can the shared core parse real-world feeds robustly
  in common Kotlin, and with what?
- **Answer: yes — xmlutil's `KtXmlReader` (pull parser, relaxed mode), with
  our own byte decoding and one text fix-up.** ksoup was not needed.

## What was built

`shared/src/commonMain/.../feed/`: `FeedParser` (RSS 2.0 + iTunes + Podcasting
2.0 → `model.Feed`), `FeedText` (bytes → text), `Rfc822` (dates, durations),
`HtmlEntities`. Tests: `shared/src/jvmTest/.../FeedCorpusTest.kt` against the
corpus in `shared/src/jvmTest/resources/feeds/` (README there).

## Results

| Case | Result |
|---|---|
| Nodesignal on PodHome (2.3 MB, 325 items, UTF-8 BOM) | all items, no warning; podroll, trailers, txt, podping, updateFrequency, lnaddress recipients with `lnurl_callback`, music time splits with `remoteItem` |
| Self-hosted Sovereigncast feed | all 22 items, season/episode, socialInteract |
| Undeclared HTML entities, bare `&` | resolved via an entity table; bare `&` escaped before parsing |
| UTF-8 declared, Latin-1 bytes | read as Windows-1252, with a warning |
| ISO-8859-1 declared | correct, no warning |
| `pc:` prefix, namespace URI with trailing slash, old iTunes URI casing | matched by normalised URI, not by prefix |
| Items without guid / title / enclosure length | guid falls back to the enclosure URL; an item with neither is skipped with a warning |
| BOM and blank lines before `<?xml` | stripped |
| Download cut off mid-item | complete items kept, a warning names the position |
| HTML in CDATA and escaped | both come out as the same HTML string |
| RFC 822 variants (no weekday, 1-digit day, named zones, `+02:00`, 2-digit year) | parsed |

Speed (JVM, warm): **~0.26 s** for the 2.3 MB feed. Single pass, no DOM.

## Findings worth keeping

- **Decoding is ours, not the reader's.** `KtXmlReader` takes text; the BOM,
  the declaration and the UTF-8 → Windows-1252 fallback live in `FeedText`.
- **Relaxed mode mangles a bare `&`**: "Q&A live" became "Q&A;live" (it reads
  `&A` as an entity name). Escaping every `&` that does not start an entity
  before parsing fixes it without touching valid entities.
- **Unknown entities arrive as `ENTITY_REF` events** with the name in
  `localName` (expandEntities = false), so HTML entities resolve through our
  own table; unknown ones stay visible as written.
- `KtXmlReader` is marked `@ExperimentalXmlUtilApi`. Pinned to xmlutil 1.0.2;
  the corpus tests are the guard when updating.

## Open for later

- Feeding the reader from a stream (Ktor `ByteReadChannel`) instead of a
  String — not needed at 2.3 MB, revisit with multi-megabyte feeds.
- The same corpus on iOS (Kotlin/Native) — part of the iOS spike, needs macOS.
