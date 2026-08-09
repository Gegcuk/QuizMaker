# Manual Test: Checkout Snapshot Settlement (#438)

## Purpose

Verify that checkout creation still supports both `packId` and the legacy `priceId`, rejects inactive packs before contacting Stripe, and honors the original paid terms exactly once when the catalog changes while a Checkout Session is open.

The public request and response shapes are unchanged. The behavior change is server-side: an issued session settles from its immutable payment snapshot instead of the latest catalog row.

## Preconditions

- Use a disposable local or staging database and Stripe test mode only.
- Use a test user with `BILLING_WRITE` and no production payment identifiers.
- Choose one active test pack and record its pack ID, Stripe test price ID, amount, currency, and token entitlement.
- Do not edit Flyway history or a production payment row manually.

## Automated Local Verification

Run these commands **locally from the repository root**:

1. `./mvnw test -Dtest=CheckoutValidationServiceImplTest,BillingCheckoutControllerPendingPaymentTest,BillingMetricsServiceImplTest,StripeWebhookServiceImplTest,StripeWebhookServiceHandlerTest,EnhancedMetadataBuilderTest,ConcurrencyAndIdempotencyTest`
2. `./mvnw test -Dtest=CheckoutPackResolverImplTest,StripeServiceImplTest,CheckoutSessionSettlementServiceImplTest`
3. `./mvnw test -Dtest=BillingCheckoutControllerTest,CheckoutSessionSettlementServiceIntegrationTest,CheckoutPriceSnapshotMigrationTest`

Expected result: all tests pass without a real Stripe call. The third command requires the repository's configured local MySQL test database.

## Checkout Compatibility

1. Authenticate as the test user.
2. Call `POST /api/v1/billing/checkout-sessions` with only the active pack's `packId`.
3. Confirm `200` and a Stripe test Checkout Session URL/ID.
4. Repeat with only the legacy `priceId` for the same active pack.
5. Confirm `200` and the same user-visible checkout behavior.
6. Send both identifiers with values from different packs.
7. Confirm RFC 7807 `409` and no Stripe Session or pending payment is created.
8. Deactivate a different test pack and request checkout for it.
9. Confirm `404` and no Stripe Session or pending payment is created.

## Catalog Drift After Session Creation

Use Stripe test mode and a disposable catalog row. Do not alter a production pack while customers can purchase it.

1. Create a Checkout Session for the active test pack but do not pay yet.
2. Confirm the local `payments` row is `PENDING` and stores the original `pack_id`, `stripe_price_id_snapshot`, `amount_cents`, `currency`, and `credited_tokens`.
3. Deactivate the pack locally, or sync it to a different test price and entitlement.
4. Complete the already-open Stripe test Checkout Session.
5. Deliver its `checkout.session.completed` or `checkout.session.async_payment_succeeded` webhook.
6. Confirm the payment becomes `SUCCEEDED` and the original token amount, not the new catalog amount, is credited once.
7. Redeliver the same event and deliver a second paid Checkout Session event for the same session.
8. Confirm one `checkout_session_settlements` row, one purchase ledger entry, and no second balance increase.

## Mismatch Failure

Exercise this only with a fake Stripe response or the focused automated test; do not modify a genuine paid Stripe Session.

1. Return a retrieved session whose line-item price, quantity, amount, currency, pack metadata, or user binding differs from the pending payment snapshot.
2. Confirm webhook processing fails so Stripe may retry.
3. Confirm the payment remains non-successful and no settlement row, purchase ledger entry, or balance credit appears.
4. Confirm `billing.checkout.validation.failures` increases for a bounded reason such as `price_mismatch`; no user, session, event, customer, or price ID appears as a metric tag.

## Swagger Contract

1. Open `/swagger-ui/index.html` and select the Billing group.
2. Inspect `POST /api/v1/billing/checkout-sessions`.
3. Confirm it recommends `packId`, documents deprecated `priceId` compatibility, and states that price, currency, and entitlement are snapshotted at creation and retain their original terms after catalog changes.

## Deployment Verification

Run these commands **over SSH on the staging or production Droplet** after normal deployment:

1. `docker compose --env-file .env exec mysql mysql -u"<DB_USER>" -p -e "SHOW COLUMNS FROM <DB_NAME>.payments LIKE 'stripe_price_id_snapshot';"`
2. Run the read-only audit query in `docs/runbooks/checkout-pack-integrity.md` against `<DB_NAME>`.

Expected result: the column exists. Investigate every returned ambiguous row before enabling or continuing paid checkout. A current catalog price differing from a non-null snapshot is valid catalog drift and must not be overwritten.

Migration V67 is additive. If deployment must be rolled back, the previous application ignores the new column; leave it in place and forward-fix rather than editing Flyway history or dropping payment evidence.
