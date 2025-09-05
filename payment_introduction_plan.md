# Payments & Token-Based Billing — Implementation Plan (Stripe)

## Objectives
- Monetize AI features via pre-paid tokens (credits) that users purchase and spend.
- Cover current AI quiz generation and enable future AI services (e.g., open-question auto-checking).
- Provide accurate token estimation, reservation, usage accounting, refunds/cancellations, and a full audit ledger.
- Integrate Stripe for payments, support test/dev flows, and secure webhooks.
- Make rounding, idempotency, and state transitions explicit to avoid edge-case double-spends.

## Scope (Phase 1 → Phase 2)
- Phase 1 (MVP):
  - Token balance + ledger + simple reservation/commit for quiz generation jobs.
  - Stripe Checkout Sessions for token packs, webhook to credit balances (single currency).
  - Balance and transactions endpoints, estimation endpoint, success-page reconciliation.
- Phase 2:
  - Granular per-call usage capture, partial refunds, admin dashboards, promotions/free grants, AI open-question checking consumption, multi-currency, and invoice exports.

---

## High-Level Architecture
- New feature module: `features/billing` (feature-first layout per docs/agents.md):
  - `api/`: controllers for balance, transactions, checkout sessions, webhook.
  - `application/`: token accounting, pricing/estimation, Stripe service, idempotency, reservation sweeper.
  - `domain/`: entities (Balance, TokenTransaction, Reservation, ProductPack, Payment, ProcessedStripeEvent), value objects, domain rules.
  - `infra/`: repositories (JPA), mappers (MapStruct), Stripe client adapter.
- Cross-feature integrations:
  - Quiz Generation: reserve at job start, commit actual usage at completion, release unused on failure/cancel.
  - Future: Attempt scoring (AI-checked answers) consumes from balance via the same accounting service.

---

## Domain Model (JPA, LAZY, optimistic locking)
- Balance
  - `id (UUID)`, `userId (UUID)`, `availableTokens (long)`, `reservedTokens (long)`, `version (long)`.
  - Invariant: `availableTokens >= 0` and `reservedTokens >= 0`.
- TokenTransaction (ledger)
  - `id (UUID)`, `userId`, `type (enum: PURCHASE, RESERVE, COMMIT, RELEASE, REFUND, ADJUSTMENT)`, `amountTokens (long, signed)`
  - `source (enum: QUIZ_GENERATION, AI_CHECK, ADMIN, STRIPE)`, `refId (UUID or String)`
  - `metaJson (JSON)`, `idempotencyKey (nullable, unique)`, `createdAt`.
  - Audit snapshots: `balanceAfterAvailable (long)`, `balanceAfterReserved (long)`.
  - Append-only; never update amounts post-creation (immutability).
- Reservation
  - `id (UUID)`, `userId`, `state (enum: ACTIVE, COMMITTED, RELEASED, CANCELLED, EXPIRED)`
  - `estimatedTokens (long)`, `committedTokens (long)`, `metaJson (JSON)`, `expiresAt (Instant)`
  - Optional: `jobId` linkage for quiz generation.
  - `version (long)` for optimistic locking.
- ProductPack
  - `id (UUID)`, `name`, `tokens (long)`, `priceCents (long)`, `currency (string)`, `stripePriceId (string, unique)`
  - Managed by admin or seeded via config.
- Payment
  - `id (UUID)`, `userId`, `status (enum: PENDING, SUCCEEDED, FAILED, REFUNDED)`
  - `stripeSessionId`, `stripePaymentIntentId`, `packId`, `amountCents`, `currency`, `createdAt`, `updatedAt`.
  - Snapshot: `creditedTokens (long)`, optional `stripeCustomerId (string)`, `sessionMetadata (JSON)`.
- ProcessedStripeEvent
  - `eventId (string, PK)`, `createdAt` — used for webhook replay/idempotency.

Notes
- Use `@Version` on Balance and Reservation for optimistic locking.
- Use projections/DTOs for list endpoints.
- MVP currency: single-currency (e.g., `usd`) only; multi-currency deferred.

---

## Reservation State Machine (must enforce)
- States: `ACTIVE → (COMMITTED | RELEASED | CANCELLED | EXPIRED)`; terminal states cannot transition further.
- Only `ACTIVE` reservations may be committed or released.
- `EXPIRED` is set by a background sweeper when `now() > expiresAt`; expiration triggers an automatic RELEASE of the reserved amount back to `available` (emit RELEASE transaction).
- Reject COMMIT if reservation is not `ACTIVE` or if `actualTokens > estimatedTokens` (Phase 1: no overage). The error should prompt users to top up or retry after adjusting safety factor.

