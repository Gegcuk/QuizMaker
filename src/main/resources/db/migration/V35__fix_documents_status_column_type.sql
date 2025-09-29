-- Fix status column in documents table to use TINYINT instead of VARCHAR for enum storage
-- This aligns with JPA's default enum storage as ordinal values for DocumentStatus enum

ALTER TABLE documents 
MODIFY COLUMN status TINYINT NOT NULL;
