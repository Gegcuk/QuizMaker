# QuizMaker — Critical Security & Logic Hardening Plan
_Date: 2025-08-10_

**Scope:** Option 1.1 — Critical security & logic checks and improvements.

---

## 🔥 Priority 0 (Do first)

### 1) Lock down Questions API (prevent answer leaks)
- **Issue:** `/api/v1/questions` and `/api/v1/questions/{id}` return `QuestionDto` with full answers to all authenticated users.
- **Fix:** Either
  - Add `@PreAuthorize("hasRole('ADMIN')")` to both endpoints, or
  - Create a new public-safe DTO without answers and map to it for non-admin users.
- **Acceptance:** Non-admin users cannot retrieve correct answers.

### 2) Fix IDOR in attempt listing
- **Issue:** Non-admins can query attempts of other users by passing `userId`.
- **Fix:** If not admin, ignore provided `userId` and resolve to authenticated user ID; enforce in both controller and service.
- **Acceptance:** Non-admins can only see their own attempts.

### 3) Enforce quiz status/visibility when starting attempts
- **Issue:** Any user can start attempts on `DRAFT` or `PRIVATE` quizzes by UUID.
- **Fix:** Allow attempts only if `PUBLISHED` and `PUBLIC`, unless caller is admin or quiz creator.
- **Acceptance:** Unauthorized users cannot start attempts on unavailable quizzes.

### 4) Implement refresh token rotation + logout
- **Issue:** Refresh tokens are stateless and logout is empty — stolen refresh tokens remain valid.
- **Fix:** Store hashed `jti` in DB; rotate tokens on refresh; revoke on logout.
- **Acceptance:** Reuse of old refresh token fails; logout revokes refresh.

### 5) CORS guardrails
- **Issue:** `allowCredentials=true` with wildcard origins is unsafe.
- **Fix:** Disallow wildcards in prod; fail startup if misconfigured.
- **Acceptance:** Only allowed origins work in prod.

### 6) Security headers
- **Fix:** Add headers in `SecurityConfig`:
  - X-Content-Type-Options
  - Referrer-Policy
  - Permissions-Policy
  - X-Frame-Options
  - HSTS in prod
- **Acceptance:** Headers appear in all responses.

---

## ⚠️ Priority 1 (Quick wins)

### 7) BCrypt strength via config
- **Fix:** Make bcrypt strength configurable; set to 12 in prod.

### 8) Swagger exposure policy
- **Fix:** Disable or protect Swagger in prod.

### 9) Tests
- **Add regression tests:** Ensure fixes above remain enforced.

---

## Suggested PR order
1. Questions API hardening
2. Attempt ownership fix
3. Quiz availability checks
4. CORS & headers
5. Refresh rotation + logout
6. BCrypt + Swagger policy
