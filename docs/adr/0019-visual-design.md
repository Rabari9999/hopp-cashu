# ADR 0019 — Visual design

- **Status:** accepted (foundations); key screens still to be designed
- **Date:** 2026-10-07

## Decision

### Logo

The round-2 mark, corrected in round 3 (`docs/brand/round 2/`, see
`docs/brand/logo-brief.md`): one hop — hollow ring, arc, orange dot. Lowercase
**hopp** in every logo; "Hopp" in running text and the stores. The headphone
reading is intended. All files are outlined paths, so they convert to Android
vector drawables and iOS assets without a font.

Description: *one hop from listener to podcaster — the arc is the path Lightning
travels in hops, the dot the ecash token that arrives in one piece.*

The hop animation (splash screen, boost confirmation) is built in code: the dot
travels along the arc path. Skipped under "reduce motion" (ADR 0016).

### Foundation: Material 3 with our own brand

Compose Material 3 components, themed — not a custom component library, not a
per-platform look. Hopp looks the same on Android and iOS.

- **No dynamic colour** (Material You). The brand colours hold on every device.
- **Light/dark follows the system**, overridable in settings.
- **Typeface: Figtree** (OFL, variable font, bundled) for everything — the
  wordmark's typeface, the same on both platforms.
- **Shapes: round and calm.** Generous corner radii echoing the round shapes of
  the mark, pill-shaped buttons and chips, much white space and few lines; cover
  art is the largest colour area on screen.

### Colour roles

**Orange means money.** `#f7931a` is reserved for the value layer — boost and
streaming controls, sats amounts, the Sovereigncast payment actions — and the
dot of the logo. Nothing else is orange, so orange on screen always means "this
pays someone".

| Role | Light | Dark |
|---|---|---|
| background | `#f6f5f2` | `#0a0a0a` |
| surface (cards, sheets) | `#ffffff` | `#161615` |
| surfaceVariant (chips, fields) | `#ecebe7` | `#232220` |
| onSurface (text) | `#0a0a0a` | `#f6f5f2` |
| onSurfaceVariant (secondary text) | `#5e5d59` | `#a3a19b` |
| outline | `#d6d4ce` | `#3a3935` |
| primary (play, main actions) | `#0a0a0a` | `#f6f5f2` |
| onPrimary | `#f6f5f2` | `#0a0a0a` |
| value (payment fill) | `#f7931a` | `#f7931a` |
| onValue (label on orange) | `#0a0a0a` | `#0a0a0a` |
| valueText (orange as text) | `#a65500` | `#f7931a` |

Contrast, measured (WCAG): text `#0a0a0a`/`#f6f5f2` 18.2:1; secondary text
6.1:1 light, 7.7:1 dark; **label on orange must be black** (8.6:1 — white on
orange is 2.3:1); orange as text on light uses `valueText` `#a65500` (4.9:1),
on dark the brand orange itself (8.6:1). This implements ADR 0016's contrast
rule.

Preview: `docs/brand/palette-preview.png`.

## Consequences

- `value`, `onValue` and `valueText` are custom colour roles next to the
  Material scheme (a `HoppColors` extension), not Material's tertiary — so
  nothing in Material's own components turns orange by accident.
- Figtree ships as a font resource in the shared module (Compose Multiplatform
  resources).
- Icon set, navigation and the key screens (now playing, boost dialog,
  Sovereigncast layer) are the next design step.
