-- Create oauth_accounts table for OAuth 2.0 social login integration
-- This table links users to their OAuth provider accounts (Google, GitHub, Facebook, etc.)

CREATE TABLE oauth_accounts (
    oauth_account_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    provider VARCHAR(50) NOT NULL COMMENT 'OAuth provider name (google, github, facebook, etc.)',
    provider_user_id VARCHAR(255) NOT NULL COMMENT 'User ID from the OAuth provider',
    email VARCHAR(254) COMMENT 'Email from OAuth provider',
    name VARCHAR(255) COMMENT 'Full name from OAuth provider',
    profile_image_url VARCHAR(500) COMMENT 'Profile image URL from OAuth provider',
    access_token TEXT COMMENT 'OAuth access token (encrypted in production)',
    refresh_token TEXT COMMENT 'OAuth refresh token (encrypted in production)',
    token_expires_at TIMESTAMP NULL COMMENT 'When the OAuth access token expires',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_oauth_accounts_user_id FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    CONSTRAINT uk_oauth_accounts_provider_user_id UNIQUE (provider, provider_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Create indexes for faster lookups
CREATE INDEX idx_oauth_accounts_user_id ON oauth_accounts(user_id);
CREATE INDEX idx_oauth_accounts_provider ON oauth_accounts(provider);
CREATE INDEX idx_oauth_accounts_email ON oauth_accounts(email);

