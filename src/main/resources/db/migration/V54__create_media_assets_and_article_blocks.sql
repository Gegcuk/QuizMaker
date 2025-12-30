-- Media assets backing table for CDN uploads + article hero/blocks references
CREATE TABLE IF NOT EXISTS media_assets (
    asset_id BINARY(16) NOT NULL,
    object_key VARCHAR(1024) NOT NULL,
    type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    mime_type VARCHAR(255) NOT NULL,
    size_bytes BIGINT NULL,
    width INT NULL,
    height INT NULL,
    original_filename VARCHAR(512),
    sha256 VARCHAR(64),
    created_by VARCHAR(255),
    article_id BINARY(16),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (asset_id),
    UNIQUE KEY uk_media_object_key (object_key(255)),
    KEY idx_media_status_type (status, type),
    KEY idx_media_article_id (article_id)
) ENGINE=InnoDB;

-- Extend articles with hero image reference + structured blocks (JSON)
ALTER TABLE articles
    ADD COLUMN hero_image_asset_id BINARY(16) NULL,
    ADD COLUMN hero_image_alt VARCHAR(512),
    ADD COLUMN hero_image_caption VARCHAR(1024),
    ADD COLUMN content_blocks JSON NULL;

ALTER TABLE articles
    ADD CONSTRAINT fk_article_hero_media_asset
        FOREIGN KEY (hero_image_asset_id) REFERENCES media_assets(asset_id);

CREATE INDEX idx_article_hero_image_asset_id ON articles(hero_image_asset_id);
