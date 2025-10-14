# Billing Controller API Reference

Complete frontend integration guide for `/api/v1/billing` REST endpoints. This document is self-contained and consolidates all DTOs, validation rules, feature-flag behavior, permissions, and error semantics needed to integrate billing flows without inspecting backend code.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Feature Flag Behavior](#feature-flag-behavior)
- [Request DTOs](#request-dtos)
- [Response DTOs](#response-dtos)
- [Enumerations](#enumerations)
- [Endpoints](#endpoints)
  - [Configuration & Session Status](#configuration--session-status)
  - [Balance & Transactions](#balance--transactions)
  - [Quiz Generation Estimation](#quiz-generation-estimation)
  - [Checkout & Token Packs](#checkout--token-packs)
  - [Stripe Customer Management](#stripe-customer-management)
  - [Stripe Subscriptions](#stripe-subscriptions)
- [Error Handling](#error-handling)
- [Integration Guide](#integration-guide)
  - [Token Top-Up Flow](#token-top-up-flow)
  - [Subscription Management Flow](#subscription-management-flow)
  - [Quiz Generation Estimation Flow](#quiz-generation-estimation-flow)
- [Security Considerations](#security-considerations)

---

## Overview

* **Base Path**: `/api/v1/billing`
* **Authentication**: Required for all endpoints (JWT Bearer token in `Authorization` header)
* **Authorization**: Permission-based. Requires either `BILLING_READ` or `BILLING_WRITE` depending on capability (see Authorization Matrix)
* **Content-Type**: `application/json` for requests and responses (unless otherwise noted)
* **Rate Limiting**: Enforced per endpoint via `RateLimitService`; exceeding limits throws `RateLimitExceededException`
* **Error Format**: RFC-9457 `ProblemDetail` body produced by `BillingErrorHandler`

---

## Authorization Matrix

| Capability | Endpoint(s) | Required Permission(s) | Notes |
| --- | --- | --- | --- |
| **Read billing config** | `GET /config` | None (authenticated user) | Still requires valid token |
| **Check checkout session status** | `GET /checkout-sessions/{sessionId}` | `BILLING_READ` | Ownership enforced via authenticated user ID |
| **View balance** | `GET /balance` | `BILLING_READ` | Rate limited to 60/min per user |
| **List transactions** | `GET /transactions` | `BILLING_READ` | Rate limited to 30/min per user |
| **Estimate quiz generation cost** | `POST /estimate/quiz-generation` | `BILLING_READ` | Rate limited to 10/min per user |
| **Create checkout session** | `POST /checkout-sessions` | `BILLING_WRITE` | Rate limited to 5/min per user |
| **Create Stripe customer** | `POST /create-customer` | `BILLING_WRITE` | Rate limited to 3/min per user |
| **Fetch Stripe customer** | `GET /customers/{customerId}` | `BILLING_READ` | Ownership verified via metadata/email |
| **Create subscription** | `POST /create-subscription` | `BILLING_WRITE` | Requires existing or auto-created Stripe customer |
| **Update subscription** | `POST /update-subscription` | `BILLING_WRITE` | Lookup key resolved server-side |
| **Cancel subscription** | `POST /cancel-subscription` | `BILLING_WRITE` | Immediate cancellation via Stripe API |

All endpoints require an authenticated principal that resolves to a platform user ID. Unauthorized requests respond with `401`.

---

## Feature Flag Behavior

Billing endpoints are gated behind the `billing` feature flag. When `FeatureFlags.isBilling()` is `false`, the controller short-circuits and responds with `404 Not Found` for the following routes: `/checkout-sessions/{sessionId}`, `/config`, `/balance`, `/transactions`, `/estimate/quiz-generation`, and `/checkout-sessions`. Client integrations should treat `404` as "billing currently unavailable" rather than "resource missing".

`/create-customer`, `/customers/{customerId}`, and subscription endpoints do **not** check the flag (Stripe interactions remain available for authenticated users with appropriate permissions).

---

## Request DTOs

### CreateCheckoutSessionRequest

Used by `POST /checkout-sessions`.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `priceId` | `string` | Yes | `@NotBlank` | Stripe price identifier that defines the purchase amount |
| `packId` | `UUID` | No | – | Internal token pack identifier stored as Stripe metadata |

Example:
```json
{
  "priceId": "price_1234567890",
  "packId": "550e8400-e29b-41d4-a716-446655440000"
}
```

### CreateCustomerRequest

Used by `POST /create-customer`.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `email` | `string` | Yes | `@NotBlank`, `@Email` | Email used to create the Stripe customer |

### CreateSubscriptionRequest

Used by `POST /create-subscription`.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `priceId` | `string` | Yes | `@NotBlank` | Stripe price ID for the subscription plan |

### UpdateSubscriptionRequest

Used by `POST /update-subscription`.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `subscriptionId` | `string` | Yes | `@NotBlank` | Stripe subscription identifier to update |
| `newPriceLookupKey` | `string` | Yes | `@NotBlank` | Stripe lookup key that resolves to the new price |

### CancelSubscriptionRequest

Used by `POST /cancel-subscription`.

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `subscriptionId` | `string` | Yes | `@NotBlank` | Stripe subscription identifier to cancel |

### GenerateQuizFromDocumentRequest (imported)

Used by `POST /estimate/quiz-generation`. Fields mirror the quiz generation request DTO; validations enforced server-side:

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `documentId` | `UUID` | Yes | `@NotNull` | Processed document to generate quiz from |
| `quizScope` | `QuizScope` enum | No | Defaults to `ENTIRE_DOCUMENT` | Determines subset of content used |
| `chunkIndices` | `array<int>` | Conditional | Required & non-negative when `quizScope = SPECIFIC_CHUNKS` | Selected chunks |
| `chapterTitle` | `string` | Conditional | Required when `quizScope` targets specific chapter/section | Human-friendly identifier |
| `chapterNumber` | `int` | Conditional | Alternative to `chapterTitle` for chapter scopes | Must be >= 0 |
| `quizTitle` | `string` | No | `@Size(max=100)` | Optional custom title |
| `quizDescription` | `string` | No | `@Size(max=500)` | Optional custom description |
| `questionsPerType` | `map<QuestionType,int>` | Yes | `@NotNull`, `@Size(min=1)`, each value 1–10 | Question distribution |
| `difficulty` | `Difficulty` enum | Yes | `@NotNull` | Desired difficulty |
| `estimatedTimePerQuestion` | `int` | No | `@Min(1)`, `@Max(10)` | Minutes per question (defaults to 1) |
| `categoryId` | `UUID` | No | – | Optional quiz category |
| `tagIds` | `array<UUID>` | No | Defaults to empty list | Quiz tags |
| `language` | `string` | No | Defaults to `"en"` if blank | ISO 639-1 code |

The record constructor enforces additional guards (e.g., chunk indices non-negative, chapter metadata presence). Violations throw `IllegalArgumentException` mapped to `400`.

---

## Response DTOs

### ConfigResponse

Returned by `GET /config`.

| Field | Type | Description |
| --- | --- | --- |
| `publishableKey` | `string` | Stripe publishable key for client SDK |
| `prices` | `array<PackDto>` | Available token packs |

### PackDto

Embedded within `ConfigResponse`.

| Field | Type | Description |
| --- | --- | --- |
| `id` | `UUID` | Internal pack ID |
| `name` | `string` | Marketing display name |
| `tokens` | `long` | Number of billing tokens granted |
| `priceCents` | `long` | Price in minor currency units |
| `currency` | `string` | ISO currency code |
| `stripePriceId` | `string` | Stripe price reference |

### CheckoutSessionStatus

Returned by `GET /checkout-sessions/{sessionId}`.

| Field | Type | Description |
| --- | --- | --- |
| `sessionId` | `string` | Stripe checkout session identifier |
| `status` | `string` | Session status (e.g., `complete`, `expired`) |
| `credited` | `boolean` | Whether tokens were already credited |
| `creditedTokens` | `long` or `null` | Number of tokens credited (if any) |

### CheckoutSessionResponse

Returned by `POST /checkout-sessions`.

| Field | Type | Description |
| --- | --- | --- |
| `url` | `string` | Stripe-hosted checkout URL |
| `sessionId` | `string` | Stripe session identifier |

### BalanceDto

Returned by `GET /balance`.

| Field | Type | Description |
| --- | --- | --- |
| `userId` | `UUID` | Owner of the balance |
| `availableTokens` | `long` | Spendable tokens |
| `reservedTokens` | `long` | Tokens held for pending operations |
| `updatedAt` | `LocalDateTime` | Last update timestamp |

### TransactionDto (paged)

Returned by `GET /transactions` as a `Page<TransactionDto>`.

| Field | Type | Description |
| --- | --- | --- |
| `id` | `UUID` | Transaction identifier |
| `userId` | `UUID` | Owner |
| `type` | `TokenTransactionType` | Transaction type |
| `source` | `TokenTransactionSource` | Origin system |
| `amountTokens` | `long` | Token delta (positive or negative) |
| `refId` | `string` | External reference (nullable) |
| `idempotencyKey` | `string` | Idempotency key used |
| `balanceAfterAvailable` | `long` | Available balance snapshot |
| `balanceAfterReserved` | `long` | Reserved balance snapshot |
| `metaJson` | `string` | JSON metadata |
| `createdAt` | `LocalDateTime` | Timestamp |

### EstimationDto

Returned by `POST /estimate/quiz-generation`.

| Field | Type | Description |
| --- | --- | --- |
| `estimatedLlmTokens` | `long` | Estimated raw LLM tokens |
| `estimatedBillingTokens` | `long` | Converted billing tokens |
| `approxCostCents` | `long` or `null` | Placeholder for future pricing |
| `currency` | `string` | Currency for potential pricing |
| `estimate` | `boolean` | Always `true` to indicate estimate |
| `humanizedEstimate` | `string` | Human-readable summary |
| `estimationId` | `UUID` | Unique estimation identifier |

### CustomerResponse

Returned by `POST /create-customer` and `GET /customers/{customerId}`.

| Field | Type | Description |
| --- | --- | --- |
| `id` | `string` | Stripe customer ID |
| `email` | `string` | Customer email |
| `name` | `string` | Customer name (if available) |

### SubscriptionResponse

Returned by `POST /create-subscription`.

| Field | Type | Description |
| --- | --- | --- |
| `subscriptionId` | `string` | Stripe subscription ID |
| `clientSecret` | `string` | Setup intent or payment intent client secret |

### Raw Stripe JSON strings

`POST /update-subscription` and `POST /cancel-subscription` return the Stripe subscription payload serialized via `StripeObject.PRETTY_PRINT_GSON`. Treat response body as formatted JSON string (not structured DTO).

---

## Enumerations

### TokenTransactionType
`PURCHASE`, `RESERVE`, `COMMIT`, `RELEASE`, `REFUND`, `ADJUSTMENT`

### TokenTransactionSource
`QUIZ_GENERATION`, `AI_CHECK`, `ADMIN`, `STRIPE`

### QuizScope (from quiz feature)
`ENTIRE_DOCUMENT`, `SPECIFIC_CHUNKS`, `SPECIFIC_CHAPTER`, `SPECIFIC_SECTION`

### QuestionType (from quiz feature)
`MCQ_SINGLE`, `MCQ_MULTI`, `TRUE_FALSE`, `FILL_IN_THE_BLANK`, `MATCHING`, `ORDERING`, etc. – must align with backend enum.

### Difficulty (from quiz feature)
`EASY`, `MEDIUM`, `HARD` (plus any other values in quiz domain enum).

---

## Endpoints

### Configuration & Session Status

#### `GET /config`
* **Permissions**: Authenticated (no specific permission)
* **Feature Flag**: Returns `404` when billing disabled
* **Response**: `200 OK` with `ConfigResponse`
* **Caching**: Client-managed (no cache headers)
* **Errors**:
  * `404 Not Found` – billing disabled
  * `503 Service Unavailable` – configuration missing/misconfigured (`IllegalStateException` flagged as configuration error)
  * `500 Internal Server Error` – unexpected errors

#### `GET /checkout-sessions/{sessionId}`
* **Permissions**: `BILLING_READ`
* **Rate Limit**: None beyond general API protection
* **Response**: `200 OK` with `CheckoutSessionStatus`
* **Ownership**: Validates session belongs to authenticated user; `403` if not
* **Errors**:
  * `404 Not Found` – billing disabled or session not found
  * `403 Forbidden` – session owned by another user
  * `409 Conflict` – idempotency conflict surfaced via domain exception
  * `500 Internal Server Error` – unexpected

### Balance & Transactions

#### `GET /balance`
* **Permissions**: `BILLING_READ`
* **Feature Flag**: 404 when disabled
* **Rate Limit**: 60 requests/min per user. On exceed, `429 Too Many Requests` with optional `Retry-After`
* **Response**: `200 OK` with `BalanceDto` and header `Cache-Control: private, max-age=30`
* **Errors**:
  * `404 Not Found` – billing disabled
  * `429 Too Many Requests` – rate limit exceeded
  * `403 Forbidden` – if ownership check fails (should not happen for authenticated user)
  * `500 Internal Server Error` – unexpected

#### `GET /transactions`
* **Permissions**: `BILLING_READ`
* **Feature Flag**: 404 when disabled
* **Rate Limit**: 30 requests/min per user
* **Query Parameters**:
  * Pagination: standard Spring `Pageable` (`size`, `page`, `sort`). Default size 20
  * `type` (`TokenTransactionType`) – optional filter
  * `source` (`TokenTransactionSource`) – optional filter
  * `dateFrom`, `dateTo` (`ISO-8601 LocalDateTime`) – optional inclusive bounds
* **Response**: `200 OK` with `Page<TransactionDto>` and header `Cache-Control: private, max-age=60`
* **Errors**:
  * `400 Bad Request` – invalid enum/value (handled via type mismatch)
  * `404 Not Found` – billing disabled
  * `429 Too Many Requests` – rate limit exceeded

### Quiz Generation Estimation

#### `POST /estimate/quiz-generation`
* **Permissions**: `BILLING_READ`
* **Feature Flag**: 404 when disabled
* **Rate Limit**: 10 requests/min per user
* **Request Body**: `GenerateQuizFromDocumentRequest`
* **Response**: `200 OK` with `EstimationDto`
* **Logging**: Server logs include document ID and user ID
* **Errors**:
  * `400 Bad Request` – validation failures or `IllegalArgumentException`
  * `404 Not Found` – billing disabled or missing document/quiz context
  * `403 Forbidden` – if user lacks access to referenced document
  * `429 Too Many Requests` – rate limit exceeded
  * `500 Internal Server Error` – unexpected

### Checkout & Token Packs

#### `POST /checkout-sessions`
* **Permissions**: `BILLING_WRITE`
* **Feature Flag**: 404 when disabled
* **Rate Limit**: 5 requests/min per user
* **Request Body**: `CreateCheckoutSessionRequest`
* **Response**: `200 OK` with `CheckoutSessionResponse`
* **Workflow**: Calls Stripe to create hosted checkout session using provided price/pack metadata
* **Errors**:
  * `400 Bad Request` – validation errors, Stripe errors, insufficient tokens
  * `404 Not Found` – billing disabled
  * `409 Conflict` – idempotency conflict
  * `429 Too Many Requests` – rate limit exceeded
  * `500 Internal Server Error` – unexpected Stripe/internal errors

### Stripe Customer Management

#### `POST /create-customer`
* **Permissions**: `BILLING_WRITE`
* **Rate Limit**: 3 requests/min per user
* **Request Body**: `CreateCustomerRequest`
* **Response**: `200 OK` with `CustomerResponse`
* **Errors**:
  * `400 Bad Request` – validation errors or Stripe API error
  * `429 Too Many Requests` – rate limit exceeded
  * `500 Internal Server Error` – unexpected

#### `GET /customers/{customerId}`
* **Permissions**: `BILLING_READ`
* **Ownership Validation**:
  * Primary: Stripe customer metadata `userId`
  * Fallback: when enabled by configuration (`allowEmailFallbackForCustomerOwnership`), compares Stripe email to platform user email
  * Otherwise throws `ForbiddenException`
* **Response**: `200 OK` with `CustomerResponse`
* **Errors**:
  * `403 Forbidden` – ownership check failed
  * `400 Bad Request` – Stripe error
  * `500 Internal Server Error` – user not found / other issues

### Stripe Subscriptions

All subscription endpoints resolve authenticated user ID. `create-subscription` auto-creates a customer if none exist (using latest payment or user email).

#### `POST /create-subscription`
* **Permissions**: `BILLING_WRITE`
* **Request Body**: `CreateSubscriptionRequest`
* **Response**: `200 OK` with `SubscriptionResponse`
* **Errors**:
  * `400 Bad Request` – validation or Stripe API error
  * `500 Internal Server Error` – missing user, Stripe failure

#### `POST /update-subscription`
* **Permissions**: `BILLING_WRITE`
* **Request Body**: `UpdateSubscriptionRequest`
* **Response**: `200 OK` with JSON string representing updated Stripe subscription
* **Errors**:
  * `400 Bad Request` – validation, Stripe API error, price lookup failure
  * `500 Internal Server Error` – unexpected

#### `POST /cancel-subscription`
* **Permissions**: `BILLING_WRITE`
* **Request Body**: `CancelSubscriptionRequest`
* **Response**: `200 OK` with JSON string representing canceled Stripe subscription
* **Errors**:
  * `400 Bad Request` – validation or Stripe API error
  * `500 Internal Server Error` – unexpected

---

## Error Handling

The `BillingErrorHandler` converts exceptions into `ProblemDetail` responses. Key mappings:

| Exception | Status | `type` URI | Notes |
| --- | --- | --- | --- |
| `InvalidCheckoutSessionException` | `404` or `500` (webhook) | `/problems/invalid-checkout-session` | `500` when triggered from webhook to force Stripe retry |
| `IdempotencyConflictException` | `409 Conflict` | `/problems/idempotency-conflict` | Occurs when reusing idempotency key with different payload |
| `InsufficientTokensException` | `400 Bad Request` | `/problems/insufficient-tokens` | General shortage |
| `InsufficientAvailableTokensException` | `400 Bad Request` | `/problems/insufficient-available-tokens` | Adds `requestedTokens`, `availableTokens`, `shortfall` properties |
| `ReservationNotActiveException` | `400 Bad Request` | `/problems/reservation-not-active` | For stale reservations |
| `StripeWebhookInvalidSignatureException` | `401 Unauthorized` | `/problems/invalid-webhook-signature` | Mostly for webhook controller |
| `MethodArgumentNotValidException` | `400 Bad Request` | `/problems/validation-error` | Contains `errors` map |
| `MethodArgumentTypeMismatchException` | `400 Bad Request` | `/problems/type-mismatch` | For invalid query parameter types |
| `IllegalArgumentException` | `400 Bad Request` | `/problems/invalid-argument` | DTO constructor guards |
| `ForbiddenException` | `403 Forbidden` | `/problems/forbidden` | Ownership failures |
| `LargePayloadSecurityException` | `500 Internal Server Error` | `/problems/security-error` | Security guard |
| `StripeException` | `400 Bad Request` | `/problems/stripe-error` | Stripe API error response |
| `RateLimitExceededException` | `429 Too Many Requests` | `/problems/rate-limit-exceeded` | Adds `Retry-After` header when available |
| `IllegalStateException` | `503 Service Unavailable` when config-related, else `500` | `/problems/configuration-error` or `/problems/internal-error` | Indicates misconfiguration or generic internal error |
| `HttpMessageNotReadableException` | `400 Bad Request` | `/problems/invalid-request-body` | Malformed JSON |
| `Exception` (fallback) | `500 Internal Server Error` | `/problems/internal-error` | Generic catch-all |

All responses include `title`, `status`, `detail`, and optionally domain-specific properties.

---

## Integration Guide

### Token Top-Up Flow

1. **Load billing config**: `GET /config` to retrieve publishable key and pack catalog.
2. **Display balance**: `GET /balance` (handle `404` when billing disabled).
3. **Create checkout session**: `POST /checkout-sessions` with selected pack price ID.
4. **Redirect to Stripe**: Use `url` from response.
5. **Poll session status**: `GET /checkout-sessions/{sessionId}` until `status = complete` and `credited = true`.
6. **Refresh balance & transactions**: Call `GET /balance` and `GET /transactions` to update UI.

Handling tips:
* Respect rate limits by debouncing polling and caching results.
* Cache `ConfigResponse` and pack catalog locally and refresh infrequently.
* Provide user-friendly messaging for `429` and include retry countdown from `Retry-After`.

### Subscription Management Flow

1. **Ensure customer exists**: Call `POST /create-customer` with user email (idempotent on Stripe).
2. **Create subscription**: `POST /create-subscription` with target price ID (receives `subscriptionId`, `clientSecret`).
3. **Confirm payment**: Use Stripe client with `clientSecret`.
4. **Update plan**: `POST /update-subscription` with lookup key when user changes tier.
5. **Cancel plan**: `POST /cancel-subscription` when user opts out.
6. **Fetch customer**: `GET /customers/{customerId}` for account settings page (handle `403` when metadata/email mismatch).

### Quiz Generation Estimation Flow

1. Collect quiz generation form data and validate locally (UUIDs, chunk indices).
2. Call `POST /estimate/quiz-generation` with `GenerateQuizFromDocumentRequest`.
3. Render `humanizedEstimate` and `estimatedBillingTokens` in UI.
4. Cache `estimationId` for correlation when creating reservations.
5. Handle `400` errors by mapping `ProblemDetail.errors` or message to field-level messages.

---

## Security Considerations

* **Ownership Enforcement**: All endpoints resolve authenticated user ID and restrict data to that user. Customers are validated via Stripe metadata and optionally email fallback.
* **Feature Flag**: Treat `404` from billing endpoints as feature unavailability and disable billing UI accordingly.
* **Rate Limiting**: Surface friendly messaging when receiving `429`. Implement exponential backoff for repeated polling.
* **Token Handling**: Store JWTs securely (HttpOnly cookies or secure storage). Refresh tokens proactively.
* **Stripe Data**: Never expose secret keys in frontend. Use `publishableKey` from config and server-provided `clientSecret` only.
* **Error Surfacing**: Display `detail` from `ProblemDetail` for user-facing errors. Avoid logging sensitive details on the client.
* **Resilience**: Retry transient `500`/`503` errors after short delay; alert users when configuration errors persist.

This guide equips frontend developers to integrate billing capabilities confidently while honoring backend contracts and safeguards.