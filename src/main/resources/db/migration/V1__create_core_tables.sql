-- Create core tables for QuizMaker application

-- Create roles table
CREATE TABLE roles (
    role_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    is_default BOOLEAN DEFAULT FALSE
);

-- Create users table
CREATE TABLE users (
    user_id BINARY(16) NOT NULL,
    username VARCHAR(50) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login TIMESTAMP NULL,
    updated_at TIMESTAMP NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP NULL,
    email_verified BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (user_id)
);

-- Create user_roles junction table
CREATE TABLE user_roles (
    user_id BINARY(16) NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE,
    FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE CASCADE
);

-- Create indexes
CREATE INDEX idx_users_username ON users(username);
CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_active ON users(is_active);
CREATE INDEX idx_user_roles_user_id ON user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON user_roles(role_id);

-- Insert default roles
INSERT INTO roles (role_name, description, is_default) VALUES
('ROLE_USER', 'Basic user role', TRUE),
('ROLE_QUIZ_CREATOR', 'Can create and manage quizzes', FALSE),
('ROLE_MODERATOR', 'Can moderate content and users', FALSE),
('ROLE_ADMIN', 'Administrator with full access', FALSE),
('ROLE_SUPER_ADMIN', 'Super administrator with system-wide access', FALSE);
