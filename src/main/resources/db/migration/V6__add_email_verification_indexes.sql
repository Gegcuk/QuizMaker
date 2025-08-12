-- Add indexes for email verification tokens for better performance

-- Index for expiration queries (already exists, but ensuring it's optimized)
CREATE INDEX idx_email_verif_expires ON email_verification_tokens (expires_at);

-- Composite index for user_id and used status queries
CREATE INDEX idx_email_verif_user_used ON email_verification_tokens (user_id, used);
