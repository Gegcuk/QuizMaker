# MVP Endpoints — Detailed Implementation Plan

## 📋 Overview

This document provides a comprehensive implementation plan for the MVP surface endpoints that are currently missing from the QuizMaker application. It includes detailed specifications, DTOs, business rules, testing strategies, and implementation guidelines.

## 🔧 Conventions (Apply to All)

### Authentication
- **JWT (Bearer)** in Authorization header
- **Anonymous allowed** only for public quiz view & share-link access

### Error Model
```json
{
  "timestamp": "2025-01-27T10:30:00Z",
  "path": "/api/v1/auth/register",
  "status": 400,
  "error": "Bad Request",
  "code": "VALIDATION_ERROR",
  "details": [
    "Email must be unique",
    "Password must contain at least 8 characters"
  ]
}
```

### Pagination
- **page**: 0-based
- **size**: max 50
- **sort**: field,direction (e.g., `createdAt,desc`)
- **Response includes**: content, page, size, totalElements, totalPages

### Idempotency
- For AI payment ops (reserve/commit/release) require `Idempotency-Key` header
- Store hash + result for 24h

### Rate Limits (MVP)
- **Auth/forgot/reset/resend**: 5/min per IP/email
- **Share-link access/consume**: 60/min per IP + token-hash
- **Search**: 120/min per IP

### ETag/Cache
- GET list/detail endpoints return ETag
- Honor `If-None-Match` with 304

### Validation
- Jakarta annotations in DTO + manual business rules
- Return 400 with details array

---

## 🔐 Auth & Account

### ✅ Already Implemented
- `POST /api/v1/auth/register`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`
- `GET /api/v1/auth/me`

### ❌ Missing Endpoints (MVP Add)

#### 1. `POST /api/v1/auth/forgot-password`

**Request:**
```json
{
  "email": "user@example.com"
}
```

**Response (202):**
```json
{
  "message": "If the email exists, a reset link was sent."
}
```

**Business Rules:**
- Issue reset token (TTL 1h)
- Rate-limit by email/IP (5/min)
- No observable errors (avoid user enumeration)
- Always return 202 regardless of email existence

**Implementation:**
```java
@PostMapping("/forgot-password")
@ResponseStatus(HttpStatus.ACCEPTED)
public ResponseEntity<ForgotPasswordResponse> forgotPassword(
    @Valid @RequestBody ForgotPasswordRequest request) {
    
    // Rate limiting check
    rateLimitService.checkRateLimit("forgot-password", request.email());
    
    // Generate reset token (if email exists)
    authService.generatePasswordResetToken(request.email());
    
    return ResponseEntity.accepted()
        .body(new ForgotPasswordResponse("If the email exists, a reset link was sent."));
}
```

#### 2. `POST /api/v1/auth/reset-password`

**Request:**
```json
{
  "token": "reset-token-here",
  "newPassword": "NewP@ssw0rd123!"
}
```

**Response (200):**
```json
{
  "message": "Password updated successfully"
}
```

**Business Rules:**
- Token must be valid and unused
- Invalidate token on success
- Bump password version to kill existing sessions
- Password must meet complexity requirements

**Implementation:**
```java
@PostMapping("/reset-password")
public ResponseEntity<ResetPasswordResponse> resetPassword(
    @Valid @RequestBody ResetPasswordRequest request) {
    
    authService.resetPassword(request.token(), request.newPassword());
    
    return ResponseEntity.ok(new ResetPasswordResponse("Password updated successfully"));
}
```

#### 3. `POST /api/v1/auth/verify-email`

**Request:**
```json
{
  "token": "verification-token-here"
}
```

**Response (200):**
```json
{
  "verified": true,
  "message": "Email verified successfully"
}
```

**Business Rules:**
- Token must be valid and unused
- Allow single-use only
- Return 400 for expired/invalid tokens

#### 4. `POST /api/v1/auth/resend-verification`

**Request:**
```json
{
  "email": "user@example.com"
}
```

**Response (202):**
```json
{
  "message": "If the email exists and is not verified, a verification link was sent."
}
```

**Business Rules:**
- Rate-limit by email/IP
- Only send if email exists and not verified
- Generic response to avoid enumeration

### DTOs for Auth

```java
// New DTOs needed
public record ForgotPasswordRequest(@Email String email) {}
public record ForgotPasswordResponse(String message) {}

