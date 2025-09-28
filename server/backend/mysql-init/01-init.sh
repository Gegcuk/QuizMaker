#!/bin/sh
set -e

log() {
  printf '[mysql-init] %s\n' "$1"
}

# Prefer the local UNIX socket during init; TCP may be disabled (port 0)
MYSQL_SOCKET="${MYSQL_SOCKET:-/var/run/mysqld/mysqld.sock}"
MYSQL_PING="mysqladmin --silent --socket=${MYSQL_SOCKET} ping"
MYSQL_BASE="mysql --protocol=SOCKET --socket=${MYSQL_SOCKET} -uroot"

log "Starting MySQL initialization..."

# Wait for MySQL to be ready (temporary server uses socket only)
log "Waiting for MySQL to be ready..."
for i in $(seq 1 60); do
  if ${MYSQL_PING} 2>/dev/null; then
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

# Check if we need to set root password (connect via socket)
log "Checking root password status..."

# Try using provided password first
if ${MYSQL_BASE} -p"${MYSQL_ROOT_PASSWORD:-}" -e "SELECT 1;" >/dev/null 2>&1; then
  log "Root password already set and working"
else
  log "Cannot connect with provided password, trying without password..."

  # Try without password (fresh MySQL initialization case)
  if ${MYSQL_BASE} -e "SELECT 1;" >/dev/null 2>&1; then
    log "Setting root password..."
    ${MYSQL_BASE} -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '${MYSQL_ROOT_PASSWORD:-defaultpass}';"
    ${MYSQL_BASE} -e "ALTER USER 'root'@'%' IDENTIFIED BY '${MYSQL_ROOT_PASSWORD:-defaultpass}';"
    ${MYSQL_BASE} -e "FLUSH PRIVILEGES;"
    log "Root password set successfully"
  else
    log "ERROR: Cannot connect to MySQL with or without password"
    log "This may indicate different credentials or MySQL not fully ready"
    exit 1
  fi
fi

# Root-authenticated command helper (use socket to avoid networking issues)
MYSQL_CMD="mysql --protocol=SOCKET --socket=${MYSQL_SOCKET} -uroot -p${MYSQL_ROOT_PASSWORD:-defaultpass}"

log "Configuring database and application user access..."

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

# Create database if it doesn't exist
log "Creating database '${MYSQL_DATABASE}' if it doesn't exist..."
${MYSQL_CMD} -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# Check if the user already has a '%' host entry
has_wildcard_host=$(${MYSQL_CMD} -N -B -e "SELECT COUNT(*) FROM mysql.user WHERE User='${MYSQL_USER}' AND Host='%';" 2>/dev/null | tr -d '\r' || echo "0")

if [ "${has_wildcard_host}" = "0" ]; then
  log "No '%' host entry found for user '${MYSQL_USER}'. Creating remote access grant."
  ${MYSQL_CMD} <<EOF_SQL
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
else
  log "User '${MYSQL_USER}' already has '%' host entry. Refreshing credentials and privileges."
  ${MYSQL_CMD} <<EOF_SQL
ALTER USER '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
fi

# Verify the user was created successfully
user_hosts=$(${MYSQL_CMD} -N -B -e "SELECT CONCAT(User, '@', Host) FROM mysql.user WHERE User='${MYSQL_USER}';" 2>/dev/null || true)
if [ -n "${user_hosts}" ]; then
  log "Verified host mappings for '${MYSQL_USER}':"
  old_ifs="$IFS"; IFS='\n'
  for host in ${user_hosts}; do log "  - ${host}"; done
  IFS="$old_ifs"
else
  log "Warning: Unable to confirm host entries for user '${MYSQL_USER}'"
fi

log "MySQL initialization completed successfully!"
