# Checkout Pack Integrity

## Invariant

Each token checkout represents exactly one active `product_packs` row. The server derives the Stripe price, amount, currency, and token entitlement from that row. `priceId` remains a deprecated request field only for legacy clients; it must resolve to the same pack as `packId` when both are sent.

Webhook processing treats the retrieved Stripe line item as authoritative and cross-checks `packId` and `priceId` metadata. A mismatch is rejected before payment persistence or token crediting.

## Historical audit

Run this read-only query before enabling paid checkout and retain the output with the deployment record. It identifies payments whose stored pack does not match the Stripe price captured in `session_metadata`.

```sql
SELECT p.id,
       p.stripe_session_id,
       p.user_id,
       p.status,
       p.pack_id,
       JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.priceId')) AS metadata_price_id,
       pp.stripe_price_id AS configured_price_id,
       p.amount_cents,
       p.currency,
       p.credited_tokens
FROM payments p
LEFT JOIN product_packs pp ON pp.id = p.pack_id
WHERE JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.priceId')) IS NOT NULL
  AND (pp.id IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(p.session_metadata, '$.priceId')) <> pp.stripe_price_id);
```

This audit is diagnostic only. Do not change balances or payment records automatically. Compare each flagged Stripe session with Stripe's line item and payment status, then prepare an approved remediation for any confirmed entitlement discrepancy.