---

## Pricing & Unit Definition
- Define “tokens” as a billing abstraction independent of model vendor.
- Conversion (configurable): `1 billing token` ~= `X LLM tokens` (prompt + completion) or use a “per-question” fixed cost for MVP.
  - MVP recommendation: per-AI-call accounting using model usage where available; fallback to estimation.
- Token Packs: define 3–4 packs with good price breaks (Small/Medium/Large/Pro) with Stripe Price IDs.
- Rounding: always use `ceil` for LLM→billing token conversion, both in estimates and in commits, to avoid under-billing.

---

## Token Estimation & Accounting
- Estimation inputs: document scope (chunks count), `questionsPerType`, difficulty, prompt template overhead.
- Estimation service responsibilities:
  - Estimate per-chunk LLM tokens: `system + user prompt` + expected completion (~questionCount × templateSize).
  - Sum across chunks; apply safety factor (e.g., 1.2x) for MVP.
  - Convert LLM tokens → billing tokens using configured ratio.
- Reservation at job start:
  - Check `availableTokens >= estimatedBillingTokens` else 402-like response (use 409 or 400 with ProblemDetail)
  - Move from available → reserved, emit RESERVE transaction with `reservationId`.
- During generation:
  - For each AI call: if usage stats present (OpenAI): accumulate `actualLLMTokens`. Keep in-memory and periodically persist into `Reservation.metaJson` (usage snapshot); optionally also attach to the associated job record for richer analytics.
  - On rate-limit or fatal error: do not exceed reservation; do not draw more.
- Commit on completion:
  - Compute `actualBillingTokens = ceil(actualLLMTokens / ratio)` server-side. If client provides `actualBillingTokens`, treat as an upper bound only and validate it is ≤ server-computed and ≤ reserved.
  - Deduct from reserved (no overage in Phase 1). Emit COMMIT transaction with snapshots; then release any remainder back to available via a RELEASE transaction.
- Failure/cancel:
  - Release entire reservation. Emit RELEASE transaction with reason.
- Idempotency:
  - All reserve/commit/release operations accept `Idempotency-Key` and store on TokenTransaction to ensure exactly-once.
- Ledger semantics:
  - Only PURCHASE/COMMIT/REFUND/ADJUSTMENT affect net token total. Record RESERVE/RELEASE as `amountTokens=0` but include snapshots and metadata to reflect state changes between available/reserved.

---

## API Surface (MVP)
All routes under `/api/v1/billing`, JSON bodies, ProblemDetail errors.
- GET `/balance`
  - Returns: `{ availableTokens, reservedTokens, currency, lastUpdated }` for current user.
- GET `/transactions`
  - Query: `page,size,sort` (default `createdAt,desc`), optional filters: `type`, `source`, `dateFrom`, `dateTo`.
  - Returns page of ledger entries for current user.
- GET `/packs`
  - Public or authed: returns purchasable packs with name, tokens, price, currency.
- POST `/checkout-session`
  - Body: `{ packId }` OR `{ stripePriceId }`.
  - Creates a Stripe Checkout Session (Mode=payment) with success/cancel URLs.
  - Returns: `{ checkoutUrl, sessionId }`.
- GET `/checkout-sessions/{sessionId}`
  - Auth required. Returns the server’s view of the Checkout Session outcome and whether the user’s balance has been credited. Validates session ownership by matching `client_reference_id`/`metadata.userId` with the current user; otherwise return 404/403. Used by the success page to reconcile in case webhook delivery is delayed.
- POST `/estimate/quiz-generation`
  - Body: same shape as `GenerateQuizFromDocumentRequest` plus optional `documentId`/scope.
  - Returns: `{ estimatedBillingTokens, estimatedLLMTokens, chunks, safetyFactor, currency, approxCostCents }` (round to whole cents; clearly label as estimate, not a quote).
- POST `/reservations`
  - Body: `{ estimatedBillingTokens, jobId? }` (server may compute estimate to verify).
  - Returns: `{ reservationId, expiresAt }`.
- POST `/reservations/{id}/commit`
  - Body: `{ actualLLMTokens?, actualBillingTokens?, jobId? }` (server validates ≤ reserved; converts if LLM tokens given; only ACTIVE may commit).
  - Returns: `{ committedTokens, releasedTokens }`.
