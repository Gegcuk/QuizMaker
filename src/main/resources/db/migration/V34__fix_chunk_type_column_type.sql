-- Fix chunk_type column to use TINYINT instead of VARCHAR for enum storage
-- This aligns with JPA's default enum storage as ordinal values

ALTER TABLE document_chunks 
MODIFY COLUMN chunk_type TINYINT NULL;
