# Changelog

Zwei Stufen (ADR 0017): Neuerungen oben – daraus entsteht der „Was ist neu“-Text
der Stores –, darunter die Fehlerbehebungen. Jeder Eintrag nennt vorne den Bereich
der App. Englisch: [CHANGELOG.md](CHANGELOG.md).

## [Unreleased]

### 🆕 New Features
- **App:** Hopp auf Französisch, Italienisch und Portugiesisch.
- **Sync:** Was gerade läuft, zieht mit: Starte eine Folge auf einem Gerät, und die anderen zeigen sie, bereit zum Weiterhören.

### 🐛 Fixes
- **App:** Hopp bleibt nach einem Neustart des Handys nicht mehr auf dem Startbildschirm hängen.
- **Sync:** Ein Relay, das nicht antwortet, bremst den Abgleich nicht mehr aus.
- **Sovereigncast:** Der Boost-Knopf bei Release Boosting und Boost2Unlock bleibt unter dem Text sichtbar.
- **Nostr:** Deine Nostr-Kontakte erscheinen mit Name und Bild statt als npub.
- **Nostr:** Eine ausgenommene Show erreicht deine Nostr-Kontakte nicht mehr über ein Gerät, das noch nichts davon wusste.
- **Nostr:** Was deine Nostr-Kontakte tun, lädt weiter, wenn du die Seite verlässt.
- **Nostr:** Ein Schalter auf einem zurückgebliebenen Gerät ersetzt nicht mehr die Schlüssel deiner anderen Geräte.

## [0.4.0] - 2026-11-05

### 🆕 New Features
- **Nostr:** Wenn du es einschaltest: Sieh, was deine Nostr-Kontakte hören, boosten und kommentieren – in Entdecken und auf der Seite einer Show.
- **Nostr:** Wenn du willst: Teile deine Abos versiegelt mit gegenseitigen Nostr-Kontakten, sodass nur sie sie lesen können; einzelne Shows lassen sich ausnehmen.
- **Playlist:** „Weiterhören“ und „Als Nächstes“ sind eine Playlist: oben, was läuft, darunter, was als Nächstes kommt, sortierbar – auf Home, in der Bibliothek und im Player.
- **Playlist:** Wische eine Folge weg, um sie aus der Playlist zu nehmen, oder halte sie gedrückt für mehr – überspringen, als Nächstes, als gehört markieren, herunterladen.
- **Bibliothek:** Versieh deine Shows mit eigenen Tags – so vielen du willst – und filtere die Bibliothek danach.
- **Nostr:** Profilname und -bild direkt in Hopp setzen; das Bild geht verkleinert und ohne Metadaten hoch.
- **App:** Dein Profilbild oben: Nostr-Seite, Hörstatistik und Abmelden, einen Tipp entfernt.
- **Sync:** Wie der Abgleich gelaufen ist, zeigt dein Profilbild; sein Menü öffnet die Details oder gleicht sofort ab.
- **Einstellungen:** „Was ist neu“ unter „Über Hopp“ zeigt, was jede Version gebracht hat – nach einem Update einmal auch als Zeile auf Home.
- **Einstellungen:** Hopp mit einer Spende per Lightning unterstützen – aus der verbundenen Wallet oder mit jeder Wallet-App.
- **App:** Hopp auf Spanisch, als Vorschau.
- **Tour:** Die Sprache gleich am Anfang wählen.
- **Bibliothek:** Die Playlist und deine Downloads direkt in der Bibliothek, in aufklappbaren Bereichen.
- **Playlist:** Neue Folgen kommen ans Ende der Playlist, sobald Hopp sie findet – auf all deinen Geräten, nicht mehr erst nach dem Download auf dem Handy.
- **Home:** Die Bereiche ordnest du direkt auf Home an, über den Regler oder einen langen Druck auf einen Titel.
- **Home:** Auf großen Bildschirmen in zwei Spalten: links Hören, rechts deine Shows.
- **Nostr:** Eine Seite zeigt alle deine Shows und ob sie mit deinen Nostr-Kontakten geteilt werden.
- **Sync:** Zeigt den Abgleich-Schlüssel, um ihn bei Relays freizuschalten, die nur bekannte Schlüssel annehmen.

