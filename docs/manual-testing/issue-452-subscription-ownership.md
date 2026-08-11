# Manual Test: Issue 452 Subscription Ownership

## Purpose

Verify that subscription update and cancellation remain compatible for the subscription owner, concurrent or repeated requests create one economic Stripe mutation, and a caller cannot mutate another account's subscription by supplying its Stripe ID.

## Preconditions

- Use a non-production environment connected to Stripe test mode.
- Create two test users, A and B, each with a distinct Stripe customer created by Quizzence so the customer metadata contains the corresponding `userId`.
- Complete one subscription payment for each user and wait for its webhook processing to create the local `subscription_status` record.
- Obtain each user's own subscription ID from Stripe test mode or the authenticated application flow. Do not use production customer, subscription, or payment identifiers.
- Have two valid server-configured price lookup keys for the update test.
- Use a test environment with Flyway V70 applied. Confirm `subscription_mutation_operations` exists before testing retries.

## Owner Update

1. Authenticate as user A with `BILLING_WRITE`.
2. Send `POST /api/v1/billing/update-subscription` with A's `subscriptionId` and a valid `newPriceLookupKey`.
3. Confirm a `200` response and that Stripe test mode shows only A's subscription using the new price.
4. Confirm user B's subscription is unchanged.

## Compatible Requests With and Without a Retry Key

1. Repeat an owner update without an `Idempotency-Key` header and confirm the existing request body still returns `200`.
2. Change to another valid price and send the update with `Idempotency-Key: <UNIQUE_TEST_KEY>`.
3. Retry the identical request with the identical header and confirm `200` with no second Stripe proration or invoice side effect.
4. Reuse `<UNIQUE_TEST_KEY>` with a different `newPriceLookupKey` and confirm `409` with the idempotency-conflict problem type.
5. Confirm the database stores only a 64-character hash in `idempotency_key_hash`; the raw client key must not be persisted or logged.

## Owner Cancellation and Retry

1. Authenticate as user A with `BILLING_WRITE`.
2. Send `POST /api/v1/billing/cancel-subscription` with A's `subscriptionId`.
3. Confirm a `200` response, Stripe marks A's subscription as cancelled, and the local status becomes blocked with reason `subscription_cancelled_by_user` (or is subsequently reconciled by the deletion webhook).
4. Repeat the same request.
5. Confirm a `200` response and verify Stripe shows no second cancellation mutation or duplicate side effect.

## Concurrent Requests

1. From two terminals, prepare the same cancellation request for one active test subscription. Use the same authenticated user; the header may be omitted to exercise legacy compatibility.
2. Send both requests at nearly the same time.
3. Confirm both requests return `200`, Stripe records one cancellation, and local `subscription_status` is blocked with `subscription_cancelled_by_user`.
4. Repeat with two identical updates using the same `Idempotency-Key`. Confirm one Stripe update and two successful responses.
5. On a fresh active subscription, pause or delay an update in a controlled Stripe test double and submit cancellation concurrently. Release the update and confirm the final provider and local state is cancelled.
6. Reverse the order. Confirm cancellation succeeds and the waiting update returns `409`; cancellation is the terminal state.

The narrow stale-snapshot race is verified deterministically without a real Stripe call. From the repository root on the local machine, run:

```bash
./mvnw -Dtest=SubscriptionMutationConcurrencyMySqlIntegrationTest#concurrentLegacyCancelsProduceOneEconomicMutation test
```

The test pauses the second request after it has read the active provider state, lets the first request finish cancellation and durable completion, and then resumes the second claim. It must report two cancelled responses, one durable operation, and exactly one Stripe cancellation call.

## Cross-Account Denial

1. Authenticate as user A with `BILLING_WRITE`.
2. Send update and cancellation requests using B's `subscriptionId`.
3. Confirm each response is `403` with the generic access-denied problem detail. It must not expose B's customer ID, subscription ID, existence, price, invoice, or payment state.
4. Confirm in Stripe test mode that B's subscription has not changed.
5. Confirm application logs contain the `billing_subscription_mutation` audit event with hashed identifiers only and a bounded denial reason such as `local_mapping_mismatch`.

## Drift and Provider Failure

1. In an isolated test environment, temporarily make the local subscription mapping point to a subscription whose Stripe customer's `userId` metadata belongs to the other user.
2. Submit either mutation as the mapped local user. Confirm `403`, no Stripe mutation, and an audit event with `stripe_customer_mismatch`.
3. Make Stripe unavailable in the test environment (for example, block the test endpoint or use a controlled provider failure).
4. Confirm the mutation returns `503` with the retry-safe generic problem detail and the local subscription status is unchanged.

## Lost Response Recovery

1. In a controlled test environment, let Stripe apply an update or cancellation, then fail local operation completion before the HTTP response is returned.
2. Retry the identical request. For a client-supplied key, reuse the same `Idempotency-Key`; a legacy no-header request remains supported.
3. Confirm the backend retrieves the already-applied Stripe state, marks the durable operation `SUCCEEDED`, and does not call the Stripe mutation a second time.
4. For cancellation, confirm local `subscription_status` is reconciled to blocked.
5. Confirm no database transaction or row lock remains open while the delayed Stripe call is in progress.

## API Contract

Open `/swagger-ui/index.html` and select the Billing group. Confirm both mutation endpoints still accept the existing request bodies, document `subscriptionId` as a compatibility cross-check, require `BILLING_WRITE`, and list the ownership `403`, conflict `409`, and retryable `503` responses. Confirm `Idempotency-Key` is an optional header with a 128-character service limit; clients that omit it remain supported.

Send either mutation with a 129-character `Idempotency-Key`. Confirm the API returns `400` with the constraint-violation problem type and does not call Stripe.
