-- Preserve the Stripe price that was approved when each Checkout Session was created.
-- The column remains nullable for historical rows whose original price cannot be proven.
ALTER TABLE payments
    ADD COLUMN stripe_price_id_snapshot VARCHAR(100) NULL AFTER pack_id;

-- Checkout rows created by the current application store the original price either in
-- the pending metadata or, after an asynchronous event, in enhanced purchase metadata.
UPDATE payments
SET stripe_price_id_snapshot = COALESCE(
        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(session_metadata, '$.priceId')), 'null'),
        NULLIF(JSON_UNQUOTE(JSON_EXTRACT(session_metadata, '$.primaryPack.stripePriceId')), 'null')
    )
WHERE stripe_price_id_snapshot IS NULL
  AND session_metadata IS NOT NULL;
