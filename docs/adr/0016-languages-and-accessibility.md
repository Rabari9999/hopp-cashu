# ADR 0016 — Languages and accessibility

- **Status:** accepted; amended 2026-11-02 (maintainer): Spanish as a draft in
  0.3.3, and the preparation for many languages
- **Date:** 2026-10-07, amended 2026-11-02

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

### Amendment 2026-11-02: Spanish, and room for many languages

- **Spanish ships in 0.3.3 as a draft** (maintainer): translated in full from
  English with the German as reference, to be read by a native speaker before
  it is announced. Its open points are listed in `docs/translations.md`.
- **One list of languages:** `Languages.kt` names each language with its own
  name and how it writes numbers and dates (decimal mark, grouping, date
  separator). Formatting reads it — no more `== "de"` — as do the browser's
  language choice and the "open in another tab" page. Android's
  `locales_config.xml` and iOS's `CFBundleLocalizations` are kept in step by
  `LanguagesTest`; the folders of strings by `StringResourcesTest`.
- **Every language checked against English:** the same names, plurals and
  arrays, the same placeholders. A language marked complete must have every
  string; one that is not may ship, its missing strings shown in English.
- English keeps its dates with dots for now ("07.10.2026"); a change there is
  a decision of its own.
- Strings that count something are plurals (`show_episodes` and six more,
  which said "1 episodes").
- Weblate and store listings in Spanish follow once a reviewer is found;
  `docs/translations.md` says how a language is added.

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
