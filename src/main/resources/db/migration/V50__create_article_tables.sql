-- Article feature schema

CREATE TABLE IF NOT EXISTS articles (
    article_id BINARY(16) NOT NULL,
    slug VARCHAR(255) NOT NULL UNIQUE,
    title VARCHAR(512) NOT NULL,
    description VARCHAR(2048) NOT NULL,
    excerpt VARCHAR(1024) NOT NULL,
    hero_kicker VARCHAR(255),
    reading_time VARCHAR(100) NOT NULL,
    published_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NULL,
    status ENUM('DRAFT','PUBLISHED') NOT NULL,
    canonical_url VARCHAR(2048),
    og_image VARCHAR(2048),
    noindex TINYINT(1) NOT NULL DEFAULT 0,
    content_group VARCHAR(100) NOT NULL,
    author_name VARCHAR(255) NOT NULL,
    author_title VARCHAR(255),
    primary_cta_label VARCHAR(255) NOT NULL,
    primary_cta_href VARCHAR(2048) NOT NULL,
    primary_cta_event_name VARCHAR(255),
    secondary_cta_label VARCHAR(255) NOT NULL,
    secondary_cta_href VARCHAR(2048) NOT NULL,
    secondary_cta_event_name VARCHAR(255),
    revision INT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (article_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS article_stats (
    stat_id BINARY(16) NOT NULL,
    article_id BINARY(16) NOT NULL,
    label VARCHAR(255) NOT NULL,
    value VARCHAR(255) NOT NULL,
    detail TEXT,
    link VARCHAR(2048),
    position INT NOT NULL,
    PRIMARY KEY (stat_id),
    CONSTRAINT fk_article_stats_article FOREIGN KEY (article_id) REFERENCES articles(article_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS article_key_points (
    key_point_id BINARY(16) NOT NULL,
    article_id BINARY(16) NOT NULL,
    content VARCHAR(1024) NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (key_point_id),
    CONSTRAINT fk_article_key_points_article FOREIGN KEY (article_id) REFERENCES articles(article_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS article_checklist_items (
    checklist_item_id BINARY(16) NOT NULL,
    article_id BINARY(16) NOT NULL,
    content VARCHAR(1024) NOT NULL,
    position INT NOT NULL,
    PRIMARY KEY (checklist_item_id),
    CONSTRAINT fk_article_checklist_article FOREIGN KEY (article_id) REFERENCES articles(article_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS article_sections (
    section_row_id BINARY(16) NOT NULL,
    article_id BINARY(16) NOT NULL,
    section_id VARCHAR(255) NOT NULL,
    title VARCHAR(512) NOT NULL,
    summary TEXT,
    content TEXT,
    position INT NOT NULL,
    PRIMARY KEY (section_row_id),
    CONSTRAINT fk_article_sections_article FOREIGN KEY (article_id) REFERENCES articles(article_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS article_faqs (
    faq_id BINARY(16) NOT NULL,
    article_id BINARY(16) NOT NULL,
    question VARCHAR(1024) NOT NULL,
    answer TEXT,
    position INT NOT NULL,
    PRIMARY KEY (faq_id),
    CONSTRAINT fk_article_faqs_article FOREIGN KEY (article_id) REFERENCES articles(article_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS article_references (
    reference_id BINARY(16) NOT NULL,
    article_id BINARY(16) NOT NULL,
    title VARCHAR(512) NOT NULL,
    url VARCHAR(2048) NOT NULL,
    source_type VARCHAR(255),
    position INT NOT NULL,
    PRIMARY KEY (reference_id),
    CONSTRAINT fk_article_references_article FOREIGN KEY (article_id) REFERENCES articles(article_id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS article_tags (
    article_id BINARY(16) NOT NULL,
    tag_id BINARY(16) NOT NULL,
    PRIMARY KEY (article_id, tag_id),
    CONSTRAINT fk_article_tags_article FOREIGN KEY (article_id) REFERENCES articles(article_id) ON DELETE CASCADE,
    CONSTRAINT fk_article_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(tag_id) ON DELETE CASCADE
) ENGINE=InnoDB;