- POST `/reservations/{id}/release`
  - Body: `{ reason, jobId? }` (only ACTIVE may release; EXPIRED is auto-released by sweeper).
  - Returns: `{ releasedTokens }`.
- POST `/stripe/webhook`
  - Verifies signature; handle only `checkout.session.completed` for crediting to avoid double-credits. Other events (e.g., `payment_intent.succeeded`, `charge.refunded`) are observed for logging/alerts and future use.
  - On `checkout.session.completed`: create Payment=SUCCEEDED, credit Balance, add PURCHASE transaction.

Security & Limits
- All billing endpoints require auth except `/packs` and `/stripe/webhook`.
- Rate limit POST endpoints (e.g., 30/min per user). `/checkout-session`: stricter limit, e.g., 2/min per user and per IP; consider captcha if abused.
- Use method security with permissions like `billing:read`, `billing:purchase`, `billing:manage` (admin).

---

## Stripe Integration (MVP)
- Library: add `com.stripe:stripe-java` to `pom.xml`.
- Use Checkout Sessions (server-side):
  - Create session with `price` (Stripe Price ID), `quantity=1`, `mode=payment`.
  - Provide `success_url` and `cancel_url` (from config), include current `userId` and `packId` in `client_reference_id`/`metadata`.
- Webhook handler:
  - Verify signature with `STRIPE_WEBHOOK_SECRET`.
  - On `checkout.session.completed` (canonical credit event):
    - Lookup user via `client_reference_id`/`metadata.userId`.
    - Resolve pack via `metadata.packId` or retrieve session with `expand=["line_items"]` to access `line_items.data[0].price.id`.
    - Create Payment(SUCCEEDED), credit user Balance with pack.tokens (store `creditedTokens`).
    - Add PURCHASE transaction with idempotency key = `sessionId` and snapshots.
  - Webhook idempotency:
    - Maintain a `processed_stripe_events` table keyed by `event.id` (unique). If seen, noop; ensures dedupe across workers/deployments and redeliveries.
  - Refunds/disputes (MVP policy): do not auto-debit if tokens already spent. Either disallow or create negative balance and block usage; document in ToS. Implement in Phase 2.

Success-page reconciliation
- After redirect to `success_url`, the client should call `GET /api/v1/billing/checkout-sessions/{sessionId}` to confirm Payment and balance credit in case the webhook is delayed or failed.

---

## Config & Secrets
- ENV (add to `.env`, `.env.example`, Spring config):
  - `STRIPE_SECRET_KEY`
  - `STRIPE_WEBHOOK_SECRET`
  - `STRIPE_SUCCESS_URL`, `STRIPE_CANCEL_URL`
  - `STRIPE_PRICE_SMALL`, `STRIPE_PRICE_MEDIUM`, `STRIPE_PRICE_LARGE` (or use DB ProductPack)
  - `BILLING_TOKEN_TO_LLM_RATIO` (e.g., 1 billing token = 1,000 LLM tokens)
  - `BILLING_RESERVATION_TTL_MINUTES` (e.g., 120)
  - `BILLING_SAFETY_FACTOR` (e.g., 1.2)
  - `BILLING_CURRENCY=usd` (MVP single currency)

---

## Data Flow — Quiz Generation
1) Client requests generation
- Backend estimates LLM and billing tokens (`AiQuizEstimationService`).
- BillingService reserves tokens; returns `reservationId`.
- Create `QuizGenerationJob` including `reservationId`.

2) Async generation
- For each Chat call:
  - Capture usage (if available from `ChatResponse`) and accumulate at job scope.
- On completion:
  - Commit reservation with `actualLLMTokens` (server computes billing tokens) and release remainder.
  - Update job status and ledger.
- On failure/cancellation:
  - Release reservation.

Edge cases
- If usage exceeds reservation (Phase 1): reject commit with a clear error. User must top up or retry with higher safety factor.
- If no usage reported: fallback to estimation (commit estimation) or a fixed per-question debit configured server-side.

---

