# ADR 0015 — Privacy

- **Status:** accepted
- **Date:** 2026-10-07

## Decision

### Hopp collects nothing itself

No analytics, no tracking SDKs, no server of ours that sees usage. The only
service run for Hopp, the push gateway (ADR 0007), stores device token ↔
endpoint and nothing else. Store declarations: Google Play gets a privacy policy
(on sovereigncast.space), the App Store's privacy labels say "Data Not Collected".

### Who sees what — shown in the app

A settings page lists it plainly:

| Who | Sees | When |
|---|---|---|
| Feed hosts (PodHome, self-hosted instances, …) | IP address, User-Agent, which episode is fetched | every refresh and download |
| Podcast Index | search queries; the subscription list if the batch check is on (ADR 0013) | search, podroll, optional refresh |
| Sovereigncast instances | IP address, which show is viewed, sender name on boosts if given | community features |
| The listener's wallet and NWC relay | payments | boosts, streaming |
| Boost recipients | episode, playback position, message, optional name | boost metadata (Podcasting 2.0 standard) |
| Nostr relays | signed public events | only with public boosts or comments on (ADR 0006) |
| Push gateway (sovereigncast.space) | device token ↔ endpoint, no content | only with push (ADR 0007) |
| Hosts of images, chapters, transcripts | IP address | when loading; their URLs come from the feed |
| Google / Apple | the app's data in the OS cloud backup (see below) | if the listener has OS backups on |

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
  of "Other". The `/`-suffixed marker avoids matching unrelated words.
- Backup rules (`data_extraction_rules.xml` on Android, `isExcludedFromBackup`
  on iOS) are part of phase 1, not an afterthought.
- The crash handler writes reports locally; the "share report" screen shows the
  exact text before anything leaves the device.
