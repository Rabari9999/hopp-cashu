# ADR 0019 — Visual design

- **Status:** accepted
- **Date:** 2026-10-07

## Decision

### Logo

The round-2 mark, corrected in round 3 (`docs/brand/logo/`, see
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
| outline (boundaries of controls: switch off, fields, chips) | `#7a7974` | `#7a7974` |
| outlineVariant (decoration: hairlines, dividers) | `#d6d4ce` | `#3a3935` |
| surfaceContainer… (menus, sheets, switch track) | `#ffffff` → `#ecebe7` | `#0a0a0a` → `#2a2927` |
| primary (play, main actions) | `#0a0a0a` | `#f6f5f2` |
| onPrimary | `#f6f5f2` | `#0a0a0a` |
| value (payment fill) | `#f7931a` | `#f7931a` |
| onValue (label on orange) | `#0a0a0a` | `#0a0a0a` |
| valueText (orange as text) | `#a65500` | `#f7931a` |
| progressTrack (unplayed part of progress bars) | `#8a8984` | `#6e6d68` |

Contrast, measured (WCAG): text `#0a0a0a`/`#f6f5f2` 18.2:1; secondary text
6.1:1 light, 7.7:1 dark; **label on orange must be black** (8.6:1 — white on
orange is 2.3:1); orange as text on light uses `valueText` `#a65500` (4.9:1),
on dark the brand orange itself (8.6:1). This implements ADR 0016's contrast
rule. Controls need 3:1 against what they sit on (WCAG 1.4.11): `outline`
`#7a7974` gives 4.0:1 on the light background, 3.7:1 on the light switch
track, 4.5:1 on the dark background, 3.3:1 on the dark track. The first
`outline` (`#d6d4ce` / `#3a3935`, 1.4:1 / 1.7:1) left a switch that is off
nearly invisible — found on the device, 2026-10-08; it lives on as
`outlineVariant` for decoration.

**Progress bars** use `progressTrack` for the unplayed part (3.2:1 light, 3.8:1
dark against the background — WCAG's 3:1 for non-text UI), `onSurface` for the
played part and 3 dp gaps at chapter starts. The first draft used
`surfaceVariant` (1.1:1) and the chapter gaps were invisible on a real device.

Preview: `docs/brand/palette-preview.png`.

### Icons

**Material Symbols Rounded** (Apache 2.0) for everything standard — round, in
line with the shapes, available in Compose. Own drawings only for the value
layer: **boost is the hop from the logo** (arc + dot), never a lightning bolt;
always paired with the word "Boost". On an orange fill the hop is single-colour
black (an orange dot on orange disappears).

### Navigation

Bottom bar with three tabs — **Home · Library · Discover** (Home replaced New
on 2026-10-08, see ADR 0012) — and the mini player above it. Wallet, boost history and settings sit behind a profile button at the
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
- **Swipe down** (or back) minimises now playing to the mini player; tapping
  the mini player or swiping it up opens it again — without reloading, so
  playback continues seamlessly.
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
~~Each episode shows its boosted sats.~~ *Dropped 2026-10-09:* a total per
episode is in effect what the episode earned, and Sovereigncast does not
publish that — its public side shows amounts only for the month's top boosts
(the all-time ranking and top episodes carry none). The Listener API has no
such endpoint (ADR 0010).

**Community tab:** the running action as a card; topic suggestions with a vote
button (filled = voted) and boosted amounts in orange; "suggest" action; top
boosters of the month; a quiet line naming the Sovereigncast instance the data
comes from. Phase 1 shows progress, paying, the open suggestions and free votes;
the rest arrives in phase 2 (ADR 0018).

**Amended 2026-10-09 (device feedback):** the page was too busy. The header
keeps cover, title, author, badge and episode count, "Subscribed" and Boost;
auto-download and unsubscribe moved into its menu, the description folds to two
lines. The Sovereigncast card is one compact line per action (label without
"via Sovereigncast" — the badge says it); a tap opens a sheet with what the
community page shows (cover, release time, description, block chain, clip,
progress, pay button). Tabs follow a horizontal swipe, and the header scrolls
away with the list. A third tab **Boosts** shows the month's top boosts, the
latest boosts and the all-time ranking (read-only, so pulled forward from phase
2). A suggestion opens in full on a tap. The episode page has the same swipeable
tabs and a Boost button for the episode.

**Amended 2026-10-09 (device feedback, later):** the episode page also has a
**Transcript** tab (readable before playing; reads along while the episode
plays; a line starts it there) and a **Clips** tab for `podcast:soundbite`. A
clip plays its stretch, then pauses and returns to the listener's own position.
Clips live only on the episode page — in the player they would interrupt what
is playing. With more than three tabs the tab row scrolls.

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

### Amendments 2026-10-10 (device feedback)

- **Settings** are an overview of their parts with icon and one-line summary,
  grouped *Value for value* (wallet, payment history — each a row of its own),
  *App* (playback, downloads & updates, notifications, directories,
  subscriptions) and *Hopp* (privacy, crash report, about). The top bar's
  entry is a cogwheel, not an account icon — Hopp has no account.
- **Home** gains *At your shows*: one quiet line per running Release Boosting
  round or Boost2Unlock episode at a subscribed Sovereigncast show (cover,
  label, episode, thin progress, sats and release time); a tap opens the show.
  Order: New, Continue, At your shows, Recently boosted, Recently played.
- **Notifications:** the quick boost's result reports nothing, only failures
  (default) or everything; a success disappears after a few seconds. Amended
  2026-10-11: the same choice covers boosts from the sheet (player, episode,
  show), which now run in the app's scope (`BoostReports`), so one on its way
  is finished and reported even when its screen closes. Playback
  and download notifications stay — Android needs them for background work.
- **First start:** a five-page tour (Hopp, subscribing, library, value for
  value, wallet), skippable, again from *About Hopp*.
- **Later the same day:** the tour leaves out its wallet page when a wallet is
  connected; the payment history is only in the settings overview (not on the
  wallet page too); leaving the settings for a tab closes them, so a tab never
  comes back showing the settings (tab stacks are kept for shows and episodes,
  which belong to a tab — the settings belong to none); the Sovereigncast badge
  explains itself on a tap, everywhere, and search marks and filters
  Sovereigncast shows by the curated list on sovereigncast.space (ADR 0008).
- **2026-10-11:** a show's episodes can be searched by topic — title and
  description, every word, "ue" finding "ü" — on the show page (all
  episodes, not just the loaded page) and before subscribing (in the loaded
  feed). Episodes can be favourites (heart on the episode page, entry in the
  episode menu); Home shows a *Favourites* section with the newest five and
  "All ›" — only once there are any. The tour's wallet page links the NWC
  explainer (nwc.ereignishorizont.xyz) only when the page answers.