## DB Migrations (Flyway)
- `Vxxx__billing_init.sql`:
  - `balances(user_id PK/FK, available_tokens BIGINT NOT NULL DEFAULT 0, reserved_tokens BIGINT NOT NULL DEFAULT 0, version BIGINT NOT NULL, updated_at)`
  - `token_transactions(id UUID PK, user_id FK, type, source, amount_tokens BIGINT NOT NULL, ref_id, idempotency_key UNIQUE NULL, meta_json JSON, balance_after_available BIGINT, balance_after_reserved BIGINT, created_at)`
  - `reservations(id UUID PK, user_id FK, state, estimated_tokens BIGINT NOT NULL DEFAULT 0, committed_tokens BIGINT NOT NULL DEFAULT 0, meta_json JSON NULL, expires_at, job_id UUID NULL, version BIGINT NOT NULL, created_at, updated_at)`
  - `product_packs(id UUID PK, name, tokens BIGINT, price_cents BIGINT, currency, stripe_price_id UNIQUE)`
  - `payments(id UUID PK, user_id FK, status, stripe_session_id UNIQUE, stripe_payment_intent_id UNIQUE NULL, pack_id FK NULL, amount_cents BIGINT, currency, credited_tokens BIGINT NOT NULL DEFAULT 0, stripe_customer_id VARCHAR NULL, session_metadata JSON NULL, created_at, updated_at)`
  - `processed_stripe_events(event_id VARCHAR PRIMARY KEY, created_at)`
  - Indexes: `idx_tx_user_created_at`, `idx_tx_idempotency_key` (unique), `idx_res_user_state`, `idx_res_expires_at`, `idx_pay_user_created_at`.

---

## Application Services (contracts)
- BillingService
  - `getBalance(userId)` → BalanceDto
  - `listTransactions(userId, pageable)` → Page<TransactionDto>
  - `reserve(userId, estimatedBillingTokens, ref)` → ReservationDto
  - `commit(reservationId, actualTokens, ref)` → CommitResultDto
  - `release(reservationId, reason, ref)` → ReleaseResultDto
  - `creditPurchase(userId, pack, sessionId)` (idempotent)
- EstimationService
  - `estimateQuizGeneration(documentId, request)` → EstimationDto
  - `llmTokensToBillingTokens(llmTokens)`; inverse conversion
- StripeService
  - `createCheckoutSession(userId, packId|priceId)` → `{ url, sessionId }`
  - `handleWebhook(payload, signature)` → void
- ReservationSweeper
  - `expireAndRelease()` scheduled job to move past-due `ACTIVE` reservations to `EXPIRED` and emit RELEASE transactions.

Implementation notes
- Use `@Transactional` with appropriate readOnly flags.
- Use `@PreAuthorize` on BillingService methods for access control.
- MapStruct for DTOs; ProblemDetail for errors.
- On `OptimisticLockException` during reserve/commit, retry the operation once with a short backoff; if it still fails, return a clear error to the client.

---

## Integration Points in Current Codebase
- `src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java`
  - Gate all generation start endpoints with a call to EstimationService + BillingService.reserve.
  - Propagate `reservationId` to job creation and return it in `QuizGenerationResponse` if useful.
- `src/main/java/uk/gegc/quizmaker/features/quiz/application/impl/QuizServiceImpl.java`
  - In `startQuizGeneration(...)`: estimate → reserve → create job with reservation → start async.
  - On cancel: call `BillingService.release(reservationId, reason)`.
- `src/main/java/uk/gegc/quizmaker/features/ai/application/impl/AiQuizGenerationServiceImpl.java`
  - After each `ChatClient` call, extract `ChatResponse` usage if available; track totals.
  - On job completion: `BillingService.commit(reservationId, actualLLMTokens)`.
  - On failure: `BillingService.release(reservationId, error)`.

---

## Security, Auditing, and Compliance
- Secure webhooks: verify signature; process only explicitly allowed event types (`checkout.session.completed`).
- Validate tenant/user correlation via `client_reference_id` or `metadata.userId`.
- Avoid PII in logs; log IDs only (sessionId, reservationId, jobId). Never store cardholder data in `metaJson`.
- Permissions:
  - Users: `billing:read`, `billing:purchase` (default allow for authenticated users)
  - Admins: `billing:manage` for packs and adjustments.
- Add correlation IDs and idempotency for all mutating billing endpoints.

Error Taxonomy (ProblemDetail types)
- `insufficient-tokens`: attempting to reserve without enough available tokens (409 Conflict).
- `reservation-not-active`: commit/release called on non-ACTIVE reservation (409 Conflict).
- `commit-exceeds-reserved`: actual usage exceeds reserved tokens (409 Conflict, Phase 1 policy).
- `pack-not-found`: invalid packId/priceId (404 Not Found).
- `invalid-checkout-session`: session not found or not owned by current user (404/403).
- `stripe-webhook-invalid-signature`: signature verification failed (400).
- `stripe-webhook-duplicate-event`: event already processed (200 OK with no-op semantics).
- `idempotency-conflict`: duplicate idempotency key with different payload (409 Conflict).

