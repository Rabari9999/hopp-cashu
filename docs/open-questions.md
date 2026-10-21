# Open questions

Topics still to be decided, in the order we plan to go through them. Each
carries the current **proposal**; once decided it becomes an ADR.

## 4. Wallet strategy

Decided → [ADR 0005](adr/0005-wallet-strategy.md).

## 5. Identity

Decided → [ADR 0006](adr/0006-identity.md).

## 6. Push notifications

Decided → [ADR 0007](adr/0007-push-notifications.md).

## Directories and discovery

Decided → [ADR 0008](adr/0008-directories-and-discovery.md).

## 7. iOS App Store risk

Decided → [ADR 0009](adr/0009-ios-app-store-strategy.md).

## 8. Distribution

Decided → [ADR 0011](adr/0011-distribution.md).

## 9. Listener API v1 (server side, in `nodesignal/podcasting_tools`)

Decided → [ADR 0010](adr/0010-listener-api-v1.md).

## 11. Player features in phase 1

Decided → [ADR 0012](adr/0012-player-features-phase-1.md).

## 12. App architecture

Decided → [ADR 0013](adr/0013-app-architecture.md).

## 13. Privacy

Decided → [ADR 0015](adr/0015-privacy.md).

## 14. Languages and accessibility

Decided → [ADR 0016](adr/0016-languages-and-accessibility.md).

## 15. Quality and builds

Decided → [ADR 0017](adr/0017-quality-and-builds.md).

## 10. Phase plan

Decided → [ADR 0018](adr/0018-phase-plan.md).

## 16. Visual design

Decided → [ADR 0019](adr/0019-visual-design.md): logo, design system, icons,
navigation, now playing, show page with community tab, boost dialog.

All other topics of the specification phase are decided. New questions go here.

## 17. The Podcast Index key and F-Droid — decided 2026-10-10

Key and secret are public in `shared/build.gradle.kts` (the proposal below),
as AntennaPod (`net/discovery/build.gradle`) and KDE Kasts (in its search
code) do. A Gradle property or CI variable still overrides them; F-Droid's
build from source and our signed APK use the same values. ADR 0008 amended.

ADR 0008 ships the key in the app; since package 3 it comes from a Gradle
property or the CI variables `PODCASTINDEX_KEY` / `PODCASTINDEX_SECRET`, not
from git. F-Droid builds from the public source and has no secrets — and
because the key is compiled into the APK, an F-Droid build without it is a
different APK, so the reproducible-build check against ours (ADR 0011) fails.

**Proposal:** commit the key and secret to the public repository once it goes
public (the secret is extractable from any APK anyway, ADR 0008 accepts that),
and rotate them if they are abused. Alternative: an F-Droid build without
search, as its own unsigned variant — loses reproducibility for that variant.
To be decided before the F-Droid submission — **now on the critical path**:
since 2026-10-10 F-Droid and Zapstore are the first release channels (ADR 0011).


## 18. A web client — proposed 2026-10-21

Concept → [ADR 0022](adr/0022-web-client.md) (proposed): a web client as a
way onto the iPhone while the App Store is out of reach. Decided so far:
NIP-46 and NIP-07 only, WebLN for payments, comments and boosts, the sync
key (ADR 0021, 5), the full Sovereigncast feature set, the address
`https://play.sovereigncast.space`, built and tested locally first. Open:
who runs the feed proxy and on which domain; the spike (stage W1) decides
whether Compose on the web carries it.
