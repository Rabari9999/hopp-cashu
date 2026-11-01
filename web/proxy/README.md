# Feed proxy

The web client (ADR 0022, 6) reads feeds, chapters, transcripts and images
through this: most podcast hosts send no CORS headers, so a browser may not
read them itself. GET only, public addresses only (checked on the address
dialled, after DNS and after every redirect), no cookies or credentials either
way, size limits (feeds 32 MB as in the apps, images 5 MB), a rate limit per address, no logs
beyond errors. CORS for the web client's origins only. Not audio: `<audio>`
plays from any host.

Locally (OrbStack), beside the web client on port 8642:

```bash
docker build -t hopp-feed-proxy web/proxy
docker run --rm -p 127.0.0.1:8643:8643 -e HOPP_PROXY_ORIGINS=http://127.0.0.1:8642 hopp-feed-proxy
```

Tests (no Go needed on the Mac):
`docker run --rm -v "$PWD/web/proxy":/src -w /src golang:1.25-alpine go test ./...`

Who runs it for listeners, and where, is open (ADR 0022, 11.2).