Transaction refId conventions
- PURCHASE: `refId = paymentId` (or `stripeSessionId`); `source = STRIPE`.
- RESERVE: `refId = reservationId`; `source = QUIZ_GENERATION` (or feature).
- COMMIT: `refId = reservationId`; include `jobId` in `metaJson`.
- RELEASE: `refId = reservationId`; include release reason in `metaJson`.
- REFUND: `refId = paymentId/chargeId` (Phase 2); `source = STRIPE`.
- ADJUSTMENT: `refId = adjustmentId` (admin action id) with audit reason in `metaJson`.

---

## Observability & Admin
- Metrics: reservations created, commits, releases, purchase volume, conversion rate, webhook failures, processed event dedupes.
- Track estimate vs commit ratio (p95/p99) to tune `billing.safetyFactor`.
- Admin endpoints:
  - CRUD ProductPack (Phase 2) `/api/v1/admin/billing/packs`.
  - Manual adjustments `/api/v1/admin/billing/adjustments` (requires reason and audit note).
- Reports (Phase 2): daily ledger summaries, top customers, churn.

---

## Phased Rollout
- Phase 1 (1–2 sprints):
  1) DB migrations and entities
  2) Balance + ledger + reserve/commit/release services
  3) Public packs + checkout-session + webhook (credit on success)
  4) Estimation endpoint
  5) Integrate reservations into quiz generation paths
  6) Basic UI: balance display and purchase flow
- Phase 2:
  - Granular per-call usage, promos/free credits, admin dashboards, refunds, multi-currency, AI open-answer checking consumption.

---

## Testing Strategy
- Unit tests: accounting invariants, idempotency, StripeService mapping, estimation math.
- Integration tests:
  - Webhook signature verification
  - Reserve/commit across concurrent jobs (optimistic locking on Balance). Test double-commit attempts across two workers to ensure state checks + versioning prevent double-spend.
  - Generation happy path: reserve → commit → ledger entries emitted
  - Failure path: reserve → error → release
- E2E smoke: buy pack (test mode) → see balance → run generation → tokens deducted.
- Webhook replay: simulate duplicate `checkout.session.completed` deliveries and ensure only one PURCHASE is emitted (processed events dedupe + idempotency key).

---

## Short Stripe Usage Instructions
- Create a Stripe account and get test keys.
- Create Products and Prices (recurring or one-time) for token packs in Stripe Dashboard.
- Save the Price IDs to app config (`STRIPE_PRICE_*`) or persist in ProductPack.
- Backend:
  - Add `com.stripe:stripe-java` dependency.
  - Set `STRIPE_SECRET_KEY` and `STRIPE_WEBHOOK_SECRET` in environment.
  - Implement `POST /api/v1/billing/checkout-session` to create a Checkout Session with the desired `price`.
  - Implement `POST /api/v1/billing/stripe/webhook` and verify signatures. On `checkout.session.completed`, credit the user’s balance (do not credit on `payment_intent.succeeded`).
  - On success page, call `GET /api/v1/billing/checkout-sessions/{sessionId}` to reconcile if webhook is delayed.
- Local dev:
  - Install Stripe CLI, run: `stripe login` then `stripe listen --forward-to http://localhost:8080/api/v1/billing/stripe/webhook`.
  - Use Stripe test cards (e.g., 4242 4242 4242 4242) to complete a test payment.

---

## Clarifying Decisions (recommended defaults)
- Rounding: use `ceil` consistently in estimation and commit.
- Overages (Phase 1): disallow; fail early and show top-up path.
- Starter tokens: consider a small non-renewable grant (ADJUSTMENT with audit reason) to de-risk the flow.
- Refunds: “No refunds for spent tokens; unused tokens refundable within X days” keeps accounting simple.

---

## Appendix — Example Config Keys
- `billing.tokenToLlmRatio=1000` (1 billing token = 1,000 LLM tokens)
- `billing.reservationTtlMinutes=120`
- `billing.safetyFactor=1.2`
- `billing.currency=usd`
- `stripe.secretKey=sk_test_...`
- `stripe.webhookSecret=whsec_...`
- `stripe.successUrl=https://app.example.com/billing/success`
- `stripe.cancelUrl=https://app.example.com/billing/cancel`
- `stripe.price.small=price_...`
- `stripe.price.medium=price_...`
- `stripe.price.large=price_...`

