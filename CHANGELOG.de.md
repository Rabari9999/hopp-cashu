# Changelog

Zwei Stufen (ADR 0017): Neuerungen oben – daraus entsteht der „Was ist neu“-Text
der Stores –, darunter die Fehlerbehebungen. Jeder Eintrag nennt vorne den Bereich
der App. Englisch: [CHANGELOG.md](CHANGELOG.md).

## [Unreleased]

### 🆕 New Features
- **Entdecken:** Kategorie-Kacheln, „Für dich“, neue Shows mit ⚡ Value und „Gerade beliebt“ aus dem Podcast Index.
- **Entdecken:** Die Sprache der vorgeschlagenen Shows ist frei wählbar, statt immer der Gerätesprache zu folgen.
- **Shows:** Die Show-Seite zeigt die Kategorien; ein Tipp öffnet sie in Entdecken.
- **Shows:** Nimmt der Feed einer Show keine Sats an, bietet Hopp eine Feed-Variante an, die es kann.
- **Bibliothek:** Sortierung nach neuester Folge (Standard), A–Z oder meiste ungehört.
- **Player:** Eine Standard-Geschwindigkeit für alle Shows; eigene Geschwindigkeiten einzelner Shows bleiben.
- **Player:** Ein Tipp auf die Geschwindigkeit schaltet durch alle Stufen, auch 1,1×, 1,3×, 2,5× und 3×.
- **Downloads:** Fertige Downloads kommen automatisch in „Als Nächstes“ (abschaltbar).
- **Kommentare:** Kommentare zappen – öffentlich, privat oder anonym.
- **Kommentare:** npub des Autors und nevent der Note kopieren.
- **Kommentare:** Profil des Autors in deiner Nostr-App öffnen.
- **Nostr:** Eigene Relays hinzufügen und entfernen (Relays mit Anmeldung, NIP-42, noch nicht).
- **Wallet:** Eigene Werte für Tagesbudget, Streaming-Rate, Schnellboost und „Hopp unterstützen“; 210 statt 500 sats als Vorschlag.
- **Benachrichtigungen:** Für Schnellboost, Boost und Zap getrennt einstellbar.

### 🐛 Fixes
- **Einführung:** Auf dem quer gehaltenen Telefon waren die Seiten abgeschnitten; Text und Buttons stehen jetzt nebeneinander.
- **Player:** Die Geschwindigkeit steht auf Englisch als „1.1×“, auf Deutsch als „1,1×“; Dateigrößen ebenso.
- **Folgen:** „Pause“ auf der Folgenseite wird nicht mehr zu „Pa…“ gekürzt und erscheint schon, während ein Stream lädt.
- **Kommentare:** Der Betrag eines Boosts steht wie bei Zaps neben dem Namen statt im Text.
- **Player:** Lädt ein Stream noch, dreht sich ein Ring um den Play-Button; ein zweiter Tipp stoppt.
- **Kommentare:** Ein Zap löste keine Benachrichtigung aus.
- **Kommentare:** Ohne Nostr-Post der Show nennt ein Kommentar jetzt Show und Folge, damit andere Apps den Bezug zeigen.
- **Nostr:** Mit importiertem Schlüssel nutzte Hopp nicht deine Relay-Liste.

## [0.2.1] - 2026-10-18

### 🆕 New Features
- **Folgen:** Nicht geladene Folgen zeigen ihre Dateigröße.

### 🐛 Fixes
- **Wallet:** Keine Zahlung geht mehr doppelt raus, auch wenn ein Relay, die Verbindung oder die App zwischendurch abbricht; unklare Zahlungen heißen „Ausgang unbekannt“.
- **Wallet:** Als bezahlt gilt nur, was die Wallet belegt (Preimage).
- **Wallet:** Unklare Zahlungen und Routing-Gebühren zählen zum Tagesbudget.
- **Sovereigncast:** Bezahlte Release-Boosts, Boost2Unlock und Vorschläge zählen sofort; keine Rechnung geht zweimal an die Wallet.
- **Downloads:** Ein Download, der immer scheitert, hält die anderen nicht mehr auf.
- **Folgen:** Gleiche Titel oder Kapitel-Startzeiten ließen Listen abstürzen.
- **Nostr:** Ein zweiter Schlüssel im iCloud-Schlüsselbund überschreibt den ersten nicht mehr.
- **Nostr:** nsec und Bunker-Adresse werden nicht mehr im App-Zustand gespeichert.
- **Shows:** `&amp;` in Feed-Texten erscheint als `&`.
- **Sovereigncast:** Ungewöhnliche Daten einer Instanz bringen die App nicht mehr zum Absturz.

## [0.2.0] - 2026-10-17

