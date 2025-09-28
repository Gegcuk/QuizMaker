#!/bin/sh
set -e

log() {
  printf '[mysql-init] %s\n' "$1"
}

log "Starting MySQL initialization..."

# Wait for MySQL to be ready
log "Waiting for MySQL to be ready..."
for i in $(seq 1 30); do
  if mysqladmin ping -h localhost --silent 2>/dev/null; then
    log "MySQL is ready!"
    break
  fi
  if [ $i -eq 30 ]; then
    log "ERROR: MySQL failed to start within 30 seconds"
    exit 1
  fi
  log "Waiting for MySQL... ($i/30)"
  sleep 1
done

# Check if we need to set root password
log "Checking root password status..."

# First try with the provided password
if mysql -uroot -p"${MYSQL_ROOT_PASSWORD:-}" -e "SELECT 1;" >/dev/null 2>&1; then
  log "Root password already set and working"
else
  log "Cannot connect with provided password, trying without password..."
  
  # Try without password (fresh MySQL installation)
  if mysql -uroot -e "SELECT 1;" >/dev/null 2>&1; then
    log "Setting root password..."
    mysql -uroot -e "ALTER USER 'root'@'localhost' IDENTIFIED BY '${MYSQL_ROOT_PASSWORD:-defaultpass}';"
    mysql -uroot -e "ALTER USER 'root'@'%' IDENTIFIED BY '${MYSQL_ROOT_PASSWORD:-defaultpass}';"
    mysql -uroot -e "FLUSH PRIVILEGES;"
    log "Root password set successfully"
  else
    log "ERROR: Cannot connect to MySQL with or without password"
    log "This might indicate MySQL is not ready or has different credentials"
    exit 1
  fi
fi

# Now use root password for subsequent operations
MYSQL_CMD="mysql -uroot -p${MYSQL_ROOT_PASSWORD:-defaultpass}"

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

# Create database if it doesn't exist
log "Creating database '${MYSQL_DATABASE}' if it doesn't exist..."
$MYSQL_CMD -e "CREATE DATABASE IF NOT EXISTS \`${MYSQL_DATABASE}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# Check if the user already has a '%' host entry
has_wildcard_host=$($MYSQL_CMD -e "SELECT COUNT(*) FROM mysql.user WHERE User='${MYSQL_USER}' AND Host='%';" 2>/dev/null | tail -1 | tr -d '\r' || echo "0")

if [ "${has_wildcard_host}" = "0" ]; then
  log "No '%' host entry found for user '${MYSQL_USER}'. Creating remote access grant."
  $MYSQL_CMD <<EOF_SQL
CREATE USER IF NOT EXISTS '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
else
  log "User '${MYSQL_USER}' already has '%' host entry. Refreshing credentials and privileges."
  $MYSQL_CMD <<EOF_SQL
ALTER USER '${MYSQL_USER}'@'%' IDENTIFIED BY '${MYSQL_PASSWORD}';
GRANT ALL PRIVILEGES ON \`${MYSQL_DATABASE}\`.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
EOF_SQL
fi

# Verify the user was created successfully
user_hosts=$($MYSQL_CMD -e "SELECT CONCAT(User, '@', Host) FROM mysql.user WHERE User='${MYSQL_USER}';" 2>/dev/null || true)
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

log "MySQL initialization completed successfully!"