### 🐛 Fixes
- **Sync:** Mit einem per QR-Code verbundenen Signer fragt der Abgleich ihn wirklich und sagt, wenn keine Antwort kommt; der erste Sync-Schlüssel braucht keine zweite Bestätigung.
- **Sync:** Nennt ein Signer Relays, die Hopp nicht erreicht, kommen Hopps Anfragen weiter über die Relays an, über die er sich verbunden hat; erreicht Hopp keines, sagt es das statt „Dein Signer hat abgelehnt“.
- **Sync:** Bei einem Relay ohne Fehler steht kein Hinweis auf ein Problem mehr.
- **Sovereigncast:** Ohne verbundene Wallet zahlst du per QR-Code oder kopierter Rechnung; du siehst, wie lange sie gilt, und kannst das Warten abbrechen.
- **Sync:** Klappt auch mit Schreib-Relays, die nur Events ihres Besitzers oder bestätigter Autoren annehmen: Der Sync nutzt zusätzlich drei Relays von Hopp.
- **App:** Das Aktualisieren endet, sobald die Feeds da sind – nicht mehr eine halbe Minute später.
- **Sync:** Folgen, die du auf einem Gerät gehört hast, stehen auch auf den anderen unter „Zuletzt gehört“.
- **Sync:** Eine Änderung, die du gleich wieder zurücknimmst – ein Bereich aus- und wieder eingeblendet, eine Folge hin- und zurückgeschoben –, geht nicht mehr an die Relays.
- **Sync:** Gleicht seltener ab und schickt nur, was sich geändert hat – schont beim Hören den Akku.
- **Entdecken:** Eine abonnierte Show öffnet sich aus der Suche direkt, nicht mehr als Vorschau, in der sich die Folgen nicht öffnen ließen.
- **Privatsphäre:** Die Seite nennt auch Nostr-Kontakte, Spenden, die Sync-Relays von Hopp, den Bild-Server und im Browser den Server von Hopp Web.
- **Wallet:** Der Hinweis, welche Wallets Keysend können, nennt die richtigen.
- **App:** Die Seitenleiste ist breiter und macht auf dem Handy quer Platz für die Kamera – lange Namen passen mit Luft hinein.
- **Statistik:** Die Hörzeit in Tagen heißt richtig „Tage gehört“ statt „Tage am Stück“ und erscheint erst ab einem Zehntel Tag.
- **App:** Anzahlen stimmen: „1 Folge“ statt „1 Folgen“.
- **Tablets:** Die Playerleiste hält Abstand zur Taskleiste und zur Gestenleiste.
- **Nostr:** Ein Profilbild gilt erst als hochgeladen, wenn sein Server bestätigt, genau dieses Bild zu haben.

## [0.3.2] - 2026-11-01

### 🆕 New Features
- **Bibliothek:** Alle Folgen aller Shows auf einmal als gehört markieren – praktisch nach einer Neueinrichtung.
- **Einstellungen:** Darstellung hell, dunkel oder wie das System.
- **Tablets:** Seitenleiste mit den Einstellungen unten, eine Player-Leiste am unteren Rand und Seiten in lesbarer Breite.
- **Player:** Auf Tablets Shownotes, Kapitel und Personen als Tabs neben dem Player.
- **Nostr:** Der Verbindungscode als QR-Code, zum Scannen mit einem Signer auf einem anderen Gerät.
- **Sync:** Das Sync-Fenster zeigt nach jeder Runde jedes Relay, und was fehlschlug, mit Grund.
- **Sync:** „Von diesem Gerät neu schreiben“, wenn die Relays ein Dokument nur zum Teil haben.
- **iPhone:** Hopp sucht im eingestellten Intervall nach neuen Folgen, solange es geöffnet ist.

### 🐛 Fixes
- **Player:** Eine als gehört markierte Folge verschwindet aus dem Player – einzeln, mit ihrer Show oder mit allen Shows.
- **Player:** Eine auf einem anderen Gerät zu Ende gehörte Folge verschwindet auch hier aus dem Player.
- **Player:** Schnelles Tippen auf das Tempo bleibt beim getippten Tempo.
- **Sync:** „Alle gehört“ und „diese und ältere“ gehen als ein Eintrag pro Show raus – keine gebremsten Relays mehr.
- **Sync:** Zuverlässiger mit Relays, die Ereignisse begrenzen oder nur teilweise halten.
- **Sync:** „Wartet hier“ zählt keine Folgen mehr, die dein Feed nicht mehr hat.
- **Downloads:** Nach dem Wiederherstellen lädt Hopp keine Folgen mehr automatisch herunter, die du schon gehört hast.
- **Verlauf:** Bei frühen Installationen standen Gebühren und Ergebnisse in falschen Spalten; das ist repariert.
- **Wallet:** Eine Zahlung gilt nur mit dem Nachweis der Wallet als bezahlt.
- **Darstellung:** Die Statusleiste folgt dem in Hopp gewählten Design.
- **Bibliothek:** Der Zähler ungehörter Folgen ist orange statt rot.
- **Kapitel:** Die Zeiten stehen in einer Spalte, die Titel bündig daneben.
- **Shows:** Eine Folge ohne eigenes Cover zeigt das der Show.
- **Nostr:** Die Anmeldung mit Verbindungscode findet deinen Signer schneller.

