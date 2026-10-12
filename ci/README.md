# CI runner (ADR 0017)

Hopp's jobs run on our own GitLab runners (tag `hopp`), never on GitLab's
shared runners. Two hosts are registered; both use the same `hopp-ci` image
and the same `.gitlab-ci.yml`, so either can take every job:

| Runner | Host | Jobs at once | State |
|---|---|---|---|
| `hopp mac (OrbStack)` | Apple-Silicon Mac, 24 GB RAM, Docker via OrbStack | 2 | active — the normal path |
| `hopp build box` | Linux box, 6 GB RAM (a Gradle Android build needs about 4 GB) | 1 | paused — fallback |

Measured 2026-10-12 on a real push (warm dependency cache, no build cache):
`shared-tests` 3.3 min on the Mac against 6–7 min on the Linux box,
`android-debug` 9.5 min against 16–18 min.

When the Mac is off or asleep, jobs wait. To fall back, unpause the Linux
runner in GitLab (Settings → CI/CD → Runners) — pause it again afterwards, or
the two runners share jobs and a build lands on the slow box at random.

The same runners also deploy `nodesignal/sovereigncast-space` and
`nodesignal/hopp.sovereigncast.space` (their `pages` jobs carry `tags: [hopp]`);
a new runner has to be enabled for those projects too.

## Setup on the Mac (OrbStack)

The image is built for `linux/amd64` and runs under Rosetta, even on Apple
Silicon: Kotlin/Native has no Linux-arm64 host (the `shared-tests` job compiles
the iOS klibs), and the Android build tools' `aapt2` exists for Linux only as
x86_64. Native arm64 would be faster but cannot build Hopp.

```bash
# 1. OrbStack and the runner
brew install --cask orbstack        # open it once; enable "Start at login"
brew install gitlab-runner

# 2. Build the image (stays local)
docker buildx build --platform linux/amd64 -t hopp-ci:1 --load ci/

# 3. Register (token from GitLab: Settings → CI/CD → Runners → New project
#    runner, tag "hopp", "Run untagged jobs" off)
mkdir -p ~/gitlab-runner-cache/hopp-ci
gitlab-runner register --non-interactive \
  --url https://gitlab.com --token "$RUNNER_TOKEN" \
  --executor docker --docker-image hopp-ci:1 \
  --docker-pull-policy if-not-present \
  --docker-volumes "$HOME/gitlab-runner-cache/hopp-ci:/cache" \
  --description "hopp mac (OrbStack)"
# then in ~/.gitlab-runner/config.toml: concurrent = 2

# 4. Start it with the login session
brew services start gitlab-runner
```

The Mac must not sleep while it should take jobs (System Settings → Energy:
prevent automatic sleeping when the display is off).

## Setup on the Linux box

```bash
# 1. Build image with JDK 21 + Android SDK (stays local)
docker build -t hopp-ci:1 ci/

# 2. gitlab-runner binary
curl -L -o /usr/local/bin/gitlab-runner \
  https://gitlab-runner-downloads.s3.amazonaws.com/latest/binaries/gitlab-runner-linux-amd64
chmod +x /usr/local/bin/gitlab-runner
useradd --system --create-home --shell /bin/bash gitlab-runner
gitlab-runner install --user=gitlab-runner --working-directory=/home/gitlab-runner
gitlab-runner start

# 3. Register (token from GitLab: Settings → CI/CD → Runners → New project runner,
#    tag "hopp")
gitlab-runner register --non-interactive \
  --url https://gitlab.com --token "$RUNNER_TOKEN" \
  --executor docker --docker-image hopp-ci:1 \
  --docker-pull-policy if-not-present \
  --docker-volumes /srv/hopp-ci-cache:/cache \
  --description "hopp build box"
# then in /etc/gitlab-runner/config.toml: concurrent = 1
```

## Both hosts

The Gradle cache lives in the host directory mounted at `/cache`
(`~/gitlab-runner-cache/hopp-ci` on the Mac, `/srv/hopp-ci-cache` on the Linux
box) and survives jobs. Bump the image tag (`hopp-ci:2`) when the SDK level
changes, in `ci/Dockerfile`, `.gitlab-ci.yml` and the registration — and
rebuild the image on **every** runner host, or a job fails on whichever host
lacks it (the pull policy never fetches it from anywhere).
