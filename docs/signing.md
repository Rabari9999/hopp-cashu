# Release signing

One key signs Hopp everywhere — Google Play (uploaded as the app signing key,
not Google-generated), F-Droid (reproducible build, our signature) and
Zapstore (ADR 0011). It lives only on the maintainer's machine, backed up
offline; CI builds unsigned (ADR 0017).

**Release certificate (SHA-256):**

```
C8:0F:DE:C5:F9:8A:0A:AF:49:54:40:5E:3C:52:C1:7A:78:DB:40:F7:BD:05:D2:AC:25:85:32:40:03:DC:A3:D1
```

Lowercase without colons (F-Droid's `AllowedAPKSigningKeys`):
`c80fdec5f98a0aaf4954405e3c52c17a78db40f7bd05d2ac2585324003dca3d1`

Where it is used:

- `fdroid/space.sovereigncast.hopp.yml` — `AllowedAPKSigningKeys`
- `https://sovereigncast.space/.well-known/assetlinks.json` — Android App
  Links for `/open` (ADR 0002), in the `nodesignal/sovereigncast-space` repo

To check an APK: `apksigner verify --print-certs hopp.apk` must show this
SHA-256 digest. `scripts/release.sh` signs CI's unsigned release APK and
refuses to publish one with any other certificate.

If the key were ever lost, Hopp could not be updated on F-Droid or Zapstore
again and Google Play would need a key upgrade — hence the offline backups.

Debug builds are a different matter: they are signed with
`androidApp/debug.keystore`, checked in on purpose (password `android`,
SHA-256 `5F:A6:6E:42:…:B8:68`). It only lets one debug APK replace the
previous one; it is worth nothing to anyone, and an APK signed with it can
never update a release install.

## Beta builds (added 2026-10-12)

Betas carry release code and a beta key of their own. Until 2026-10-22 they
fell back to the public debug key without one — anyone could build an APK
that installs over such a beta and reads its data (security review
2026-10-10, M-07; sync audit 2026-10-22, H-04). Since then **no beta is built
without the beta key**: packaging one stops with a pointer here, and CI
builds a beta only where the key's variables are set. The one exception is
the device check (`scripts/device-check.sh`, `-Phopp.beta.deviceCheck=true`):
its emulators get a beta with the debug key, its version marked `-local`,
never handed on. They show as **"Hopp β"** on the home screen and never claim
to be the release. Since 2026-10-14 a beta is an app of its own,
`space.sovereigncast.hopp.beta`: it installs next to the release, with its own
library and wallet connection. (Debug builds keep `space.sovereigncast.hopp` —
the tests run as that package — and so conflict with an installed release.)
Links Android verifies for Hopp (`/open`, ADR 0002) go to the release only;
`hopp://` and `nostr+walletconnect:` offer both apps. To give them a key of their own:

1. Create it once, off CI: `keytool -genkeypair -keystore hopp-beta.jks -alias hopp-beta -keyalg EC -groupname secp256r1 -validity 10000`.
2. GitLab variables hold text only, so the keystore goes in as base64:
   `base64 -i ~/Documents/hopp-beta.jks | pbcopy` copies it as one line. In
   GitLab → Settings → CI/CD → Variables → Add variable: type **Variable**,
   visibility **Masked and hidden**, **Protect variable** on, key
   `HOPP_BETA_KEYSTORE_BASE64`, value pasted. Then the same for
   `HOPP_BETA_KEYSTORE_PASSWORD` (plus `HOPP_BETA_KEY_PASSWORD` if it differs,
   `HOPP_BETA_KEY_ALIAS` if not `hopp-beta`). The pipeline writes the keystore
   out for the beta build — to a temporary file outside the project, readable
   by the runner's user only, deleted however the build ends.
3. The next pipeline on a protected branch signs the beta with it (protected
   variables reach protected branches and tags only — protect a release branch
   such as `0.3.0` to get its betas from CI). Testers install that beta once
   by hand after uninstalling the old one (another signature cannot update
   it); from then on it updates as before — each beta's `versionCode` is the
   minutes since 2026 at its commit, so a later beta always updates an earlier
   one (the release keeps its own count).
4. For a beta built on the Mac, the same three environment variables point at
   the keystore there (kept outside the repository, backed up like any key).
