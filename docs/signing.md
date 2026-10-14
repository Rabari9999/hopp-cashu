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

Betas carry release code and, until a beta key exists, the public debug key —
anyone can build an APK that installs over such a beta (security review
2026-10-10, M-07). They show as **"Hopp β"** on the home screen and never claim
to be the release. To give them a key of their own:

1. Create it once, off CI: `keytool -genkeypair -keystore hopp-beta.jks -alias hopp-beta -keyalg EC -groupname secp256r1 -validity 10000`.
2. In GitLab → Settings → CI/CD → Variables, add (protected, masked where possible):
   `HOPP_BETA_KEYSTORE` as a **File** variable with the keystore, and
   `HOPP_BETA_KEYSTORE_PASSWORD` (plus `HOPP_BETA_KEY_PASSWORD` if it differs,
   `HOPP_BETA_KEY_ALIAS` if not `hopp-beta`).
3. The next pipeline on `main` signs the beta with it. Testers install that beta
   once by hand (another signature cannot update the old one); from then on it
   updates as before. Without the variables the build falls back to the debug key.
