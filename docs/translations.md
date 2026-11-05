# Translations

Hopp speaks English, German and — as a draft since 0.4.0 — Spanish (ADR 0016).

## Adding a language

1. A line in `shared/src/commonMain/kotlin/space/sovereigncast/hopp/ui/components/Languages.kt`:
   its code, its own name, decimal mark, grouping and date separator, and the
   two lines a second browser tab shows. `complete = false` while strings are
   missing — they then show in English.
2. `shared/src/commonMain/composeResources/values-<code>/strings.xml` — the
   app's strings, translated from `values/strings.xml`. Keep every
   placeholder (`%1$s`, `%2$d`; their order may change), plurals as
   `<plurals>`, apostrophes as they are (no `\'`).
3. `androidApp/src/main/res/values-<code>/strings.xml` — Android's own few
   (notifications, widget, Android Auto). Here `\'` is needed.
4. The code in `androidApp/src/main/res/xml/locales_config.xml` and in
   `iosApp/iosApp/Info.plist` (`CFBundleLocalizations`), plus `knownRegions`
   in the Xcode project.
5. `sc_info_url`: the language's page on sovereigncast.space, where it has one.
6. Store listings: `fastlane/metadata/android/<locale>/` — shown to the
   maintainer before they are committed.

`./gradlew :shared:jvmTest --tests '*StringResourcesTest*' --tests '*LanguagesTest*'`
checks 1–4.

## Spanish: open points for the reviewer

The draft (2026-11-02) follows the German for tone: "tú", neutral Spanish,
"podcast" for a show, "billetera" for a wallet. To be decided by a native
speaker:

- *Importe* (Spain) or *monto* (Latin America); *denunciar* or *reportar*;
  "me gusta" for likes.
- "boostable" written as *que admite boosts*; "value block" as *bloque de
  valor*; "build" kept in English.
- *Buenas tardes* covers the afternoon and the early evening: check it against
  the hours Home greets with.
- Weekdays abbreviated without a period (*Lun*), *wifi* in lower case.
- Menu paths of other apps in the move guides (AntennaPod, Pocket Casts …)
  are generic, not checked against their Spanish interfaces.
- Store listings in Spanish are not written yet.

## Later

- Hosted Weblate (ADR 0016) for the app's strings and the store listings.
- Store screenshots selected by test tags instead of labels
  (`devicetests/store/shots.yaml`), so a language does not mean a new
  alternative in every selector.
