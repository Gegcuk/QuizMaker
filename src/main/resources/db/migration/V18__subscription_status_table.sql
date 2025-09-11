-- Create subscription status table for tracking subscription lifecycle
CREATE TABLE subscription_status (
    id BINARY(16) PRIMARY KEY,
    user_id BINARY(16) NOT NULL UNIQUE,
    subscription_id VARCHAR(255),
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    block_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    INDEX idx_subscription_status_user_id (user_id),
    INDEX idx_subscription_status_subscription_id (subscription_id),
    INDEX idx_subscription_status_blocked (blocked)
);
