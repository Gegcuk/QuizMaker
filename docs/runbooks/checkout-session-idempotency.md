# Checkout Session Idempotency

## Invariant

One paid Stripe Checkout Session creates at most one `PURCHASE` token transaction and one balance increase. The server creates a `PENDING` payment when it creates the Checkout Session; webhook delivery never creates a payment from Stripe-supplied data.

`checkout.session.completed`, `checkout.session.async_payment_succeeded`, and `checkout.session.async_payment_failed` all retrieve the Checkout Session from Stripe and pass its verified facts to the settlement service. Credits are issued only when the retrieved `payment_status` is `paid`.

## Delivery outcomes

| Event outcome | Payment state | Credit |
| --- | --- | --- |
| `completed` with an unpaid session | `PENDING` | No |
| `async_payment_succeeded` with `payment_status=paid` | `SUCCEEDED` | One purchase credit |
| `completed` with `payment_status=paid` | `SUCCEEDED` | One purchase credit |
| `async_payment_failed` while pending | `FAILED` | No |
| Any later checkout event after settlement | Unchanged | No additional credit |

An unavailable Stripe API, missing server-created payment, snapshot mismatch, or database failure returns HTTP 500. Stripe must retry those events. A malformed payload or invalid signature is not processed.

## Idempotency layers

1. `processed_stripe_events.event_id` records a delivery receipt and suppresses exact retries.
2. `checkout_session_settlements.stripe_session_id` is the durable economic marker. It serializes distinct events for the same Checkout Session.
3. `token_transactions.idempotency_key` uses `checkout-session:<stripe_session_id>` as an independent ledger-level guard.

Migration `V61__create_checkout_session_settlements.sql` backfills successful and refunded historical payments as settled without changing balances or token transactions. Its status comparison is text-based so it also deploys to databases created before the `PARTIALLY_REFUNDED` enum value existed.

## Read-only incident checks

Investigate a Stripe session without issuing a manual credit. First check its payment, settlement marker, and purchase ledger entry:

```sql
SELECT p.id,
       p.stripe_session_id,
       p.status,
       p.user_id,
       p.amount_cents,
       p.currency,
       p.credited_tokens,
       css.created_at AS settled_at,
       tt.id AS purchase_transaction_id,
       tt.amount_tokens,
       tt.created_at AS credited_at
FROM payments p
LEFT JOIN checkout_session_settlements css ON css.stripe_session_id = p.stripe_session_id
LEFT JOIN token_transactions tt
       ON tt.idempotency_key = CONCAT('checkout-session:', p.stripe_session_id)
WHERE p.stripe_session_id = :stripe_session_id;
```

Then check each received delivery separately:

```sql
SELECT event_id, created_at
FROM processed_stripe_events
WHERE event_id IN (:event_id_1, :event_id_2)
ORDER BY created_at;
```

Compare a discrepancy with Stripe's retrieved Checkout Session and its `payment_status`. Do not alter `payments`, `checkout_session_settlements`, balances, or token transactions directly. Escalate with the query output and Stripe session ID so remediation can preserve ledger history.
