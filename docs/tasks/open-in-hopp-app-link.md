# Task — Open Hopp from the "Open in Hopp" link

- **Status:** implemented 2026-10-12 — device check and the Sovereigncast hint left (see "Left to do")
- **Opened:** 2026-10-11
- **Relates to:** ADR 0002 §4 (deep links), ADR 0018 phase 1 (Sovereigncast layer)

## Why

Sovereigncast's show page now has an **"Open in Hopp"** link with a QR code
(podcasting_tools, show page → "Make it available in Hopp"). Podcasters share
it in show notes and on social media, and scan it themselves to check that
Hopp recognises their show. The link is

```
https://www.sovereigncast.space/open?feed=<url-encoded feed URL>
```

The page behind it is live and already says what to do when Hopp is missing
(store links, the feed address for any other app). But Hopp itself does not
claim the link yet: the manifest only has the NWC schemes, so tapping the link
on a phone with Hopp installed still lands on the web page. Sovereigncast
shows a hint until this task ships ("Hopp does not open this link by itself
yet …") — remove it there once a release with this task is out.

## What to build

1. **App Link** in `androidApp/src/main/AndroidManifest.xml`, on `MainActivity`:

   ```xml
   <intent-filter android:autoVerify="true">
       <action android:name="android.intent.action.VIEW" />
       <category android:name="android.intent.category.DEFAULT" />
       <category android:name="android.intent.category.BROWSABLE" />
       <data android:scheme="https" />
       <data android:host="www.sovereigncast.space" />
       <data android:pathPrefix="/open" />
   </intent-filter>
   ```

   **`www`, not the bare domain.** Checked 2026-10-11:
   `https://sovereigncast.space/…` answers `308` to `https://www.sovereigncast.space/…`,
   and Android does not follow a redirect when it verifies
   `/.well-known/assetlinks.json` — the bare domain can never be verified
   while it redirects. `www` serves `assetlinks.json` directly (`200`,
   `application/json`) with the release certificate from `docs/signing.md`.
   The redirect also **drops the query string** (`/open?feed=x` →
   `/open`), so a link on the bare domain loses the feed even in a browser;
   that is a bug on sovereigncast.space and worth fixing there, but Hopp and
   Sovereigncast should use `www` regardless.

2. **The custom scheme** from ADR 0002 as the fallback that needs no
   verification: `hopp://subscribe?feed=<url-encoded feed URL>` — a second
   `<intent-filter>` without `autoVerify`.

3. **Handling** in `MainActivity.takeLink()`: today it only accepts
   `nostr…` URIs and hands them to `IncomingLinks.walletUri`. Add a
   `IncomingLinks.feedUrl` next to it for both link forms, read the `feed`
   query parameter, and accept only `http`/`https` feed URLs. In `App.kt`,
   open `ShowPreviewRoute(feedUrl)` — through `selectTab` onto the tab that
   owns the preview, never a plain `navigate` (see CLAUDE.md), and the same
   way for a cold start (`onCreate`) and a running app (`onNewIntent`). An
   already subscribed show opens its show page instead of the preview.

4. **ADR 0002 §4:** amend the fallback domain to `www.sovereigncast.space`,
   with the reason above.

## Done when

- A Robolectric test in `androidApp/src/test` delivers both link forms as
  `ACTION_VIEW` intents — on a cold start and through `onNewIntent` — and
  finds the show preview for that feed, or the show page when subscribed.
- A link with a missing, empty or non-http(s) `feed` parameter opens Hopp
  normally and nothing else.
- On a device with a release build, `adb shell pm get-app-links
  space.sovereigncast.hopp` lists `www.sovereigncast.space` as `verified`,
  and scanning the QR code on a Sovereigncast show page opens Hopp on that
  show.
- Sovereigncast's interim hint is removed (podcasting_tools,
  `templates/show_detail.html`, search for `open-in-hopp-app-link`).

## Left to do (2026-10-12)

Built and tested in Robolectric (`OpenInHoppTest`: both link forms, cold start
and running app, subscribed show, links without a usable feed). What a test
cannot do:

- **Device check with a release build** — `adb shell pm get-app-links
  space.sovereigncast.hopp` should list `www.sovereigncast.space` as
  `verified`. Beta builds are signed with the public debug key, which is not
  in `assetlinks.json`: on a beta the link stays unverified (add it by hand
  under Settings → Apps → Hopp → Open by default, or test with `hopp://`).
- **Remove Sovereigncast's interim hint** once a release with this is out
  (podcasting_tools, `templates/show_detail.html`, search for
  `open-in-hopp-app-link`) — then delete this file.
