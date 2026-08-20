-- Opaque, short-lived application exchange codes replace bearer tokens in OAuth redirect URLs.
-- Only a SHA-256 digest and the validated client/PKCE binding are retained.
CREATE TABLE oauth_exchange_codes (
    code_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_id BINARY(16) NOT NULL,
    client_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    redirect_uri VARCHAR(2048) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    pkce_challenge CHAR(43) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    pkce_method VARCHAR(8) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    issued_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    consumed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (code_hash),
    INDEX idx_oec_user_id (user_id),
    INDEX idx_oec_expires_at (expires_at),
    CONSTRAINT fk_oec_user FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT chk_oec_pkce_method CHECK (pkce_method = 'S256'),
    CONSTRAINT chk_oec_expiry CHECK (expires_at > issued_at),
    CONSTRAINT chk_oec_consumed_time CHECK (
        consumed_at IS NULL OR (consumed_at >= issued_at AND consumed_at < expires_at)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