## [0.3.1] - 2026-10-28

### 🆕 New Features
- **Widget:** Der Player auf deinem Startbildschirm, wie auf dem Sperrbildschirm – mit Abspielen, Springen, nächstem Kapitel und deinem Schnell-Boost (Android).
- **Statistik:** Sieh, was du gehört und gegeben hast – Zeit, gesparte Zeit, Top-Shows und -Folgen, Sats pro Show –, mit Sync auf all deinen Geräten.
- **Shows:** Intro und Abspann jeder Folge einer Show überspringen, einzustellen mit ihrem Tempo unter „Wiedergabe für diese Show“ oder für alle Shows unter Einstellungen → Wiedergabe → „Pro Show“.
- **Wallet:** Die Wallet-Seite sagt in einfachen Worten, wen deine Wallet erreicht.
- **Streaming:** Player und Streaming-Blatt sagen, wenn deine Wallet jemanden in der Folge nicht erreicht.
- **Teilen:** Wähle, was du teilst – Hopp-Link, die Webseite aus dem Feed, den Audio-Link, die geladene Datei oder bei einer Show die Feed-Adresse. Hopp merkt sich deine Wahl.
- **Personen:** Tippe auf eine Person in einer Folge oder im Player und sieh alle Folgen mit ihr – in deinen Abos und in anderen Podcasts; auch die Suche in Discover findet Personen.
- **Sovereigncast:** Die Vorschau vor dem Abonnieren zeigt das Sovereigncast-Abzeichen und die offenen Themenvorschläge.

### 🐛 Fixes
- **Android Auto:** „Vor“ am Lenkrad oder am Kopfhörer spult um deine Sekunden vor, statt zur nächsten Folge zu springen; das nächste Kapitel hat seinen eigenen Knopf.
- **Als Nächstes:** Eine als gehört markierte Folge verlässt „Als Nächstes“ und startet nicht mehr von selbst.
- **Streaming:** Anteile, die deine Wallet nicht zahlen kann, werden nicht mehr angespart und dann Runde für Runde übersprungen.
- **Wallet:** Eine Wallet, die nicht antwortet – etwa coinos bei geschlossener App –, bekommt nichts geschickt; Hopp sagt das und wartet.
- **Verlauf:** Ein Eintrag zeigt, was rausgehen sollte und was daraus wurde, statt „0 sats“.
- **Sovereigncast:** Eine Show, deren Feed ihre Instanz erst später nennt, bekommt ihre Community-Tabs ohne neues Abonnieren – Herunterziehen auf der Show-Seite reicht.

## [0.3.0] - 2026-10-25

### 🆕 New Features
- **Nostr:** Abos, Hörstand, Favoriten, „Als Nächstes“, Hörgewohnheiten und Download-Einstellungen zwischen deinen Geräten abgleichen – verschlüsselt, ohne Hopp-Server.
- **Nostr:** Auf einem neuen Gerät nach der Anmeldung die Bibliothek aus Nostr wiederherstellen; danach öffnet Hopp die Startseite und sagt, was zurückgekommen ist.
- **Tour:** „Ich nutze Hopp schon“ auf der ersten Seite und „Wiederherstellen“ neben „Einrichten“ auf der Nostr-Seite führen direkt zur Wiederherstellung aus Nostr.
- **Tour:** „Ich komme von einer anderen Podcast-App“ führt zum Umzug deiner Abos, mit dem Weg zum Export der bekannten Apps.
- **Bibliothek:** Den OPML-Export einer anderen App an Hopp teilen oder mit Hopp öffnen: Hopp fragt kurz und abonniert.
- **Bibliothek:** OPML-Dateien auch auf iPhone und iPad wählen und exportieren.
- **Player:** Leise Shows lauter stellen (Android), in den Wiedergabe-Einstellungen und neben den Geschwindigkeiten.
- **Boosts:** Hopp zeigt, was in deiner Wallet ist, und sendet keinen Boost, den sie nicht deckt.
- **Player:** Beim Ziehen der Position zeigt eine Blase, wo du landest, mit dem Kapitel.
- **Player:** Zurück- und Vorspulen auf dem Sperrbildschirm und in der Benachrichtigung (Android), mit deinen Sekunden; daneben das nächste Kapitel.
- **Home:** Release Boosting und Boost2Unlock unter „Mitmachen“ öffnen direkt ihre Details in der Show.
- **Home:** Einzelne Einträge unter „Mitmachen“ ausblenden – eine Runde, die Vorschläge einer Show oder eine ganze Show – und unter „Home anpassen“ wieder einblenden.
- **Home:** Ein kleines Zeichen neben „aktualisiert“ zeigt, wie der Abgleich lief; ein Tipp zeigt Relays, was kam und was noch gesendet wird.
- **Kommentare:** Der Post der Show zur Folge steht über den Kommentaren, wie in Fountain.
- **Downloads:** Automatisches Herunterladen ganz abschalten – dann lädt nur, was du selbst herunterlädst.

