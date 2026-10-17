# Changelog

Two tiers, as in Sovereigncast (ADR 0017): everything above **🐛 Fixes** is the
highlight tier the stores' "what's new" text is cut from (Google Play: 500
characters per language); the fixes follow. German: [CHANGELOG.de.md](CHANGELOG.de.md).

## [Unreleased]

## [0.2.0] - 2026-10-17

### 🆕 New Features
- **An optional Nostr identity** — made in the settings with a name Hopp greets you by; your votes on Sovereigncast shows then count once per person, on every device. Sign in with Amber or a bunker (nsec.app and others) and your key stays there, or make one in Hopp and back it up with a password. Or stay anonymous.
- **Hear a show's trailer** — on its page, before subscribing too; the episode you were listening to waits where you left it.
- **Show notes in the player** — a sheet like chapters and transcript; a time mark jumps there.
- **Comments on every episode** — from Nostr, in the player and on the episode page: what other listeners say (Fountain's too, with the sats of their boosts), and with a Nostr identity your own comments, answers and likes. Mute, report or hide what you do not want to see.
- **Boosts on Nostr too** — with a Nostr identity, a boost to an episode can also go out as a public comment with its amount, as in Fountain; a switch in the boost dialog, preset in the settings.
- **Publish or unlock at once** — Release Boosting and Boost2Unlock offer the amount that brings the episode out now (or fills the goal), worked out as the community page does.
- **Your Nostr key, safe and portable** — save it in your password manager (Android) or iCloud Keychain (iOS) and get it back with one tap, copy it as nsec for any Nostr app, or use a key you already have.

### 🐛 Fixes
- Chapter images other than square were cut in the player; now whole, the rest filled with the picture blurred — and full screen with zoom on a tap, from the chapter list too.
- Shows that announce an episode only with a plain Nostr note (Einundzwanzig, say) had no comments in Hopp; Hopp now finds the announcement through the show's website and shows the answers to it.
- Zaps with a message on an episode's announcement now show as comments with their amount.
- Some Fountain comments were missing in Hopp for good when a relay was too slow on the first load; Hopp now asks in full each time, Fountain's relay included.
- Your own comment stood in other Nostr apps with no reference to the episode; it now shows the show's announcement as a quote.
- The episode page sometimes opened on the chapters instead of the show notes.
- Episodes marked as heard but never played no longer show under "Recently played".
- A long greeting like "Guten Morgen, Thorsten" ran off the screen; it now gets a little smaller and stays on one line.
- A show in the library showed at most a "1", however many episodes waited; the number now counts every episode not heard yet.
- Boosting with a share for Hopp, the split bar was in another order than the names below it; both are largest first now.
- "Customize Home" sat at the very bottom, hard to find below many sections; now a quiet icon beside the greeting.
- Sovereigncast shows are refreshed on every start, and the scheduled search for new episodes asks their instances too.
- Your comments now stand on their own, as Fountain's do (instead of answering the show's post), so they show up there too.
- The privacy page was a wall of cards: now what Hopp can check comes first, and who sees what is a short list, the details a tap away. The Nostr page too: one way in for newcomers, one button to back up the key.
- A show without value for value still offered boosting and streaming in the player; a greyed bar says it takes no sats now. And a comment named the Release Boosting post's author instead of the show.
- The tour has a page on Nostr: what it is, what Hopp does with it, and the sync to come; its wallet page names Zeus instead of Primal, whose NWC does not do what Hopp needs.
- An answer to the last comment was typed under the keyboard; and comments and boosts went under a show's Release Boosting post instead of the episode's announcement.
- The player's cycling line of the show's latest boosts is gone: it confused more than it told.
- Comments load only when you ask, in the player too — at once only behind a VPN; once loaded they stay on the device and show at once.
- Comments took 30–40 seconds when a relay did not answer; and Fountain's comments ended with a link to the episode you are on.
- The player's chips showed greyed-out what an episode lacks; now only what it has.
- Show notes that wrap each list item in a paragraph (Einundzwanzig) showed the bullet alone, the text two lines below.
- "Your shows" could open scrolled past its first show once a show with a Release Boosting round moved to the front.

## [0.1.1] - 2026-10-15

### 🆕 New Features
- **Support a show from within Hopp** — a show's funding link to a Lightning address opens Hopp's boost sheet as a donation when a wallet is connected, instead of leaving the app.
- **Subscribe straight from the search results** — a + on every result, and you stay on the search.
- **Downloads at a glance on every episode** — a badge on the cover: downloaded, waiting, loading or failed. A download waiting for Wi-Fi can go now over mobile data, one or all at once.
- **Import OPML from the settings** too, next to the export.
- **Mark a whole show as played**, or an episode and every older one — from the show's menu and the episode's.
- **Downloads at a glance** — the settings say how much the downloads take, delete all of them at once, and on Android show them as “Hopp” in the Files app.
- **Search suggestions while typing** — a word begun is enough: the Sovereigncast shows, your subscriptions, the recommendations and what this session found are suggested at once (the directories only find whole words).
- **Animated chapter images** — GIFs move now, on Android.
- **Connect a wallet by QR code** — scan the NWC code with the camera; the camera is only asked for when you scan.
- **Sovereigncast shows: suggest a topic and boost suggestions** (right from their row) — the payment keeps spam out, and once it is paid the suggestion is on the list; sats give an open suggestion weight. The show's most popular episodes have a tab of their own and open in Hopp; while you listen the player shows the show's latest boosts a line at a time, and a long boost message opens in full on a tap; and an unlocked clip plays right in Hopp's player — afterwards the episode you were listening to is back where you left it.

### 🐛 Fixes
- Routing fees count against the daily budget — they leave the wallet too.
- Signed so that F-Droid can publish our APK: 0.1.0 was signed with re-aligned entries, which F-Droid's build from source cannot match. Store texts, icon and screenshots for F-Droid and Zapstore.

## [0.1.0] - 2026-10-14

### 🆕 New Features
- **A podcast player without an account** — library as cards or a list, with each show's new episodes, Home with what is new, what you were listening to, your shows and when they come out next — greeting you by your boost name, its sections in your order or hidden —, queue, downloads on Wi-Fi, sleep timer (also at the end of a chapter), speed per show, skip intervals of your choice (also on the lock screen), skip silence, Android Auto — on the phone, the tablet and the iPad, upright or on its side. In English and German, on Android 13+ also apart from the system's language.
- **Find shows** in the Podcast Index (optionally fyyd and Apple), by feed address or OPML, or straight from a Sovereigncast show's "Open in Hopp" link; look at a show before subscribing.
- **Value for value** — connect your Lightning wallet over NWC, boost an episode or a whole show, stream sats while you listen and see what is sent and what is set aside, per episode — parts of a sat are rounded up once you are done with an episode; a notification tells you if a boost fails. Splits arrive in whole sats; routing fees are shown on top.
- **Sovereigncast shows** — Release Boosting and Boost2Unlock with all details, vote on topic suggestions, the community's top boosts.
- **Search a show's episodes** by topic, and keep **favourites** on Home.
- **Share or copy** a show, an episode — from a point if you like — a chapter or a line of the transcript; the link opens Hopp, or offers the feed to any other app. Titles, descriptions, show notes and boost messages can be selected and copied.
- **Episode pages** with show notes, chapters, people and a boost button; a transcript that reads along in the player.
- **A short tour on first start** — subscribing, the library, value for value and connecting a wallet.
- **Privacy page** — who sees what, in plain words, with a tip for each; Hopp checks what it can (VPN, private DNS, your settings) and marks it green. Hopp itself collects nothing; after a crash it shows the report for you to share yourself.
