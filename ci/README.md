# CI runner (ADR 0017)

Hopp's jobs run on our own GitLab runner, one job at a time (the host has
6 GB RAM; a Gradle Android build needs about 4 GB).

## Setup on the runner host

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

The Gradle cache lives in `/srv/hopp-ci-cache` on the host and survives jobs.
Bump the image tag (`hopp-ci:2`) when the SDK level changes, in
`ci/Dockerfile`, the registration and `.gitlab-ci.yml`.
