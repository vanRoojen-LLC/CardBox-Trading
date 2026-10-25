#!/usr/bin/env bash
set -euo pipefail
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
fixture_dir=$(mktemp -d)
trap 'rm -rf "$fixture_dir"' EXIT
cat > "$fixture_dir/docker" <<'STUB'
#!/usr/bin/env bash
printf '%s\n' "$*" >> "$DOCKER_CALLS"
if [ "$1" = pull ]; then
  case "$MODE:$3" in
    fail:*) exit 1 ;;
    fallback:mirror.gcr.io/*) exit 1 ;;
  esac
fi
STUB
chmod +x "$fixture_dir/docker"
export PATH="$fixture_dir:$PATH" DOCKER_CALLS="$fixture_dir/calls"
MODE=success bash "$script_dir/pull-build-images.sh"
[ "$(grep -c '^pull ' "$DOCKER_CALLS")" = 3 ]
[ "$(grep -c '^tag ' "$DOCKER_CALLS")" = 3 ]
: > "$DOCKER_CALLS"
MODE=fallback bash "$script_dir/pull-build-images.sh"
[ "$(grep -c '^pull ' "$DOCKER_CALLS")" = 6 ]
[ "$(grep -c '^tag ' "$DOCKER_CALLS")" = 3 ]
if grep '^tag mirror.gcr.io/' "$DOCKER_CALLS"; then exit 1; fi
: > "$DOCKER_CALLS"
if MODE=fail bash "$script_dir/pull-build-images.sh"; then exit 1; fi
[ "$(grep -c '^pull ' "$DOCKER_CALLS")" = 3 ]
if grep '^tag ' "$DOCKER_CALLS"; then exit 1; fi
echo 'Official image acquisition: first-mirror, fallback, and complete-failure cases passed'
