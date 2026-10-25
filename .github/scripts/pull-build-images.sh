#!/usr/bin/env bash
# Same official images through independent mirrors; never hide a build/compiler failure behind retries.
set -euo pipefail
for image in node:22-alpine maven:3.9-eclipse-temurin-21 eclipse-temurin:21-jre-alpine; do
  pulled=false
  for registry in mirror.gcr.io/library public.ecr.aws/docker/library docker.io/library; do
    if docker pull -q "$registry/$image"; then
      docker tag "$registry/$image" "docker.io/library/$image"
      pulled=true
      break
    fi
  done
  if [ "$pulled" != true ]; then
    echo "Could not acquire official build image: $image" >&2
    exit 1
  fi
done
