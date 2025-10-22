# Billing Controller API Reference

Documentation for billing endpoints exposed under `/api/v1/billing`. They cover configuration, token balances, transaction history, Stripe checkout, subscriptions, and webhook handling.

## Overview
- **Base path**: `/api/v1/billing`
- **Authentication**: Required for all endpoints except `/config` and webhook receivers
- **Authorization**:
  - `BILLING_READ` for balance, transactions, estimation, checkout session status, and Stripe customer reads
  - `BILLING_WRITE` for checkout session creation, customer creation, and subscription management
- **Feature flag**: When billing is disabled (`featureFlags.isBilling() == false`), most endpoints return `404 Not Found`
- **Rate limits** (per authenticated user unless noted):
  - Balance: 60/min (`billing-balance`)
  - Transactions: 30/min (`billing-transactions`)
  - Quiz generation estimate: 10/min (`quiz-estimation`)
  - Checkout session creation: 5/min (`checkout-session-create`)
  - Stripe customer creation: 3/min (`billing-create-customer`)
  - Share-link related endpoints unaffected (handled elsewhere)
- **Error schema**: `ErrorResponse` (`timestamp`, `status`, `error`, `details`); Stripe concurrency conflicts may surface as `ProblemDetail`

## DTO Snapshot
### ConfigResponse
| Field | Type | Notes |
| --- | --- | --- |
| `publishableKey` | string | Stripe publishable key |
| `prices` | array<PackDto> | Available packs |

### PackDto
| Field | Type | Notes |
| --- | --- | --- |
| `id` | UUID | Internal pack id |
| `name` | string | Display name |
| `tokens` | long | Billing tokens granted |
| `priceCents` | long | Price in smallest currency unit |
| `currency` | string | ISO currency code |
| `stripePriceId` | string | Stripe price identifier |

### BalanceDto
| Field | Type | Notes |
| --- | --- | --- |
| `userId` | UUID | Owner |
| `availableTokens` | long | Spendable tokens |
| `reservedTokens` | long | Locked tokens |
| `updatedAt` | datetime | Last update timestamp |

### TransactionDto
| Field | Type | Notes |
| --- | --- | --- |
| `id` | UUID | Transaction id |
| `type` | enum `TokenTransactionType` | `DEBIT` / `CREDIT` |
| `source` | enum `TokenTransactionSource` | e.g., `PURCHASE`, `GENERATION`, `ADJUSTMENT` |
| `amountTokens` | long | Positive values; sign inferred by `type` |
| `refId` | string \| null | Stripe payment id or related reference |
| `idempotencyKey` | string \| null | Duplicate detection key |
| `balanceAfterAvailable` / `balanceAfterReserved` | long \| null | Snapshot balances |
| `metaJson` | string \| null | Additional JSON metadata |
| `createdAt` | datetime | Timestamp |

### CheckoutSessionStatus
| Field | Type | Notes |
| --- | --- | --- |
| `sessionId` | string | Stripe session id |
| `status` | string | Stripe status (`open`, `complete`, etc.) |
| `credited` | boolean | Whether tokens credited |
| `creditedTokens` | long \| null | Tokens applied |

### CheckoutSessionResponse
| Field | Type | Notes |
| --- | --- | --- |
| `url` | string | Stripe-hosted checkout URL |
| `sessionId` | string | Created session id |

### EstimationDto
| Field | Type | Notes |
| --- | --- | --- |
| `estimatedLlmTokens` | long | Raw token estimate |
| `estimatedBillingTokens` | long | Converted billing tokens |
| `approxCostCents` | long \| null | Optional future pricing |
| `currency` | string | Currency code |
| `estimate` | boolean | Always `true` |
| `humanizedEstimate` | string | Friendly summary |
| `estimationId` | UUID | Trace identifier |

### CustomerResponse
| Field | Type | Notes |
| --- | --- | --- |
| `id` | string | Stripe customer id |
| `email` | string | Customer email |
| `name` | string \| null | Stripe-side name |

### SubscriptionResponse
| Field | Type | Notes |
| --- | --- | --- |
| `subscriptionId` | string | Stripe subscription id |
| `clientSecret` | string | Payment intent client secret (if applicable) |

### Request DTOs
| DTO | Purpose | Required Fields |
| --- | --- | --- |
| `CreateCheckoutSessionRequest` | Start token pack purchase | `priceId` (`string`), optional `packId` (UUID) |
| `CreateCustomerRequest` | Create Stripe customer | `email` (`string`, valid email) |
| `CreateSubscriptionRequest` | Start subscription | `priceId` (`string`) |
| `UpdateSubscriptionRequest` | Change subscription price | `subscriptionId`, `newPriceLookupKey` |
| `CancelSubscriptionRequest` | Cancel subscription | `subscriptionId` |
| `GenerateQuizFromDocumentRequest` | Estimate quiz generation cost | See [Quiz documentation](quiz_controller.md) for full field list |

