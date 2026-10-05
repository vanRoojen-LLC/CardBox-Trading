#!/bin/bash
# Cloud sessions only: get a Claude cloud thread ready to run what the Cloud workflow runs
# (.github/workflows/cloud.yml): the API's Maven verify, whose integration tests start PostgreSQL
# through Testcontainers and so need a Docker daemon, and the web build.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel)}"

if ! docker info >/dev/null 2>&1; then
  nohup dockerd >/tmp/dockerd.log 2>&1 &
  for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
  docker info >/dev/null
fi
# The image the integration tests ask for, pulled now so the first test run does not wait on it.
docker pull --quiet postgres:17-alpine >/dev/null

mvn -B -q -f cloud/api/pom.xml dependency:go-offline >/dev/null
(cd cloud/web && npm ci --no-audit --no-fund --silent)
