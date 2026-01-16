ALTER TABLE questions
    ADD COLUMN attachment_asset_id BINARY(16) NULL;

ALTER TABLE questions
    ADD CONSTRAINT fk_question_attachment_asset
        FOREIGN KEY (attachment_asset_id) REFERENCES media_assets(asset_id)
        ON DELETE SET NULL;

CREATE INDEX idx_question_attachment_asset_id ON questions(attachment_asset_id);