## Endpoint Summary
| Method | Path | Auth | Description | Success |
| --- | --- | --- | --- | --- |
| GET | `/config` | Public | Fetch Stripe publishable key and packs | `200 OK` → `ConfigResponse` |
| GET | `/checkout-sessions/{sessionId}` | `BILLING_READ` | Retrieve checkout status | `200 OK` → `CheckoutSessionStatus` |
| GET | `/balance` | `BILLING_READ` | Current token balance | `200 OK` → `BalanceDto` |
| GET | `/transactions` | `BILLING_READ` | Paginated transaction history | `200 OK` → `Page<TransactionDto>` |
| POST | `/estimate/quiz-generation` | `BILLING_READ` | Estimate tokens for AI quiz generation | `200 OK` → `EstimationDto` |
| POST | `/checkout-sessions` | `BILLING_WRITE` | Create Stripe checkout session | `200 OK` → `CheckoutSessionResponse` |
| POST | `/create-customer` | `BILLING_WRITE` | Create Stripe customer for user | `200 OK` → `CustomerResponse` |
| GET | `/customers/{customerId}` | `BILLING_READ` | Fetch Stripe customer (ownership validated) | `200 OK` → `CustomerResponse` |
| POST | `/create-subscription` | `BILLING_WRITE` | Start subscription using price id | `200 OK` → `SubscriptionResponse` |
| POST | `/update-subscription` | `BILLING_WRITE` | Change existing subscription price | `200 OK` → Stripe JSON string |
| POST | `/cancel-subscription` | `BILLING_WRITE` | Cancel subscription | `200 OK` → Stripe JSON string |
| POST | `/stripe/webhook` | Public | Stripe webhook endpoint | `200 OK` (empty body) |
| POST | `/webhooks` | Public | Alternate webhook endpoint | `200 OK` (empty body) |

## Endpoint Details
### GET /api/v1/billing/config
- No authentication required.
- Returns Stripe publishable key and list of purchasable packs.
- Responds `404` when billing feature disabled.

### GET /api/v1/billing/checkout-sessions/{sessionId}
- Requires `BILLING_READ`.
- Returns checkout status and whether tokens were credited.
- Respects billing feature flag (`404` when disabled).
- Used to poll Stripe checkout completion from the frontend.

### GET /api/v1/billing/balance
- Requires `BILLING_READ`.
- Rate limited to 60 requests per minute per user.
- Response adds `Cache-Control: private, max-age=30` to encourage short-term caching.

### GET /api/v1/billing/transactions
- Requires `BILLING_READ`.
- Rate limited to 30 requests/min.
- Query parameters:
  - `page`, `size` (pagination; defaults 0/20)
  - `type` (`TokenTransactionType`)
  - `source` (`TokenTransactionSource`)
  - `dateFrom`, `dateTo` (`ISO-8601`)
- Response includes `Page<TransactionDto>` with private cache headers (`max-age=60`).

### POST /api/v1/billing/estimate/quiz-generation
- Requires `BILLING_READ`.
- Rate limited to 10 requests/min.
- Accepts `GenerateQuizFromDocumentRequest`; see quiz documentation for detailed field semantics.
- Returns token estimate (`EstimationDto`).

### POST /api/v1/billing/checkout-sessions
- Requires `BILLING_WRITE`.
- Rate limited to 5 requests/min.
- Body: `CreateCheckoutSessionRequest` with Stripe price id (optionally product pack id).
- Returns checkout URL and session id.

### POST /api/v1/billing/create-customer
- Requires `BILLING_WRITE`.
- Rate limited to 3 requests/min.
- Body: `CreateCustomerRequest` containing email.
- Returns Stripe customer metadata. When billing is disabled returns `404`.

### GET /api/v1/billing/customers/{customerId}
- Requires `BILLING_READ`.
- Validates ownership via Stripe metadata (`userId`) and optional email fallback (config dependent).
- Returns `403 Forbidden` if customer does not belong to caller.

### POST /api/v1/billing/create-subscription
- Requires `BILLING_WRITE`.
- Automatically resolves or creates Stripe customer for authenticated user.
- Body: `CreateSubscriptionRequest`.
- Returns `SubscriptionResponse` (`subscriptionId`, `clientSecret`).

### POST /api/v1/billing/update-subscription
- Requires `BILLING_WRITE`.
- Body: `UpdateSubscriptionRequest` (`subscriptionId`, `newPriceLookupKey`).
- Exchanges lookup key for Stripe price id, updates subscription, and returns Stripe JSON string (prettified).

### POST /api/v1/billing/cancel-subscription
- Requires `BILLING_WRITE`.
- Body: `CancelSubscriptionRequest`.
- Cancels Stripe subscription and returns Stripe JSON string (prettified).

### Webhook Endpoints
- `POST /api/v1/billing/stripe/webhook` and `/api/v1/billing/webhooks` are unauthenticated for Stripe callbacks.
- Both delegate to the same handler; if billing disabled they return `404`.
- Expect `Stripe-Signature` header; invalid signatures raise `StripeWebhookInvalidSignatureException` leading to `400 Bad Request`.
- Successful processing returns `200 OK` with empty response body.

## Error Handling
| Status | When Returned |
| --- | --- |
| `400 Bad Request` | Invalid payloads, unsupported feature flag operations, Stripe validation errors |
| `401 Unauthorized` | Missing/invalid JWT |
| `403 Forbidden` | Caller lacks `BILLING_*` permission or fails ownership checks |
| `404 Not Found` | Billing feature disabled or resource not found (session/customer) |
| `409 Conflict` | Stripe/API idempotency conflicts (via `ProblemDetail`) |
| `422 Unprocessable Entity` | Generated when underlying billing rules reject request |
| `429 Too Many Requests` | Rate limit exceeded (includes `Retry-After`) |
| `500 Internal Server Error` | Unexpected Stripe or persistence failure |

## Integration Notes
- Guard UI features behind the same billing feature flag to avoid surfacing 404 errors when billing is disabled.
- Cache `/config` (public) results client-side; refresh on application load.
- Always poll `/checkout-sessions/{id}` after redirect to detect successful purchases before updating balances in UI.
- When handling webhooks, ensure the endpoint URL matches the configured Stripe webhook secret; log processing results for auditing.
- Store returned `subscriptionId` client-side to enable change/cancel operations without additional lookup.
