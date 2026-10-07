# ADR 0019 — Visual design

- **Status:** accepted
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

### Icons

**Material Symbols Rounded** (Apache 2.0) for everything standard — round, in
line with the shapes, available in Compose. Own drawings only for the value
layer: **boost is the hop from the logo** (arc + dot), never a lightning bolt;
always paired with the word "Boost". On an orange fill the hop is single-colour
black (an orange dot on orange disappears).

### Navigation

Bottom bar with three tabs — **New · Library · Discover** — and the mini player
above it. Wallet, boost history and settings sit behind a profile button at the
top right. Calm and centred on listening.

### Now playing

From top to bottom: large cover (the chapter image replaces it when a chapter
has one) → title and people → progress bar with the chapter boundaries as gaps →
controls (speed, −15 s, play, +30 s, sleep timer) → **value bar** → sheets.

- **Value bar:** the boost button and the streaming switch with rate and today's
  total, on one surface card; the only orange on screen. Prominent on purpose,
  kept compact (about 7 dp vertical padding). Streaming is off until the
  listener turns it on (ADR 0005); the first tap shows a short invitation with
  rate and budget.
- **Chapters, Transcript, People, Community** open as bottom sheets.
- **Jumping between chapters** costs no extra space: the chapter label on the
  cover is the control — `‹` and `›` (40 dp) jump to the previous/next chapter,
  the middle shows title, "chapter 4 of 9" and time left, and opens the chapter
  sheet. Without chapters the label is absent. In addition: **swipe the cover**
  left/right (snaps to the chapter) and **long-press ↺/↻** to skip by chapter.
  Hardware/lock-screen/Android Auto "next" keeps its usual meaning (next item
  in the queue).

### Show page

Header (cover, title, cadence, subscribe) → **Sovereigncast card**, only while an
action runs (Release Boosting or Boost2Unlock: label "via Sovereigncast",
progress bar, time or amount left, pay button) → tabs **Episodes · Community**.
Each episode shows its boosted sats.

**Community tab:** the running action as a card; topic suggestions with a vote
button (filled = voted) and boosted amounts in orange; "suggest" action; top
boosters of the month; a quiet line naming the Sovereigncast instance the data
comes from. Phase 1 shows progress, paying, the open suggestions and free votes;
the rest arrives in phase 2 (ADR 0018).

### Boost dialog

A bottom sheet: recipient and position ("episode 296 · at 27:14"), amount
presets as pills, optional message and name, the **split** and one large button
with the exact amount; underneath the paying wallet and the budget left today.

The split is **compact**: one bar with a segment per recipient in shades of
grey, an unpayable recipient hatched orange; below it the largest four as a
running line and "+N more ›" to expand the full list; one line naming what is
skipped (ADR 0005). Seven recipients take three lines instead of seven.

Drafts: `docs/design/key-screens-draft-1.png` (first round) and
`key-screens-draft-2.png` (revised value bar, community tab, compact split,
chapter control), rendered by `docs/design/render_drafts.py`. They fix
structure and hierarchy, not pixels — the screenshot tests (ADR 0017) become
the reference once the screens exist.

## Consequences

- `value`, `onValue` and `valueText` are custom colour roles next to the
  Material scheme (a `HoppColors` extension), not Material's tertiary — so
  nothing in Material's own components turns orange by accident.
- Figtree ships as a font resource in the shared module (Compose Multiplatform
  resources).
- The chapter control needs a chapter index in the player state (current
  chapter, boundaries) shared by the label, the swipe and the long-press.
- Further screens (onboarding, wallet connection, search, settings) follow the
  same rules and are designed when they are built.