public record ResetPasswordRequest(
    @NotBlank String token,
    @Size(min = 8, max = 128) String newPassword
) {}
public record ResetPasswordResponse(String message) {}

public record VerifyEmailRequest(@NotBlank String token) {}
public record VerifyEmailResponse(boolean verified, String message) {}

public record ResendVerificationRequest(@Email String email) {}
public record ResendVerificationResponse(String message) {}
```

### Definition of Done (Auth)
- ✅ Email templates live
- ✅ Tokens stored hashed (pepper+SHA256)
- ✅ Brute-force protection enabled
- ✅ Audit events emitted: USER_REGISTERED, LOGIN_SUCCEEDED/FAILED, EMAIL_VERIFIED, PASSWORD_RESET
- ✅ Rate limiting implemented
- ✅ Integration tests cover all scenarios

---

## 👤 User (Self-Serve)

### ❌ Missing Endpoints (MVP Add)

#### 1. `GET /api/v1/users/me`

**Response (200):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "username": "alice",
  "email": "alice@example.com",
  "displayName": "Alice Johnson",
  "bio": "Software developer passionate about education",
  "avatarUrl": "https://cdn.example.com/avatars/alice.jpg",
  "preferences": {
    "theme": "dark",
    "notifications": {
      "email": true,
      "push": false
    }
  },
  "joinedAt": "2025-01-15T10:30:00Z",
  "verified": true,
  "roles": ["ROLE_USER"]
}
```

#### 2. `PATCH /api/v1/users/me`

**Request (partial):**
```json
{
  "displayName": "Alice Johnson",
  "bio": "Software developer passionate about education",
  "preferences": {
    "theme": "dark",
    "notifications": {
      "email": true,
      "push": false
    }
  }
}
```

**Business Rules:**
- Limit field lengths (displayName: 50 chars, bio: 500 chars)
- Strip HTML from bio
- Validate preferences structure
- Only allow updating own profile

#### 3. `POST /api/v1/users/me/avatar`

**Request:** `multipart/form-data` (file)

**Response (200):**
```json
{
  "avatarUrl": "https://cdn.example.com/avatars/alice.jpg",
  "message": "Avatar updated successfully"
}
```

**Business Rules:**
- MIME whitelist: `image/png`, `image/jpeg`, `image/webp`
- Max file size: 2MB
- Auto resize to ≤ 512x512
- Store in CDN/cloud storage
- Return public URL

### DTOs for User

```java
public record MeResponse(
    UUID id,
    String username,
    String email,
    String displayName,
    String bio,
    String avatarUrl,
    Map<String, Object> preferences,
    Instant joinedAt,
    boolean verified,
    List<String> roles
) {}

public record UpdateMeRequest(
    @Size(max = 50) String displayName,
    @Size(max = 500) String bio,
    Map<String, Object> preferences
) {}

public record AvatarUploadResponse(
    String avatarUrl,
    String message
) {}
```

### Definition of Done (User)
- ✅ XSS-sanitized bio
- ✅ Image scanning (basic malware check)
- ✅ Audit events: PROFILE_UPDATED, AVATAR_UPDATED
- ✅ File upload security (MIME validation, size limits)
- ✅ Integration tests for all CRUD operations

---

## 🔍 Search / Discovery

### Current State: 🔄 Basic filtering via QuizSearchCriteria

### Implementation Options:

#### Option A: Extend Existing Controller (Recommended for MVP)

Extend `GET /api/v1/quizzes` to support enhanced search parameters:

**Enhanced Query Parameters:**
- `search`: Full-text search on title/description
- `tags`: Comma-separated tag names
- `difficulty`: EASY, MEDIUM, HARD
- `categoryId`: UUID of category
- `creatorId`: UUID of creator
- `status`: PUBLISHED (enforced)
- `visibility`: PUBLIC (enforced)
- `page`, `size`, `sort`: Standard pagination

