# ADR 0015 — Privacy

- **Status:** accepted
- **Date:** 2026-10-07

## Decision

### Hopp collects nothing itself

No analytics, no tracking SDKs, no server of ours that sees usage. The only
service run for Hopp, the push gateway (ADR 0007), stores device token ↔
endpoint and nothing else. Store declarations: Google Play gets a privacy policy
(https://hopp.sovereigncast.space/privacy/ and /de/datenschutz/ — Hopp's own site, repo `nodesignal/hopp.sovereigncast.space`; live since 2026-10-18, Android and iOS, controller "Nodesignal Podcast" with an e-mail address and no postal address, no imprint yet), the App Store's privacy labels say "Data Not Collected".

### Who sees what — shown in the app

A settings page lists it plainly:

| Who | Sees | When |
|---|---|---|
| Feed hosts (PodHome, self-hosted instances, …) | IP address, User-Agent, which episode is fetched | every refresh and download |
| Podcast Index | search queries; the subscription list if the batch check is on (ADR 0013); the device's language and a category (added 2026-10-19); a person's name (added 2026-10-27, ADR 0008) | search, podroll, Discover's popular and new value shows, optional refresh; a person's page when it opens |
| Sovereigncast instances | IP address, which show is viewed, sender name on boosts if given | community features; the preview of a show not subscribed yet, which asks the instance its feed names (added 2026-10-27, ADR 0002) |
| The listener's wallet and NWC relay | payments | boosts, streaming |
| Boost recipients | episode, playback position, message, optional name | boost metadata (Podcasting 2.0 standard) |
| A commenter's Lightning address server | IP address, the amount, the zap request: which comment, the message, the listener's npub (in a private zap only for the author, encrypted; none in an anonymous one — chosen in the zap sheet, as in Amethyst, and always without an identity); it publishes the receipt on Nostr relays, in public | only when the listener zaps a comment (added 2026-10-19, ADR 0021) |
| Nostr relays | signed public events; the IP address; with sync on, that the listener uses Hopp and when their library changes — the library itself sealed with a sync key that only their key opens (ADR 0021, 5, amended 2026-10-21) | only with a Nostr identity: its profile when made (ADR 0021), later comments and public boosts (ADR 0006); sync only when turned on (ADR 0021, 5, added 2026-10-20), on the listener's write relays and Hopp's three for sync (`nos.lol`, `relay.primal.net`, `relay.nostr.net`; amended 2026-11-02) |
| Push gateway (sovereigncast.space) | device token ↔ endpoint, no content | only with push (ADR 0007) |
| Hosts of images, chapters, transcripts | IP address | when loading; their URLs come from the feed |
| Whoever opens a shared link (and www.sovereigncast.space, in a browser) | show, episode and time in the link; the page sees the opener's IP | only when the listener shares (added 2026-10-12) |
| Google / Apple | the app's data in the OS cloud backup (see below) | if the listener has OS backups on |

**Amended 2026-10-11 (device feedback): every card carries a tip, and Hopp
checks what it can see.** VPN active and private DNS (Android:
`NetworkCapabilities.TRANSPORT_VPN` and `LinkProperties.isPrivateDnsActive` of
the default network; DNS counts as covered while a VPN runs), the optional
directories off, no sender name set — those four are counted at the top ("3 of
4 points Hopp can check are optimised"); the refresh interval and the NWC relay
are shown, not judged. A check is only green when Hopp can see it — a VPN says
the IP is hidden from the servers, not whom the listener trusts with it — and
nothing is red: the page helps, it does not scold. A tap leads to where the
point is set (system VPN or network settings, Hopp's own settings, the wallet
page). The OS backup cannot be read by an app, so that card stays a tip.

**Amended 2026-10-16 (device feedback): too much text, no idea where to look.**
The page opens with one line, then "Your protection": the four checks
together ("2 of 4 done"), each a tap to where it is set. "Who sees what" is a
list, one row a party with a single line; what, when, the tip and what Hopp
sees of it open in a sheet on a tap. The User-Agent and what never leaves the
device are two more rows.

### User-Agent

`Hopp/<version> (<Android|iOS>; <Phone|Tablet>)`, e.g. `Hopp/1.2.0 (Android; Phone)`
— on feed fetches, downloads and API calls. Enough for podcasters' app, platform
and device statistics; no OS version, no device model.

### Crash reports: shared manually, with a preview

After a crash Hopp offers to show the report and lets the listener send it
themselves (mail, GitLab issue). Nothing is uploaded automatically; the report
contains no subscriptions, wallet data or keys.

### Tor / proxy: phase 3

A SOCKS proxy / Orbot setting for feeds and downloads comes in phase 3. Until
then, Orbot's system-wide VPN mode works without app support.

### OS cloud backup: everything except keys

Android Auto Backup and iCloud may back up library, positions, settings **and the
boost log**. Never backed up: anything in Keystore/Keychain (NWC connection,
Nostr key, wallet seeds) — those are excluded by the platform and explicitly in
the backup rules. Downloaded episodes are excluded too (large, re-downloadable).
The listener's payment history therefore can reach Google's or Apple's backup;
the "who sees what" page says so.

## Consequences

- **Server work (podcasting_tools):** add `('hopp/', 'Hopp')` to
  `download_stats._APP_MARKERS`, so Sovereigncast's statistics count Hopp instead
  of "Other". The `/`-suffixed marker avoids matching unrelated words. *Done
  2026-10-07*, together with `; Phone)` as a device token — without it an Android
  UA lacking a browser's "Mobile" was filed as a tablet.
- **The player must send the User-Agent too, not only the HTTP client.**
  Media3's default `HttpDataSource` identifies as `ExoPlayerLib/…` (counted as
  "Other"), and AVPlayer as `AppleCoreMedia/…` — which download statistics,
  Sovereigncast's included, count as **Apple Podcasts**. Set the UA on the
  Media3 data source factory and on `AVURLAsset` (`AVURLAssetHTTPHeaderFieldsKey`,
  undocumented but widely used — verify in the iOS spike)
  for streaming as well as downloads. Do not leave Ktor's OkHttp engine on its
  default `okhttp/…` UA either: Sovereigncast treats that as a bot and does not
  count the download at all.
- Backup rules (`data_extraction_rules.xml` on Android, `isExcludedFromBackup`
  on iOS) are part of phase 1, not an afterthought.
- The crash handler writes reports locally; the "share report" screen shows the
  exact text before anything leaves the device.
- **Camera (amended 2026-10-14):** Android asks for the camera only when the
  listener taps "Scan QR code" on the wallet page, to read a wallet's NWC
  connection. CameraX and ZXing work on the phone; no picture is stored or
  leaves it. Without a camera the button is not there.
- **Downloads in the Files app (amended 2026-10-14):** the episodes stay in
  Hopp's private storage. A read-only DocumentsProvider shows them as "Hopp"
  in the system's Files app (named after show and episode) — only the system
  binds to it (MANAGE_DOCUMENTS), and a file leaves only when the listener
  picks it there. Deleting stays in Hopp ("Delete all downloads").
- **A show's website when comments load (amended 2026-10-17):** for a show
  whose feed names no Nostr key, loading its comments asks the show's website
  for its NIP-05 key (`/.well-known/nostr.json?name=_`), once per site while
  the app runs. Only after the listener chose to load comments — the website
  then sees the IP address, as the relays do, and the feed host mostly is it.
