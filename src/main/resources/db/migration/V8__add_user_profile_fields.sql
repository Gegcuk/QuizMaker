-- Add user profile fields to users table
ALTER TABLE users 
ADD COLUMN display_name VARCHAR(50) NULL,
ADD COLUMN bio TEXT NULL,
ADD COLUMN avatar_url VARCHAR(500) NULL,
ADD COLUMN preferences JSON NULL;

-- Add indexes for better performance
CREATE INDEX idx_users_display_name ON users(display_name);
CREATE INDEX idx_users_avatar_url ON users(avatar_url);

-- Add constraints
ALTER TABLE users 
ADD CONSTRAINT chk_display_name_length CHECK (LENGTH(display_name) <= 50),
ADD CONSTRAINT chk_bio_length CHECK (LENGTH(bio) <= 500),
ADD CONSTRAINT chk_avatar_url_length CHECK (LENGTH(avatar_url) <= 500);

