-- Prospective retained versions only: no invented historical object identity or destructive cleanup.
ALTER TABLE media_assets ADD COLUMN library_removed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE saved_image_versions (
    id BINARY(16) PRIMARY KEY,
    asset_id BINARY(16) NOT NULL,
    source_key VARCHAR(1024) NOT NULL,
    source_etag VARCHAR(255) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NOT NULL,
    width INT NOT NULL,
    height INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    ready BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_saved_image_asset UNIQUE (asset_id),
    CONSTRAINT uk_saved_image_identity UNIQUE (id, asset_id),
    CONSTRAINT fk_saved_image_asset FOREIGN KEY (asset_id) REFERENCES media_assets(asset_id),
    CONSTRAINT ck_saved_image_dimensions CHECK (size_bytes > 0 AND width > 0 AND height > 0)
) ENGINE=InnoDB;

CREATE TABLE saved_image_bindings (
    id BINARY(16) PRIMARY KEY,
    question_id BINARY(16) NOT NULL,
    asset_id BINARY(16) NOT NULL,
    image_version_id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_saved_image_question_asset UNIQUE (question_id, asset_id),
    CONSTRAINT fk_saved_image_binding_version FOREIGN KEY (image_version_id, asset_id) REFERENCES saved_image_versions(id, asset_id)
) ENGINE=InnoDB;
-- No cascade to authoring questions: retained evidence has its own lifetime.
