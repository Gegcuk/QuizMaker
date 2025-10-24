# BillingController API Spec

Base path: `/api/v1/billing`\
Content type: `application/json` (webhooks consume raw payload strings)

## Auth

| Operation group | Auth | Permissions |
| ---------------- | ---- | ----------- |
| Billing config | none | Public (feature flag must be enabled) |
| Checkout status, balance, transactions, quiz-generation estimate | JWT | `BILLING_READ` |
| Checkout session creation, Stripe customer & subscription management | JWT | `BILLING_WRITE` |
| Stripe customer lookup | JWT | `BILLING_READ` |
| Stripe webhooks (`/stripe/webhook`, `/webhooks`) | none | Signed by Stripe; auth not required |

---

## DTOs

**ConfigResponse / PackDto**

```ts
{ publishableKey: string; prices: PackDto[]; }

type PackDto = {
  id: string;              // UUID
  name: string;
  tokens: number;
  priceCents: number;
  currency: string;        // e.g., "usd"
  stripePriceId: string;
};
```

**BalanceDto**

```ts
{ userId: string; availableTokens: number; reservedTokens: number; updatedAt: string; }
```

**TransactionDto**

```ts
{
  id: string;
  userId: string;
  type: 'CREDIT' | 'DEBIT';
  source: string;          // TokenTransactionSource
  amountTokens: number;
  refId?: string | null;
  idempotencyKey?: string | null;
  balanceAfterAvailable?: number | null;
  balanceAfterReserved?: number | null;
  metaJson?: string | null;
  createdAt: string;
}
```

**EstimationDto**

```ts
{
  estimatedLlmTokens: number;
  estimatedBillingTokens: number;
  approxCostCents?: number | null;
  currency: string;
  estimate: boolean;
  humanizedEstimate: string;
  estimationId: string;
}
```

**Checkout/Customer/Subscription DTOs**

```ts
type CheckoutSessionStatus = {
  sessionId: string;
  status: string;
  credited: boolean;
  creditedTokens?: number | null;
};

type CheckoutSessionResponse = { url: string; sessionId: string; };

type CreateCheckoutSessionRequest = { priceId: string; packId?: string | null; };

type CreateCustomerRequest = { email: string; };

type CreateSubscriptionRequest = { priceId: string; };

type UpdateSubscriptionRequest = { subscriptionId: string; newPriceLookupKey: string; };

type CancelSubscriptionRequest = { subscriptionId: string; };

type CustomerResponse = { id: string; email: string; name?: string | null; };

type SubscriptionResponse = { subscriptionId: string; clientSecret: string; };
```

**ErrorResponse / ProblemDetail**

```ts
{ timestamp: string; status: number; error: string; details: string[]; }
// Some endpoints return RFC 7807 ProblemDetail for Stripe/idempotency errors.
```

---

## Endpoints

| Method | Path | ReqBody | Resp | Auth | Notes |
| ------ | ---- | ------- | ---- | ---- | ----- |
| GET | `/config` | – | `ConfigResponse` | none | Returns 404 when billing feature disabled |
| GET | `/checkout-sessions/{sessionId}` | – | `CheckoutSessionStatus` | `BILLING_READ` | Validates ownership via Stripe metadata |
| GET | `/balance` | – | `BalanceDto` | `BILLING_READ` | Rate limited 60/min per user; `Cache-Control: private, max-age=30` |
| GET | `/transactions` | – | `Page<TransactionDto>` | `BILLING_READ` | Filters: `type`, `source`, `dateFrom`, `dateTo`; rate limited 30/min |
| POST | `/estimate/quiz-generation` | `GenerateQuizFromDocumentRequest` | `EstimationDto` | `BILLING_READ` | Rate limited 10/min |
| POST | `/checkout-sessions` | `CreateCheckoutSessionRequest` | `CheckoutSessionResponse` | `BILLING_WRITE` | Rate limited 5/min |
| POST | `/create-customer` | `CreateCustomerRequest` | `CustomerResponse` | `BILLING_WRITE` | Rate limited 3/min |
| GET | `/customers/{customerId}` | – | `CustomerResponse` | `BILLING_READ` | Ownership verified via Stripe metadata/email fallback |
| POST | `/create-subscription` | `CreateSubscriptionRequest` | `SubscriptionResponse` | `BILLING_WRITE` | Automatically creates/reuses Stripe customer |
| POST | `/update-subscription` | `UpdateSubscriptionRequest` | `string` (JSON text) | `BILLING_WRITE` | Response is Stripe JSON via `PRETTY_PRINT_GSON` |
| POST | `/cancel-subscription` | `CancelSubscriptionRequest` | `string` (JSON text) | `BILLING_WRITE` | Cancels Stripe subscription |
| POST | `/stripe/webhook` | raw string | `string` | none | Stripe-signed callback; returns empty string on success |
| POST | `/webhooks` | raw string | `string` | none | Alias of `/stripe/webhook` |

---

## Errors

| Code | Meaning | Notes |
| ---- | ------- | ----- |
| 400 | Validation error / unsupported request | Includes invalid feature flag usage or bad Stripe payloads |
| 401 | Unauthorized | Missing/invalid JWT |
| 403 | Forbidden | Caller lacks billing permission or Stripe ownership check fails |
| 404 | Not found | Feature flag disabled or resource not owned/found |
| 409 | Conflict | Stripe/idempotency conflicts (ProblemDetail) |
| 422 | Unprocessable entity | Downstream processing failure (e.g., document estimate constraints) |
| 429 | Too many requests | Rate limiter triggered (balance, transactions, estimation, checkout) |
| 500 | Server error | Unexpected internal/Stripe error |

---

## Validation Summary

- Billing endpoints return `404 Not Found` when `featureFlags.isBilling()` is false; clients should handle as “billing disabled”.
- Multiple rate limits enforced via `RateLimitService` (`billing-balance`, `billing-transactions`, `quiz-estimation`, `checkout-session-create`, `billing-create-customer`).
- Ownership of Stripe customers checked via metadata or optional email fallback (`billingProperties.isAllowEmailFallbackForCustomerOwnership`).
- Subscription operations auto-create Stripe customers if none exist.
- Webhook handlers expect valid `Stripe-Signature`; invalid signatures raise `StripeWebhookInvalidSignatureException` (`400`).

---

## Notes for Agents

- Always send `Authorization: Bearer <jwt>` along with appropriate billing permissions except for `/config` and webhook endpoints.
- Cache `/config` client-side and refresh only when billing feature toggles.
- After checkout creation, redirect users to `CheckoutSessionResponse.url` then poll `/checkout-sessions/{sessionId}` for completion.
- Treat 404 on authenticated endpoints as “billing disabled”; hide billing UI accordingly.
- Webhook endpoints should only be called by Stripe—never expose them in the client.
