# Fallback fonts for the web client

Figtree has no symbols or emoji, and in the browser Compose draws with
Skia, which knows only the fonts it is given (ADR 0022). These are loaded
after the first frame (`FallbackFonts.kt`) and used where Figtree has no glyph.

| File | From | What |
|---|---|---|
| `hopp_fallback_symbols.ttf` | Noto Sans Symbols 2 | arrows, technical, geometric shapes, misc symbols, dingbats (⚡ ✓ ▾ ♡ ✎ …) |
| `hopp_fallback_math.ttf` | Noto Sans Math | math operators (⋮ ⋯ ≠ ≥) |
| `hopp_fallback_cjk.ttf` | Noto Sans JP | 日本語, the language's own name |
| `hopp_fallback_currency.ttf` | Noto Sans | currency symbols (₿ ₽ ₺ …; Figtree has only € and ₹) |
| `hopp_fallback_emoji.ttf` | Noto Color Emoji (COLRv1) | emoji in titles, show notes and comments, in colour |

Subsets made with fonttools' `pyftsubset` (2026-10-28) from google/fonts;
the currency subset (U+20A0–20CF) from Noto Sans pinned to wght 400, wdth 100
with `fonttools varLib.instancer`, and the emoji font is Noto Color Emoji whole
without its `SVG ` table, which Skia does not use: 5 MB, 2.4 MB as Brotli
(2026-11-10; the monochrome Noto Emoji before was 1.1 MB);
all SIL Open Font License 1.1 (`OFL-Noto.txt`).
