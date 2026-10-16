# Changelog

Zwei Stufen wie bei Sovereigncast (ADR 0017): alles über **🐛 Fixes** sind die
Highlights, aus denen der „Was ist neu“-Text der Stores entsteht (Google Play:
500 Zeichen je Sprache); danach die Fehlerbehebungen. Englisch: [CHANGELOG.md](CHANGELOG.md).

## [Unreleased]

### 🆕 New Features
- **Eine optionale Nostr-Identität** — in den Einstellungen erstellt, mit einem Namen, mit dem Hopp dich begrüßt; deine Stimmen bei Sovereigncast-Shows zählen dann einmal pro Person, auf jedem Gerät. Mit Amber oder einem Bunker (nsec.app und andere) anmelden, dann bleibt dein Schlüssel dort, oder in Hopp erstellen und mit Passwort sichern. Oder anonym bleiben.
- **Den Trailer einer Show anhören** — auf ihrer Seite, auch vor dem Abonnieren; die Folge, die du gerade hörst, wartet, wo du warst.
- **Shownotes im Player** — als Blatt wie Kapitel und Transkript; eine Zeitmarke springt dorthin.
- **Kommentare zu jeder Folge** — von Nostr, im Player und auf der Folgenseite: was andere Hörer sagen (auch bei Fountain, mit den Sats ihrer Boosts), und mit Nostr-Identität deine eigenen Kommentare, Antworten und Likes. Stummschalten, melden oder ausblenden, was du nicht sehen willst.
- **Boosts auch auf Nostr** — mit Nostr-Identität kann ein Boost an eine Folge zusätzlich als öffentlicher Kommentar mit Betrag erscheinen, wie bei Fountain; ein Schalter im Boost-Dialog, vorbelegt in den Einstellungen.
- **Sofort veröffentlichen oder freischalten** — Release Boosting und Boost2Unlock bieten den Betrag an, der die Folge jetzt herausbringt (oder das Ziel auffüllt), berechnet wie auf der Community-Seite.
- **Dein Nostr-Schlüssel, sicher und mitnehmbar** — im Passwortmanager (Android) oder iCloud-Schlüsselbund (iOS) sichern und mit einem Tipp zurückholen, als nsec für jede Nostr-App kopieren oder einen vorhandenen Schlüssel verwenden.

### 🐛 Fixes
- Die Privatsphäre-Seite war eine Wand aus Karten: Jetzt steht oben, was Hopp prüfen kann, und wer was sieht ist eine kurze Liste, Details per Tipp. Ebenso die Nostr-Seite: ein Weg für Neue, ein Knopf zum Sichern des Schlüssels.
- Eine Show ohne Value for Value bot im Player trotzdem Boost und Streaming an; jetzt sagt eine ausgegraute Leiste, dass sie keine Sats annimmt. Und ein Kommentar nannte den Autor des Release-Boosting-Posts statt der Show.
- Die Tour hat eine Seite zu Nostr: was es ist, was Hopp damit macht und der kommende Abgleich; die Wallet-Seite nennt Zeus statt Primal, dessen NWC nicht kann, was Hopp braucht.
- Eine Antwort auf den letzten Kommentar tippte man unter der Tastatur; und Kommentare und Boosts landeten am Release-Boosting-Post der Show statt an der Ankündigung der Folge.
- Die wechselnde Zeile mit den letzten Boosts der Show im Player ist weg: Sie verwirrte mehr, als sie half.
- Kommentare laden nur, wenn du es möchtest, auch im Player – sofort nur mit VPN; einmal geladen, bleiben sie auf dem Gerät und erscheinen sofort.
- Kommentare brauchten 30–40 Sekunden, wenn ein Relay nicht antwortete; und Fountains Kommentare endeten mit einem Link auf die Folge, die du gerade hörst.
- Die Chips im Player zeigten ausgegraut, was einer Folge fehlt; jetzt nur noch, was sie hat.
- Shownotes, die jeden Listenpunkt in einen Absatz packen (Einundzwanzig), zeigten den Punkt allein und den Text zwei Zeilen darunter.
- „Deine Shows“ konnte an der ersten Show vorbei gescrollt öffnen, sobald eine Show mit Release-Boosting-Runde nach vorn rückte.

## [0.1.1] - 2026-10-15

