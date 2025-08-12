# QuizMaker — Combined Roadmap (B2C‑First, Then B2B)

## 📋 Table of Contents
1. [What's the plan?](#whats-the-plan)
2. [Current Implementation Snapshot (Aug 2025)](#current-implementation-snapshot-aug-2025)
3. [MVP (First Release) — B2C Focus](#mvp-first-release--b2c-focus)
4. ["Big Rocks" (Do Before/With MVP)](#big-rocks-do-beforewith-mvp)
5. [Revenue (Kept Simple for Launch)](#revenue-kept-simple-for-launch)
6. [B2B (Phase 3 After B2C Traction)](#b2b-phase-3-after-b2c-traction)
7. [Social & Analytics (Phase 2, Behind a Flag)](#social--analytics-phase-2-behind-a-flag)
8. [Implementation Sequencing (6–8 Weeks)](#implementation-sequencing-68-weeks)
9. [Future / Advanced (Kept, Not in MVP)](#future--advanced-kept-not-in-mvp)
10. [Appendix A — Endpoint Manifest (Union, No Losses)](#appendix-a--endpoint-manifest-union-no-losses)
11. [Appendix B — Data/Infra Notes](#appendix-b--datainfra-notes)
12. [Appendix C — Payments (Later-Phase Full Surface, Preserved)](#appendix-c--payments-later-phase-full-surface-preserved)
13. [Appendix D — Moonshots](#appendix-d--moonshots)

---

## What's the plan?

This roadmap outlines the strategic development phases for QuizMaker, transitioning from a consumer-focused platform to an enterprise solution. The approach prioritizes building a solid B2C foundation before expanding into B2B markets.

- **Phase 0 (hardening):** polish what's live, lock auth/visibility, ship share-links.
- **Phase 1 (B2C MVP):** creator → publish → attempt → results; AI-from-docs; discovery; minimal user settings; moderation; **token packs for AI gen only**.
- **Phase 2 (Revenue & retention):** analytics view, social basics, performance, polish.
- **Phase 3 (B2B):** orgs, RBAC, org wallets, private catalogs.
- **Later Phases / Backlog:** full subscription/purchase surface, deep analytics, advanced quiz modes, integrations, moonshots.

---

## Current Implementation Snapshot (Aug 2025)

This section provides a comprehensive overview of what has been built, what's in progress, and what remains to be implemented. It serves as the foundation for planning future development phases.

### ✅ Implemented
- **🔐 Auth & Account:** register, login, refresh, logout, get current user (`/api/v1/auth/*`)
- **📝 Quizzes:** CRUD, tag/category ops, visibility/status, public listing, AI generation (async jobs), job mgmt, results summary, leaderboard (`/api/v1/quizzes/*`)
- **📊 Attempts:** start/list/details/current-question/submit(bulk)/complete/stats/pause-resume/delete/shuffled (`/api/v1/attempts/*`)
- **🔗 Share-Links:** create, access, consume, revoke, list (`/api/v1/quizzes/*/share-link`, `/api/v1/quizzes/shared/*`)
- **✅ Moderation:** approve, reject, unpublish, pending review, audit trail (`/api/v1/admin/quizzes/*`)
- **📁 Documents:** upload, process, chunk, reprocess, status (`/api/documents/*`)
- **🤖 AI Generation:** document-based quiz generation, job management, status tracking (`/api/v1/quizzes/generate-*`)
- **🏷️ Categories & Tags:** CRUD operations (`/api/v1/categories/*`, `/api/v1/tags/*`)
- **❓ Questions:** CRUD operations (`/api/v1/questions/*`)
- **👨‍💼 Admin:** role management (`/api/v1/admin/roles/*`)
- **🔧 Utility:** health check (`/api/v1/health`)

### 🔄 Partially Implemented
- **🤖 AI Chat:** basic chat functionality (`/api/ai/chat`) - limited scope
- **🔍 Search:** basic quiz filtering via QuizSearchCriteria - no dedicated search endpoint
- **📈 Analytics:** basic attempt stats and quiz results - no comprehensive analytics

### ❌ Missing
- **👤 User Management:** profile management, avatar upload, user settings
- **🔍 Dedicated Search:** search controller with faceted search, autocomplete
- **💰 Payments:** token packs, billing, webhooks
- **👥 Social Features:** comments, ratings, bookmarks, following
- **📱 Mobile/PWA:** mobile-specific endpoints
- **🔌 Integrations:** LMS, webhooks, third-party integrations
- **📊 Advanced Analytics:** detailed reporting, BI endpoints
- **🎯 Advanced Quiz Modes:** adaptive, collaborative, challenges
- **🎨 Personalization:** recommendations, learning paths
- **⚙️ Admin Operations:** system management, monitoring

---

## MVP (First Release) — B2C Focus

The Minimum Viable Product focuses on core user journeys that deliver immediate value to individual creators and learners. This phase establishes the fundamental platform capabilities while keeping complexity manageable.

### Hero flows (laser-focused)
1. **Create → Edit → Submit for review → Approve → Publish (Public)** ✅
2. **Attempt:** start → answer → complete → see own results ✅
3. **AI quiz generation** from document (async) with progress/errors ✅
4. **Search/Discovery** of public quizzes 🔄

### Endpoints (MVP surface)

#### 🔐 Auth & Account ✅
Authentication and account management endpoints for user registration, login, and profile management.
```
✅ POST /api/v1/auth/register
✅ POST /api/v1/auth/login
✅ POST /api/v1/auth/refresh
✅ POST /api/v1/auth/logout
✅ GET  /api/v1/auth/me
❌ POST /api/v1/auth/forgot-password
❌ POST /api/v1/auth/reset-password
❌ POST /api/v1/auth/verify-email
❌ POST /api/v1/auth/resend-verification
```

#### 👤 User (self-serve only) ❌
User profile management and self-service account operations.
```
❌ GET  /api/v1/users/me
❌ PATCH /api/v1/users/me
❌ POST /api/v1/users/me/avatar
✅ GET  /api/v1/admin/users
```

#### 🔍 Search / Discovery 🔄
Public quiz discovery and search functionality for finding content.
```
🔄 GET /api/v1/quizzes?search=...&page=...   # via QuizSearchCriteria
❌ GET /api/v1/search?type=quiz&q=...&page=...   # dedicated search endpoint
```

#### 📝 Attempts (MVP extras) ✅
Quiz attempt management including starting, completing, and reviewing attempts.
```
✅ POST /api/v1/attempts/quizzes/{quizId}     # start attempt
✅ GET  /api/v1/attempts                      # list attempts
✅ GET  /api/v1/attempts/{id}                 # get attempt details
✅ GET  /api/v1/attempts/{id}/current-question # get current question
✅ POST /api/v1/attempts/{id}/answers         # submit single answer
✅ POST /api/v1/attempts/{id}/answers/batch   # submit batch answers
✅ POST /api/v1/attempts/{id}/complete        # complete attempt
✅ GET  /api/v1/attempts/{id}/stats           # get attempt stats
✅ POST /api/v1/attempts/{id}/pause           # pause attempt
✅ POST /api/v1/attempts/{id}/resume          # resume attempt
✅ DELETE /api/v1/attempts/{id}               # delete attempt
✅ GET  /api/v1/attempts/quizzes/{quizId}/questions/shuffled # get shuffled questions
❌ POST /api/v1/attempts/{attemptId}/notes
❌ GET  /api/v1/attempts/active
```

#### 🔗 Share-Links (quizzes) ✅
Secure sharing functionality allowing creators to share quizzes with anyone via secure tokens.
```
✅ POST   /api/v1/quizzes/{quizId}/share-link
✅ GET    /api/v1/quizzes/shared/{token}
✅ GET    /api/v1/quizzes/shared/{token}/consume
✅ DELETE /api/v1/quizzes/shared/{tokenId}
✅ GET    /api/v1/quizzes/share-links
```

#### ✅ Moderation & Publishing ✅
Content moderation workflow ensuring quality and compliance before public publishing.
```
✅ POST /api/v1/quizzes/{id}/submit-for-review
✅ POST /api/v1/admin/quizzes/{id}/approve
✅ POST /api/v1/admin/quizzes/{id}/reject
✅ POST /api/v1/quizzes/{id}/unpublish
✅ GET  /api/v1/admin/quizzes/pending-review
❌ POST /api/v1/admin/content/{contentId}/flag
```

#### 🤖 AI Gen + Payments (MVP monetization = AI only) 🔄
AI-powered quiz generation from documents with token-based payment system.
```
✅ POST /api/v1/quizzes/generate-from-document
✅ POST /api/v1/quizzes/generate-from-upload
✅ GET  /api/v1/quizzes/generation-status/{jobId}
✅ GET  /api/v1/quizzes/generated-quiz/{jobId}
✅ DELETE /api/v1/quizzes/generation-status/{jobId}
✅ GET  /api/v1/quizzes/generation-jobs
✅ GET  /api/v1/quizzes/generation-jobs/statistics
✅ POST /api/v1/quizzes/generation-jobs/cleanup-stale
✅ POST /api/v1/quizzes/generation-jobs/{jobId}/force-cancel
❌ POST /api/v1/ai/estimate
❌ GET  /api/v1/tokens/packs
❌ POST /api/v1/tokens/purchase
❌ POST /api/v1/payments/stripe/webhook
❌ POST /api/v1/tokens/reserve
❌ POST /api/v1/tokens/commit
❌ POST /api/v1/tokens/release
❌ GET  /api/v1/tokens/balance
❌ GET  /api/v1/tokens/ledger
❌ GET  /api/v1/tokens/rates
```

### 🛡️ Guardrails (MVP) 🔄
Essential security and performance measures to ensure platform stability and user safety.
- ✅ DTO validation & crisp error contract
- ✅ Correlation IDs (MDC), structured logs
- ✅ Unified job pattern (idempotent, retries)
- ❌ Simple rate limits on auth & share-links

---

## "Big Rocks" (Do Before/With MVP)

These foundational elements must be implemented early as they form the core architecture that everything else builds upon. They're critical for scalability, security, and maintainability.

- ✅ **AuthZ model:** scoped RBAC + moderation states (`DRAFT`, `PENDING_REVIEW`, `PUBLISHED`, `REJECTED`) and "material edit" re-review rule.
- ✅ **Background jobs:** single pattern (status/progress/error/retry).
- 🔄 **Observability:** correlation IDs, minimal metrics, failure dashboards.
- 🔄 **Indexes:** `quizzes(status,visibility,created_at)`, tags, jobs, share_links TTL.

---

## Revenue (Kept Simple for Launch)

Revenue strategy focuses on simplicity and clear value proposition, starting with AI generation as the primary monetization point before expanding to broader subscription models.

- **B2C MVP:** only charge for **AI_QUIZ_GENERATION** with token packs (above).
- **Later:** full subscriptions & one-time purchases for content/exports/analytics (preserved in Appendix C).

---

## B2B (Phase 3 After B2C Traction)

Enterprise features designed for organizations, educational institutions, and corporate training needs. This phase introduces multi-tenancy, advanced permissions, and organizational workflows.

### Tenancy & RBAC ❌
- Orgs + org-scoped roles (`ORG_ADMIN`, `CREATOR`, `LEARNER`, `MODERATOR`, `AUDITOR`)
- Private org catalog

**Endpoints**
```
❌ POST /api/v1/orgs
❌ POST /api/v1/orgs/{orgId}/roles
❌ POST /api/v1/roles/bindings
❌ GET  /api/v1/orgs/{orgId}/catalog
```

### Org Wallets / Budgets ❌
- Org wallet & spend permissions (`AI_SPEND`, `AI_SPEND_MANAGE`)
- Per-org monthly caps

### Sales Blockers to Prep ❌
- External IDs on users, email unique per org
- Audit log export endpoints, data export
- Config flags for data residency

---

## Social & Analytics (Phase 2, Behind a Flag)

Community features and insights that drive user engagement and retention. These features are developed behind feature flags to allow controlled rollout and A/B testing.

### Social (minimal first) ❌
```
❌ # Comments
❌ GET    /api/v1/quizzes/{quizId}/comments
❌ POST   /api/v1/quizzes/{quizId}/comments
❌ PUT    /api/v1/comments/{commentId}
❌ DELETE /api/v1/comments/{commentId}

❌ # Bookmarks
❌ POST   /api/v1/quizzes/{quizId}/bookmark
❌ DELETE /api/v1/quizzes/{quizId}/bookmark
❌ GET    /api/v1/users/bookmarks

❌ # Reports
❌ POST /api/v1/reports
```

### Analytics (events + simple per-quiz view) 🔄
- ✅ Emit events: viewed/started/completed, Q shown/answered, AI gen requested/completed, moderation actions
- 🔄 In-app analytics view per quiz (counts, completion rate, avg score, difficulty histogram)

---

## Implementation Sequencing (6–8 Weeks)

A detailed timeline for delivering the MVP, broken down into manageable two-week sprints with clear deliverables and dependencies.

### **Weeks 1–2 (Foundation)** ✅
- ✅ Share-links end-to-end + revoke + cookie scope  
- ✅ Moderation flows + audits  
- 🔄 Search (public) + simple facets; ETags  
- ✅ Job pattern unification; correlation IDs

### **Weeks 3–4 (Monetization & polish)** 🔄
- ❌ Payments for AI gen (packs, webhook, ledger, reserve/commit/release)  
- ❌ Rate limits; indexes; error contract  
- 🔄 Creator ergonomics: duplicate quiz, preview

### **Weeks 5–6 (Engagement & insights)** ❌
- ❌ Minimal social (comments/bookmarks/report) behind feature flag  
- 🔄 Per-quiz analytics view; N+1 fixes; caching on hot reads

### **Weeks 7–8 (QA & launch)** ❌
- ❌ Docs/OpenAPI/Postman; landing + onboarding polish  
- ❌ Bulk import CSV/JSON; leaderboard polish  
- ❌ Launch playbook + rollout

### **Phase 3+ (B2B)** ❌
- ❌ Orgs/RBAC, org wallets, org catalog, exports; SSO-ready data model

---

## Future / Advanced (Kept, Not in MVP)

Advanced features and capabilities that extend beyond the core MVP but are important for long-term platform vision and competitive differentiation.

- ❌ Security extras (2FA, sessions, OAuth, API keys, GDPR, etc.)
- ❌ Deep analytics & reporting, BI endpoints
- ❌ Advanced quiz modes (adaptive, collaborative, challenges)
- ❌ Scheduling, advanced question types
- ❌ Integrations (LMS, analytics, calendar, webhooks mgmt)
- ❌ Mobile/PWA & accessibility, TTS/voice
- ❌ Admin ops, cache/rate-limit/job consoles
- ❌ Moonshots (EEG adaptive, quantum certs, spatial worlds)

---

## Appendix A — Endpoint Manifest (Union, No Losses)

A comprehensive catalog of all planned API endpoints across all phases of development. This serves as the master reference for API design and ensures no functionality is lost during planning.

> Grouped; duplicates kept minimal. Everything here is retained across versions.

### 🔐 Auth / Security / Accounts
Comprehensive authentication, authorization, and account security endpoints including 2FA, OAuth, API keys, and GDPR compliance features.
```
✅ POST /api/v1/auth/register
✅ POST /api/v1/auth/login
✅ POST /api/v1/auth/refresh
✅ POST /api/v1/auth/logout
✅ GET  /api/v1/auth/me
❌ POST /api/v1/auth/forgot-password
❌ POST /api/v1/auth/reset-password
❌ POST /api/v1/auth/change-password
❌ POST /api/v1/auth/verify-email
❌ POST /api/v1/auth/resend-verification

❌ GET    /api/v1/auth/sessions
❌ DELETE /api/v1/auth/sessions/{sessionId}
❌ GET    /api/v1/auth/login-history

❌ POST /api/v1/auth/2fa/setup
❌ POST /api/v1/auth/2fa/verify
❌ POST /api/v1/auth/2fa/disable
❌ GET  /api/v1/auth/2fa/backup-codes

❌ GET  /api/v1/auth/oauth/providers
❌ GET  /api/v1/auth/oauth/{provider}/authorize
❌ POST /api/v1/auth/oauth/{provider}/callback

❌ GET    /api/v1/auth/api-keys
❌ POST   /api/v1/auth/api-keys
❌ DELETE /api/v1/auth/api-keys/{keyId}

❌ POST /api/v1/users/export-data
❌ POST /api/v1/users/delete-account
❌ GET  /api/v1/users/consent-status
❌ PUT  /api/v1/users/consent
```

### 👤 Users
User management endpoints for profile operations, admin functions, and user lifecycle management.
```
❌ GET    /api/v1/users/me
❌ PATCH  /api/v1/users/me
❌ POST   /api/v1/users/me/avatar
✅ GET    /api/v1/admin/users

❌ GET    /api/v1/users
❌ GET    /api/v1/users/{userId}
❌ PATCH  /api/v1/users/{userId}
❌ DELETE /api/v1/users/{userId}
❌ POST   /api/v1/users/{userId}/activate
❌ POST   /api/v1/users/{userId}/deactivate
❌ GET    /api/v1/users/{userId}/stats
❌ PATCH  /api/v1/users/{userId}/roles
```

### 🔍 Search / Discovery
Advanced search and discovery features including faceted search, autocomplete, saved searches, and personalized recommendations.
```
🔄 GET /api/v1/quizzes?search=...&page=...   # via QuizSearchCriteria
❌ GET /api/v1/search?type=quiz&q=...&page=...
❌ GET /api/v1/search/quizzes
❌ GET /api/v1/search/users
❌ GET /api/v1/search/questions
❌ GET /api/v1/search/autocomplete
❌ GET /api/v1/search/suggestions
❌ GET /api/v1/search/filters
❌ POST /api/v1/search/save-search
❌ GET /api/v1/search/saved-searches

❌ GET /api/v1/discover/trending
❌ GET /api/v1/discover/recommended
❌ GET /api/v1/discover/nearby
```

### 📁 Files / Media
File upload, management, and media processing capabilities including image resizing and video streaming.
```
✅ POST   /api/documents/upload
✅ GET    /api/documents/{documentId}
✅ DELETE /api/documents/{documentId}
✅ GET    /api/documents
❌ POST   /api/v1/files/upload
❌ GET    /api/v1/files/{fileId}
❌ DELETE /api/v1/files/{fileId}
❌ GET    /api/v1/files/user/{userId}

❌ POST   /api/v1/media/images/upload
❌ GET    /api/v1/media/images/{imageId}
❌ POST   /api/v1/media/images/{imageId}/resize
❌ DELETE /api/v1/media/images/{imageId}

❌ POST /api/v1/media/videos/upload
❌ GET  /api/v1/media/videos/{videoId}/stream
❌ POST /api/v1/media/videos/{videoId}/thumbnail
```

### 📝 Quizzes (build + ops)
Advanced quiz creation and management features including duplication, export/import, analytics, and scheduling.
```
✅ POST /api/v1/quizzes
✅ GET  /api/v1/quizzes
✅ GET  /api/v1/quizzes/{quizId}
✅ PATCH /api/v1/quizzes/{quizId}
✅ DELETE /api/v1/quizzes/{quizId}
✅ DELETE /api/v1/quizzes?ids=...
✅ POST /api/v1/quizzes/{quizId}/questions/{questionId}
✅ DELETE /api/v1/quizzes/{quizId}/questions/{questionId}
✅ POST /api/v1/quizzes/{quizId}/tags/{tagId}
✅ DELETE /api/v1/quizzes/{quizId}/tags/{tagId}
✅ PATCH /api/v1/quizzes/{quizId}/category/{categoryId}
✅ GET  /api/v1/quizzes/{quizId}/results
✅ GET  /api/v1/quizzes/{quizId}/leaderboard
✅ PATCH /api/v1/quizzes/{quizId}/visibility
✅ PATCH /api/v1/quizzes/{quizId}/status
✅ GET  /api/v1/quizzes/public
✅ POST /api/v1/quizzes/{quizId}/submit-for-review
✅ POST /api/v1/quizzes/{quizId}/unpublish
❌ POST /api/v1/quizzes/{quizId}/duplicate
❌ POST /api/v1/quizzes/{quizId}/export
❌ POST /api/v1/quizzes/import
❌ GET  /api/v1/quizzes/{quizId}/analytics
❌ GET  /api/v1/quizzes/{quizId}/preview
❌ GET  /api/v1/quizzes/{quizId}/versions
❌ POST /api/v1/quizzes/{quizId}/clone-with-modifications
❌ PUT  /api/v1/quizzes/{quizId}/bulk-questions

❌ POST   /api/v1/quizzes/{quizId}/schedule
❌ GET    /api/v1/quizzes/scheduled
❌ PUT    /api/v1/quizzes/{quizId}/schedule/{scheduleId}
❌ DELETE /api/v1/quizzes/{quizId}/schedule/{scheduleId}
```

### 🔗 Share-Links (quizzes) ✅
Secure sharing functionality with token-based access control and usage tracking.
```
✅ POST   /api/v1/quizzes/{quizId}/share-link
✅ GET    /api/v1/quizzes/shared/{token}
✅ GET    /api/v1/quizzes/shared/{token}/consume
✅ DELETE /api/v1/quizzes/shared/{tokenId}
✅ GET    /api/v1/quizzes/share-links
```

### 📊 Attempts ✅
Advanced attempt management including review capabilities, restart functionality, and cross-quiz analysis.
```
✅ POST /api/v1/attempts/quizzes/{quizId}
✅ GET  /api/v1/attempts
✅ GET  /api/v1/attempts/{attemptId}
✅ GET  /api/v1/attempts/{attemptId}/current-question
✅ POST /api/v1/attempts/{attemptId}/answers
✅ POST /api/v1/attempts/{attemptId}/answers/batch
✅ POST /api/v1/attempts/{attemptId}/complete
✅ GET  /api/v1/attempts/{attemptId}/stats
✅ POST /api/v1/attempts/{attemptId}/pause
✅ POST /api/v1/attempts/{attemptId}/resume
✅ DELETE /api/v1/attempts/{attemptId}
✅ GET  /api/v1/attempts/quizzes/{quizId}/questions/shuffled
❌ GET    /api/v1/attempts/{attemptId}/review
❌ POST   /api/v1/attempts/{attemptId}/restart
❌ GET    /api/v1/attempts/active
❌ POST   /api/v1/attempts/{attemptId}/notes
❌ GET    /api/v1/attempts/cross-quiz-analysis
```

### ✅ Moderation / Publishing ✅
Content moderation workflow and publishing controls for maintaining platform quality and compliance.
```
✅ POST /api/v1/quizzes/{id}/submit-for-review
✅ POST /api/v1/admin/quizzes/{id}/approve
✅ POST /api/v1/admin/quizzes/{id}/reject
✅ POST /api/v1/quizzes/{id}/unpublish
✅ GET  /api/v1/admin/quizzes/pending-review
✅ GET  /api/v1/admin/quizzes/{quizId}/audits
❌ POST /api/v1/admin/content/{contentId}/flag
```

### 👥 Social & Community ❌
Community features including comments, ratings, following, bookmarks, and notification systems.
```
❌ # Comments
❌ GET    /api/v1/quizzes/{quizId}/comments
❌ POST   /api/v1/quizzes/{quizId}/comments
❌ PUT    /api/v1/comments/{commentId}
❌ DELETE /api/v1/comments/{commentId}

❌ # Ratings
❌ POST   /api/v1/quizzes/{quizId}/rate
❌ GET    /api/v1/quizzes/{quizId}/ratings
❌ DELETE /api/v1/quizzes/{quizId}/rate

❌ # Following
❌ POST   /api/v1/users/{userId}/follow
❌ DELETE /api/v1/users/{userId}/follow
❌ GET    /api/v1/users/{userId}/followers
❌ GET    /api/v1/users/{userId}/following

❌ # Bookmarks
❌ POST   /api/v1/quizzes/{quizId}/bookmark
❌ DELETE /api/v1/quizzes/{quizId}/bookmark
❌ GET    /api/v1/users/bookmarks

❌ # Notifications
❌ GET    /api/v1/notifications
❌ POST   /api/v1/notifications/{notificationId}/read
❌ DELETE /api/v1/notifications/{notificationId}
❌ POST   /api/v1/notifications/mark-all-read
❌ GET    /api/v1/notifications/settings
❌ PUT    /api/v1/notifications/settings
```

### 📈 Analytics & Reporting 🔄
Comprehensive analytics and reporting capabilities including performance metrics, learning analytics, and custom reporting.
```
🔄 # Quiz analytics
🔄 GET /api/v1/quizzes/{quizId}/results
🔄 GET /api/v1/quizzes/{quizId}/leaderboard
❌ GET /api/v1/analytics/quizzes/{quizId}/performance
❌ GET /api/v1/analytics/quizzes/{quizId}/completion-rates
❌ GET /api/v1/analytics/quizzes/{quizId}/question-difficulty
❌ GET /api/v1/analytics/quizzes/{quizId}/time-analysis

❌ # User analytics
❌ GET /api/v1/analytics/users/{userId}/learning-path
❌ GET /api/v1/analytics/users/{userId}/knowledge-gaps
❌ GET /api/v1/analytics/users/{userId}/progress-trends

❌ # System analytics
❌ GET /api/v1/analytics/system/usage-stats
❌ GET /api/v1/analytics/system/popular-content
❌ GET /api/v1/analytics/system/user-engagement

❌ # Reporting
❌ POST /api/v1/reports/generate
❌ GET  /api/v1/reports/{reportId}
❌ GET  /api/v1/reports/templates
❌ POST /api/v1/reports/schedule

❌ # Export
❌ GET /api/v1/export/quiz/{quizId}/pdf
❌ GET /api/v1/export/quiz/{quizId}/excel
❌ GET /api/v1/export/results/{attemptId}/pdf

❌ # Advanced reporting
❌ POST /api/v1/reports/custom
❌ GET  /api/v1/reports/custom/{reportId}/data
❌ POST /api/v1/reports/custom/{reportId}/share
❌ GET  /api/v1/reports/dashboard

❌ # Data export
❌ POST /api/v1/export/bulk-data
❌ GET  /api/v1/export/{exportId}/status
❌ GET  /api/v1/export/{exportId}/download

❌ # ML features
❌ GET  /api/v1/ml/user-clustering
❌ GET  /api/v1/ml/content-recommendations
❌ GET  /api/v1/ml/difficulty-prediction
❌ POST /api/v1/ml/retrain-models
```

### 🎯 Advanced Quiz Modes & Questions ❌
Innovative quiz formats including adaptive learning, collaborative quizzes, and timed challenges.
```
❌ # Adaptive
❌ POST /api/v1/quizzes/{quizId}/adaptive-session
❌ GET  /api/v1/quizzes/{quizId}/difficulty-adjustment

❌ # Collaborative
❌ POST /api/v1/quizzes/collaborative
❌ POST /api/v1/quizzes/{quizId}/invite-collaborators
❌ GET  /api/v1/quizzes/{quizId}/collaboration-status

❌ # Timed challenges
❌ POST /api/v1/quizzes/{quizId}/speed-challenge
❌ GET  /api/v1/challenges/daily
❌ POST /api/v1/challenges/custom
❌ POST /api/v1/challenges/{challengeId}/participate
❌ GET  /api/v1/challenges/{challengeId}/leaderboard
```

### 🎨 Personalization / Learning Paths / Accessibility / Offline ❌
Personalized learning experiences, accessibility features, and offline capabilities for inclusive education.
```
❌ # Recommendations
❌ GET /api/v1/recommendations/quizzes
❌ GET /api/v1/recommendations/topics
❌ GET /api/v1/recommendations/users

❌ # Learning paths
❌ GET  /api/v1/learning-paths
❌ POST /api/v1/learning-paths
❌ GET  /api/v1/learning-paths/{pathId}/progress
❌ POST /api/v1/learning-paths/{pathId}/enroll

❌ # Preferences & notifications
❌ GET /api/v1/users/preferences
❌ PUT /api/v1/users/preferences
❌ GET /api/v1/users/notification-settings
❌ PUT /api/v1/users/notification-settings

❌ # Accessibility
❌ GET /api/v1/accessibility/options
❌ PUT /api/v1/users/accessibility-settings
❌ GET /api/v1/quizzes/{quizId}/accessibility-info

❌ # Text-to-Speech / Voice
❌ POST /api/v1/tts/generate
❌ GET  /api/v1/tts/{audioId}
❌ POST /api/v1/voice/speech-to-text

❌ # Offline
❌ GET  /api/v1/sync/manifest
❌ POST /api/v1/sync/upload
❌ GET  /api/v1/sync/conflicts
❌ POST /api/v1/sync/resolve
```

### ⚙️ Admin / Ops / Config 🔄
Administrative tools and operational features for platform management, monitoring, and configuration.
```
✅ GET  /api/v1/admin/users
✅ GET  /api/v1/admin/roles
✅ GET  /api/v1/admin/roles/{roleId}
✅ POST /api/v1/admin/roles
✅ PUT  /api/v1/admin/roles/{roleId}
✅ GET  /api/v1/health
❌ # System
❌ GET  /api/v1/admin/system/health-detailed
❌ POST /api/v1/admin/system/maintenance-mode
❌ GET  /api/v1/admin/system/metrics
❌ POST /api/v1/admin/system/cache/clear

❌ # Users
❌ GET  /api/v1/admin/users/suspicious-activity
❌ POST /api/v1/admin/users/{userId}/ban
❌ POST /api/v1/admin/users/{userId}/unban
❌ GET  /api/v1/admin/users/inactive
❌ POST /api/v1/admin/users/bulk-action

❌ # Content moderation
❌ GET  /api/v1/admin/content/flagged
❌ POST /api/v1/admin/content/{contentId}/approve
❌ POST /api/v1/admin/content/{contentId}/reject
❌ GET  /api/v1/admin/reports/abuse

❌ # Settings & flags
❌ GET  /api/v1/admin/settings
❌ PUT  /api/v1/admin/settings
❌ GET  /api/v1/admin/feature-flags
❌ PUT  /api/v1/admin/feature-flags/{flagId}

❌ # Email templates
❌ GET  /api/v1/admin/email-templates
❌ PUT  /api/v1/admin/email-templates/{templateId}
❌ POST /api/v1/admin/email-templates/test
```

### 🚀 Cache / Rate Limits / Jobs ❌
Performance optimization and operational management including caching, rate limiting, and background job management.
```
❌ GET    /api/v1/cache/stats
❌ POST   /api/v1/cache/warm-up
❌ DELETE /api/v1/cache/{cacheKey}
❌ POST   /api/v1/cache/invalidate-pattern

❌ GET  /api/v1/rate-limit/status
❌ GET  /api/v1/rate-limit/quotas
❌ POST /api/v1/rate-limit/request-increase

❌ GET  /api/v1/jobs
❌ GET  /api/v1/jobs/{jobId}
❌ POST /api/v1/jobs/{jobId}/cancel
❌ POST /api/v1/jobs/{jobId}/retry
❌ GET  /api/v1/jobs/failed
```

### 🔌 Integrations & APIs ❌
Third-party integrations and API management including webhooks, LMS integration, and API versioning.
```
❌ # Webhooks mgmt
❌ GET    /api/v1/webhooks
❌ POST   /api/v1/webhooks
❌ PUT    /api/v1/webhooks/{webhookId}
❌ DELETE /api/v1/webhooks/{webhookId}
❌ GET    /api/v1/webhooks/{webhookId}/deliveries
❌ POST   /api/v1/webhooks/{webhookId}/test

❌ # LMS
❌ POST /api/v1/integrations/lms/sync
❌ GET  /api/v1/integrations/lms/courses
❌ POST /api/v1/integrations/lms/grade-passback

❌ # Analytics integrations
❌ POST /api/v1/integrations/analytics/track-event
❌ GET  /api/v1/integrations/analytics/reports

❌ # Calendar
❌ POST /api/v1/integrations/calendar/schedule-quiz
❌ GET  /api/v1/integrations/calendar/events

❌ # API versioning
❌ GET /api/versions
❌ GET /api/v1/deprecation-notices
# (future) /api/v2/...
```

### 📱 Mobile / PWA ❌
Mobile application support and Progressive Web App features for cross-platform accessibility.
```
❌ # Mobile push
❌ POST /api/v1/mobile/register-device
❌ PUT  /api/v1/mobile/device-settings
❌ POST /api/v1/mobile/push-test

❌ # Mobile sync
❌ GET  /api/v1/mobile/sync-manifest
❌ POST /api/v1/mobile/sync-data
❌ GET  /api/v1/mobile/sync-status

❌ # PWA
❌ GET /api/v1/pwa/manifest
❌ GET /api/v1/pwa/service-worker
❌ GET /api/v1/pwa/offline-resources
```

### 📋 Content Templates ❌
Template system for creating reusable quiz structures and question patterns.
```
❌ # Quiz templates
❌ GET  /api/v1/templates/quizzes
❌ POST /api/v1/templates/quizzes
❌ GET  /api/v1/templates/quizzes/{templateId}
❌ POST /api/v1/quizzes/from-template/{templateId}

❌ # Question templates
❌ GET  /api/v1/templates/questions
❌ POST /api/v1/templates/questions
```

---

## Appendix B — Data/Infra Notes

Technical infrastructure considerations and database optimization strategies for ensuring platform performance and scalability.

- **Indexes:** quizzes(status,visibility,created_at); jobs; tags join; ledger; share_links(expiry)
- **Idempotency store:** (key, actorId, firstSeenAt, resultHash, ttl)
- **Observability:** correlation id (req→job→webhook), counters for AI gen/ledger/moderation
- **Security quick wins:** JWT secrets via env/secret manager, bootstrap superadmin, `@PreAuthorize` on all mutations, MIME/type/size checks, rate limits

---

## Appendix C — Payments (Later-Phase Full Surface, Preserved)

Comprehensive payment and subscription management system for future monetization beyond the initial AI token model.

_Subscriptions & purchases beyond AI-gen-only MVP:_

```
❌ # Plans & subscriptions
❌ GET  /api/v1/payments/plans
❌ GET  /api/v1/payments/plans/{planId}
❌ POST /api/v1/payments/subscriptions/create
❌ GET  /api/v1/payments/subscriptions/current
❌ PATCH /api/v1/payments/subscriptions/change-plan
❌ POST /api/v1/payments/subscriptions/cancel
❌ POST /api/v1/payments/subscriptions/reactivate

❌ # Payment methods
❌ GET    /api/v1/payments/payment-methods
❌ POST   /api/v1/payments/payment-methods/attach
❌ DELETE /api/v1/payments/payment-methods/{paymentMethodId}
❌ PATCH  /api/v1/payments/payment-methods/{paymentMethodId}/set-default

❌ # Billing
❌ GET  /api/v1/payments/billing/history
❌ GET  /api/v1/payments/billing/upcoming
❌ POST /api/v1/payments/billing/update-details
❌ GET  /api/v1/payments/billing/download-invoice/{invoiceId}

❌ # One-time purchases
❌ POST /api/v1/payments/purchases/create-intent
❌ POST /api/v1/payments/purchases/confirm
❌ GET  /api/v1/payments/purchases/status/{purchaseId}
❌ GET  /api/v1/payments/purchases/history
❌ POST /api/v1/payments/purchases/quiz/{quizId}
❌ POST /api/v1/payments/purchases/ai-credits
❌ POST /api/v1/payments/purchases/export/{type}
❌ POST /api/v1/payments/purchases/analytics-report/{reportType}
❌ GET  /api/v1/payments/purchases/owned-content
❌ POST /api/v1/payments/purchases/{purchaseId}/request-refund

❌ # Usage & features
❌ GET  /api/v1/payments/usage/current
❌ GET  /api/v1/payments/usage/limits
❌ GET  /api/v1/payments/usage/history
❌ GET  /api/v1/payments/features/available
❌ POST /api/v1/payments/features/check-access

❌ # Stripe webhook (already in MVP)
❌ POST /api/v1/payments/webhooks/stripe
```

---

## Appendix D — Moonshots

Cutting-edge experimental features that push the boundaries of educational technology and could provide significant competitive advantages in the future.

- ❌ **Neural-adaptive quizzes** (EEG-based difficulty)
- ❌ **Quantum-encrypted verification**
- ❌ **Spatial/AR/VR quiz worlds**
