# Publishing on Zapstore

Zapstore is a Nostr-based app store (ADR 0011): an app is a set of Nostr events
signed by its developer's key, the APK lives on Zapstore's Blossom CDN. The
Zapstore app checks that the APK's signing certificate is linked to that
Nostr identity, so listeners get Hopp from us and nobody else. Upstream
docs: https://zapstore.dev/docs/publish.

## Tool

`zsp` 2, the publisher CLI — a single binary from
https://github.com/zapstore/zsp/releases (`zsp-<version>-darwin-arm64` on the
Mac, checked against the release's SHA-256; `go install
github.com/zapstore/zsp/v2/cmd/zsp@latest` works too). It reads
`zapstore.yaml` in the repository root. zsp 1 (0.4.x) took `SIGN_WITH=browser`
and `publish --check`; zsp 2 has neither (2026-10-28).

Signing (`SIGN_WITH`) — never an `nsec` in the environment on a shared machine:

- `SIGN_WITH="bunker://…"` — a NIP-46 remote signer (Amber, nsec.app), the
  way Nodesignal's key signs.

zsp also reads `SIGN_WITH` from a `.env` in the working directory; `.env` is
in `.gitignore`. Its sibling hotbox (github.com/zapstore/hotbox) would keep
the Nostr key and the release key in one local vault; not used yet — its
`hotbox sign` re-aligns the APK, which F-Droid's signature copy cannot take
(docs/fdroid.md).

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

   Linked on 2026-10-14 with zsp 1 (the commands above) for Nodesignal's
   npub; **the link expires 2028-10-13**. zsp 2 has no `identity` command:
   renew it with its wizard, `zsp` with no arguments, run by hand (zsp's
   README). `zsp check` shows the link and when it ends.

## Every release

`scripts/release.sh` publishes on Zapstore right after the GitLab release
when `SIGN_WITH` is set:

```bash
SIGN_WITH='bunker://…' scripts/release.sh vX.Y.Z ~/Documents/hopp-release.jks
```

It first runs `zsp check`, which signs and publishes nothing, and stops unless
zsp finds this version's APK in the GitLab release. Without `SIGN_WITH` it
stops after the GitLab release; publish by hand then:

```bash
zsp check zapstore.yaml                       # read-only: APK, listing, proof, next step
SIGN_WITH='bunker://…' zsp publish --no-compress zapstore.yaml
SIGN_WITH='bunker://…' node web/deploy/zapstore-images.mjs
```

`GITLAB_TOKEN` (release.sh takes glab's) keeps GitLab's API from
rate-limiting zsp; zsp sends it to gitlab.com only. zsp fetches the APK from
the release, takes the release notes from `CHANGELOG.md`'s section for the
version and shows a preview in the browser before it publishes. `--channel
beta` would publish to Zapstore's beta channel instead of `main`; Hopp's
releases go to `main`.

## The screenshots' order

zsp 2 sorts an app's `image` tags by their address before it signs the app
event (kind 32267), and each image is named after its SHA-256 on Zapstore's
CDN — so Zapstore showed Hopp's twelve screenshots in no order at all (found
2026-11-11). `zapstore.yaml` lists them phone first, then tablet, each
1-home … 6-privacy (devicetests/store/README.md).

`zsp publish --no-compress` uploads the files as they are (no WebP), so each
address is the SHA-256 of one of our files. `web/deploy/zapstore-images.mjs`
then reads the app event from relay.zapstore.dev, finds each listed file's
image by that hash and signs the event again — same tags, the images in
`zapstore.yaml`'s order — through the same bunker (Amber asks once more; the
script keeps the client key `sign.mjs` uses). Without `SIGN_WITH` it only
says whether the order is right. zsp compares app events with their tags
sorted, so a later publish leaves this one alone unless the listing itself
changed; `release.sh` runs the script after every publish. It needs
`(cd web/deploy && npm ci)` once, and a checkout whose screenshots are main's.