### 🆕 New Features
- **Eine Show direkt aus Hopp unterstützen** — führt der Funding-Link einer Show zu einer Lightning-Adresse, öffnet er bei verbundener Wallet Hopps Boost-Fenster als Spende, statt die App zu verlassen.
- **Direkt aus den Suchergebnissen abonnieren** — ein + an jedem Ergebnis, und du bleibst in der Suche.
- **Downloads an jeder Folge im Blick** — ein Abzeichen am Cover: geladen, wartet, lädt oder fehlgeschlagen. Ein Download, der auf WLAN wartet, kann jetzt über Mobilfunk starten, einzeln oder alle auf einmal.
- **OPML auch in den Einstellungen importieren**, neben dem Export.
- **Eine ganze Show als gehört markieren**, oder eine Folge und alle älteren — im Menü der Show und der Folge.
- **Downloads im Blick** — die Einstellungen zeigen, wie viel Platz die Downloads belegen, löschen alle auf einmal und zeigen sie unter Android als „Hopp“ in der Dateien-App.
- **Vorschläge beim Tippen** — ein angefangenes Wort genügt: Sovereigncast-Shows, deine Abos, die Empfehlungen und was in dieser Sitzung gefunden wurde, erscheinen sofort (die Verzeichnisse finden nur ganze Wörter).
- **Animierte Kapitelbilder** — GIFs bewegen sich jetzt, unter Android.
- **Wallet per QR-Code verbinden** — den NWC-Code mit der Kamera scannen; nach der Kamera wird erst beim Scannen gefragt.
- **Sovereigncast-Shows: Themen vorschlagen und Vorschläge boosten** (direkt in ihrer Zeile) — die Zahlung hält Spam fern, und sobald sie bezahlt ist, steht der Vorschlag in der Liste; Sats geben einem offenen Vorschlag Gewicht. Die beliebtesten Folgen der Show haben einen eigenen Tab und öffnen sich in Hopp; beim Hören zeigt der Player die letzten Boosts der Show Zeile für Zeile, und eine lange Boost-Nachricht öffnet sich per Tipp ganz; und ein freigeschalteter Clip läuft direkt in Hopps Player – danach ist die Folge, die du gehört hast, wieder da, wo du warst.

### 🐛 Fixes
- Routing-Gebühren zählen zum Tagesbudget — sie verlassen die Wallet ja auch.
- So signiert, dass F-Droid unsere APK veröffentlichen kann: 0.1.0 war mit neu ausgerichteten Einträgen signiert, die F-Droids Build aus dem Quellcode nicht treffen kann. Store-Texte, Icon und Screenshots für F-Droid und Zapstore.

## [0.1.0] - 2026-10-14

### 🆕 New Features
- **Ein Podcast-Player ohne Konto** — Bibliothek als Karten oder Liste, mit den neuen Folgen je Show, Home mit Neuem, dem, was du gerade hörst, deinen Shows samt nächstem Erscheinen — mit Begrüßung beim Boost-Namen, die Bereiche in deiner Reihenfolge oder ausgeblendet —, Warteschlange, Downloads im WLAN, Sleep-Timer (auch zum Kapitelende), Tempo je Show, eigene Sprungweiten (auch auf dem Sperrbildschirm), Stille überspringen, Android Auto — auf Telefon, Tablet und iPad, hochkant oder quer. Auf Englisch und Deutsch, ab Android 13 auch abweichend von der Systemsprache.
- **Shows finden** im Podcast Index (wahlweise auch fyyd und Apple), per Feed-Adresse oder OPML oder direkt über den Link „In Hopp öffnen“ einer Sovereigncast-Show; eine Show ansehen, bevor du sie abonnierst.
- **Value for Value** — verbinde deine Lightning-Wallet per NWC, booste eine Folge oder die ganze Show, streame Sats beim Hören und sieh je Folge, was gesendet und was vorgemerkt ist – Reste unter 1 Sat werden aufgerundet, wenn du mit einer Folge fertig bist; schlägt ein Boost fehl, sagt es dir eine Benachrichtigung. Aufteilungen kommen in ganzen Sats an; Routing-Gebühren stehen extra.
- **Sovereigncast-Shows** — Release Boosting und Boost2Unlock mit allen Details, über Themenvorschläge abstimmen, die Top-Boosts der Community.
- **Folgen einer Show durchsuchen** nach Themen, und **Favoriten** auf Home sammeln.
- **Teilen**: eine Show, eine Folge – auf Wunsch ab einer Stelle –, ein Kapitel oder eine Zeile aus dem Transkript; der Link öffnet Hopp oder bietet den Feed für jede andere App an. Titel, Beschreibungen, Shownotes und Boost-Nachrichten lassen sich markieren und kopieren.
- **Folgenseiten** mit Shownotes, Kapiteln, Personen und Boost-Button; ein Transkript, das im Player mitläuft.
- **Eine kurze Tour beim ersten Start** — Abonnieren, Bibliothek, Value for Value und Wallet verbinden.
- **Seite zur Privatsphäre** — wer was sieht, in klaren Worten, mit einem Tipp zu jedem Punkt; was Hopp prüfen kann (VPN, privates DNS, deine Einstellungen), wird grün markiert. Hopp selbst sammelt nichts; nach einem Absturz zeigt es den Bericht, den du selbst teilen kannst.
