# Translations

Hopp speaks English and German, and through an LLM's translation Spanish, French,
Italian and Portuguese (ADR 0016).

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

## How the translations are made

Spanish, French, Italian and Portuguese are translated by an LLM (maintainer,
2026-11-06): from English, with the German as a reference for meaning. No native
speaker reads them first and no translation platform is planned; a mistake
someone reports is corrected in the file. A new string in English gets its
translations in the same change — `StringResourcesTest` fails without them for
a language marked complete.

The choices made, in case a report questions one:

- **Spanish:** "tú", neutral Spanish, "podcast" for a show, "billetera" for a
  wallet.
- **French:** "tu", "émission" for a show, "portefeuille" for a wallet, "signeur"
  for a Nostr signer. Numbers group with a narrow no-break space (*21 000*).
- **Italian:** "tu", "podcast" for a show, "puntata" for an episode,
  "portafoglio" for a wallet, "app di firma" for a signer.
- **Portuguese:** one `pt` for all who speak it — Brazilian spelling, "você",
  "podcast" for a show, "carteira" for a wallet, "assinador" for a signer.
- **All of them:** Nostr contacts are contacts, never friends. `sc_info_url` and
  `nwc_explainer_url` point to the English pages where a language has none.
  Store listings exist in English and German only; "What's new" shows English.

## Later

- Store screenshots selected by test tags instead of labels
  (`devicetests/store/shots.yaml`), so a language does not mean a new
  alternative in every selector.
