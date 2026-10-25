# Changelog

Two tiers (ADR 0017): new features first – the stores' "what's new" text is cut
from them – then the fixes. Each entry starts with the part of the app it
belongs to. German: [CHANGELOG.de.md](CHANGELOG.de.md).

## [Unreleased]

## [0.3.0] - 2026-10-25

### 🆕 New Features
- **Nostr:** Sync subscriptions, progress, favourites, "Up next", listening habits and download settings between your devices – encrypted, no Hopp server.
- **Nostr:** On a new device, restore your library from Nostr after signing in; Hopp then opens Home and says what came back.
- **Tour:** "I already use Hopp" on the first page, and "Restore" beside "Set up" on the Nostr page, lead straight to restoring from Nostr.
- **Tour:** "I’m coming from another podcast app" leads to moving your subscriptions over, with where each app keeps its export.
- **Library:** Share another app's OPML export to Hopp, or open it with Hopp: it asks, then subscribes.
- **Library:** Choose and export OPML files on the iPhone and iPad too.
- **Player:** Raise the volume of quiet shows (Android), in the playback settings and beside the speeds.
- **Boosts:** Hopp shows what your wallet holds and sends no boost it cannot cover.
- **Player:** While you drag the position, a bubble shows where you would land, with the chapter.
- **Player:** Skip back and forward on the lock screen and in the notification (Android), with your seconds; the next chapter beside them.
- **Home:** A Release Boosting or Boost2Unlock under "Take part" opens straight to its details on the show.
- **Home:** Hide single entries under "Take part" – a round, a show's suggestions or a whole show – and bring them back under "Customize Home".
- **Home:** A small mark beside "updated" shows how sync went; a tap shows relays, what came and what is still to send.
- **Comments:** The show's post on an episode sits above its comments, as in Fountain.
- **Downloads:** Switch automatic downloads off altogether – then only what you download yourself is fetched.

### 🐛 Fixes
- **Sovereigncast:** A Release Boosting round at its goal shows a tick and asks for no more boosts – the episode can’t come out any earlier.
- **Downloads:** Automatic downloads no longer go on over mobile data once Wi-Fi is gone.
- **Player:** An episode you leave for another goes back to the top of "Up next" and plays on after it.
- **Player:** After a restart the episode you listened to last is back in the player, where you left it.
- **Tour:** Back from a page the tour opened returns to the tour.
- **Nostr:** Connecting Amber as a bunker no longer fails with "no permission"; a used-up bunker address is named as such.
- **Home:** Greets you by your Nostr name when no boost name is set, also after restoring from Nostr.
- **Boosts:** A share whose payment had no clear answer from the wallet is no longer paid a second time.
- **Sovereigncast:** Release Boosting, Boost2Unlock and suggestions count against the daily budget too.
- **Wallet:** A wallet that cannot speak Hopp's encryption is refused when connecting, with the reason.
- **Privacy:** If the device's key store cannot be read for a moment, Hopp says so instead of losing your key or wallet.
- **Comments:** Muting and changing relays no longer overwrite your lists when the relays could not be read.
- **Nostr:** Importing an encrypted key with an extreme security setting no longer crashes the app.


## [0.2.2] - 2026-10-20

### 🆕 New Features
- **Discover:** Category tiles, "For you", new shows with ⚡ Value and "Popular right now" from the Podcast Index.
- **Discover:** Choose the language of suggested shows instead of always the device's.
- **Shows:** A show's page names its categories; a tap opens them in Discover.
- **Shows:** For a show whose feed takes no sats, Hopp offers a variant of the feed that does.
- **Library:** Sort by newest episode (default), A–Z or most unheard.
- **Player:** One default speed for all shows; a show's own speed stays.
- **Player:** Tapping the speed steps through every speed, 1.1×, 1.3×, 2.5× and 3× included.
- **Downloads:** Finished downloads go to "Up next" automatically (can be turned off).
- **Comments:** Zap comments – public, private or anonymous.
- **Comments:** Copy the author's npub and the note's nevent.
- **Comments:** Open the author's profile in your Nostr app.
- **Comments:** The amount of your own boosts stands beside your name, as with zaps.
- **Nostr:** Add and remove your own relays (relays that need a login, NIP-42, not yet).
- **Wallet:** Your own values for daily budget, streaming rate, quick boost and "Support Hopp"; 210 instead of 500 sats suggested.
- **Notifications:** Set separately for quick boost, boost and zap.

### 🐛 Fixes
- **Player:** On a phone held sideways the chips (show notes, chapters, comments) are in view again.
- **Player:** VoiceOver and TalkBack read the current speed, not only "Playback speed".
- **Player:** The speed shows "1.1×" in English and "1,1×" in German; file sizes likewise.
- **Player:** While a stream loads, a ring turns round the play button; a second tap stops it.
- **Tour:** On a phone held sideways the pages were cut off; text and buttons now stand side by side.
- **Episodes:** "Pause" on the episode page is no longer cut to "Pa…", and it shows while a stream is still loading.
- **Comments:** A zap sent no notification.
- **Comments:** Without a Nostr post by the show, a comment now names the show and episode, so other apps show what it is about.
- **Nostr:** With an imported key Hopp did not use your relay list.

