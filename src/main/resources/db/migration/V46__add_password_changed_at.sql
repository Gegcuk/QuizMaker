-- Track when a user last changed their password to invalidate stale tokens
ALTER TABLE users
    ADD COLUMN password_changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
    AFTER password;
