# Manual Test: Issue 452 Subscription Ownership

## Purpose

Verify that subscription update and cancellation remain compatible for the subscription owner, while a caller cannot mutate another account's subscription by supplying its Stripe ID.

## Preconditions

- Use a non-production environment connected to Stripe test mode.
- Create two test users, A and B, each with a distinct Stripe customer created by Quizzence so the customer metadata contains the corresponding `userId`.
- Complete one subscription payment for each user and wait for its webhook processing to create the local `subscription_status` record.
- Obtain each user's own subscription ID from Stripe test mode or the authenticated application flow. Do not use production customer, subscription, or payment identifiers.
- Have two valid server-configured price lookup keys for the update test.

## Owner Update

1. Authenticate as user A with `BILLING_WRITE`.
2. Send `POST /api/v1/billing/update-subscription` with A's `subscriptionId` and a valid `newPriceLookupKey`.
3. Confirm a `200` response and that Stripe test mode shows only A's subscription using the new price.
4. Confirm user B's subscription is unchanged.

## Owner Cancellation and Retry

1. Authenticate as user A with `BILLING_WRITE`.
2. Send `POST /api/v1/billing/cancel-subscription` with A's `subscriptionId`.
3. Confirm a `200` response, Stripe marks A's subscription as cancelled, and the local status becomes blocked with reason `subscription_cancelled_by_user` (or is subsequently reconciled by the deletion webhook).
4. Repeat the same request.
5. Confirm a `200` response and verify Stripe shows no second cancellation mutation or duplicate side effect.

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

## API Contract

Open `/swagger-ui/index.html` and select the Billing group. Confirm both mutation endpoints still accept the existing `subscriptionId` field, document it as a compatibility cross-check, require `BILLING_WRITE`, and list the ownership `403` and retryable `503` responses.
