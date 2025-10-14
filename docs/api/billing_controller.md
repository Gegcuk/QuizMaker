# Billing Controller API Reference

Complete frontend integration guide for `/api/v1/billing` REST endpoints. This document is self-contained and includes all DTOs, validation rules, feature flags, permissions, rate limits, and error semantics needed to integrate billing and subscription features.

## Table of Contents

- [Overview](#overview)
- [Authorization Matrix](#authorization-matrix)
- [Feature Flag Behavior](#feature-flag-behavior)
- [Rate Limits](#rate-limits)
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
* **Authentication**: Required for all endpoints. Uses JWT Bearer token in `Authorization` header.
* **Authorization**: Permission-based. Requires either `BILLING_READ` or `BILLING_WRITE`.
* **Feature Flags**: Billing can be disabled system-wide. Disabled endpoints return `404 Not Found`.
* **Content-Type**: `application/json` for requests and responses
* **Rate Limiting**: Per-endpoint limits. Violations return `429 Too Many Requests` with `Retry-After` header.
* **Error Format**: RFC-9457 `ProblemDetail` format with domain-specific properties
* **Stripe Integration**: Handles payments, subscriptions, and customer management via Stripe API

---

## Authorization Matrix

| Capability | Endpoint(s) | Required Permission(s) | Rate Limit | Notes |
| --- | --- | --- | --- | --- |
| **Read billing config** | `GET /config` | None (authenticated) | - | Returns Stripe publishable key and token packs |
| **Check session status** | `GET /checkout-sessions/{sessionId}` | `BILLING_READ` | - | Ownership enforced |
| **View balance** | `GET /balance` | `BILLING_READ` | 60/min | Current token balance |
| **List transactions** | `GET /transactions` | `BILLING_READ` | 30/min | Transaction history |
| **Estimate costs** | `POST /estimate/quiz-generation` | `BILLING_READ` | 10/min | Token cost estimation |
| **Create checkout** | `POST /checkout-sessions` | `BILLING_WRITE` | 5/min | Purchase tokens |
| **Create customer** | `POST /create-customer` | `BILLING_WRITE` | 3/min | Stripe customer creation |
| **View customer** | `GET /customers/{customerId}` | `BILLING_READ` | - | Ownership verified |
| **Create subscription** | `POST /create-subscription` | `BILLING_WRITE` | - | Start subscription |
| **Update subscription** | `POST /update-subscription` | `BILLING_WRITE` | - | Change subscription plan |
| **Cancel subscription** | `POST /cancel-subscription` | `BILLING_WRITE` | - | Cancel subscription |

**Ownership Rules**:
- All endpoints automatically filter by authenticated user
- Balance and transactions are user-specific
- Checkout sessions validated against user ID
- Stripe customers validated via metadata or email

---

## Feature Flag Behavior

Billing functionality can be globally enabled or disabled.

### When Billing is Disabled

Affected endpoints return `404 Not Found`:
- `GET /config`
- `GET /checkout-sessions/{sessionId}`
- `GET /balance`
- `GET /transactions`
- `POST /estimate/quiz-generation`
- `POST /checkout-sessions`

### Always Available (Not Affected by Flag)

These endpoints remain accessible for Stripe integration:
- `POST /create-customer`
- `GET /customers/{customerId}`
- `POST /create-subscription`
- `POST /update-subscription`
- `POST /cancel-subscription`

### Integration Strategy

```javascript
// Check if billing is enabled
const checkBillingAvailable = async () => {
  try {
    const response = await fetch('/api/v1/billing/config', {
      headers: { 'Authorization': `Bearer ${token}` }
    });
    
    if (response.status === 404) {
      console.log('Billing is disabled');
      return false;
    }
    
    return response.ok;
  } catch (error) {
    return false;
  }
};
```

---

## Rate Limits

| Endpoint | Limit | Scope | Notes |
| --- | --- | --- | --- |
| `GET /balance` | 60 requests/min | Per user | High limit for real-time balance checks |
| `GET /transactions` | 30 requests/min | Per user | Transaction history pagination |
| `POST /estimate/quiz-generation` | 10 requests/min | Per user | Prevent estimation abuse |
| `POST /checkout-sessions` | 5 requests/min | Per user | Prevent checkout spam |
| `POST /create-customer` | 3 requests/min | Per user | Stripe customer creation |

**Handling Rate Limits**:
- Server returns `429 Too Many Requests`
- Check `Retry-After` header for wait time (seconds)
- Implement exponential backoff for retries
- Cache balance/transaction data to reduce requests

---

## Request DTOs

### CreateCheckoutSessionRequest

**Used by**: `POST /checkout-sessions`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `priceId` | string | Yes | Non-blank | Stripe price ID from config |
| `packId` | UUID | No | - | Internal pack reference |

**Example**:
```json
{
  "priceId": "price_1234567890abcdef",
  "packId": "550e8400-e29b-41d4-a716-446655440000"
}
```

---

### CreateCustomerRequest

**Used by**: `POST /create-customer`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `email` | string | Yes | Valid email format | Customer email address |

**Example**:
```json
{
  "email": "user@example.com"
}
```

---

### CreateSubscriptionRequest

**Used by**: `POST /create-subscription`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `priceId` | string | Yes | Non-blank | Stripe price ID for subscription plan |

**Example**:
```json
{
  "priceId": "price_monthly_pro_plan"
}
```

---

### UpdateSubscriptionRequest

**Used by**: `POST /update-subscription`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `subscriptionId` | string | Yes | Non-blank | Stripe subscription ID to update |
| `newPriceLookupKey` | string | Yes | Non-blank | Lookup key for new price tier |

**Example**:
```json
{
  "subscriptionId": "sub_1234567890abcdef",
  "newPriceLookupKey": "premium_monthly"
}
```

**Notes**:
- Lookup key resolved to price ID server-side
- Updates subscription to new price/plan immediately

---

### CancelSubscriptionRequest

**Used by**: `POST /cancel-subscription`

| Field | Type | Required | Validation | Description |
| --- | --- | --- | --- | --- |
| `subscriptionId` | string | Yes | Non-blank | Stripe subscription ID to cancel |

**Example**:
```json
{
  "subscriptionId": "sub_1234567890abcdef"
}
```

**Notes**:
- Cancellation is immediate
- No pro-rated refunds in current implementation

---

### GenerateQuizFromDocumentRequest

**Used by**: `POST /estimate/quiz-generation`

| Field | Type | Required | Validation | Default | Description |
| --- | --- | --- | --- | --- | --- |
| `documentId` | UUID | Yes | Valid UUID | - | Document to generate from |
| `quizScope` | `QuizScope` enum | No | Valid scope | `ENTIRE_DOCUMENT` | Content scope |
| `chunkIndices` | array of integers | Conditional | Required for SPECIFIC_CHUNKS | `null` | Selected chunks |
| `chapterTitle` | string | Conditional | For SPECIFIC_CHAPTER | `null` | Chapter identifier |
| `chapterNumber` | integer | Conditional | Alternative to title | `null` | Chapter number |
| `questionsPerType` | object (map) | Yes | 1-10 per type | - | Question distribution |
| `difficulty` | `Difficulty` enum | Yes | Valid difficulty | - | Question difficulty |
| `language` | string | No | ISO 639-1 code | `en` | Target language |

**Example (Entire Document)**:
```json
{
  "documentId": "doc-uuid-here",
  "quizScope": "ENTIRE_DOCUMENT",
  "questionsPerType": {
    "MCQ_SINGLE": 5,
    "TRUE_FALSE": 3,
    "OPEN": 2
  },
  "difficulty": "MEDIUM",
  "language": "en"
}
```

**Example (Specific Chunks)**:
```json
{
  "documentId": "doc-uuid-here",
  "quizScope": "SPECIFIC_CHUNKS",
  "chunkIndices": [0, 2, 5, 7],
  "questionsPerType": {
    "MCQ_SINGLE": 3,
    "TRUE_FALSE": 2
  },
  "difficulty": "EASY",
  "language": "en"
}
```

---

## Response DTOs

### ConfigResponse

**Returned by**: `GET /config`

| Field | Type | Description |
| --- | --- | --- |
| `publishableKey` | string | Stripe publishable key for client-side SDK |
| `prices` | array of `PackDto` | Available token packs |

**Example**:
```json
{
  "publishableKey": "pk_test_1234567890abcdef",
  "prices": [
    {
      "id": "pack-uuid-1",
      "name": "Starter Pack",
      "tokens": 100,
      "priceCents": 999,
      "currency": "usd",
      "stripePriceId": "price_starter"
    },
    {
      "id": "pack-uuid-2",
      "name": "Pro Pack",
      "tokens": 500,
      "priceCents": 3999,
      "currency": "usd",
      "stripePriceId": "price_pro"
    },
    {
      "id": "pack-uuid-3",
      "name": "Enterprise Pack",
      "tokens": 2000,
      "priceCents": 12999,
      "currency": "usd",
      "stripePriceId": "price_enterprise"
    }
  ]
}
```

---

### PackDto

**Embedded in**: `ConfigResponse`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Internal pack identifier |
| `name` | string | Display name (e.g., "Starter Pack") |
| `tokens` | integer (long) | Number of tokens included |
| `priceCents` | integer (long) | Price in cents (999 = $9.99) |
| `currency` | string | ISO currency code (e.g., "usd") |
| `stripePriceId` | string | Stripe price reference |

---

### CheckoutSessionResponse

**Returned by**: `POST /checkout-sessions`

| Field | Type | Description |
| --- | --- | --- |
| `url` | string | Stripe-hosted checkout URL (redirect user here) |
| `sessionId` | string | Stripe session ID (use for status polling) |

**Example**:
```json
{
  "url": "https://checkout.stripe.com/c/pay/cs_test_a1b2c3d4...",
  "sessionId": "cs_test_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

**Usage**:
- Redirect user to `url` for payment
- Poll status using `sessionId`

---

### CheckoutSessionStatus

**Returned by**: `GET /checkout-sessions/{sessionId}`

| Field | Type | Description |
| --- | --- | --- |
| `sessionId` | string | Stripe session identifier |
| `status` | string | Session status: `open`, `complete`, `expired` |
| `credited` | boolean | Whether tokens were credited to account |
| `creditedTokens` | integer (long) or null | Tokens credited (null if not credited) |

**Example (Complete & Credited)**:
```json
{
  "sessionId": "cs_test_a1b2c3d4...",
  "status": "complete",
  "credited": true,
  "creditedTokens": 100
}
```

**Example (In Progress)**:
```json
{
  "sessionId": "cs_test_a1b2c3d4...",
  "status": "open",
  "credited": false,
  "creditedTokens": null
}
```

---

### BalanceDto

**Returned by**: `GET /balance`

| Field | Type | Description |
| --- | --- | --- |
| `userId` | UUID | User identifier |
| `availableTokens` | integer (long) | Tokens available for use |
| `reservedTokens` | integer (long) | Tokens held for active operations |
| `updatedAt` | ISO 8601 datetime | Last balance update time |

**Example**:
```json
{
  "userId": "user-uuid-here",
  "availableTokens": 450,
  "reservedTokens": 50,
  "updatedAt": "2024-01-15T10:30:45Z"
}
```

**Notes**:
- `availableTokens`: Can be spent immediately
- `reservedTokens`: Held for pending quiz generations
- Total balance = availableTokens + reservedTokens

---

### TransactionDto

**Returned by**: `GET /transactions`

| Field | Type | Description |
| --- | --- | --- |
| `id` | UUID | Transaction identifier |
| `userId` | UUID | User who owns this transaction |
| `type` | `TokenTransactionType` enum | Transaction type |
| `source` | `TokenTransactionSource` enum | Origin system |
| `amountTokens` | integer (long) | Token delta (positive = credit, negative = debit) |
| `refId` | string (nullable) | External reference (e.g., Stripe session ID) |
| `idempotencyKey` | string | Idempotency key used |
| `balanceAfterAvailable` | integer (long) | Available balance after transaction |
| `balanceAfterReserved` | integer (long) | Reserved balance after transaction |
| `metaJson` | string (nullable) | Additional metadata as JSON |
| `createdAt` | ISO 8601 datetime | Transaction timestamp |

**Example**:
```json
{
  "id": "trans-uuid-here",
  "userId": "user-uuid",
  "type": "PURCHASE",
  "source": "STRIPE",
  "amountTokens": 100,
  "refId": "cs_test_a1b2c3d4...",
  "idempotencyKey": "checkout_session_cs_test_a1b2c3d4...",
  "balanceAfterAvailable": 550,
  "balanceAfterReserved": 0,
  "metaJson": "{\"packId\":\"pack-uuid\",\"priceCents\":999}",
  "createdAt": "2024-01-15T10:30:45Z"
}
```

---

### EstimationDto

**Returned by**: `POST /estimate/quiz-generation`

| Field | Type | Description |
| --- | --- | --- |
| `estimatedLlmTokens` | integer (long) | Estimated AI tokens needed |
| `estimatedBillingTokens` | integer (long) | Converted billing tokens |
| `approxCostCents` | integer (long) or null | Approximate cost in cents (future feature) |
| `currency` | string | Currency code (e.g., "usd") |
| `estimate` | boolean | Always `true` (indicates this is an estimate) |
| `humanizedEstimate` | string | Human-readable summary |
| `estimationId` | UUID | Unique estimation identifier |

**Example**:
```json
{
  "estimatedLlmTokens": 1500,
  "estimatedBillingTokens": 2,
  "approxCostCents": null,
  "currency": "usd",
  "estimate": true,
  "humanizedEstimate": "~2 billing tokens (1,500 LLM tokens)",
  "estimationId": "estimation-uuid-here"
}
```

**Notes**:
- Billing tokens calculated from LLM tokens using conversion rate
- `humanizedEstimate` ready for UI display
- `approxCostCents` reserved for future pricing features

---

### CustomerResponse

**Returned by**: `POST /create-customer`, `GET /customers/{customerId}`

| Field | Type | Description |
| --- | --- | --- |
| `id` | string | Stripe customer ID |
| `email` | string | Customer email |
| `name` | string (nullable) | Customer name (if available) |

**Example**:
```json
{
  "id": "cus_1234567890abcdef",
  "email": "user@example.com",
  "name": "John Doe"
}
```

---

### SubscriptionResponse

**Returned by**: `POST /create-subscription`

| Field | Type | Description |
| --- | --- | --- |
| `subscriptionId` | string | Stripe subscription ID |
| `clientSecret` | string | Payment/setup intent client secret |

**Example**:
```json
{
  "subscriptionId": "sub_1234567890abcdef",
  "clientSecret": "seti_1234567890abcdef_secret_xyz"
}
```

**Notes**:
- Use `clientSecret` with Stripe.js to confirm payment
- `subscriptionId` used for updates and cancellations

---

### SubscriptionUpdateResponse

**Returned by**: `POST /update-subscription`, `POST /cancel-subscription`

Raw Stripe subscription JSON (formatted string, not structured DTO).

**Example**:
```json
{
  "id": "sub_1234567890abcdef",
  "object": "subscription",
  "status": "active",
  "current_period_end": 1705324800,
  "current_period_start": 1702732800,
  "plan": {
    "id": "price_premium_monthly",
    "amount": 1999,
    "currency": "usd"
  }
}
```

**Notes**:
- Response is Stripe's raw subscription object
- Parse as JSON to extract fields needed
- See Stripe API documentation for full schema

---

## Enumerations

### TokenTransactionType

| Value | Description |
| --- | --- |
| `PURCHASE` | Token purchase via checkout |
| `RESERVE` | Tokens reserved for operation |
| `COMMIT` | Reserved tokens committed (operation completed) |
| `RELEASE` | Reserved tokens released (operation cancelled) |
| `REFUND` | Tokens refunded to user |
| `ADJUSTMENT` | Manual balance adjustment (admin) |

---

### TokenTransactionSource

| Value | Description |
| --- | --- |
| `QUIZ_GENERATION` | Tokens used for AI quiz generation |
| `AI_CHAT` | Tokens used for AI chat |
| `ADMIN` | Administrative adjustment |
| `STRIPE` | Payment/purchase via Stripe |

---

### QuizScope

| Value | Description | Required Fields |
| --- | --- | --- |
| `ENTIRE_DOCUMENT` | Use entire document | None |
| `SPECIFIC_CHUNKS` | Use specific chunks | `chunkIndices` |
| `SPECIFIC_CHAPTER` | Use specific chapter | `chapterTitle` or `chapterNumber` |
| `SPECIFIC_SECTION` | Use specific section | `sectionTitle` |

---

### Difficulty

| Value | Description |
| --- | --- |
| `EASY` | Easy difficulty |
| `MEDIUM` | Medium difficulty |
| `HARD` | Hard difficulty |

---

### QuestionType

| Value | Description |
| --- | --- |
| `MCQ_SINGLE` | Multiple choice (single answer) |
| `MCQ_MULTI` | Multiple choice (multiple answers) |
| `TRUE_FALSE` | True/False question |
| `OPEN` | Open-ended text answer |
| `FILL_GAP` | Fill in the blank(s) |
| `ORDERING` | Put items in order |
| `MATCHING` | Match items between lists |
| `COMPLIANCE` | Compliance statements |
| `HOTSPOT` | Image hotspot selection |

---

## Endpoints

### Configuration & Session Status

#### 1. Get Billing Configuration

```
GET /api/v1/billing/config
```

**Authorization**: Authenticated user (no specific permission)

**Success Response**: `200 OK` - `ConfigResponse`

**Error Responses**:
- `404` - Billing disabled
- `503` - Configuration missing or misconfigured
- `500` - Unexpected error

**Example Usage**:
```javascript
const loadBillingConfig = async () => {
  const response = await fetch('/api/v1/billing/config', {
    headers: { 'Authorization': `Bearer ${token}` }
  });

  if (response.status === 404) {
    console.log('Billing not available');
    return null;
  }

  const config = await response.json();
  console.log('Stripe key:', config.publishableKey);
  console.log('Available packs:', config.prices);
  return config;
};
```

---

#### 2. Check Checkout Session Status

```
GET /api/v1/billing/checkout-sessions/{sessionId}
```

**Required Permission**: `BILLING_READ`

**Path Parameters**:
- `{sessionId}` - Stripe checkout session ID

**Success Response**: `200 OK` - `CheckoutSessionStatus`

**Error Responses**:
- `404` - Billing disabled or session not found
- `403` - Session belongs to another user
- `409` - Idempotency conflict

---

### Balance & Transactions

#### 3. Get Balance

```
GET /api/v1/billing/balance
```

**Required Permission**: `BILLING_READ`

**Rate Limit**: 60 requests/min

**Success Response**: `200 OK` - `BalanceDto`

**Headers**:
- `Cache-Control: private, max-age=30`

**Error Responses**:
- `404` - Billing disabled
- `429` - Rate limit exceeded
- `403` - Authorization error

**Example**:
```javascript
const getBalance = async () => {
  const response = await fetch('/api/v1/billing/balance', {
    headers: { 'Authorization': `Bearer ${token}` }
  });

  if (response.status === 429) {
    const retryAfter = response.headers.get('Retry-After');
    console.log(`Rate limited. Retry after ${retryAfter}s`);
    return null;
  }

  const balance = await response.json();
  console.log(`Available: ${balance.availableTokens}, Reserved: ${balance.reservedTokens}`);
  return balance;
};
```

---

#### 4. List Transactions

```
GET /api/v1/billing/transactions
```

**Required Permission**: `BILLING_READ`

**Rate Limit**: 30 requests/min

**Query Parameters**:
- `page` (integer, optional) - Page number (0-indexed), default: 0
- `size` (integer, optional) - Page size (1-100), default: 20
- `sort` (string, optional) - Sort specification, default: "createdAt,desc"
- `type` (`TokenTransactionType`, optional) - Filter by transaction type
- `source` (`TokenTransactionSource`, optional) - Filter by source
- `dateFrom` (ISO 8601, optional) - Start date filter
- `dateTo` (ISO 8601, optional) - End date filter

**Success Response**: `200 OK` - `Page<TransactionDto>`

**Headers**:
- `Cache-Control: private, max-age=60`

**Example URL**:
```
GET /api/v1/billing/transactions?page=0&size=20&type=PURCHASE&sort=createdAt,desc
```

**Error Responses**:
- `400` - Invalid enum or date format
- `404` - Billing disabled
- `429` - Rate limit exceeded

---

### Quiz Generation Estimation

#### 5. Estimate Generation Cost

```
POST /api/v1/billing/estimate/quiz-generation
```

**Required Permission**: `BILLING_READ`

**Rate Limit**: 10 requests/min

**Request Body**: `GenerateQuizFromDocumentRequest`

**Success Response**: `200 OK` - `EstimationDto`

**Error Responses**:
- `400` - Validation error
- `404` - Billing disabled or document not found
- `403` - No access to document
- `429` - Rate limit exceeded

**Example**:
```javascript
const estimateQuizCost = async (documentId, questionsPerType) => {
  const response = await fetch('/api/v1/billing/estimate/quiz-generation', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      documentId: documentId,
      quizScope: 'ENTIRE_DOCUMENT',
      questionsPerType: questionsPerType,
      difficulty: 'MEDIUM',
      language: 'en'
    })
  });

  const estimation = await response.json();
  console.log(estimation.humanizedEstimate);
  console.log(`Will use ~${estimation.estimatedBillingTokens} tokens`);
  
  return estimation;
};
```

---

### Checkout & Token Packs

#### 6. Create Checkout Session

```
POST /api/v1/billing/checkout-sessions
```

**Required Permission**: `BILLING_WRITE`

**Rate Limit**: 5 requests/min

**Request Body**: `CreateCheckoutSessionRequest`

**Success Response**: `200 OK` - `CheckoutSessionResponse`

**Error Responses**:
- `400` - Validation or Stripe error
- `404` - Billing disabled
- `409` - Idempotency conflict
- `429` - Rate limit exceeded

**Example Flow**:
```javascript
const purchaseTokens = async (priceId, packId) => {
  // 1. Create checkout session
  const response = await fetch('/api/v1/billing/checkout-sessions', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      priceId: priceId,
      packId: packId
    })
  });

  if (!response.ok) {
    const error = await response.json();
    console.error('Checkout creation failed:', error.detail);
    return;
  }

  const { url, sessionId } = await response.json();

  // 2. Redirect to Stripe
  window.location.href = url;

  // 3. After redirect back, poll status (on return page)
  const pollStatus = async () => {
    const statusResponse = await fetch(
      `/api/v1/billing/checkout-sessions/${sessionId}`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );

    const status = await statusResponse.json();

    if (status.status === 'complete' && status.credited) {
      console.log(`Success! ${status.creditedTokens} tokens added`);
      // Refresh balance
      await getBalance();
      return true;
    } else if (status.status === 'expired') {
      console.log('Checkout session expired');
      return false;
    }

    // Still processing
    await new Promise(r => setTimeout(r, 2000));
    return pollStatus();
  };

  return { sessionId, poll: pollStatus };
};
```

---

### Stripe Customer Management

#### 7. Create Stripe Customer

```
POST /api/v1/billing/create-customer
```

**Required Permission**: `BILLING_WRITE`

**Rate Limit**: 3 requests/min

**Request Body**: `CreateCustomerRequest`

**Success Response**: `200 OK` - `CustomerResponse`

**Error Responses**:
- `400` - Validation or Stripe error
- `429` - Rate limit exceeded

**Example**:
```javascript
const createStripeCustomer = async (email) => {
  const response = await fetch('/api/v1/billing/create-customer', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ email })
  });

  const customer = await response.json();
  console.log('Stripe customer created:', customer.id);
  return customer;
};
```

---

#### 8. Get Stripe Customer

```
GET /api/v1/billing/customers/{customerId}
```

**Required Permission**: `BILLING_READ`

**Path Parameters**:
- `{customerId}` - Stripe customer ID

**Success Response**: `200 OK` - `CustomerResponse`

**Error Responses**:
- `403` - Customer doesn't belong to user
- `400` - Stripe error
- `500` - User not found

**Ownership Validation**:
- Primary: Checks Stripe customer metadata `userId`
- Fallback: Compares email (if enabled by config)

---

### Stripe Subscriptions

#### 9. Create Subscription

```
POST /api/v1/billing/create-subscription
```

**Required Permission**: `BILLING_WRITE`

**Request Body**: `CreateSubscriptionRequest`

**Success Response**: `200 OK` - `SubscriptionResponse`

**Error Responses**:
- `400` - Validation or Stripe error
- `500` - User not found or unexpected error

**Example**:
```javascript
const subscribeToMonthlyPlan = async (priceId) => {
  // 1. Ensure customer exists (auto-created if needed)
  const response = await fetch('/api/v1/billing/create-subscription', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ priceId })
  });

  const { subscriptionId, clientSecret } = await response.json();

  // 2. Confirm with Stripe.js
  const stripe = Stripe(publishableKey);
  const { error } = await stripe.confirmPayment({
    clientSecret: clientSecret,
    confirmParams: {
      return_url: 'https://yourapp.com/billing/success'
    }
  });

  if (error) {
    console.error('Payment failed:', error.message);
  } else {
    console.log('Subscription created:', subscriptionId);
  }
};
```

---

#### 10. Update Subscription

```
POST /api/v1/billing/update-subscription
```

**Required Permission**: `BILLING_WRITE`

**Request Body**: `UpdateSubscriptionRequest`

**Success Response**: `200 OK` - Raw Stripe subscription JSON (string)

**Error Responses**:
- `400` - Validation, Stripe error, or price lookup failure
- `500` - Unexpected error

**Example**:
```javascript
const upgradeSubscription = async (subscriptionId, newPlanKey) => {
  const response = await fetch('/api/v1/billing/update-subscription', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      subscriptionId: subscriptionId,
      newPriceLookupKey: newPlanKey
    })
  });

  const subscription = await response.json();
  console.log('Updated to:', subscription.plan.id);
  return subscription;
};
```

---

#### 11. Cancel Subscription

```
POST /api/v1/billing/cancel-subscription
```

**Required Permission**: `BILLING_WRITE`

**Request Body**: `CancelSubscriptionRequest`

**Success Response**: `200 OK` - Raw Stripe subscription JSON (string)

**Error Responses**:
- `400` - Validation or Stripe error
- `500` - Unexpected error

**Example**:
```javascript
const cancelSubscription = async (subscriptionId) => {
  if (!confirm('Are you sure you want to cancel your subscription?')) {
    return;
  }

  const response = await fetch('/api/v1/billing/cancel-subscription', {
    method: 'POST',
    headers: {
      'Authorization': `Bearer ${token}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ subscriptionId })
  });

  const subscription = await response.json();
  console.log('Subscription cancelled:', subscription.status);
  return subscription;
};
```

---

## Error Handling

### ProblemDetail Format

All billing errors use RFC-9457 ProblemDetail format:

```json
{
  "type": "/problems/insufficient-tokens",
  "title": "Insufficient Tokens",
  "status": 400,
  "detail": "Insufficient tokens for operation. Required: 10, Available: 5",
  "instance": "/api/v1/billing/checkout-sessions",
  "requestedTokens": 10,
  "availableTokens": 5,
  "shortfall": 5
}
```

### Common HTTP Status Codes

| Code | Meaning | When to Expect |
| --- | --- | --- |
| `400` | Bad Request | Validation errors, insufficient tokens, Stripe errors, invalid arguments |
| `401` | Unauthorized | Missing or invalid authentication token |
| `403` | Forbidden | Resource ownership validation failed |
| `404` | Not Found | Billing disabled, resource not found |
| `409` | Conflict | Idempotency conflict (same key, different payload) |
| `429` | Too Many Requests | Rate limit exceeded |
| `503` | Service Unavailable | Configuration error |
| `500` | Internal Server Error | Unexpected errors, Stripe API failures |

### Domain-Specific Errors

#### Insufficient Tokens

```json
{
  "type": "/problems/insufficient-available-tokens",
  "status": 400,
  "detail": "Insufficient available tokens for operation",
  "requestedTokens": 10,
  "availableTokens": 5,
  "shortfall": 5
}
```

#### Idempotency Conflict

```json
{
  "type": "/problems/idempotency-conflict",
  "status": 409,
  "detail": "Idempotency key already used with different parameters",
  "instance": "/api/v1/billing/checkout-sessions"
}
```

#### Rate Limit Exceeded

```json
{
  "type": "/problems/rate-limit-exceeded",
  "status": 429,
  "detail": "Rate limit exceeded for balance endpoint. Limit: 60 requests per minute."
}
```

**Headers**: `Retry-After: 30`

#### Stripe Error

```json
{
  "type": "/problems/stripe-error",
  "status": 400,
  "detail": "Stripe API error: Invalid price ID",
  "stripeError": "No such price: 'price_invalid'"
}
```

#### Configuration Error

```json
{
  "type": "/problems/configuration-error",
  "status": 503,
  "detail": "Billing configuration is missing or invalid. Contact system administrator."
}
```

---

## Integration Guide

### Token Top-Up Flow

Complete flow for purchasing tokens:

```javascript
class TokenPurchaseFlow {
  async execute() {
    // Step 1: Load configuration
    const config = await this.loadConfig();
    if (!config) {
      this.showBillingUnavailable();
      return;
    }

    // Step 2: Display current balance
    const balance = await this.getBalance();
    this.displayBalance(balance);

    // Step 3: Show available packs
    this.displayPacks(config.prices);

    // Step 4: User selects pack
    const selectedPack = await this.waitForUserSelection(config.prices);

    // Step 5: Create checkout session
    const session = await this.createCheckout(selectedPack);
    if (!session) return;

    // Step 6: Redirect to Stripe
    window.location.href = session.url;
    
    // Step 7: On return, poll status
    // (This runs on the success return URL page)
    this.pollCheckoutStatus(session.sessionId);
  }

  async loadConfig() {
    const response = await fetch('/api/v1/billing/config', {
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (response.status === 404) return null;
    return await response.json();
  }

  async getBalance() {
    const response = await fetch('/api/v1/billing/balance', {
      headers: { 'Authorization': `Bearer ${this.token}` }
    });

    if (!response.ok) return { availableTokens: 0, reservedTokens: 0 };
    return await response.json();
  }

  async createCheckout(pack) {
    const response = await fetch('/api/v1/billing/checkout-sessions', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        priceId: pack.stripePriceId,
        packId: pack.id
      })
    });

    if (response.status === 429) {
      const retryAfter = response.headers.get('Retry-After');
      alert(`Please wait ${retryAfter} seconds before trying again`);
      return null;
    }

    if (!response.ok) {
      const error = await response.json();
      alert(`Error: ${error.detail}`);
      return null;
    }

    return await response.json();
  }

  async pollCheckoutStatus(sessionId) {
    const maxAttempts = 60; // 2 minutes max
    let attempts = 0;

    while (attempts < maxAttempts) {
      const response = await fetch(
        `/api/v1/billing/checkout-sessions/${sessionId}`,
        { headers: { 'Authorization': `Bearer ${this.token}` } }
      );

      if (!response.ok) break;

      const status = await response.json();

      if (status.status === 'complete' && status.credited) {
        this.showSuccess(status.creditedTokens);
        await this.refreshBalance();
        return true;
      } else if (status.status === 'expired') {
        this.showExpired();
        return false;
      }

      attempts++;
      await new Promise(r => setTimeout(r, 2000));
    }

    this.showTimeout();
    return false;
  }

  displayBalance(balance) {
    document.getElementById('available-tokens').textContent = balance.availableTokens;
    document.getElementById('reserved-tokens').textContent = balance.reservedTokens;
  }

  displayPacks(packs) {
    const container = document.getElementById('token-packs');
    container.innerHTML = packs.map(pack => `
      <div class="pack" data-price="${pack.stripePriceId}" data-id="${pack.id}">
        <h3>${pack.name}</h3>
        <p>${pack.tokens} tokens</p>
        <p>$${(pack.priceCents / 100).toFixed(2)} ${pack.currency.toUpperCase()}</p>
        <button>Purchase</button>
      </div>
    `).join('');
  }
}
```

---

### Subscription Management Flow

```javascript
class SubscriptionManager {
  async subscribe(planPriceId) {
    // 1. Create subscription
    const response = await fetch('/api/v1/billing/create-subscription', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ priceId: planPriceId })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.detail);
    }

    const { subscriptionId, clientSecret } = await response.json();

    // 2. Confirm payment with Stripe
    const stripe = Stripe(this.publishableKey);
    const result = await stripe.confirmPayment({
      clientSecret: clientSecret,
      confirmParams: {
        return_url: window.location.origin + '/billing/subscription-success'
      }
    });

    if (result.error) {
      throw new Error(result.error.message);
    }

    return subscriptionId;
  }

  async changePlan(subscriptionId, newPlanLookupKey) {
    const response = await fetch('/api/v1/billing/update-subscription', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        subscriptionId: subscriptionId,
        newPriceLookupKey: newPlanLookupKey
      })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.detail);
    }

    const subscription = await response.json();
    console.log('Updated to plan:', subscription.plan.id);
    return subscription;
  }

  async cancel(subscriptionId) {
    if (!confirm('Cancel subscription? Access continues until period end.')) {
      return;
    }

    const response = await fetch('/api/v1/billing/cancel-subscription', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${this.token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ subscriptionId })
    });

    if (!response.ok) {
      const error = await response.json();
      throw new Error(error.detail);
    }

    const subscription = await response.json();
    console.log('Subscription cancelled. Access until:', 
      new Date(subscription.current_period_end * 1000));
    return subscription;
  }
}
```

---

### Quiz Generation Estimation Flow

```javascript
const showGenerationEstimate = async (formData) => {
  try {
    const response = await fetch('/api/v1/billing/estimate/quiz-generation', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        documentId: formData.documentId,
        quizScope: formData.scope,
        chunkIndices: formData.chunkIndices,
        questionsPerType: formData.questionsPerType,
        difficulty: formData.difficulty,
        language: formData.language || 'en'
      })
    });

    if (!response.ok) {
      if (response.status === 404) {
        alert('Billing is not available');
        return;
      }
      
      const error = await response.json();
      alert(`Estimation failed: ${error.detail}`);
      return;
    }

    const estimation = await response.json();

    // Check if user has enough tokens
    const balance = await getBalance();
    const hasEnough = balance.availableTokens >= estimation.estimatedBillingTokens;

    // Display estimate
    const estimateUI = `
      <div class="estimation">
        <p><strong>Estimated Cost:</strong> ${estimation.humanizedEstimate}</p>
        <p>Your balance: ${balance.availableTokens} tokens</p>
        ${!hasEnough ? `
          <p class="warning">
            ⚠️ Insufficient tokens. 
            Need ${estimation.estimatedBillingTokens - balance.availableTokens} more.
          </p>
          <button onclick="purchaseTokens()">Buy Tokens</button>
        ` : `
          <p class="success">✓ Sufficient balance</p>
          <button onclick="generateQuiz()">Generate Quiz</button>
        `}
      </div>
    `;

    document.getElementById('estimate-container').innerHTML = estimateUI;

  } catch (error) {
    console.error('Estimation error:', error);
    alert('Failed to estimate cost');
  }
};
```

---

### Transaction History Display

```javascript
const loadTransactions = async (page = 0, filters = {}) => {
  const params = new URLSearchParams({
    page: page,
    size: 20,
    sort: 'createdAt,desc'
  });

  if (filters.type) params.append('type', filters.type);
  if (filters.source) params.append('source', filters.source);
  if (filters.dateFrom) params.append('dateFrom', filters.dateFrom);
  if (filters.dateTo) params.append('dateTo', filters.dateTo);

  const response = await fetch(
    `/api/v1/billing/transactions?${params}`,
    { headers: { 'Authorization': `Bearer ${token}` } }
  );

  if (response.status === 429) {
    const retryAfter = response.headers.get('Retry-After');
    alert(`Rate limited. Please wait ${retryAfter} seconds.`);
    return null;
  }

  const data = await response.json();
  displayTransactions(data.content);
  setupPagination(data.totalPages, page);
  return data;
};

const displayTransactions = (transactions) => {
  const html = transactions.map(tx => `
    <tr class="${tx.amountTokens > 0 ? 'credit' : 'debit'}">
      <td>${new Date(tx.createdAt).toLocaleString()}</td>
      <td>${tx.type}</td>
      <td>${tx.source}</td>
      <td>${tx.amountTokens > 0 ? '+' : ''}${tx.amountTokens}</td>
      <td>${tx.balanceAfterAvailable}</td>
      <td>${tx.refId || '-'}</td>
    </tr>
  `).join('');

  document.getElementById('transactions-table').innerHTML = html;
};
```

---

### Balance Monitoring

```javascript
class BalanceMonitor {
  constructor(token, updateCallback) {
    this.token = token;
    this.updateCallback = updateCallback;
    this.intervalId = null;
  }

  async start(intervalSeconds = 30) {
    // Initial load
    await this.update();

    // Periodic updates
    this.intervalId = setInterval(
      () => this.update(),
      intervalSeconds * 1000
    );
  }

  stop() {
    if (this.intervalId) {
      clearInterval(this.intervalId);
      this.intervalId = null;
    }
  }

  async update() {
    try {
      const response = await fetch('/api/v1/billing/balance', {
        headers: { 'Authorization': `Bearer ${this.token}` }
      });

      if (response.status === 429) {
        console.log('Balance check rate limited, skipping...');
        return;
      }

      if (!response.ok) {
        console.error('Failed to fetch balance');
        return;
      }

      const balance = await response.json();
      this.updateCallback(balance);
    } catch (error) {
      console.error('Balance update error:', error);
    }
  }
}

// Usage
const monitor = new BalanceMonitor(token, (balance) => {
  document.getElementById('balance').textContent = 
    `${balance.availableTokens} tokens`;
  
  // Warn if low
  if (balance.availableTokens < 10) {
    showLowBalanceWarning();
  }
});

monitor.start(30); // Update every 30 seconds

// Stop when leaving page
window.addEventListener('beforeunload', () => monitor.stop());
```

---

## Security Considerations

### Token Security

1. **Secure Storage**: Store JWT tokens in HttpOnly cookies or secure storage
2. **Token Refresh**: Implement refresh logic before expiration
3. **Logout Cleanup**: Clear all tokens on logout
4. **Token Validation**: All endpoints validate token signature and expiration

### Stripe Integration

1. **Publishable Key Only**: Never expose Stripe secret keys in frontend
2. **Client Secret Usage**: Use client secrets only with Stripe.js
3. **Session Validation**: Server validates all Stripe sessions
4. **Webhook Verification**: Stripe webhooks verified server-side

### Balance Protection

1. **Server-Side Validation**: All balance operations validated server-side
2. **Atomic Transactions**: Balance updates are atomic
3. **Idempotency**: Duplicate operations prevented via idempotency keys
4. **Audit Trail**: All transactions logged

### Data Privacy

1. **User Isolation**: Users can only see their own data
2. **Ownership Validation**: All operations validate user ownership
3. **Minimal Exposure**: Error messages don't leak sensitive data
4. **Metadata Protection**: Stripe metadata secured

### Rate Limiting

1. **Respect Limits**: Implement client-side throttling
2. **Retry-After**: Honor retry-after headers
3. **Exponential Backoff**: Implement for retries
4. **Cache Results**: Cache balance and transactions

### Best Practices

**Frontend Implementation**:
- Check feature flag before showing billing UI
- Cache configuration response
- Display balance in real-time
- Show token cost estimates before generation
- Implement loading states for async operations
- Handle rate limits gracefully
- Validate inputs client-side
- Use HTTPS for all requests

**Error Handling**:
- Parse ProblemDetail responses
- Display domain-specific error fields
- Handle 404 as "feature unavailable"
- Show user-friendly messages
- Log errors for debugging
- Implement retry for transient errors

**Stripe Integration**:
- Load Stripe.js from CDN
- Use publishable key from config
- Handle payment confirmation properly
- Show clear success/failure states
- Test with Stripe test mode first
- Implement webhook status updates

**Performance**:
- Cache config response (infrequently changing)
- Debounce balance checks
- Paginate transaction history
- Lazy load transaction details
- Minimize API calls

**Testing**:
- Test with Stripe test mode
- Test rate limit scenarios
- Verify ownership validation
- Test insufficient balance flows
- Test subscription upgrade/downgrade
- Verify idempotency

**Token Management**:
- Display current balance prominently
- Show reserved tokens separately
- Warn when balance low
- Suggest appropriate token packs
- Show transaction history
- Track token usage

**Subscription UX**:
- Show current plan clearly
- Display next billing date
- Show upgrade/downgrade options
- Confirm before cancellation
- Explain access continuation after cancel
- Handle payment failures gracefully

---

## Webhook Considerations

While not part of the public API, billing webhooks handle Stripe events:

**Events Handled**:
- `checkout.session.completed` - Credits tokens after payment
- `customer.subscription.updated` - Updates subscription status
- `customer.subscription.deleted` - Handles cancellations
- `payment_intent.succeeded` - Confirms payments

**Frontend Impact**:
- Balance may update without client action
- Poll balance after redirecting from Stripe
- Don't assume immediate token credit
- Show "processing" state during webhook handling

---

## Common Use Cases

### 1. Display Token Balance Badge

```javascript
const BalanceBadge = ({ token }) => {
  const [balance, setBalance] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const loadBalance = async () => {
      try {
        const response = await fetch('/api/v1/billing/balance', {
          headers: { 'Authorization': `Bearer ${token}` }
        });
        
        if (response.ok) {
          const data = await response.json();
          setBalance(data);
        }
      } catch (error) {
        console.error('Balance load error:', error);
      } finally {
        setLoading(false);
      }
    };

    loadBalance();
  }, [token]);

  if (loading) return <span>Loading...</span>;
  if (!balance) return null;

  const total = balance.availableTokens + balance.reservedTokens;
  const isLow = balance.availableTokens < 10;

  return (
    <div className={`balance-badge ${isLow ? 'low' : ''}`}>
      <span className="icon">🪙</span>
      <span className="count">{balance.availableTokens}</span>
      {balance.reservedTokens > 0 && (
        <span className="reserved">({balance.reservedTokens} reserved)</span>
      )}
      {isLow && <span className="warning">⚠️ Low balance</span>}
    </div>
  );
};
```

---

### 2. Token Pack Selection UI

```javascript
const TokenPackSelector = ({ config, onSelect }) => {
  return (
    <div className="pack-grid">
      {config.prices.map(pack => (
        <div key={pack.id} className="pack-card">
          <h3>{pack.name}</h3>
          <div className="tokens">
            <span className="amount">{pack.tokens}</span> tokens
          </div>
          <div className="price">
            ${(pack.priceCents / 100).toFixed(2)}
          </div>
          <div className="unit-price">
            ${((pack.priceCents / pack.tokens) / 100).toFixed(3)}/token
          </div>
          {pack.tokens >= 500 && (
            <div className="badge">Best Value</div>
          )}
          <button onClick={() => onSelect(pack)}>
            Select
          </button>
        </div>
      ))}
    </div>
  );
};
```

---

### 3. Transaction History Filter

```javascript
const TransactionHistory = () => {
  const [transactions, setTransactions] = useState([]);
  const [filters, setFilters] = useState({});
  const [page, setPage] = useState(0);

  const loadTransactions = async () => {
    const params = new URLSearchParams({
      page: page,
      size: 20
    });

    if (filters.type) params.append('type', filters.type);
    if (filters.source) params.append('source', filters.source);
    if (filters.dateFrom) params.append('dateFrom', filters.dateFrom);
    if (filters.dateTo) params.append('dateTo', filters.dateTo);

    const response = await fetch(
      `/api/v1/billing/transactions?${params}`,
      { headers: { 'Authorization': `Bearer ${token}` } }
    );

    if (response.ok) {
      const data = await response.json();
      setTransactions(data.content);
    }
  };

  return (
    <div>
      <div className="filters">
        <select onChange={(e) => setFilters({...filters, type: e.target.value})}>
          <option value="">All Types</option>
          <option value="PURCHASE">Purchases</option>
          <option value="RESERVE">Reservations</option>
          <option value="COMMIT">Commits</option>
          <option value="RELEASE">Releases</option>
          <option value="REFUND">Refunds</option>
        </select>

        <select onChange={(e) => setFilters({...filters, source: e.target.value})}>
          <option value="">All Sources</option>
          <option value="STRIPE">Stripe</option>
          <option value="QUIZ_GENERATION">Quiz Generation</option>
          <option value="ADMIN">Admin</option>
        </select>

        <button onClick={loadTransactions}>Apply Filters</button>
      </div>

      <table className="transactions">
        <thead>
          <tr>
            <th>Date</th>
            <th>Type</th>
            <th>Source</th>
            <th>Amount</th>
            <th>Balance After</th>
          </tr>
        </thead>
        <tbody>
          {transactions.map(tx => (
            <tr key={tx.id} className={tx.amountTokens > 0 ? 'credit' : 'debit'}>
              <td>{new Date(tx.createdAt).toLocaleString()}</td>
              <td>{tx.type}</td>
              <td>{tx.source}</td>
              <td>{tx.amountTokens > 0 ? '+' : ''}{tx.amountTokens}</td>
              <td>{tx.balanceAfterAvailable}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};
```

---

### 4. Cost Estimation Before Generation

```javascript
const QuizGenerationForm = () => {
  const [formData, setFormData] = useState({...});
  const [estimation, setEstimation] = useState(null);
  const [balance, setBalance] = useState(null);

  const estimateCost = async () => {
    const response = await fetch('/api/v1/billing/estimate/quiz-generation', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${token}`,
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(formData)
    });

    if (response.ok) {
      const est = await response.json();
      setEstimation(est);

      // Also fetch current balance
      const balResponse = await fetch('/api/v1/billing/balance', {
        headers: { 'Authorization': `Bearer ${token}` }
      });
      if (balResponse.ok) {
        setBalance(await balResponse.json());
      }
    }
  };

  const canAfford = balance && estimation && 
    balance.availableTokens >= estimation.estimatedBillingTokens;

  return (
    <div>
      {/* Form fields */}
      <button onClick={estimateCost}>Estimate Cost</button>

      {estimation && (
        <div className="cost-estimate">
          <h4>Estimated Cost</h4>
          <p>{estimation.humanizedEstimate}</p>
          
          {balance && (
            <>
              <p>Your balance: {balance.availableTokens} tokens</p>
              {!canAfford && (
                <div className="insufficient">
                  ⚠️ Insufficient tokens. 
                  Buy {estimation.estimatedBillingTokens - balance.availableTokens} more.
                  <button onClick={gotoBilling}>Buy Tokens</button>
                </div>
              )}
              {canAfford && (
                <button onClick={startGeneration}>
                  Generate Quiz ({estimation.estimatedBillingTokens} tokens)
                </button>
              )}
            </>
          )}
        </div>
      )}
    </div>
  );
};
```

---

## Troubleshooting

### Common Issues

**1. 404 on All Billing Endpoints**
- **Cause**: Billing feature flag disabled
- **Solution**: Check with system admin, or hide billing UI
- **Detection**: Try `GET /config` - 404 means disabled

**2. Rate Limit Errors**
- **Cause**: Too many requests in short time
- **Solution**: Implement request throttling and caching
- **Prevention**: Cache balance, use Retry-After header

**3. Insufficient Tokens Error**
- **Cause**: User doesn't have enough tokens
- **Solution**: Show purchase flow, display estimation upfront
- **UX**: Always estimate before allowing generation

**4. Checkout Session Expired**
- **Cause**: User took too long to complete payment
- **Solution**: Create new session, sessions expire after 24 hours
- **UX**: Show clear expiration message

**5. Customer Ownership Mismatch**
- **Cause**: Trying to access another user's Stripe customer
- **Solution**: Ensure correct user context
- **Prevention**: Server validates ownership

**6. Subscription Payment Failed**
- **Cause**: Payment method declined
- **Solution**: Update payment method in Stripe
- **UX**: Handle Stripe errors in confirmation flow

### Debug Checklist

- [ ] Valid authentication token provided
- [ ] User has `BILLING_READ` or `BILLING_WRITE` permission
- [ ] Billing feature flag is enabled (check /config)
- [ ] Not rate limited (check last request time)
- [ ] Request body valid JSON with required fields
- [ ] Stripe publishable key loaded from config
- [ ] Customer exists before subscription operations
- [ ] Idempotency keys unique per operation
- [ ] Network connectivity stable

---

## API Evolution Notes

### Current Limitations

1. **No Pro-Rated Refunds**: Cancellations are immediate without refund
2. **Cost Display**: `approxCostCents` not implemented (always null)
3. **No Payment Methods API**: Must use Stripe customer portal
4. **No Invoice History**: Transactions only show token movements
5. **No Usage Analytics**: No detailed token usage breakdown

### Future Features

- Token usage analytics per feature
- Detailed cost breakdown (cents per operation)
- Payment method management API
- Invoice download endpoints
- Usage predictions based on history
- Spending limits and alerts
- Multi-currency support
- Corporate billing with team accounts
- Usage-based pricing tiers

### Backward Compatibility

When integrating, handle:
- New optional fields in responses
- Additional transaction types
- New permission requirements
- Enhanced error details
- Additional Stripe webhook events

