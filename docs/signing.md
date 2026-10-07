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
SHA-256 digest.

If the key were ever lost, Hopp could not be updated on F-Droid or Zapstore
again and Google Play would need a key upgrade — hence the offline backups.