---

## Step-by-Step Implementation Plan (MVP)

1) Project Setup & Configuration
- Add Stripe SDK dependency to build (stripe-java). Ensure versions align with Spring Boot 3.x.
- Add configuration keys to application config and `.env.example` as defined in “Config & Secrets”.
- Create `features/billing` module structure (api/application/domain/infra) following feature-first layout.
- Wire Spring configuration properties for billing (ratio, safetyFactor, reservationTtlMinutes, currency) and Stripe (keys, URLs).
- Register required beans (Stripe client factory/config, rate limiting for billing endpoints if not global).

2) Database Migrations (Flyway)
- Create `Vxxx__billing_init.sql` with tables and indexes exactly as specified in “DB Migrations (Flyway)”.
- Verify constraints: unique idempotency key on `token_transactions`, PK on `processed_stripe_events.event_id`, FKs to `users` for balances/payments.
- Run migrations locally; validate schema and index creation.

3) Domain & Repositories
- Define JPA entities for Balance, TokenTransaction, Reservation (with metaJson), ProductPack, Payment, ProcessedStripeEvent (`@Version` on Balance/Reservation).
- Add repositories/query methods:
  - Balance by userId.
  - TokenTransaction paginated reads with filters (type, source, date range).
  - Reservation by id/user/state and by `expiresAt` for sweeper.
  - ProductPack by id and by stripePriceId.
  - Payment by stripeSessionId/intentId and by userId.
  - ProcessedStripeEvent existence check and insert.

4) DTOs & Mapping
- Create DTOs for Balance, Transaction, Reservation, CommitResult, ReleaseResult, Estimation, Pack, CheckoutSessionResponse, CheckoutSessionStatus.
- Add mappers (MapStruct or manual) for entity↔DTO conversions.

5) BillingService (Token Accounting)
- getBalance(userId): return balance, creating zeroed balance if needed.
- listTransactions(userId, pageable, filters): implement repository paging + filtering.
- reserve(userId, estimatedTokens, ref, idempotencyKey):
  - Load balance (optimistic lock); validate `available >= estimate`.
  - Move `available → reserved`; persist Reservation(ACTIVE) with `estimatedTokens`, `expiresAt`, `metaJson=null`.
  - Emit RESERVE transaction (amountTokens=0) with snapshots + idempotencyKey.
  - Retry once on OptimisticLock; otherwise return ProblemDetail.
- commit(reservationId, actualLLMTokens|actualBillingTokens, ref, idempotencyKey):
  - Validate Reservation ACTIVE; compute `actualBillingTokens = ceil(actualLLMTokens/ratio)` server-side; require ≤ reserved (Phase 1 no overage).
  - Load balance; decrease reserved by committed; set Reservation=COMMITTED with `committedTokens`.
  - Emit COMMIT transaction (amountTokens=committed) with snapshots + idempotencyKey.
  - If remainder exists, emit RELEASE transaction (amountTokens=0) for the difference with updated snapshots.
  - Retry once on OptimisticLock.
- release(reservationId, reason, ref, idempotencyKey):
  - Validate Reservation ACTIVE; load balance; move reserved → available; set Reservation=RELEASED.
  - Emit RELEASE transaction (amountTokens=0) with snapshots + idempotencyKey.
  - Retry once on OptimisticLock.
- creditPurchase(userId, pack, sessionId, idempotencyKey):
  - Ensure idempotency via processed events or existing Payment/Transaction; upsert Payment(SUCCEEDED) snapshotting `creditedTokens`.
  - Increase available by pack.tokens; emit PURCHASE transaction (amountTokens=+tokens) with snapshots + idempotencyKey.

6) EstimationService
- estimateQuizGeneration(documentId, request):
  - Determine chunks for scope via document services.
  - Compute prompt+completion per chunk using `questionsPerType` and difficulty; apply safetyFactor.
  - Convert LLM→billing via ratio with ceil; compute `approxCostCents` if applicable; return Estimation with “estimate, not quote”.

7) StripeService & Webhook
- createCheckoutSession(userId, packId|priceId): validate pack; build session with `client_reference_id` and `metadata` (userId, packId); return URL + sessionId.
- webhook handler:
  - Verify signature; allowlist `checkout.session.completed` only.
  - Dedupe via `processed_stripe_events.event_id`; noop if seen.
  - Retrieve session; resolve pack (metadata or expand `line_items`).
  - Upsert Payment to SUCCEEDED; call `creditPurchase` (idempotent); return 200.

