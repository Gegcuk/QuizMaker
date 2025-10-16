-- Seed default "Uncategorized" category if missing
INSERT INTO categories (category_id, category_name, category_description)
SELECT UNHEX(REPLACE('00000000-0000-0000-0000-000000000001','-','')),
       'Uncategorized',
       'Default category for quizzes without a specific category'
WHERE NOT EXISTS (
    SELECT 1 FROM categories
    WHERE category_id = UNHEX(REPLACE('00000000-0000-0000-0000-000000000001','-',''))
);
