#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
local_dir="$repo_dir/server/local"
command_name="${1:-status}"

case "$command_name" in
  up|stop|down|status|logs|fixtures) ;;
  *) echo 'Usage: tools/local-api.sh {up|stop|down|status|logs|fixtures}' >&2; exit 2 ;;
esac

if [[ "$command_name" == up ]]; then
  docker info > /dev/null
  # Retain these values across restarts because the MySQL volume is persistent.
  python3 - "$local_dir/.env" <<'PY'
import base64
import os
from pathlib import Path
import secrets
import sys

path = Path(sys.argv[1])
try:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    pass
else:
    with os.fdopen(descriptor, "w") as output:
        for key in ("DB_PASSWORD", "MYSQL_ROOT_PASSWORD", "JWT_SECRET",
                    "TOKEN_PEPPER_SECRET", "RESET_TOKEN_PEPPER", "VERIFICATION_TOKEN_PEPPER"):
            output.write(f"{key}={base64.b64encode(secrets.token_bytes(48)).decode()}\n")
PY
  if [[ -x /usr/libexec/java_home ]]; then
    export JAVA_HOME
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
  cd "$repo_dir"
  source_head="$(git rev-parse HEAD)"
  source_branch="$(git rev-parse --abbrev-ref HEAD)"
  source_status="$(git status --porcelain -- src pom.xml .mvn)"
  # Build from this checkout every time; Maven enforces JDK 17.
  ./mvnw -B -DskipTests clean package
  if [[ "$source_head" != "$(git rev-parse HEAD)" ||
        "$source_branch" != "$(git rev-parse --abbrev-ref HEAD)" ||
        "$source_status" != "$(git status --porcelain -- src pom.xml .mvn)" ]]; then
    echo 'Checkout changed during the build. Finish parallel edits and retry up.' >&2
    exit 1
  fi
  SOURCE_REVISION="$source_head"
  if [[ -n "$source_status" ]]; then
    SOURCE_REVISION="$SOURCE_REVISION-dirty"
  fi
  export SOURCE_REVISION
fi

if [[ ! -f "$local_dir/.env" ]]; then
  echo 'Environment not initialized. Run tools/local-api.sh up.' >&2
  exit 1
fi

# Compose gives exported variables precedence over --env-file. Ensure caller
# credentials cannot accidentally replace this project's generated local values.
compose=(env -u DB_PASSWORD -u MYSQL_ROOT_PASSWORD -u JWT_SECRET
  -u TOKEN_PEPPER_SECRET -u RESET_TOKEN_PEPPER -u VERIFICATION_TOKEN_PEPPER
  docker compose --env-file "$local_dir/.env" -f "$local_dir/compose.yaml")
case "$command_name" in
  up)
    "${compose[@]}" build app
    "${compose[@]}" up -d --wait --wait-timeout 300
    echo 'Local API ready: http://127.0.0.1:8080'
    ;;
  stop) "${compose[@]}" stop ;;
  down) "${compose[@]}" down ;;
  status) "${compose[@]}" ps ;;
  logs) "${compose[@]}" logs --tail=100 app ;;
  fixtures) python3 "$repo_dir/tools/local_api_fixtures.py" ;;
esac