### 🐛 Fixes
- **Sovereigncast:** Eine Release-Boosting-Runde am Ziel zeigt einen Haken und bittet um keine weiteren Boosts – früher kann die Folge nicht erscheinen.
- **Downloads:** Automatische Downloads laufen nicht mehr über mobile Daten weiter, sobald das WLAN weg ist.
- **Player:** Eine Folge, die du für eine andere verlässt, kommt wieder an die Spitze von „Als Nächstes“ und läuft danach weiter.
- **Player:** Nach einem Neustart ist die zuletzt gehörte Folge wieder im Player, dort wo du aufgehört hast.
- **Tour:** Zurück von einer Seite, die die Tour geöffnet hat, führt wieder in die Tour.
- **Nostr:** Amber als Bunker verbinden scheitert nicht mehr an „no permission“; eine schon verbrauchte Bunker-Adresse wird als solche benannt.
- **Home:** Begrüßt dich mit deinem Nostr-Namen, wenn kein Boost-Name gesetzt ist, auch nach der Wiederherstellung aus Nostr.
- **Boosts:** Ein Anteil, zu dem die Wallet keine klare Antwort gab, wird nicht mehr ein zweites Mal bezahlt.
- **Sovereigncast:** Release Boosting, Boost2Unlock und Vorschläge zählen jetzt auch gegen das Tagesbudget.
- **Wallet:** Eine Wallet, die Hopps Verschlüsselung nicht spricht, wird beim Verbinden mit Begründung abgelehnt.
- **Datenschutz:** Ist der Schlüsselspeicher des Geräts kurz nicht lesbar, sagt Hopp das, statt Schlüssel oder Wallet zu verlieren.
- **Kommentare:** Stummschalten und Relays ändern überschreiben deine Listen nicht mehr, wenn die Relays nicht lesbar waren.
- **Nostr:** Der Import eines verschlüsselten Schlüssels mit extremer Sicherheitsstufe bringt die App nicht mehr zum Absturz.


## [0.2.2] - 2026-10-20

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
- **Kommentare:** Der Betrag eigener Boosts steht wie bei Zaps neben deinem Namen.
- **Nostr:** Eigene Relays hinzufügen und entfernen (Relays mit Anmeldung, NIP-42, noch nicht).
- **Wallet:** Eigene Werte für Tagesbudget, Streaming-Rate, Schnellboost und „Hopp unterstützen“; 210 statt 500 sats als Vorschlag.
- **Benachrichtigungen:** Für Schnellboost, Boost und Zap getrennt einstellbar.

### 🐛 Fixes
- **Player:** Auf dem quer gehaltenen Telefon sind die Chips (Shownotes, Kapitel, Kommentare) wieder im Bild.
- **Player:** VoiceOver und TalkBack lesen die aktuelle Geschwindigkeit vor, nicht nur „Wiedergabegeschwindigkeit“.
- **Player:** Die Geschwindigkeit steht auf Englisch als „1.1×“, auf Deutsch als „1,1×“; Dateigrößen ebenso.
- **Player:** Lädt ein Stream noch, dreht sich ein Ring um den Play-Button; ein zweiter Tipp stoppt.
- **Einführung:** Auf dem quer gehaltenen Telefon waren die Seiten abgeschnitten; Text und Buttons stehen jetzt nebeneinander.
- **Folgen:** „Pause“ auf der Folgenseite wird nicht mehr zu „Pa…“ gekürzt und erscheint schon, während ein Stream lädt.
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