8) API Controllers (Billing)
- GET /billing/balance (auth): returns balance.
- GET /billing/transactions (auth): pageable, filters `type,source,dateFrom,dateTo`.
- GET /billing/packs (public/auth): list packs.
- POST /billing/checkout-session (auth, stricter rate limit): returns redirect URL + sessionId.
- GET /billing/checkout-sessions/{sessionId} (auth): validate ownership; return credited status for reconciliation.
- POST /billing/estimate/quiz-generation (auth): returns estimate.
- POST /billing/reservations (auth): creates reservation.
- POST /billing/reservations/{id}/commit (auth): ACTIVE-only commit, ≤ reserved.
- POST /billing/reservations/{id}/release (auth): ACTIVE-only release.
- POST /billing/stripe/webhook (public): signature-verified, allowlisted.

9) Integrate with Quiz Generation Flow
- In startQuizGeneration path: estimate → reserve (Idempotency-Key) → create job with `reservationId` → start async.
- In AI async generation: accumulate actual usage; persist snapshots in `Reservation.metaJson` and job; on success `commit`; on failure/cancel `release` (both idempotent).
- On cancel endpoint: if reservation ACTIVE, call release.

10) Background Jobs & Housekeeping
- ReservationSweeper: periodically expire ACTIVE reservations where `now() > expiresAt`; auto-release and emit RELEASE transactions; log metrics.
- Monitor webhook failures; plan cleanup/archive policy for processed events (Phase 2).

11) Security, Rate Limits, Permissions
- Apply method security: `billing:read`, `billing:purchase`, `billing:manage` (admin for packs Phase 2).
- Enforce stricter limits on `/checkout-session` (e.g., 2/min per user and IP); reasonable defaults on other POSTs.
- Validate reconciliation session ownership via `client_reference_id`/`metadata.userId`.
- Use ProblemDetail everywhere; avoid PII in logs.

12) Observability & Metrics
- Publish counters/gauges: purchases, reservations (created/committed/released), webhook processed/dropped, estimate→commit ratio (p95/p99), OptimisticLock retries, commit rejections.
- Use correlation IDs (jobId, reservationId, sessionId); structured logs.
- Health/diagnostics: Stripe key presence; webhook signature configured; last webhook processed.

13) Seeding & Feature Flags
- Seed default ProductPacks (Small/Medium/Large) with Price IDs.
- Optional: starter tokens as ADJUSTMENT on user registration (with audit reason).
- Add feature flag to bypass billing for non-production/admin (emergency/testing).

14) Testing Plan
- Unit: balance invariants; ledger math; rounding; idempotency on reserve/commit/release; state transitions.
- Integration: webhook signature + event dedupe; concurrency double-commit prevention; generation happy/failure paths producing correct ledger and snapshots.
- E2E/manual: Stripe CLI session → payment → webhook credit → reconciliation endpoint; insufficient balance flow UX.

15) Rollout & Runbook
- Staging: use test keys; seed packs; run full E2E; tune safetyFactor from telemetry.
- Production: migrate schema; seed packs; configure webhook; deploy with billing flag off; flip on gradually; monitor metrics and error rates.
- Runbook: reprocess failed webhooks; manual balance adjustments; dispute handling policy (Phase 2).

16) Post-MVP Follow-ups
- Promotions/coupons; admin dashboards; refunds/chargebacks policy; multi-currency; invoice exports; expanded UI and analytics.

---

## Day-by-Day Implementation Plan (Steps 4–16)

Note: Steps 1–3 are complete. The schedule below splits remaining work into reasonable daily tasks, aligning with docs/agents.md conventions and allowing time for validation and fixes.

Day 1 — Step 4: DTOs & Mapping
- Add MapStruct dependency + processor to `pom.xml` (no codegen warnings).
- Create DTOs: BalanceDto, TransactionDto, ReservationDto, CommitResultDto, ReleaseResultDto, EstimationDto, PackDto, CheckoutSessionResponse, CheckoutSessionStatus.
- Implement MapStruct mappers (entity↔DTO) and wire with `componentModel="spring"`.
- Ensure global ProblemDetail advice covers new errors; quick compile check.

Day 2 — Step 5 (Part A): BillingService Core
- Define `BillingService` interface and Spring service skeleton.
- Implement `getBalance`, `listTransactions` (pageable + filters), and `reserve` with optimistic locking + single retry.
- Persist Reservation(ACTIVE) with TTL; append RESERVE ledger entry; minimal unit tests for invariants.