**Response:**
```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "title": "JavaScript Fundamentals",
      "description": "Test your knowledge of JavaScript basics",
      "questionCount": 12,
      "estimatedTime": 10,
      "difficulty": "EASY",
      "averageRating": 4.2,
      "totalAttempts": 23,
      "topicTags": ["javascript", "programming"],
      "creator": {
        "id": "660e8400-e29b-41d4-a716-446655440001",
        "username": "alice"
      },
      "createdAt": "2025-01-15T10:30:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}
```

**Business Rules:**
- Enforce only PUBLISHED + PUBLIC quizzes
- Return 304 with If-None-Match
- Stable sort default: `-createdAt`
- Entity graph for efficient count queries
- Index on (status, visibility, created_at) + trigram on title (if PostgreSQL)

#### Option B: Dedicated Search Endpoint (Future)

`GET /api/v1/search?type=quiz&q=javascript&page=0&size=20`

Thin facade over Option A with additional features:
- ETag support
- Search telemetry
- Faceted search
- Autocomplete suggestions

### DTOs for Search

```java
public record QuizPreviewDto(
    UUID id,
    String title,
    String description,
    int questionCount,
    int estimatedTime,
    Difficulty difficulty,
    double averageRating,
    int totalAttempts,
    List<String> topicTags,
    UserSummaryDto creator,
    Instant createdAt
) {}

public record UserSummaryDto(
    UUID id,
    String username
) {}
```

### Definition of Done (Search)
- ✅ Enforce only PUBLISHED+PUBLIC
- ✅ 304 with If-None-Match
- ✅ Return stable sort default -createdAt
- ✅ Performance optimized with proper indexes
- ✅ Rate limiting (120/min per IP)
- ✅ Integration tests for all search scenarios

---

## 📝 Attempts (MVP Extras)

### Current State: ✅ Most endpoints exist

### Enhancements Needed:

#### 1. Idempotency Improvements

**For `POST /api/v1/attempts/quizzes/{quizId}`:**
- Same quiz within 60s returns existing attempt
- Prevent multiple active attempts per user per quiz (unless quiz allows multiple)

**For `POST /api/v1/attempts/{id}/answers`:**
- Dedupe by (attemptId, questionId, answerHash)
- Validate question belongs to attempt
- Enforce timer if enabled

#### 2. New Endpoint: `GET /api/v1/attempts/active`

**Response (200):**
```json
{
  "content": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "quizId": "660e8400-e29b-41d4-a716-446655440001",
      "quizTitle": "JavaScript Fundamentals",
      "state": "IN_PROGRESS",
      "startedAt": "2025-01-27T10:30:00Z",
      "currentQuestion": 3,
      "totalQuestions": 12,
      "timeRemaining": 1800
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 5,
  "totalPages": 1
}
```

#### 3. Security Enhancements

**For all attempt endpoints:**
- Quiz must be PUBLISHED & visible to caller (or via share-cookie)
- Prevent answering after completion
- Concurrency-safe updates
- Shield correct answers if review is disabled until completion

#### 4. Event Emission

Emit events for analytics:
- ATTEMPT_STARTED
- ANSWER_SUBMITTED
- ATTEMPT_COMPLETED
- ATTEMPT_PAUSED
- ATTEMPT_RESUMED

### Definition of Done (Attempts)
- ✅ Prevent answering after complete
- ✅ Concurrency-safe updates
- ✅ Emit events for analytics
- ✅ Idempotency for all operations
- ✅ Security validation for all endpoints
- ✅ Integration tests for edge cases

---

## 🔗 Share-Links (Quizzes)

### Current State: ✅ Fully implemented

### Operational Enhancements Needed:

#### 1. Rate Limiting
- Access/consume: 60/min per IP + token-hash
- Create: 10/min per user

#### 2. Analytics Enhancement
- Hash (pepper:date:ip) for daily unique counts
- Track usage patterns
- Privacy-compliant analytics

#### 3. Security Improvements
- Token format validation: `^[A-Za-z0-9_-]{43}$`
- Proper error responses:
  - Invalid format → 400
  - Unknown/revoked → 404
  - Expired → 400
  - Already used (one-time) → 410

#### 4. Cookie Management
- `share_token` cookie with proper flags:
  - httpOnly: true
  - Secure: true
  - SameSite: Lax
  - Path: `/quizzes/{quizId}`
  - MaxAge: 3600

