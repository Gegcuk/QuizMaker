#!/bin/bash
set -euo pipefail

log() {
  echo "[mysql-init] $1"
}

log "Configuring network access for application user..."

if [[ -z "${MYSQL_USER:-}" ]]; then
  log "MYSQL_USER is not set; skipping network access configuration"
  exit 0
fi

if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  log "MYSQL_PASSWORD is not set; skipping network access configuration"
  exit 0
fi

if [[ -z "${MYSQL_DATABASE:-}" ]]; then
  log "MYSQL_DATABASE is not set; defaulting to quizmakerdb"
  MYSQL_DATABASE="quizmakerdb"
fi

mysql --protocol=socket -uroot -p"${MYSQL_ROOT_PASSWORD}" <<EOSQL
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
ALTER USER '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOSQL

log "Network access configured for user '${MYSQL_USER}' on database '${MYSQL_DATABASE}'"
