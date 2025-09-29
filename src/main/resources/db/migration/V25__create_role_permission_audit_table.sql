CREATE TABLE IF NOT EXISTS role_permission_audit (
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

-- Idempotently add CHECK constraint for action enum values
SET @schema := DATABASE();
SET @tbl := 'role_permission_audit';
SET @chk := 'chk_role_permission_audit_action';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.table_constraints
  WHERE table_schema=@schema AND table_name=@tbl AND constraint_type='CHECK' AND constraint_name=@chk
);
SET @sql := IF(@exists=0,
  'ALTER TABLE role_permission_audit ADD CONSTRAINT chk_role_permission_audit_action CHECK (action IN (\'ROLE_ASSIGNED\', \'ROLE_REMOVED\', \'ROLE_CREATED\', \'ROLE_UPDATED\', \'ROLE_DELETED\', \'PERMISSION_ADDED\', \'PERMISSION_REMOVED\', \'POLICY_RECONCILED\'))',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Ensure indexes exist if table pre-existed without them
SET @ensure_index = 'idx_role_permission_audit_actor_created';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
   WHERE table_schema=@schema AND table_name=@tbl AND index_name=@ensure_index
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_role_permission_audit_actor_created ON role_permission_audit (actor_id, created_at DESC)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ensure_index = 'idx_role_permission_audit_target_created';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
   WHERE table_schema=@schema AND table_name=@tbl AND index_name=@ensure_index
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_role_permission_audit_target_created ON role_permission_audit (target_user_id, created_at DESC)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ensure_index = 'idx_role_permission_audit_role_created';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
   WHERE table_schema=@schema AND table_name=@tbl AND index_name=@ensure_index
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_role_permission_audit_role_created ON role_permission_audit (role_id, created_at DESC)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ensure_index = 'idx_role_permission_audit_action_created';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
   WHERE table_schema=@schema AND table_name=@tbl AND index_name=@ensure_index
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_role_permission_audit_action_created ON role_permission_audit (action, created_at DESC)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ensure_index = 'idx_role_permission_audit_correlation';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
   WHERE table_schema=@schema AND table_name=@tbl AND index_name=@ensure_index
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_role_permission_audit_correlation ON role_permission_audit (correlation_id)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @ensure_index = 'idx_role_permission_audit_created';
SET @exists := (
  SELECT COUNT(1) FROM information_schema.statistics
   WHERE table_schema=@schema AND table_name=@tbl AND index_name=@ensure_index
);
SET @sql := IF(@exists=0,
  'CREATE INDEX idx_role_permission_audit_created ON role_permission_audit (created_at DESC)',
  'DO 0'
);
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