Day 3 — Step 5 (Part B): Commit & Release
- Implement `commit` (ceil conversion, ACTIVE-only, ≤ reserved, snapshots, optional remainder RELEASE).
- Implement `release` (ACTIVE-only, move reserved→available, ledger entry).
- Add idempotency handling (repository helpers) and tests for state machine + lock retry.

Day 4 — Step 6: EstimationService
- Implement `estimateQuizGeneration(documentId, request)` using document/chunk services.
- Apply safetyFactor and `ceil(llm/billingRatio)`; return EstimationDto with “estimate, not quote”.
- Add focused unit tests for rounding and scaling across chunk counts.

Day 5 — Step 7 (Part A): Stripe Checkout + Webhook Skeleton
- Implement `StripeService.createCheckoutSession(userId, packId|priceId)` with metadata, success/cancel URLs.
- Add webhook endpoint: verify signature with `stripe.webhookSecret`; allowlist `checkout.session.completed`.
- Persist `ProcessedStripeEvent` to dedupe; return 200 for duplicates; logs/metrics hooks.

Day 6 — Step 7 (Part B): Payments & Crediting
- Resolve pack from metadata or expanded line_items; upsert Payment→SUCCEEDED.
- Call `BillingService.creditPurchase(...)` idempotently; add reconciliation `GET /billing/checkout-sessions/{sessionId}`.
- Add integration tests (happy path + duplicate event) where feasible; otherwise controller tests + service unit tests.

Day 7 — Step 8: Billing API Controllers
- Implement endpoints: balance, transactions (filters), packs, checkout-session, checkout-session status, estimate, reservations(create/commit/release), stripe webhook.
- Use DTOs; correct HTTP semantics (201 on create). Apply `RateLimitService` stricter limits to `/checkout-session`.
- Controller tests with MockMvc for validation and ProblemDetail responses.

Day 8 — Step 9 (Part A): Quiz Generation Integration – Start
- In start path: call EstimationService + `BillingService.reserve`; propagate `reservationId` into `QuizGenerationJob`.
- Update responses if needed to include `reservationId`; compile + basic tests.

Day 9 — Step 9 (Part B): Quiz Generation Integration – Finish
- Accumulate actual model usage in async flow; on success `commit`, on failure/cancel `release`.
- Store usage snapshot in `Reservation.metaJson` and job; add unit/integration tests for both paths.

Day 10 — Step 10: Background Sweeper
- Implement `ReservationSweeper.expireAndRelease()` scheduled job; expire ACTIVE past TTL and emit RELEASE.
- Enable scheduling; add tests for expiration behavior and ledger entries.

Day 11 — Step 11: Security & Permissions
- Add authorities: `billing:read`, `billing:purchase`, `billing:manage`.
- Apply `@PreAuthorize` to service methods and secure endpoints; validate reconciliation ownership.
- Add rate limits to remaining POSTs with reasonable defaults.

Day 12 — Step 12: Observability & Metrics
- Publish metrics: purchases, reservation lifecycle, webhook processed/dropped, lock retries, commit rejections.
- Structured logs with correlation IDs; health indicator for Stripe key + last webhook processed.

Day 13 — Step 13: Seeding & Feature Flags
- Seed default `ProductPack` rows (idempotent) using configured Stripe Price IDs.
- Add feature flag to bypass billing in non-prod/admin; optional starter token grant path.

Day 14 — Step 14 (Part A): Unit & Repository Tests
- Unit tests: balance arithmetic, rounding, idempotency, state transitions, repository queries.
- DataJpaTest for projections/queries; MockMvc for controller validations.

Day 15 — Step 14 (Part B): E2E & Manual
- Stripe CLI flow: create checkout → payment → webhook credit → reconciliation endpoint.
- Test insufficient balance → reserve failure UX path; stabilize based on findings.

Day 16 — Step 15: Rollout & Runbook
- Flip `spring.jpa.hibernate.ddl-auto` to `validate` after confirming migrations.
- Staging smoke: telemetry review; finalize runbook (reprocess webhooks, manual adjustments, incident handling).

Day 17 — Step 16: Post‑MVP Backlog Grooming
- Create tickets for promotions, admin dashboards, refunds/chargebacks policy, multi-currency, invoice exports, analytics.
- Prioritize with size/acceptance criteria and dependencies.
