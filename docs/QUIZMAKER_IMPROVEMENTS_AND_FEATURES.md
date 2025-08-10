# QuizMaker Project Improvements & Future Features

## 📋 Table of Contents
1. [📊 Current Implementation Status](#current-implementation-status)
2. [🎯 MVP Features (First Release) - STREAMLINED](#mvp-features-first-release---streamlined)
3. [🔧 Big Rocks to Fix First](#big-rocks-to-fix-first)
4. [💼 Commercial Features (Revenue Generation) - SIMPLIFIED](#commercial-features-revenue-generation---simplified)
5. [🏢 B2B Features (Organization Ready)](#b2b-features-organization-ready)
6. [🚀 Future Features (Advanced Capabilities)](#future-features-advanced-capabilities)
7. [🌠 Moonshot Ideas (Market Disruption)](#moonshot-ideas-market-disruption)
8. [📅 Implementation Sequencing (Realistic 6-8 Weeks)](#implementation-sequencing-realistic-6-8-weeks)

---

## 📊 Current Implementation Status (August 2025)

### ✅ Implemented Features
- **Attempts**: Start, list, get details, current question, submit answer (single/batch), complete, stats, pause/resume, delete, shuffled questions
- **Quizzes**: CRUD, add/remove question, add/remove tag, change category, visibility toggle, status change, list public, AI generation from document/upload (async), generation status, retrieve generated quiz, cancel/force-cancel jobs, list jobs, job stats, cleanup stale jobs, results summary, leaderboard

### ⚠️ Partially Implemented
- **Social**: Models/DTOs/services exist for bookmarks, comments, followers, ratings; controllers are placeholders (no endpoints yet)
- **Groups/Communities**: Not implemented

### ❌ Missing Features
- **Share via link**: No dedicated share/invite token endpoints; visibility toggle (PUBLIC/PRIVATE) exists
- **User Management Controller**: Missing entirely
- **Advanced Analytics**: Basic results summary and leaderboard exist; deeper analytics missing

---

## 🔧 Big Rocks to Fix First (Before Building More)

### 1. Cut Scope Hard for MVP
**Lock in 3-4 hero flows and make them silky smooth:**

1. **Create → Edit → Submit for Review → Moderate → Publish Public**
2. **Attempt Flow**: Start → Answer → Complete → See Own Results
3. **AI Quiz Generation**: From document (async job) with clear progress/errors
4. **Basic Search/Discovery**: Public catalog + simple search

### 2. Authorization Model
- Implement **scoped RBAC + moderation states** (already outlined)
- Don't bolt on features until auth + visibility rules are rock solid
- Add `PENDING_REVIEW` and `REJECTED` statuses to existing quiz model

### 3. Background Jobs
- Standardize on **one pattern**: job table + idempotency + retries
- Extend existing generation job pattern, don't invent new ones later
- Single Job interface: `status`, `errorCode`, `progress`, `payload`, `startedAt/finishedAt` + retry policy

### 4. Observability & DX
- Add **correlation IDs** (MDC), structured logs, basic metrics
- Crisp error contracts
- You'll thank yourself when production arrives

---

## 🎯 MVP Features (First Release) - STREAMLINED

### Authentication & Account Management
- POST `/api/v1/auth/forgot-password` - Request password reset email/token
- POST `/api/v1/auth/reset-password` - Submit new password with valid token
- POST `/api/v1/auth/verify-email` - Verify email using token
- POST `/api/v1/auth/resend-verification` - Resend verification email

### Search & Discovery
- GET `/api/v1/search?type=quiz&q=...&page=..` - **Unified search** scoped to public quizzes only

### Attempt Review (Fold into existing endpoints)
- GET `/api/v1/attempts/{id}` - **Extend existing** to include review view if completed
- POST `/api/v1/attempts/{id}/restart` - Restart attempt (idempotent)
- GET `/api/v1/attempts/active` - Get user's active attempts

### User Management Controller (Bare Minimum)
- GET `/api/v1/users/me` - Get current user profile
- PATCH `/api/v1/users/me` - Update current user profile
- POST `/api/v1/users/me/avatar` - Upload avatar
- GET `/api/v1/admin/users` - **Admin list** behind role check (defer bulk actions)

### Share-Links (Quizzes Only)

#### Scope & Security
- **Token scopes**: `QUIZ_VIEW` (default) and optional `QUIZ_ATTEMPT_START` (for campaigns)
- **Token fields**: `quizId`, `scope`, `expiresAt`, `oneTime`, `createdBy`, `revokedAt`
- **No edit scopes** via link

#### Flow
1. `POST /api/v1/quizzes/{id}/share-link` → opaque token
2. `GET /api/v1/quizzes/shared/{token}` → server validates → issues short-lived, httpOnly cookie **bound to that quiz** → redirect to normal viewer route
3. `DELETE /api/v1/quizzes/shared/{tokenId}` to revoke

#### Guardrails
- Rate-limit token creation & consumption
- Optionally require login for `QUIZ_ATTEMPT_START` (recommended to avoid spam/cheating)
- Guest access via share-link: allow only `QUIZ_VIEW` by default

#### DX
- Link management UI: list, copy, revoke, set expiry/one-time
- Analytics: count views/starts via link (event props include `shareTokenId`)

### Public Publishing with Moderation

#### Re-review for Material Edits (No Versioning Yet)
- **Rules**:
  - `PUBLISHED` quiz: material edit → flip to `PENDING_REVIEW` and clear publish flags
  - `PENDING_REVIEW`: any edit by owner → revert to `DRAFT`
  - **Material** = title/description, questions content, correct answers, scoring
  - **NOT material** = tags, category, thumbnail (make list explicit in code)
  - Add tiny **diff detector** (field whitelist) to decide

#### Moderation Completeness
- **Endpoints**:
  - POST `/api/v1/quizzes/{id}/submit-for-review`
  - POST `/api/v1/admin/quizzes/{id}/approve`
  - POST `/api/v1/admin/quizzes/{id}/reject`
  - POST `/api/v1/quizzes/{id}/unpublish`
  - GET `/api/v1/admin/quizzes/pending-review`
- **Audit trail**: who/when/why; keep last 10 actions on quiz for now
- Only `PUBLISHED + PUBLIC` are listed in catalog (single invariant)

### Content Safety
- Minimal profanity/PII filter for public publishing (LLM moderation or keyword lists)
- POST `/api/v1/admin/content/{contentId}/flag` - Flag inappropriate content

### Emails
- Password reset, verification, and moderation decisions
- Basic email service integration

---

## 💼 Commercial Features (Revenue Generation) - SIMPLIFIED

### Payments Apply **Only** to AI Generation

#### Credit Model (Laser-Focused)
- **Only bill for `AI_QUIZ_GENERATION`** (doc upload + generation). Everything else free
- **Preflight estimate**: `POST /api/v1/ai/estimate` returns token cost given doc size/pages/chunking params. Show to user before they commit
- **Reserve-commit-release**:
  - `POST /api/v1/tokens/reserve` (requires `estimateId`)
  - Job starts → `POST /api/v1/tokens/commit`
  - On failure/cancel → `POST /api/v1/tokens/release`
  - All **idempotent** via `Idempotency-Key`

#### Stripe Integration
- **Packs only** (Checkout + webhook). Ledger is double-entry; balance is derived
- GET `/api/v1/tokens/packs` - List available token packs and pricing
- POST `/api/v1/tokens/purchase` - Create Stripe Checkout session for token pack
- POST `/api/v1/payments/stripe/webhook` - Stripe webhook to finalize purchases

#### Token Management
- GET `/api/v1/tokens/balance` - Get current token balance
- GET `/api/v1/tokens/ledger` - Paginated token transactions
- GET `/api/v1/tokens/rates` - Cost table per operation (by size)

#### Abuse & Predictability
- Per-user/org **monthly free allowance** (e.g., N small docs). Hard cap configurable per org
- **Job size caps** and per-minute rate limits
- **Retry policy**: retries don't bill extra; charge once per successful start

### Defer to Later Phases
- **Customer portal** - Handle via Stripe dashboard for now
- **Quotes/refunds** - Handle refunds from Stripe dashboard
- **Promos** - Add complexity and support debt
- **Auto top-up** - Do once you see traction
- **API keys** - Future "platform" phase only

---

## 🏢 B2B Features (Organization Ready)

### Tenancy & RBAC (ORG First)

#### Organizations + Org-Scoped Role Bindings
- Introduce **organizations** + **org-scoped role bindings** immediately (departments/groups can follow)
- Default roles per org: `ORG_ADMIN`, `CREATOR`, `LEARNER`, `MODERATOR`, `AUDITOR`
- Private **org catalog**: `visibility=ORG` plus RBAC; public catalog unaffected

#### Endpoints
- POST `/api/v1/orgs` (seed admin)
- POST `/api/v1/orgs/{orgId}/roles` (custom role)
- POST `/api/v1/roles/bindings` (assign role to user in org)
- GET `/api/v1/orgs/{orgId}/catalog` (org-visible quizzes)

### Org Wallets & Budgets

#### Wallet Management
- Each org has a **wallet** (credits live at org level, not personal) with per-role **spend permissions**:
  - `AI_SPEND` for Creators
  - `AI_SPEND_MANAGE` for Admins
- Budgets: monthly caps; optional **dept budgets** later

### Sales Blockers to Remove Early

#### SSO Preparation
- SSO later (OIDC/SAML), but prepare: user table must store ext IDs; keep email unique per org

#### Compliance & Governance
- **Audit logs** (CSV export) & **data export** endpoints per org
- **DPAs/data residency**: document only for now; ensure region-agnostic storage flags in config

---

## 🚀 Future Features (Advanced Capabilities)

### Social: Keep Surface Area Tiny
- POST `/api/v1/quizzes/{quizId}/comments` - Add comment to quiz
- DELETE `/api/v1/comments/{commentId}` - Delete comment
- POST `/api/v1/quizzes/{quizId}/bookmark` - Bookmark quiz
- DELETE `/api/v1/quizzes/{quizId}/bookmark` - Remove bookmark
- GET `/api/v1/users/bookmarks` - List user bookmarks
- POST `/api/v1/reports` - Report content (moderators see simple queue)
- **Delay**: ratings/follows until moderation capacity exists

### Analytics: Build Events, Not Warehouse
- Emit **product analytics events** (PostHog/Segment style):
  - Quiz viewed/started/completed
  - Question shown/answered
  - AI generation requested/completed/failure
  - Publish approved/rejected
- Keep **one simple in-app analytics** view per quiz:
  - Attempts count, completion rate, avg score, question difficulty histogram
- Later: ETL to warehouse when you need cohorts and funnels

### Performance & Scalability: Right-Sized Investments
- Add **caching** only on read-heavy endpoints (public catalog, quiz details)
- Add **indexes** for search predicates you actually use (title, tags join table, status+visibility)
- **N+1**: Check repo methods on quiz → questions and attempt → answers. Use `@EntityGraph`/fetch joins where safe

### Security Must-Haves (Quick Wins)
- Move **JWT secret** out of resources; use env/secret manager
- **Bootstrap superadmin** on empty DB via env (no manual inserts)
- **@PreAuthorize** on all mutations; Specs to filter **list** endpoints (no leakage)
- **Validation**: DTO validation groups for create vs update; max lengths; file upload MIME/type/size checks
- **Abuse protection**: Simple rate limiting on auth and AI endpoints

### API Surface Hygiene
- Prefer **resource-centric** routes and avoid deep, bespoke trees
- Use **singular verbs** consistently: `duplicate`, `export`, `share`, `schedule`
- Add **idempotency** for all POSTs that change money or jobs
- Versioning: keep **/api/v1**, but don't ship **deprecation APIs** yet

### Advanced Features (Defer from MVP)
- **Adaptive quizzes** and **collaborative editing** - R&D track, don't block core UX
- **Advanced question types** - Start with existing types, expand later
- **Gamification** - Focus on core value first
- **Third-party integrations** - Build platform foundation first

---

## 📅 Implementation Sequencing (Realistic 6-8 Weeks)

### Week 1-2: Foundation
- **Moderation** (incl. **unpublish** + audits), share-links (+ revoke), search facets, ETag
- **Org skeleton** + org-scoped roles (`ORG_ADMIN`, `CREATOR`, `LEARNER`, `MODERATOR`)
- **Idempotency store** + correlation IDs

### Week 3-4: Core Features
- **Payments for generation**: Checkout, webhook, ledger, reserve/commit/release, estimates
- **Org wallet** + `AI_SPEND` permissions; per-org caps

### Week 5-6: Social & Analytics
- **Minimal social** (comments/bookmarks/report) behind flag
- **Basic per-quiz analytics**; indexes; N+1 passes; rate limits

### Week 7-8: Polish & Launch
- **Creator ergonomics**: duplicate quiz, bulk question import (CSV/JSON), preview
- **Docs/OpenAPI/Postman**; sales one-pager for B2B (org features + governance)

---

## 🌠 Moonshot Ideas (Market Disruption)

### 1. Neural-Adaptive Quiz Experience
Leverage inexpensive consumer EEG headsets (e.g., Muse, NeuroSky) to **adapt quiz difficulty in real-time based on learner focus and stress levels**. By measuring brainwave patterns, QuizMaker could detect when a user is overly stressed or bored and dynamically adjust question difficulty, pacing, and encouragement messages. This "mind-responsive" experience would create an unforgettable *a-ha!* moment, position QuizMaker at the cutting edge of ed-tech, and open doors to partnerships with wearable manufacturers and neuro-learning research institutions.

### 2. Quantum-Encrypted Knowledge Verification
Implement **quantum cryptography for unhackable quiz results and certificates** using emerging quantum key distribution technology. This would create the world's first quantum-secured learning platform, appealing to high-stakes testing environments like medical licensing, legal bar exams, and government security clearances. The quantum signature would make cheating mathematically impossible, creating a new gold standard for educational assessment integrity.

### 3. Spatial Computing Quiz Worlds
Develop **fully immersive AR/VR quiz environments** where users physically walk through 3D knowledge landscapes, manipulate virtual objects to answer questions, and collaborate in shared virtual study spaces. Imagine taking a history quiz by literally walking through ancient Rome, or learning chemistry by building molecular structures with your hands in virtual space. This would transform QuizMaker from a quiz platform into a spatial computing pioneer, potentially revolutionizing how humans interact with knowledge itself.

---

## 📝 Implementation Status Legend

- ✅ **Completed** - Feature is implemented and functional
- ❌ **Missing** - Feature needs to be implemented
- 🔄 **In Progress** - Feature is currently being developed
- 📋 **Planned** - Feature is planned for future implementation
- 🚀 **Moonshot** - Revolutionary feature requiring significant R&D
- 🎯 **MVP Priority** - Core features for first release
- 💼 **Revenue** - Monetization features
- 🔧 **Foundation** - Must-have infrastructure
- 🏢 **B2B** - Organization-focused features

---

## 🎯 Product/Market Sanity

### ICP Split
- **Individual creators** vs **team/org customers**
- Keep org features behind flags until you see B2B pull

### Pricing Strategy
- Start with **token packs only**
- Add subscriptions later (org plan with seats)

### Activation Path
- The true "a-ha" is: **doc → AI → quiz → publish → attempts → analytics**
- Make that path frictionless and free up to a small limit

### B2B Focus
- Ship **org + wallets + moderation + public catalog**
- Leave departments/groups, SSO, SCIM for the next quarter

---

## 🗄️ Data & Infrastructure Notes

### Indexes
- `quizzes(status, visibility, created_at)`, `quiz_title`, tag join
- `jobs(status, type, created_at)`
- `ledger(user_or_org_id, created_at)`, `(idempotency_key)`
- `share_links(quiz_id, expires_at)`

### Idempotency Store
- `(key, actorId, firstSeenAt, resultHash, ttl)`, with cleanup job

### Observability
- Correlation ID across web, jobs, Stripe webhook
- Counters for generation starts/fails, ledger anomalies, moderation actions

---

## 🎯 Realistic Usage Scenario

**The "A-ha!" Moment**: A corporate training manager discovers QuizMaker while struggling to create engaging compliance training. She uploads a dense 50-page compliance manual and uses AI generation to create an interactive quiz in minutes. The quiz includes scenario-based questions that employees actually enjoy taking. When she sees the detailed analytics showing knowledge gaps and learning patterns, she realizes she can now make data-driven decisions about training effectiveness. The social features allow employees to discuss tricky scenarios, creating a collaborative learning environment. This transforms her from a frustrated trainer into a learning analytics expert, and QuizMaker becomes the cornerstone of her company's learning strategy.

---

*This document serves as a streamlined roadmap for QuizMaker's evolution from MVP to a comprehensive learning ecosystem. The focus is on shipping faster, avoiding dead-ends, and keeping the codebase maintainable as you grow. Each section represents a strategic area of development that will enhance user experience, expand functionality, and position QuizMaker as a market leader in educational technology.* 