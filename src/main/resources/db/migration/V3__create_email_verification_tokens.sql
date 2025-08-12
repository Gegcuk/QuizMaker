-- Create email verification tokens table
CREATE TABLE email_verification_tokens (
    token_id BINARY(16) NOT NULL,
    token_hash VARCHAR(255) NOT NULL UNIQUE,
    user_id BINARY(16) NOT NULL,
    email VARCHAR(254) NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (token_id)
);

-- Create indexes separately for better database compatibility
CREATE INDEX idx_email_verification_token_hash ON email_verification_tokens (token_hash);
CREATE INDEX idx_email_verification_user_id ON email_verification_tokens (user_id);
CREATE INDEX idx_email_verification_email ON email_verification_tokens (email);
CREATE INDEX idx_email_verification_expires_at ON email_verification_tokens (expires_at);
