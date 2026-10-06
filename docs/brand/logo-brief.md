# Logo brief

Round 1 explored four directions (receiver waves, the hop, wordmark-led,
"h" monogram). **"The hop" is the favourite.** Round 2 refines it with the
prompt below (used with Claude Design; attach the Sovereigncast logo from
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
