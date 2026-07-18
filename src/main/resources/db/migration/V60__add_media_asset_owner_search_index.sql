CREATE INDEX idx_media_assets_owner_status_created
    ON media_assets (created_by, status, created_at);
