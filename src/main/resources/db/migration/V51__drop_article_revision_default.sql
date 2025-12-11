-- Remove default from articles.revision so Hibernate @Version manages initial value
ALTER TABLE articles ALTER COLUMN revision DROP DEFAULT;
