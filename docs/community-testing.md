# Community testing

From 0.1.0 (2026-10) Hopp is tested in two rings:

| | Crew | Community |
|---|---|---|
| Where | Nodesignal crew group (Signal) | Nodesignal community group (Telegram, public) |
| What | every CI build of `main` (`foss-beta`) | release tags only: `v0.1.0`, `v0.1.1` … |
| Signed with | debug key, later the beta key (docs/signing.md) | **the release key** |
| From | job artifact links (GitLab login) | GitLab release, Zapstore |
| Updates | by hand | Obtainium or Zapstore |

Why the community never gets the CI builds: they are signed with the public
debug key, so anyone could post an APK in a public group that installs over
them — and reaches the connected wallet (security review 2026-10-10, M-07).
And a release can never update them: who started on a debug or beta build
reinstalls, and loses library, positions and the wallet connection. With the
release key from the start, Zapstore, F-Droid (our signature, ADR 0011) and
later Google Play are all plain updates.

Do not post APK files into the Telegram group: old versions keep circulating,
and nobody can tell ours from someone else's. Link the release instead.

## Introduction post (Telegram, pinned)

Nobody in the community has heard of Hopp before, so the post says what it is
and why to try it next to Fountain — by what Hopp does, not by claims about
Fountain (which has its own wallet and, since 1.4, NWC too). Under Telegram's
4096 characters.

> 🎧 **Hopp — wir testen unseren eigenen Podcast-Player**
>
> Aus der Nodesignal-Crew kommt etwas Neues: **Hopp**, ein Podcast-Player für Android (iOS folgt). Hopp spielt jeden Podcast — nicht nur Bitcoin-Shows — und kann alles, was Podcasting 2.0 ausmacht: Kapitel, Transkripte, Mitwirkende, Boosts und Streaming-Sats über Lightning.
>
> **Warum noch ein Player, wenn es Fountain gibt?**
> Fountain ist eine starke App mit eigener Community. Hopp setzt bewusst andere Schwerpunkte:
>
> 🔓 **Kein Konto, kein Tracking.** Du installierst Hopp und hörst. Hopp sammelt nichts und betreibt keinen eigenen Server; deine Abos liegen bei dir, nicht bei uns. Eine eigene Seite in der App erklärt in klaren Worten, wer was sieht — und was du dagegen tun kannst.
> ⚡ **Nur deine Wallet.** Hopp hat keine eingebaute Wallet und hält nie deine Sats. Du verbindest deine eigene Lightning-Wallet per NWC (Alby Hub, Primal, Coinos …), mit Tagesbudget.
> 💯 **100 % für die Podcaster.** Hopp nimmt keinen Anteil. Routing-Gebühren siehst du getrennt. Wer Hopp unterstützen will, schaltet das selbst ein — es ist aus, bis du es willst.
> 🧡 **Open Source** (MIT). Jeder kann nachsehen, was die App tut; F-Droid ist in Vorbereitung.
> 🎙 **Sovereigncast-Shows** werden in Hopp lebendig: Release Boosting, Boost2Unlock, Themen-Abstimmungen und die Top-Boosts der Community — direkt in der App.
> 🇩🇪 Auf Deutsch und Englisch, fürs Handy, Tablet und Android Auto.
>
> **Ihr seid die ersten Tester.** Hopp ist Version 0.1.0 — es wird Ecken geben. Genau dafür brauchen wir euch.
>
> 📲 **Installieren** (mit automatischen Updates):
> • **Zapstore:** nach „Hopp“ suchen.
> • **Obtainium:** „App hinzufügen“ → `https://gitlab.com/nodesignal/hopp`
> • **Direkt:** https://gitlab.com/nodesignal/hopp/-/releases → `hopp-0.1.0-foss.apk`
>
> 🔐 **Echtheit:** Hopp ist immer mit diesem Zertifikat signiert (SHA-256, z. B. mit der App AppVerifier prüfbar):
> `C8:0F:DE:C5:F9:8A:0A:AF:49:54:40:5E:3C:52:C1:7A:78:DB:40:F7:BD:05:D2:AC:25:85:32:40:03:DC:A3:D1`
> APK-Dateien, die hier im Chat herumgehen, sind **nicht** von uns — bitte nur über die Links oben.
>
> ⚠️ **Testversion:** verbinde eine Wallet mit kleinem Betrag und setze in Hopp ein Tagesbudget.
>
> 💬 **Feedback** gern hier — mit Gerät, Android-Version und der Version aus *Einstellungen → Über Hopp*. Oder als Issue: https://gitlab.com/nodesignal/hopp/-/issues

## Each release

`docs/release-checklist.md`; in the group, a short post with the changelog's
highlights and the release link.
