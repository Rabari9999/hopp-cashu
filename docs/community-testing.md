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

## Pinned message (Telegram)

> **Hopp testen** — der Podcast-Player von Nodesignal mit Value for Value.
> Version für Android: https://gitlab.com/nodesignal/hopp/-/releases
>
> **Installieren — mit automatischen Updates:**
> - **Zapstore:** Hopp suchen, installieren. Updates kommen von allein.
> - **Obtainium:** „App hinzufügen“ → `https://gitlab.com/nodesignal/hopp` → hinzufügen. Obtainium meldet jede neue Version.
> - **Von Hand:** auf der Release-Seite `hopp-X.Y.Z-foss.apk` laden.
>
> **Echtheit prüfen:** Hopp ist immer mit diesem Zertifikat signiert (SHA-256), die App AppVerifier zeigt es an:
> `C8:0F:DE:C5:F9:8A:0A:AF:49:54:40:5E:3C:52:C1:7A:78:DB:40:F7:BD:05:D2:AC:25:85:32:40:03:DC:A3:D1`
> APKs, die hier im Chat geteilt werden, sind **nicht** von uns.
>
> **Noch eine Testversion:** verbinde nur eine Wallet mit kleinem Betrag und setze ein Tagesbudget.
>
> **Feedback:** hier im Thema „Hopp“ — mit Gerät, Android-Version und der Version aus Einstellungen → Über Hopp. Oder als Issue: https://gitlab.com/nodesignal/hopp/-/issues

## Each release

`docs/release-checklist.md`; in the group, a short post with the changelog's
highlights and the release link.
