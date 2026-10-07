# Logo brief

Round 1 explored four directions (receiver waves, the hop, wordmark-led,
"h" monogram). **"The hop" is the favourite.** Round 2 refined it with the
prompt below; its result is in `logo/` and was chosen (see "Round 2 result").
Round 3 only fixes production details (used with Claude Design; attach the Sovereigncast logo from
`nodesignal/sovereigncast-space`, `public_src/assets/sovereigncast-logo.png`,
as reference).

Brand family: Sovereigncast is the transmitter (shield + radio tower + arcs,
black monoline, one orange dot `#f7931a`, bold geometric sans); Hopp is the
receiver.

## Round 2 prompt

```text
Refine ONE direction for the podcast player app logo "Hopp": "The hop".
Make the mark express how listeners pay podcasters: Lightning and Cashu ecash.

## Context
Hopp — "Honest open podcast player". A native Podcasting 2.0 player for
Android + iOS: plays any podcast feed, no account, no tracking, open source.
Listeners send Bitcoin micro-payments ("boosts", per-minute "streaming sats")
straight to podcasters. "Hopp!" is a German/Nordic call for "let's go / jump".

Hopp is the listener-side companion of Sovereigncast (attached reference logo):
black monoline line-art, ONE Bitcoin-orange dot (#f7931a) as the only accent,
bold geometric sans wordmark (Avenir Next / Inter Bold feel), near-black
#0a0a0a + white. Sovereigncast is the transmitter; Hopp is the receiver.
Stay in that family, but do not reuse the shield or the tower.

## The core idea to refine
The orange dot is a payment hopping from the listener to the podcaster.
Two payment rails give that hop its meaning:

- LIGHTNING = the path. On the Lightning Network a payment literally travels
  in "hops" from node to node through payment channels. Draw the channels as
  the arcs and the nodes as small points the dot touches down on (2–3 hops max).
- CASHU ECASH = the traveller. Cashu tokens are bearer tokens: self-contained,
  handed over directly in a single hop, no route needed. Denominations are
  powers of two (1, 2, 4, 8 …). Express this through the dot itself as a
  self-contained token, and/or through hop arcs whose sizes follow a 1 : 2 : 4
  rhythm.

The mark should read as "two rails, one hop" — but only to someone who knows;
everyone else should simply see a light, joyful bounce. Subtlety over literalness.

## Variants to show
A. Lightning-led: dot hopping along 2–3 channel arcs across small nodes,
   landing on the final point (the podcaster).
B. Cashu-led: one single confident hop of a solid token-dot; arcs or trail
   marks in 1:2:4 proportion as an echo of ecash denominations.
C. Combined: the multi-hop channel path (Lightning) carrying the self-contained
   token (Cashu) — the strongest synthesis, as long as it stays at ≤ 3 elements.
D. Wordmark-integrated: the hop drawn over the letters — e.g. the dot leaping
   from the "o" across the "pp", so the name itself becomes the payment path.

Lowercase "hopp" and title-case "Hopp" wordmarks both welcome.
Tagline: "Honest open podcast player".

## Hard constraints
- App icon must read at 48 px — no more than 3 graphic elements; arcs must not
  blur into each other at small size. Show the 48 px preview for every variant.
- Android adaptive icon: art inside the ≈66% safe zone of a 108 dp canvas,
  survives circle / squircle / square masks.
- iOS: 1024×1024, opaque full-bleed background, no text in the icon.
- Single-color silhouette version (Android 13 themed icon, white notification icon).
- Light and dark backgrounds.
- Exactly one accent color: the orange dot. Everything else black/white.

## Avoid
- Any animal: no rabbit/bunny, kangaroo, frog, grasshopper (the travel app
  "Hopper" owns a hopping-bunny logo — stay clearly away from it).
- Lightning-bolt clichés (every Lightning wallet uses one).
- Cashu's nut/acorn mascot, coins, the ₿ symbol, chain links, blockchain cubes.
- Microphones, the generic RSS icon, purple, gradients, 3D.

## Deliverables per variant
- App icon 1024 px + 48 px preview, light and dark.
- Monochrome version.
- Horizontal lockup: symbol + "Hopp" + tagline.
- Endorsed lockup: "Hopp — by Sovereigncast".
- A 3–4 frame motion storyboard of the hop (take-off → arc → landing), as it
  would play on the splash screen and as the confirmation animation after a
  listener sends a boost.
- One sentence per variant explaining how it encodes Lightning and Cashu.

Finish with a comparison row: all four icons at home-screen size next to
Pocket Casts / Overcast-style neighbours, plus the lockup of the strongest variant.
```

## Round 2 result (chosen, 2026-10-07)

Variant B, one single hop, three elements: a **hollow ring** (take-off — and the
"o" of the wordmark), an **arc**, a **solid orange dot** (the payment landing).
Monoline black `#0a0a0a`, stroke 7 on a 100-unit grid, one accent `#f7931a`,
icon background warm off-white `#f6f5f2`, secondary text `#5e5d59`, wordmark in
**Figtree** ExtraBold (OFL).

Checked by rendering every file: reads at 48 px under circle, squircle, rounded
and square masks, light and dark, as an Android 13 themed icon and as a 24 px
notification icon.

Decisions:
- **The headphone reading is welcome.** Arc plus two "ear cups" reads as
  headphones: listening and paying in one sign. Do not design it away.
- **Lowercase "hopp" in every logo**; the name in running text and the stores
  stays "Hopp".
- **The hop animation is built in code** (Compose: the dot travels along the arc
  path), for the splash screen and the boost confirmation; skipped under
  "reduce motion" (ADR 0016). No storyboard file.

## Round 3 prompt (production fixes only)

```text
Keep the chosen "hopp" mark exactly as it is — hollow ring, arc, solid orange
dot; it may read as headphones, that is intended. Only fix production details
and re-export the same file set with the same file names.

1. Lowercase everywhere: hopp-lockup-horizontal.svg uses "Hopp" — switch it to
   the lowercase "hopp" wordmark style used in hopp-wordmark.svg.
2. hopp-endorsed.svg and hopp-endorsed-white.svg cut the text off at
   "Sovereignca…": widen the viewBox (or adjust size) so "— by Sovereigncast"
   fits completely, with the same right margin as the left one.
3. Convert ALL text to outlined paths (wordmark, tagline, endorsement). No
   <text> elements may remain: Android vector drawables cannot render text, and
   viewers without Figtree fall back to another font.
4. Wordmark: the arc currently starts touching the top of the "o" and reads like
   an accent on it. Start it with a small, clear gap above the "o" — a take-off,
   not a joint. Same in every file that contains the wordmark.
5. App icons (Android foreground + monochrome, iOS light/dark): scale the mark
   from 0.60 to about 0.66 of the canvas, still centred and inside the 66 dp safe
   zone of the 108 dp adaptive canvas.
6. Add a small-size symbol (hopp-symbol-small.svg, 16–24 px use: favicon,
   notification) with a heavier stroke so the ring's hole stays open at 16 px.
7. iOS 18: add hopp-icon-ios-1024-dark-transparent.svg (mark on transparent
   background, light strokes) and hopp-icon-ios-1024-tinted.svg (single-color
   grayscale mark on transparent background), per Apple's dark/tinted icon rules.
8. Keep colours exactly: #0a0a0a, #f7931a, #f6f5f2, #5e5d59, white.

Also give one sentence on how the mark encodes Lightning and Cashu.
```