## [0.2.1] - 2026-10-18

### 🆕 New Features
- **Episodes:** Episodes not downloaded show their file size.

### 🐛 Fixes
- **Wallet:** No payment goes out twice, even when a relay, the connection or the app drops out; unclear payments say "outcome unknown".
- **Wallet:** Only what the wallet proves (preimage) counts as paid.
- **Wallet:** Unclear payments and routing fees count against the daily budget.
- **Sovereigncast:** Paid release boosts, Boost2Unlock and suggestions count at once; no invoice goes to the wallet twice.
- **Downloads:** A download that keeps failing no longer holds up the others.
- **Episodes:** Equal titles or chapter start times crashed lists.
- **Nostr:** A second key in the iCloud Keychain no longer replaces the first.
- **Nostr:** nsec and bunker address are no longer kept in the app's saved state.
- **Shows:** `&amp;` in feed texts shows as `&`.
- **Sovereigncast:** Unusual data from an instance no longer crashes the app.

## [0.2.0] - 2026-10-17

### 🆕 New Features
- **Nostr:** Optional Nostr identity – with Amber, a bunker or a key made in Hopp; votes then count once per person.
- **Nostr:** Back the key up in the password manager or iCloud Keychain, copy it as nsec, or import one you have.
- **Comments:** Read, write, answer and like Nostr comments on every episode; mute, report, hide.
- **Boosts:** A boost can also go out as a public Nostr comment.
- **Shows:** Play the trailer, before subscribing too.
- **Player:** Show notes in their own sheet; time marks jump there.
- **Sovereigncast:** Release Boosting and Boost2Unlock suggest the amount that unlocks the episode at once.

### 🐛 Fixes
- **Player:** Chapter images that are not square show whole, full screen on a tap.
- **Player:** The chips show only what an episode has; the boost ticker is gone.
- **Player:** Shows without value no longer offer boosts.
- **Comments:** Comments on shows that post only a plain Nostr note (e.g. Einundzwanzig) are found.
- **Comments:** Zaps with a message show as comments.
- **Comments:** No Fountain comment goes missing when a relay is slow; loading no longer hangs for 30–40 seconds.
- **Comments:** Load only when asked (at once with a VPN) and are kept.
- **Comments:** Your comments stand on their own and point to the episode, so Fountain and other apps show them.
- **Comments:** The answer field no longer sits under the keyboard.
- **Home:** "Recently played" leaves out episodes only marked as played.
- **Home:** Long greetings fit on one line; "Customize Home" is an icon beside the greeting.
- **Home:** "Your shows" opens at the first show again.
- **Library:** The badge counts every unheard episode, not just "1".
- **Episodes:** The episode page opens on the show notes; lists in show notes render properly.
- **Boosts:** Split bar and names are in the same order.
- **Sovereigncast:** Shows refresh on every start.
- **Privacy:** Privacy and Nostr pages are shorter and clearer.
- **Tour:** A new page on Nostr; Zeus instead of Primal as the wallet example.

## [0.1.1] - 2026-10-15

### 🆕 New Features
- **Search:** Subscribe straight from the results; suggestions while typing.
- **Shows:** Donations to a show's Lightning address go through Hopp.
- **Shows:** Mark a whole show or all older episodes as played.
- **Downloads:** Status on every cover; start waiting downloads over mobile data.
- **Downloads:** See the space used, delete all at once; on Android in the Files app.
- **Player:** Animated chapter images (Android).
- **Wallet:** Connect by QR code.
- **Sovereigncast:** Suggest topics and boost suggestions; most popular episodes; unlocked clips play in the player.
- **Settings:** OPML import.

### 🐛 Fixes
- **Wallet:** Routing fees count against the daily budget.
- **General:** Signed so that F-Droid can publish our APK.

## [0.1.0] - 2026-10-14

### 🆕 New Features
- **General:** A podcast player without an account, for Android and iOS, phone and tablet, in English and German.
- **Library & Home:** New episodes, continue listening, your shows; sections can be reordered and hidden.
- **Player:** Queue, sleep timer, speed per show, skip intervals, skip silence, Android Auto, transcript that reads along.
- **Search:** Podcast Index (optionally fyyd and Apple), feed address, OPML, "Open in Hopp" links.
- **Wallet:** Connect a Lightning wallet over NWC, boost, stream sats, payment history.
- **Sovereigncast:** Release Boosting, Boost2Unlock, voting and top boosts.
- **Episodes:** Episode page with show notes, chapters and people; favourites; search a show's episodes.
- **Sharing:** Share or copy a show, episode, chapter or transcript line.
- **Privacy:** Who sees what, at a glance; Hopp collects nothing.
