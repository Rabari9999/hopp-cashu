# ADR 0006 — Identity: no account, optional Nostr

- **Status:** accepted — detailed by ADR 0021 (comments, relays, moderation, sync)
- **Date:** 2026-10-06

## Context

What an identity is needed for:

| Purpose | Without identity | With a Nostr identity |
|---|---|---|
| Listen, subscribe, pay | ✅ fully | — |
| Sender on boosts | free-text name | additionally the npub as sender, so the podcaster can reply |
| Comments via `podcast:socialInteract` | read only | write, reply, react |
| Sovereigncast suggestions and votes | anonymous; votes deduplicated per browser today (`localStorage` id) | signed requests (NIP-98): votes per person, suggestions attributable |
| "Your suggestion became an episode" | impossible | Nostr DM via Sovereigncast's existing DM system |
| Subscriptions/progress across devices | manual OPML | encrypted sync over Nostr |

## Decision

- **No account.** Listening and paying work fully without any identity.
- **Optional Nostr identity**, arriving in **Phase 2** together with the
  Sovereigncast layer.
- **Signers:**
  - **NIP-55** (Amber and other on-device signers) on Android.
  - **NIP-46** remote signer (`bunker://` and `nostrconnect://` QR) on both
    platforms — the only external-signer path on iOS.
  - **New key generated in Hopp** for listeners without Nostr experience
    ("listener identity"), stored in Android Keystore / iOS Keychain, with an
    nsec backup/export screen.
  - **No nsec import.** Pasting an existing private key into an app is bad
    practice in Nostr; listeners with an existing identity use a signer.
- **Sync:** subscriptions, playback progress and settings as encrypted
  app-specific data over Nostr (NIP-78, NIP-44-encrypted to the user's own key).
  The **gPodder sync API** (e.g. self-hosted Nextcloud gPodder Sync, spoken by
  AntennaPod) follows later as an addition for AntennaPod switchers.
- **Boosts on Nostr: opt-in, off by default** — per boost or as a general
  setting. Comments and public boosts reveal listening habits, so Hopp always
  states clearly when something becomes public.

## Consequences

- **Server work (podcasting_tools):** the Listener API accepts NIP-98 signed
  requests for community actions (vote per npub, attributed suggestions);
  anonymous requests keep working. Sovereigncast's Nostr login already
  verifies signatures and can be reused; notifications to listeners can go
  through its NIP-44 DM system.
- NIP-46 depends on relays and is slower than NIP-55; signing must never block
  playback or payments.
- A generated in-app key makes Hopp responsible for its backup: the export
  screen and a reminder are part of the onboarding.
