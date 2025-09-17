-- Create role_permission_audit table for immutable audit trail
CREATE TABLE role_permission_audit (
    id BINARY(16) NOT NULL PRIMARY KEY,
    actor_id BINARY(16) NOT NULL,
    target_user_id BINARY(16) NULL,
    role_id BIGINT NULL,
    action VARCHAR(50) NOT NULL,
    reason VARCHAR(500) NULL,
    before_state TEXT NULL,
    after_state TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id VARCHAR(100) NULL,
    ip_address VARCHAR(45) NULL,
    user_agent VARCHAR(500) NULL,
    
    -- Foreign key constraints
    CONSTRAINT fk_role_permission_audit_actor 
        FOREIGN KEY (actor_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_permission_audit_target_user 
        FOREIGN KEY (target_user_id) REFERENCES users(user_id) ON DELETE RESTRICT,
    CONSTRAINT fk_role_permission_audit_role 
        FOREIGN KEY (role_id) REFERENCES roles(role_id) ON DELETE RESTRICT,
    
    -- Indexes for performance
    INDEX idx_role_permission_audit_actor_created (actor_id, created_at DESC),
    INDEX idx_role_permission_audit_target_created (target_user_id, created_at DESC),
    INDEX idx_role_permission_audit_role_created (role_id, created_at DESC),
    INDEX idx_role_permission_audit_action_created (action, created_at DESC),
    INDEX idx_role_permission_audit_correlation (correlation_id),
    INDEX idx_role_permission_audit_created (created_at DESC)
);

-- Add check constraint for action enum values
ALTER TABLE role_permission_audit 
ADD CONSTRAINT chk_role_permission_audit_action 
CHECK (action IN (
    'ROLE_ASSIGNED', 
    'ROLE_REMOVED', 
    'ROLE_CREATED', 
    'ROLE_UPDATED', 
    'ROLE_DELETED', 
    'PERMISSION_ADDED', 
    'PERMISSION_REMOVED', 
    'POLICY_RECONCILED'
));
