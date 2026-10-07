# Feed corpus (tests only)

Real feeds, stored as snapshots for the parser tests (ADR 0017):

- `nodesignal-podhome.xml` — the Nodesignal feed as PodHome serves it
  (2026-10-07, 325 items, starts with a UTF-8 BOM). Our own show.
- `sovereigncast-selfhosted.xml` — a feed built by Sovereigncast's
  `feed_builder.py` from its test fixture (fictional show).

Hand-made broken feeds, each covering one failure seen in the wild:

| File | What is wrong |
|---|---|
| `broken-entities.xml` | HTML entities (`&nbsp;`, `&ouml;`) XML does not know, a bare `&` |
| `broken-encoding.xml` | declares UTF-8, contains Latin-1 bytes |
| `latin1-declared.xml` | correct, but ISO-8859-1 |
| `namespace-variants.xml` | `pc:` prefix, namespace URI with trailing slash, old iTunes URI casing |
| `missing-fields.xml` | items without guid, pubDate, enclosure length or title |
| `leading-whitespace.xml` | BOM and blank lines before the XML declaration |
| `truncated.xml` | download cut off in the middle of the third item |
| `html-descriptions.xml` | HTML in CDATA and HTML escaped as text |
