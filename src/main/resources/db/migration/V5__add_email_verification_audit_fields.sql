-- Add email verification audit fields to users table
ALTER TABLE users 
ADD COLUMN email_verified_at TIMESTAMP NULL,
ADD COLUMN email_verified_by_token_id BINARY(16) NULL;

-- Add foreign key constraint to email_verification_tokens table
ALTER TABLE users 
ADD CONSTRAINT fk_users_email_verified_by_token 
FOREIGN KEY (email_verified_by_token_id) REFERENCES email_verification_tokens(token_id);