### Definition of Done (Share-Links)
- ✅ Rate limits implemented
- ✅ Masking logs (IP + UA truncation)
- ✅ Integration tests cover cookie and revocation
- ✅ Analytics tracking
- ✅ Security validation

---

## ✅ Moderation & Publishing

### Current State: ✅ Fully implemented

### Enhancements Needed:

#### 1. Material Edit Rule
If quiz is PUBLISHED and material fields change → auto flip to PENDING_REVIEW

**Material fields:**
- title
- description
- questions (add/remove/modify)
- difficulty
- estimatedTime

#### 2. Email Notifications
- Send emails on approve/reject
- Include reason for rejection
- Template-based emails

#### 3. Content Safety
- Run content safety check on submission
- Flag potentially inappropriate content
- Automated moderation assistance

### Definition of Done (Moderation)
- ✅ Audit trail entity
- ✅ Emails for approve/reject
- ✅ Content safety check runs on submission
- ✅ Material edit rule implemented
- ✅ Integration tests for all workflows

---

## 🤖 AI Generation + Payments (MVP Monetization)

### Current State: ✅ Generation endpoints exist, ❌ Payments missing

### Missing Payment Endpoints:

#### 1. `POST /api/v1/ai/estimate`

**Request:**
```json
{
  "pages": 120,
  "bytes": 1520345,
  "chunking": {
    "size": 1500,
    "strategy": "SEMANTIC"
  }
}
```

**Response:**
```json
{
  "estimateId": "550e8400-e29b-41d4-a716-446655440000",
  "operation": "AI_QUIZ_GENERATION",
  "tokens": 82000,
  "cost": 4.10,
  "currency": "USD",
  "ttlSeconds": 900
}
```

#### 2. `GET /api/v1/tokens/packs`

**Response:**
```json
{
  "packs": [
    {
      "id": "basic_100k",
      "name": "Basic Pack",
      "tokens": 100000,
      "price": 5.00,
      "currency": "USD",
      "bonus": 0
    },
    {
      "id": "premium_500k",
      "name": "Premium Pack",
      "tokens": 500000,
      "price": 20.00,
      "currency": "USD",
      "bonus": 50000
    }
  ]
}
```

#### 3. `POST /api/v1/tokens/purchase`

**Request:**
```json
{
  "packId": "basic_100k"
}
```

**Response:**
```json
{
  "checkoutUrl": "https://checkout.stripe.com/pay/cs_test_...",
  "sessionId": "cs_test_..."
}
```

#### 4. Reserve/Commit/Release System

**Reserve:**
```java
@PostMapping("/tokens/reserve")
public ResponseEntity<ReserveResponse> reserveTokens(
    @Valid @RequestBody ReserveRequest request,
    @RequestHeader("Idempotency-Key") String idempotencyKey) {
    
    TokenReservation reservation = tokenService.reserveTokens(
        request.estimateId(), 
        idempotencyKey
    );
    
    return ResponseEntity.ok(new ReserveResponse(
        reservation.getId(), 
        reservation.getExpiresAt()
    ));
}
```

**Commit:**
```java
@PostMapping("/tokens/commit")
public ResponseEntity<CommitResponse> commitTokens(
    @Valid @RequestBody CommitRequest request,
    @RequestHeader("Idempotency-Key") String idempotencyKey) {
    
    tokenService.commitTokens(request.reservationId(), request.jobId());
    
    return ResponseEntity.ok(new CommitResponse("Tokens committed successfully"));
}
```

**Release:**
```java
@PostMapping("/tokens/release")
public ResponseEntity<ReleaseResponse> releaseTokens(
    @Valid @RequestBody ReleaseRequest request,
    @RequestHeader("Idempotency-Key") String idempotencyKey) {
    
    tokenService.releaseTokens(request.reservationId(), request.reason());
    
    return ResponseEntity.ok(new ReleaseResponse("Tokens released successfully"));
}
```

#### 5. Balance & Ledger

**Balance:**
```java
@GetMapping("/tokens/balance")
public ResponseEntity<TokenBalanceResponse> getBalance() {
    TokenBalance balance = tokenService.getUserBalance(getCurrentUserId());
    
    return ResponseEntity.ok(new TokenBalanceResponse(balance.getTokens()));
}
```

