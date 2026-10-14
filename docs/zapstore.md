# Publishing on Zapstore

Zapstore is a Nostr-based app store (ADR 0011): an app is a set of Nostr events
signed by its developer's key, the APK lives on Zapstore's Blossom CDN. The
Zapstore app checks that the APK's signing certificate is linked to that
Nostr identity, so listeners get Hopp from us and nobody else. Upstream
docs: https://zapstore.dev/docs/publish.

## Tool

`zsp`, the publisher CLI — a single binary from
https://github.com/zapstore/zsp/releases (`zsp-<version>-darwin-arm64` on the
Mac; `go install github.com/zapstore/zsp@latest` works too). It reads
`zapstore.yaml` in the repository root.

Signing (`SIGN_WITH`), in order of preference — never an `nsec` in the
environment on a shared machine:

- `SIGN_WITH=browser` — a NIP-07 extension (Alby, nos2x …); zsp opens a page
  to approve the events.
- `SIGN_WITH="bunker://…"` — a NIP-46 remote signer (Amber, nsec.app).

## Once, before the first publish

1. **Nostr identity:** put Nodesignal's `npub` into `zapstore.yaml` (`pubkey`)
   and push it. On the first publish Zapstore's relay reads the file from the
   repository and whitelists that key for Hopp.
2. **Link the release certificate to it** (NIP-C1). Needs the release
   keystore (docs/signing.md), valid for a year by default — renew it before
   it expires:

   zsp picks the keystore format by the file's extension. Hopp's release
   keystore is PKCS12 although it is named `.jks` (keytool's default since
   Java 9; `xxd -l 2` shows `3082`, a real JKS starts `FEEDFEED`), so zsp
   refuses it with "load JKS: got invalid magic". A `.p12` symlink to it
   fixes that without a second copy of the key:

   ```bash
   ln -s ~/Documents/hopp-release.jks ~/Documents/hopp-release.p12
   SIGN_WITH=browser zsp identity --link-key ~/Documents/hopp-release.p12 --link-key-expiry 2y
   zsp identity --verify hopp-0.1.0-foss.apk     # afterwards: the APK is linked
   ```

## Every release

After `scripts/release.sh` has created the GitLab release:

```bash
zsp publish --check zapstore.yaml           # finds hopp-X.Y.Z-foss.apk in the GitLab release
SIGN_WITH=browser zsp publish zapstore.yaml
```

zsp fetches the APK from the release, takes the release notes from
`CHANGELOG.md`'s section for the version and shows a preview in the browser
before it publishes. `--channel beta` would publish to Zapstore's beta channel
instead of `main`; Hopp's releases go to `main`.
