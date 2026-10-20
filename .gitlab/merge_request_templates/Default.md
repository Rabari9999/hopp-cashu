## What and why

<!-- What changes for the listener, and why. Link the issue if there is one (Closes #…). -->

## How it was tested

<!-- Tests added or changed; the Gradle tasks you ran; on which device / OS, if you tried it there. -->

- [ ] `./gradlew :shared:jvmTest`
- [ ] `./gradlew :androidApp:testFossDebugUnitTest`
- [ ] `./gradlew :shared:compileKotlinIosSimulatorArm64`

## Screenshots

<!-- For a visible change: before / after, phone size. Delete this section otherwise. -->

## Privacy

<!-- Does Hopp now contact a service or send it something it did not before (ADR 0015)? What, when, and on whose tap? Otherwise: "No new network contact." -->

## Changelog line

<!-- One short sentence for the listener, starting with the part of the app (ADR 0017). The maintainer adds it to the changelog. -->

- EN: **Part:** …
- DE: **Teil:** …

## Checklist

- [ ] Branch starts from the current `main` of `nodesignal/hopp` (rebased, not merged)
- [ ] One topic in this MR
- [ ] New strings in both `values/` and `values-de/`
- [ ] Schema change → matching `migrations/<n>.sqm`
- [ ] No changes to version, changelogs, store metadata or signing (see CONTRIBUTING.md)
