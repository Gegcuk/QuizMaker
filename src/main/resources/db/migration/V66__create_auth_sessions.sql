-- A server-side session makes JWT logout and refresh-token rotation enforceable.
-- The verifier is an HMAC fingerprint of the current refresh JWT, never the raw token.
-- Some focused migration tests intentionally baseline only document tables.
-- Those partial schemas are not deployments, so auth-specific DDL must not
-- make their document compatibility checks fail.
SET @users_table_exists := (
    SELECT COUNT(*)
    FROM information_schema.tables
    WHERE table_schema = DATABASE()
      AND table_name = 'users'
);

SET @auth_sessions_schema_sql := IF(
    @users_table_exists = 1,
    'CREATE TABLE IF NOT EXISTS auth_sessions (
        session_id BINARY(16) NOT NULL,
        user_id BINARY(16) NOT NULL,
        refresh_token_hash CHAR(64) NOT NULL,
        created_at TIMESTAMP NOT NULL,
        refreshed_at TIMESTAMP NULL,
        expires_at TIMESTAMP NOT NULL,
        revoked_at TIMESTAMP NULL,
        revocation_reason VARCHAR(32) NULL,
        PRIMARY KEY (session_id),
        INDEX idx_auth_sessions_user_expires_at (user_id, expires_at),
        INDEX idx_auth_sessions_expires_at (expires_at),
        CONSTRAINT fk_auth_sessions_user
            FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE
    ) ENGINE=InnoDB',
    'SELECT 1'
);
PREPARE auth_sessions_schema_statement FROM @auth_sessions_schema_sql;
EXECUTE auth_sessions_schema_statement;
DEALLOCATE PREPARE auth_sessions_schema_statement;
