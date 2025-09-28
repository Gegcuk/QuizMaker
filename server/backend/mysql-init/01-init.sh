#!/bin/sh
set -e

log() {
  printf '[mysql-init] %s\n' "$1"
}

# Helpers for debugging secrets (safe-by-default)
show_val() {
  key="$1"; val="$2"
  len=$(printf '%s' "$val" | wc -c | tr -d ' ')
  if [ "${MYSQL_INIT_SHOW_SECRETS:-0}" = "1" ]; then
    printf '[mysql-init] %s: [%s]\n' "$key" "$val"
  else
    start=$(printf '%s' "$val" | cut -c1-3)
    end=$(printf '%s' "$val" | awk '{print substr($0, length($0)-1)}')
    printf '[mysql-init] %s: [%s...%s] (len=%s)\n' "$key" "$start" "$end" "$len"
  fi
}

# Prefer the local UNIX socket during init; TCP may be disabled (port 0)
MYSQL_SOCKET="${MYSQL_SOCKET:-/var/run/mysqld/mysqld.sock}"

step=1
log "STEP ${step}: Start MySQL initialization"

step=$((step+1))
log "STEP ${step}: Debug environment variables"
show_val MYSQL_DATABASE "${MYSQL_DATABASE:-}"
show_val MYSQL_USER "${MYSQL_USER:-}"
show_val MYSQL_PASSWORD "${MYSQL_PASSWORD:-}"
show_val MYSQL_ROOT_PASSWORD "${MYSQL_ROOT_PASSWORD:-}"
log "Note: set MYSQL_INIT_SHOW_SECRETS=1 to show full values"

step=$((step+1))
log "STEP ${step}: Wait for MySQL (socket: ${MYSQL_SOCKET})"
for i in $(seq 1 60); do
  if mysqladmin --silent --socket="${MYSQL_SOCKET}" ping 2>/dev/null; then
    log "MySQL is ready!"
    break
  fi
  if [ "$i" -eq 60 ]; then
    log "ERROR: MySQL failed to start within 60 seconds"
    exit 1
  fi
  log "Waiting for MySQL... ($i/60)"
  sleep 1
done

# Decide authentication mode but do NOT change root password here
step=$((step+1))
log "STEP ${step}: Determine root authentication"

MYSQL_AUTH_MODE="none"
if [ -n "${MYSQL_ROOT_PASSWORD:-}" ]; then
  if mysql --protocol=SOCKET --socket="${MYSQL_SOCKET}" -uroot -p"${MYSQL_ROOT_PASSWORD}" -e "SELECT 1;" >/dev/null 2>&1; then
    MYSQL_AUTH_MODE="password"
    log "Root authentication: using provided password"
  fi
fi

if [ "$MYSQL_AUTH_MODE" = "none" ]; then
  if mysql --protocol=SOCKET --socket="${MYSQL_SOCKET}" -uroot -e "SELECT 1;" >/dev/null 2>&1; then
    MYSQL_AUTH_MODE="no_password"
    log "Root authentication: no password (fresh init)"
  else
    log "ERROR: Cannot authenticate as root with or without password"
    exit 1
  fi
fi

# Define a wrapper for executing as root via socket
mysql_root() {
  if [ "$MYSQL_AUTH_MODE" = "password" ]; then
    mysql --protocol=SOCKET --socket="${MYSQL_SOCKET}" -uroot -p"${MYSQL_ROOT_PASSWORD}" "$@"
  else
    mysql --protocol=SOCKET --socket="${MYSQL_SOCKET}" -uroot "$@"
  fi
}

step=$((step+1))
log "STEP ${step}: Configure database and application user"
show_val MYSQL_DATABASE "${MYSQL_DATABASE:-}"
show_val MYSQL_USER "${MYSQL_USER:-}"
show_val MYSQL_PASSWORD "${MYSQL_PASSWORD:-}"
show_val MYSQL_ROOT_PASSWORD "${MYSQL_ROOT_PASSWORD:-}"

if [ -z "${MYSQL_USER:-}" ]; then
  log "MYSQL_USER is not set; skipping user configuration"
  exit 0
fi

if [ -z "${MYSQL_PASSWORD:-}" ]; then
  log "MYSQL_PASSWORD is not set; skipping user configuration"
  exit 0
fi

if [ -z "${MYSQL_DATABASE:-}" ]; then
  log "MYSQL_DATABASE is not set; defaulting to quizmakerdb"
  MYSQL_DATABASE="quizmakerdb"
fi

log "Creating database '${MYSQL_DATABASE}' if it doesn't exist..."
mysql_root -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

has_wildcard_host=$(mysql_root -N -B -e "SELECT COUNT(*) FROM mysql.user WHERE User='${MYSQL_USER}' AND Host='%';" 2>/dev/null | tr -d '\r' || echo "0")

if [ "${has_wildcard_host}" = "0" ]; then
  log "No '%' host entry for '${MYSQL_USER}'. Creating user and grants."
  mysql_root <<EOF_SQL
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
else
  log "User '${MYSQL_USER}' already has '%' host entry. Refreshing credentials and privileges."
  mysql_root <<EOF_SQL
ALTER USER '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
fi

step=$((step+1))
log "STEP ${step}: Verify user host mappings"
user_hosts=$(mysql_root -N -B -e "SELECT CONCAT(User, '@', Host) FROM mysql.user WHERE User='${MYSQL_USER}';" 2>/dev/null || true)
if [ -n "${user_hosts}" ]; then
  old_ifs="$IFS"; IFS='\n'
  for host in ${user_hosts}; do log "  - ${host}"; done
  IFS="$old_ifs"
else
  log "Warning: Unable to confirm host entries for user '${MYSQL_USER}'"
fi

step=$((step+1))
log "STEP ${step}: Initialization completed"
