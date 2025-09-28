#!/bin/sh
set -e

log() {
  printf '[mysql-init] %s\n' "$1"
}

log "Configuring network access for application user..."

if [ -z "${MYSQL_USER:-}" ]; then
  log "MYSQL_USER is not set; skipping network access configuration"
  exit 0
fi

if [ -z "${MYSQL_PASSWORD:-}" ]; then
  log "MYSQL_PASSWORD is not set; skipping network access configuration"
  exit 0
fi

if [ -z "${MYSQL_DATABASE:-}" ]; then
  log "MYSQL_DATABASE is not set; defaulting to quizmakerdb"
  MYSQL_DATABASE="quizmakerdb"
fi

set -- mysql --protocol=socket -uroot
if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
  set -- "$@" -p"${MYSQL_ROOT_PASSWORD}"
else
  log "MYSQL_ROOT_PASSWORD is not set; attempting to connect without password"
fi

"$@" <<EOF_SQL
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
ALTER USER '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL

log "Network access configured for user '${MYSQL_USER}' on database '${MYSQL_DATABASE}'"
