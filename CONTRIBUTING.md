# Contributing to Hopp

Thanks for helping! Changes come in as merge requests from your own fork of
`nodesignal/hopp`; a maintainer reviews, tests and merges them. Nobody outside
the maintainers pushes to this repository directly.

## Before you start

- Read [`docs/adr/`](docs/adr/). The decisions there are settled; an MR that
  works against one will be asked to change, not argued into the ADR.
- For a new feature or a visible change in behaviour, open an issue first and
  say what you have in mind. A short "yes, go ahead" saves you a rebuilt branch.
  A clear bug fix can go straight to an MR.
- One topic per MR. Two unrelated changes are two MRs, each on its own branch
  from `main` — not one branch stacked on the other.

## Set up once

1. Fork `nodesignal/hopp` with your GitLab account and add an SSH key to *your*
   account (not as a deploy key of ours).
2. Clone your fork and add this repository as `upstream`:

   ```bash
   git clone git@gitlab.com:<you>/hopp.git
   cd hopp
   git remote add upstream https://gitlab.com/nodesignal/hopp.git
   ```

3. JDK 21 and the Android SDK (`local.properties`: `sdk.dir=…`). Linking and
   running the iOS app needs a Mac with Xcode; the iOS Kotlin code compiles
   on Linux too.

## Every change

Start from the current `main` of this repository, not from your fork's `main`
— `main` here moves several times a day, and an old base comes back as
conflicts:

```bash
git fetch upstream
git switch -c fix/short-name upstream/main
```

Branch names: `fix/…`, `feat/…`, `docs/…`, `test/…`.

Before you open or update the MR, run the tests:

```bash
./gradlew --no-daemon :shared:jvmTest
./gradlew --no-daemon :androidApp:testFossDebugUnitTest
./gradlew --no-daemon :shared:compileKotlinIosSimulatorArm64   # iOS still compiles
```

When `main` has moved on while you work, rebase — do not merge `main` into
your branch:

```bash
git fetch upstream
git rebase upstream/main
git push --force-with-lease
```

Then push the branch to your fork and open the MR against `nodesignal/hopp`,
branch `main`. The MR template asks for what we need to review it.

## What we look for

- **Tests.** A fix comes with a test that fails without it; a feature with
  tests for its rules. Bugs seen on a device are reproduced in
  `androidApp/src/test` (Robolectric) where they can be — see `CLAUDE.md`.
- **Every language.** Every new string goes into
  `composeResources/values/strings.xml` and each complete language's folder
  (`values-de`, `values-es`); `StringResourcesTest` checks names and
  placeholders. Adding a language: [docs/translations.md](docs/translations.md).
- **Database.** A change to a `CREATE TABLE` in a `.sq` file needs the
  matching `migrations/<n>.sqm`; installed apps run it on update.
- **Privacy ([ADR 0015](docs/adr/0015-privacy.md)).** Hopp contacts a service
  only for what its table there lists. A change that sends something new to
  anyone — a title, a feed address, the subscription list — either fits that
  table or happens only on the listener's explicit tap, and the MR says so.
- **English** for code comments, docs and commit messages.

## Commit messages

The subject names the part of the app and says what changed, in a short
sentence — no `feat:` prefixes:

```
Discover: a V4V badge only for shows the index really knows a value block of
Comments: open the author's profile in the listener's Nostr app
```

The body says *why*: what was wrong, how it was found (a device, a feed, a
date), what the change does about it. Keep the history readable — squash
fix-ups of your own branch before review, or let us squash on merge.

## Left to the maintainers

Please do not change these in an MR; say in the MR description what you would
like instead:

- `versionCode` / `versionName`, release tags, `fastlane/` metadata and
  changelogs, `CHANGELOG*.md` (the MR template has a field for your changelog
  line), `zapstore.yaml`, `fdroid/`, `altstore/`.
- Signing: keys and passwords never come near the repository
  ([docs/signing.md](docs/signing.md)).

## CI

The pipeline runs only on Hopp's own runner (ADR 0017), which forks cannot
reach — a pipeline in your fork does not start or fails; ignore it. After
reading your MR a maintainer starts the pipeline in `nodesignal/hopp`
(MR → *Pipelines* → *Run pipeline*). Changes to `.gitlab-ci.yml`, `ci/`,
`scripts/` or the Gradle build get a closer look before that, since they run
on our hardware. Documentation-only MRs get no pipeline.

## Review and merge

All review threads have to be resolved before a merge. Answer in the thread
and push a new commit (or a rebased branch); we look again. A maintainer
merges and decides when a change goes into a release.

## AI assistants

Working with an AI coding agent is fine. You are the author: read the diff
before you push, and make sure the MR description is yours. Agents find this
repository's guidance in `CLAUDE.md`.

## Security

Report a vulnerability as a *confidential* issue (tick "This issue is
confidential"), not in a public issue or MR.

## License

Hopp is MIT-licensed ([LICENSE](LICENSE)); by contributing you agree that
your contribution is published under the same license.
