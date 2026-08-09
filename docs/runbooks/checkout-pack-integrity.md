# Checkout Pack Integrity

## Invariant

Checkout creation resolves exactly one active `product_packs` row. The server derives the Stripe price, amount, currency, and token entitlement from that row. `priceId` remains a deprecated request field only for legacy clients; it must resolve to the same pack as `packId` when both are sent.

The resulting `payments` row is the immutable purchase authority for that Checkout Session. It snapshots `pack_id`, `stripe_price_id_snapshot`, `amount_cents`, `currency`, and `credited_tokens`. Webhook processing retrieves the Stripe Session and cross-checks its user binding, single line item, quantity, price, amount, currency, and optional metadata against this snapshot.

Changing or deactivating the current catalog does not change an already issued session. If the customer pays and Stripe still matches the stored snapshot, Quizzence grants the original entitlement exactly once. A missing or conflicting snapshot fails closed for operator reconciliation; Quizzence does not guess from the current catalog.

## Historical Audit

Run this read-only query after migration V67 and before enabling paid checkout. Retain the output with the deployment record. It finds historical rows whose original Stripe price could not be backfilled or whose retained metadata conflicts with the explicit snapshot.

```sql
SELECT p.id,
       p.stripe_session_id,
       p.user_id,
       p.status,
       p.pack_id,
       p.stripe_price_id_snapshot,
       COALESCE(
           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.priceId')), 'null'),
           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.primaryPack.stripePriceId')), 'null')
       ) AS metadata_price_id,
       pp.stripe_price_id AS current_catalog_price_id,
       pp.active AS current_catalog_active,
       p.amount_cents,
       p.currency,
       p.credited_tokens
FROM payments p
LEFT JOIN product_packs pp ON pp.id = p.pack_id
WHERE p.stripe_price_id_snapshot IS NULL
   OR (
       COALESCE(
           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.priceId')), 'null'),
           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.primaryPack.stripePriceId')), 'null')
       ) IS NOT NULL
       AND COALESCE(
           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.priceId')), 'null'),
           NULLIF(JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.primaryPack.stripePriceId')), 'null')
       ) <> p.stripe_price_id_snapshot
   );
```

A difference between `current_catalog_price_id` and `stripe_price_id_snapshot` is expected after legitimate catalog changes and is not itself an incident. Never update a snapshot or balance to match the current catalog. For a row returned by the query, compare the stored payment with the Stripe Session line item and payment state, then prepare an explicitly approved reconciliation. Do not credit ambiguous historical rows automatically.

## Monitoring

Monitor `billing.checkout.validation.failures` by its bounded `reason` tag. Its values are `missing_session`, `missing_payment_snapshot`, `incomplete_payment_snapshot`, `line_item_count`, `price_mismatch`, `pack_mismatch`, `user_mismatch`, `amount_mismatch`, `currency_mismatch`, and `quantity_mismatch`. Alert on sustained increases. Do not add session, user, customer, event, or price identifiers as metric tags.
