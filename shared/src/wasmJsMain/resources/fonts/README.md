# Fallback fonts for the web client

Figtree has no symbols or emoji, and in the browser Compose draws with
Skia, which knows only the fonts it is given (ADR 0022). These are loaded
after the first frame (`FallbackFonts.kt`) and used where Figtree has no glyph.

| File | From | What |
|---|---|---|
| `hopp_fallback_symbols.ttf` | Noto Sans Symbols 2 | arrows, technical, geometric shapes, misc symbols, dingbats (⚡ ✓ ▾ ♡ ✎ …) |
| `hopp_fallback_math.ttf` | Noto Sans Math | math operators (⋮ ⋯ ≠ ≥) |
| `hopp_fallback_cjk.ttf` | Noto Sans JP | 日本語, the language's own name |
| `hopp_fallback_emoji.ttf` | Noto Emoji (monochrome) | emoji in titles and show notes |

Subsets made with fonttools' `pyftsubset` (2026-10-28) from google/fonts;
all SIL Open Font License 1.1 (`OFL-Noto.txt`).
