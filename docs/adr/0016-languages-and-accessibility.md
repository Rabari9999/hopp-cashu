# ADR 0016 — Languages and accessibility

- **Status:** accepted
- **Date:** 2026-10-07

## Decision

### Languages

- **English and German at the first release**, the two languages Sovereigncast
  maintains.
- Strings live in Compose Multiplatform resources — one source for Android and
  iOS — with plural forms.
- The UI language follows the system and can be changed in the app. Episodes
  appear in their own language; only the app is translated.
- Numbers and dates are locale-formatted ("1.234 Sats" / "1,234 sats").
- German addresses the listener with "du", as Sovereigncast does.
- Store listings in the same languages as the app.
- **Further languages through hosted Weblate** (free for libre projects): the
  community translates in the browser, changes arrive as commits. Spanish — the
  third language of sovereigncast.space — is the obvious first candidate.

### Accessibility: best effort

Accessibility is designed in but **does not block a release**. What applies
because it costs nothing extra when done from the start:

- Every control carries a content description / accessibility label when it is
  written; the seek bar is adjustable by swipe in skip-interval steps; chapters,
  transcript and the boost dialog read sensibly with TalkBack/VoiceOver.
- Layouts reflow at large font sizes (target: 200 %) instead of clipping.
- Touch targets at least 48 dp / 44 pt.
- "Reduce motion" is respected; the hop animation of the logo is skipped then.
- The transcript's font size is adjustable on its own — for hard-of-hearing
  listeners the transcript is an accessibility feature in itself.
- **Design rule on contrast:** Bitcoin orange `#f7931a` on white reaches only
  about 2.2:1 and is not used for text on light backgrounds. Orange stays for
  accents, surfaces and large elements; orange text on light backgrounds uses a
  darker tone that meets WCAG AA (4.5:1). Applies to the dark theme accordingly.

## Consequences

- A Weblate project is set up once the string resources exist (phase 1).
- Accessibility regressions are fixed when found, not gated; a TalkBack/VoiceOver
  walkthrough of the core flows (subscribe, listen, chapters, boost) belongs in
  the release checklist of topic 15 as a recommended, non-blocking step.
