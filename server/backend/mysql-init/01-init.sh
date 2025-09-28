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

set -- mysql --protocol=socket -uroot --batch --skip-column-names
if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
  set -- "$@" -p"${MYSQL_ROOT_PASSWORD}"
else
  log "MYSQL_ROOT_PASSWORD is not set; attempting to connect without password"
fi

has_wildcard_host=$("$@" -e "SELECT COUNT(*) FROM mysql.user WHERE User='${MYSQL_USER}' AND Host='%';" 2>/dev/null | tr -d '\r' || true)
if [ -z "${has_wildcard_host}" ]; then
  has_wildcard_host="0"
fi
if [ "${has_wildcard_host}" = "0" ]; then
  log "No '%' host entry found for user '${MYSQL_USER}'. Creating remote access grant."
  "$@" <<EOF_SQL
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
else
  log "User '${MYSQL_USER}' already has '%' host entry. Refreshing credentials and privileges."
  "$@" <<EOF_SQL
ALTER USER '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
fi

user_hosts=$("$@" -e "SELECT CONCAT(User, '@', Host) FROM mysql.user WHERE User='${MYSQL_USER}';" 2>/dev/null || true)
if [ -n "${user_hosts}" ]; then
  log "Verified host mappings for '${MYSQL_USER}':"
  old_ifs="$IFS"
  IFS='\n'
  for host in ${user_hosts}; do
    log "  - ${host}"
  done
  IFS="$old_ifs"
else
  log "Warning: Unable to confirm host entries for user '${MYSQL_USER}'"
fi