**Ledger:**
```java
@GetMapping("/tokens/ledger")
public ResponseEntity<Page<TransactionDto>> getLedger(
    @PageableDefault(page = 0, size = 20) Pageable pageable) {
    
    Page<Transaction> transactions = tokenService.getUserTransactions(
        getCurrentUserId(), 
        pageable
    );
    
    return ResponseEntity.ok(transactions.map(this::mapToDto));
}
```

#### 6. Stripe Webhook

```java
@PostMapping("/payments/stripe/webhook")
public ResponseEntity<Void> handleStripeWebhook(
    @RequestBody String payload,
    @RequestHeader("Stripe-Signature") String signature) {
    
    stripeService.handleWebhook(payload, signature);
    
    return ResponseEntity.ok().build();
}
```

### DTOs for AI Tokens

```java
public record EstimateRequest(
    int pages,
    long bytes,
    ChunkingParams chunking
) {}

public record EstimateResponse(
    UUID estimateId,
    String operation,
    long tokens,
    BigDecimal cost,
    String currency,
    long ttlSeconds
) {}

public record ReserveRequest(UUID estimateId) {}
public record ReserveResponse(UUID reservationId, Instant expiresAt) {}

public record CommitRequest(UUID reservationId, UUID jobId) {}
public record CommitResponse(String message) {}

public record ReleaseRequest(UUID reservationId, String reason) {}
public record ReleaseResponse(String message) {}

public record TokenBalanceResponse(long tokens) {}

public record TransactionDto(
    UUID id,
    String type,
    long amount,
    String description,
    Instant createdAt
) {}
```

### Definition of Done (AI+Payments)
- ✅ Job start requires active reservation OR free-tier allowance
- ✅ Ledger double-entry accounting
- ✅ Webhook signature verified
- ✅ Retries idempotent
- ✅ Integration tests for full payment flow
- ✅ Stripe integration tested

---

## 🛡️ Cross-cutting Guardrails (MVP)

### 1. DTO Size Limits
- Answers batch ≤ 50
- Upload max 20MB
- Search results max 100 per page

### 2. Rate Limiting
Simple IP-based rate limiting using bucket4j or Redis:

```java
@Component
public class RateLimitService {
    
    public void checkRateLimit(String operation, String key) {
        // Implementation using bucket4j or Redis
    }
}
```

### 3. Correlation IDs
Propagate `X-Request-Id` to jobs/webhooks:

```java
@Component
public class CorrelationIdFilter implements Filter {
    
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        String correlationId = ((HttpServletRequest) request).getHeader("X-Request-Id");
        if (correlationId == null) {
            correlationId = UUID.randomUUID().toString();
        }
        
        MDC.put("correlationId", correlationId);
        ((HttpServletResponse) response).addHeader("X-Request-Id", correlationId);
        
        chain.doFilter(request, response);
    }
}
```

### 4. OpenAPI Documentation
Annotate all endpoints with OpenAPI annotations:

```java
@Operation(
    summary = "Create a new quiz",
    description = "Creates a new quiz with the provided details"
)
@ApiResponses({
    @ApiResponse(responseCode = "201", description = "Quiz created successfully"),
    @ApiResponse(responseCode = "400", description = "Invalid request data"),
    @ApiResponse(responseCode = "401", description = "Unauthorized"),
    @ApiResponse(responseCode = "403", description = "Forbidden")
})
@PostMapping
public ResponseEntity<CreateQuizResponse> createQuiz(...) {
    // Implementation
}
```

### 5. Basic Metrics
Count metrics for key operations:

```java
@Component
public class MetricsService {
    
    private final MeterRegistry meterRegistry;
    
    public void incrementAuthAttempt(String type, boolean success) {
        meterRegistry.counter("auth.attempts", 
            "type", type, 
            "success", String.valueOf(success)
        ).increment();
    }
    
    public void incrementShareLinkAccess(String tokenHash) {
        meterRegistry.counter("share_links.access", "token_hash", tokenHash).increment();
    }
    
    public void incrementAiJob(String status) {
        meterRegistry.counter("ai.jobs", "status", status).increment();
    }
}
```

---

## 🧪 Test Matrix (High-Value)