### 🆕 New Features
- **Nostr:** Optionale Nostr-Identität – mit Amber, einem Bunker oder einem Schlüssel aus Hopp; Stimmen zählen dann einmal pro Person.
- **Nostr:** Schlüssel im Passwort-Manager oder iCloud-Schlüsselbund sichern, als nsec kopieren oder vorhandenen importieren.
- **Kommentare:** Nostr-Kommentare zu jeder Folge lesen, schreiben, beantworten, liken; stummschalten, melden, ausblenden.
- **Boosts:** Ein Boost kann zusätzlich als öffentlicher Nostr-Kommentar erscheinen.
- **Shows:** Trailer anhören, auch vor dem Abonnieren.
- **Player:** Shownotes als eigenes Fenster; Zeitmarken springen dorthin.
- **Sovereigncast:** Release Boosting und Boost2Unlock schlagen den Betrag vor, der die Folge sofort freischaltet.

### 🐛 Fixes
- **Player:** Nicht quadratische Kapitelbilder werden ganz gezeigt, per Tipp im Vollbild.
- **Player:** Die Chips zeigen nur, was eine Folge hat; die Boost-Laufzeile ist entfernt.
- **Player:** Shows ohne Value bieten keinen Boost mehr an.
- **Kommentare:** Kommentare zu Shows, die nur eine einfache Nostr-Note posten (z. B. Einundzwanzig), werden gefunden.
- **Kommentare:** Zaps mit Nachricht erscheinen als Kommentar.
- **Kommentare:** Kein Fountain-Kommentar fehlt mehr, wenn ein Relay langsam ist; das Laden hängt nicht mehr 30–40 Sekunden.
- **Kommentare:** Laden nur auf Wunsch (mit VPN sofort) und bleiben gespeichert.
- **Kommentare:** Eigene Kommentare stehen für sich und verweisen auf die Folge, damit Fountain und andere Apps sie zeigen.
- **Kommentare:** Das Antwortfeld liegt nicht mehr unter der Tastatur.
- **Home:** „Zuletzt gehört“ zeigt keine nur als gehört markierten Folgen mehr.
- **Home:** Lange Begrüßungen passen auf eine Zeile; „Home anpassen“ ist ein Symbol neben der Begrüßung.
- **Home:** „Deine Shows“ öffnet wieder bei der ersten Show.
- **Bibliothek:** Der Zähler zeigt alle ungehörten Folgen, nicht nur „1“.
- **Folgen:** Die Detailseite öffnet mit den Shownotes; Listen in Shownotes werden richtig dargestellt.
- **Boosts:** Aufteilungsbalken und Namen stehen in derselben Reihenfolge.
- **Sovereigncast:** Shows werden bei jedem Start aktualisiert.
- **Datenschutz:** Datenschutz- und Nostr-Seite sind kürzer und übersichtlicher.
- **Einführung:** Neue Seite zu Nostr; Zeus statt Primal als Wallet-Beispiel.

## [0.1.1] - 2026-10-15

### 🆕 New Features
- **Suche:** Direkt aus den Treffern abonnieren; Vorschläge schon beim Tippen.
- **Shows:** Spenden an eine Lightning-Adresse der Show laufen über Hopp.
- **Shows:** Ganze Show oder alle älteren Folgen als gehört markieren.
- **Downloads:** Status auf jedem Cover; wartende Downloads auch über Mobilfunk starten.
- **Downloads:** Speicherbedarf sehen, alle auf einmal löschen; unter Android in der Dateien-App.
- **Player:** Animierte Kapitelbilder (Android).
- **Wallet:** Per QR-Code verbinden.
- **Sovereigncast:** Themen vorschlagen und Vorschläge boosten; beliebteste Folgen; freigeschaltete Clips direkt im Player.
- **Einstellungen:** OPML-Import.

### 🐛 Fixes
- **Wallet:** Routing-Gebühren zählen zum Tagesbudget.
- **Allgemein:** Signatur so, dass F-Droid unser APK veröffentlichen kann.

## [0.1.0] - 2026-10-14

### 🆕 New Features
- **Allgemein:** Podcast-Player ohne Konto, für Android und iOS, Telefon und Tablet, auf Deutsch und Englisch.
- **Bibliothek & Home:** Neue Folgen, Weiterhören, deine Shows; Bereiche sortier- und ausblendbar.
- **Player:** Warteschlange, Sleep-Timer, Geschwindigkeit pro Show, Sprungweiten, Stille überspringen, Android Auto, mitlaufendes Transkript.
- **Suche:** Podcast Index (optional fyyd und Apple), Feed-Adresse, OPML, „In Hopp öffnen“-Links.
- **Wallet:** Lightning-Wallet per NWC verbinden, boosten, Sats streamen, Zahlungsverlauf.
- **Sovereigncast:** Release Boosting, Boost2Unlock, Abstimmungen und Top-Boosts.
- **Folgen:** Detailseite mit Shownotes, Kapiteln und Personen; Favoriten; Suche in den Folgen einer Show.
- **Teilen:** Show, Folge, Kapitel oder Transkript-Zeile teilen oder kopieren.
- **Datenschutz:** Übersicht, wer was sieht; Hopp sammelt nichts.
