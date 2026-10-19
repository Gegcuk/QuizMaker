-- Authentication-owned, privacy-minimized admission state; additive and safe for older binaries.
CREATE TABLE auth_rate_limits (
    key_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    dimension VARCHAR(48) NOT NULL,
    admissions JSON NOT NULL,
    window_started_at DATETIME(6) NULL,
    expires_at DATETIME(6) NOT NULL,
    PRIMARY KEY (key_digest),
    INDEX idx_auth_rate_limits_expiry (expires_at)
) ENGINE=InnoDB;
