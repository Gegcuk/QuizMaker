-- Add unique constraint on email column to prevent race condition duplicates
ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email);