### Auth Tests
- ✅ Register → verify → login → refresh flow
- ✅ Forgot/reset success + token replay
- ✅ Rate limiting enforcement
- ✅ Email verification flow
- ✅ Password complexity validation

### User Tests
- ✅ GET/PATCH /me operations
- ✅ Avatar upload with invalid MIME types
- ✅ XSS in bio sanitization
- ✅ File size limits
- ✅ Profile validation

### Search Tests
- ✅ Public-only invariant enforcement
- ✅ ETag 304 responses
- ✅ Pagination bounds
- ✅ Filter combinations
- ✅ Rate limiting

### Attempts Tests
- ✅ Idempotent complete operations
- ✅ Cannot answer after completion
- ✅ Timer enforcement
- ✅ Active attempts listing
- ✅ Concurrency safety

### Share-Links Tests
- ✅ Invalid token → 400
- ✅ Expired → 400
- ✅ Revoked → 404
- ✅ One-time consume → 410 on second use
- ✅ Cookie flags validation
- ✅ Rate limiting

### AI+Payments Tests
- ✅ Estimate → reserve → job start (tokens held) → commit (deduct)
- ✅ Failure → release tokens
- ✅ Webhook idempotency
- ✅ Stripe integration
- ✅ Balance accuracy

---

## 📅 Delivery Order (2–3 Week Slice)

### Week 1: Foundation
1. **Auth additions** (forgot/reset/verify/resend) + emails
2. **User self-serve trio** (GET/PATCH/avatar)
3. **Rate limiting infrastructure**

### Week 2: Core Features
1. **Search v1** (facets + ETag on quizzes list)
2. **Attempts edges** (idempotency, active endpoint)
3. **Share-links rate limits & analytics polish**

### Week 3: Monetization
1. **AI tokenization** (estimate→reserve/commit/release + packs + webhook)
2. **Integration testing**
3. **Documentation & deployment**

---

## 📚 Implementation Checklist

### Phase 1: Auth & User (Week 1)
- [ ] Implement forgot password endpoint
- [ ] Implement reset password endpoint
- [ ] Implement email verification endpoint
- [ ] Implement resend verification endpoint
- [ ] Create email templates
- [ ] Implement rate limiting for auth endpoints
- [ ] Implement user profile endpoints
- [ ] Implement avatar upload
- [ ] Add audit events
- [ ] Write integration tests

### Phase 2: Search & Attempts (Week 2)
- [ ] Enhance quiz search with faceted filtering
- [ ] Add ETag support
- [ ] Implement active attempts endpoint
- [ ] Add idempotency to attempt operations
- [ ] Enhance share-link rate limiting
- [ ] Add analytics tracking
- [ ] Write integration tests

### Phase 3: Payments (Week 3)
- [ ] Implement token estimation
- [ ] Implement token packs
- [ ] Implement Stripe integration
- [ ] Implement reserve/commit/release system
- [ ] Implement balance and ledger
- [ ] Add webhook handling
- [ ] Write integration tests
- [ ] Deploy and monitor

### Cross-cutting
- [ ] Add correlation IDs
- [ ] Implement rate limiting
- [ ] Add OpenAPI documentation
- [ ] Add basic metrics
- [ ] Create Postman collection
- [ ] Performance testing
- [ ] Security review

---

## 🎯 Success Criteria

### Technical
- ✅ All endpoints return proper HTTP status codes
- ✅ Rate limiting prevents abuse
- ✅ Idempotency prevents duplicate operations
- ✅ Audit trail captures all important events
- ✅ Integration tests pass with >90% coverage

### Business
- ✅ Users can complete full registration flow
- ✅ Users can manage their profiles
- ✅ Users can search and discover quizzes
- ✅ Users can complete quiz attempts
- ✅ Users can share quizzes securely
- ✅ Users can purchase and use AI tokens

### Performance
- ✅ Search responds in <200ms
- ✅ File uploads handle up to 20MB
- ✅ Rate limiting doesn't impact legitimate users
- ✅ Database queries are optimized

### Security
- ✅ No user enumeration vulnerabilities
- ✅ XSS protection in user content
- ✅ CSRF protection on state-changing operations
- ✅ Proper authentication and authorization
- ✅ Secure file upload handling
