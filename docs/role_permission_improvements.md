# Role & Permission Improvements — Comprehensive Plan
_Repo scan date: 2025‑08‑10 (Europe/London)_

This document captures (1) **current state** of authz in the QuizMaker repo, and (2) a **comprehensive plan** to evolve it for companies / groups / departments, fine‑grained sharing, and public‑with‑moderation publishing. It also lists **endpoints** to add and exact **implementation steps**.

---

## 1) Current State (what’s in the code today)

**Core entities & enums**
- `model.user.Role` ↔ `model.user.Permission` (many‑to‑many via `role_permissions`) and `User` ↔ `Role` (many‑to‑many via `user_roles`).
- `model.user.RoleName` (enum): `ROLE_USER`, `ROLE_QUIZ_CREATOR`, `ROLE_MODERATOR`, `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`.
- `model.user.PermissionName` (enum) with resource‑action style codes (`QUIZ_READ`, `QUIZ_CREATE`, `ATTEMPT_START`, etc.).

**Service / config**
- `service.admin.RoleService` (+ `impl.RoleServiceImpl`) exposes role CRUD and seeding.
- `config.DataInitializer` calls `roleService.initializeDefaultRolesAndPermissions()` at startup.
- `security.PermissionEvaluator` + `security.PermissionUtil` helpers for permission/role checks.
- `repository.user.*` includes eager fetching helpers to reduce N+1 on roles/permissions.

**Quizzes**
- `model.quiz.Visibility`: `PUBLIC`, `PRIVATE`.
- `model.quiz.QuizStatus`: `PUBLISHED`, `DRAFT`, `ARCHIVED`.
- `Quiz` has soft‑delete flags and sensible defaults set in `@PrePersist`.  
- Public catalog today is effectively “`status=PUBLISHED` and `visibility=PUBLIC`” (enforced at controller/service level).

**What’s *not* there yet**
- No **scope/tenancy** on role assignments (e.g., no ORG/DEPT/GROUP binding).
- No **organizations/departments/groups** entities.
- No **ACL** table for resource‑level shares.
- No built‑in **bootstrap superadmin** flow (avoid manual DB insert).
- Moderation flow not explicit (no `PENDING_REVIEW` / `REJECTED` status, no moderation metadata on `Quiz`).

---

## 2) Goals

1. Support **multi‑tenant boundaries** (organizations; optional departments; optional groups).
2. Clean **RBAC** (roles → permission bundles) with **scope** (SYSTEM/ORG/DEPT/GROUP).
3. Lightweight **ACL** to share specific resources to users/groups/depts/org.
4. **Public catalog with moderation**: normal users can submit quizzes for review; moderators approve → publish to public.
5. Keep it **maintainable**: central registry of permissions; minimize ad‑hoc per‑user grants; clear state machine for quizzes.
6. Avoid breaking existing behavior for single‑user mode.

---

## 3) Target Authorization Model (RBAC + ACL + ABAC)

- **RBAC with scope:** a role assignment is `(user, role, scopeType, scopeId?, expiresAt?)` where `scopeType ∈ {SYSTEM, ORG, DEPT, GROUP}`.
- **ACL:** exceptions at the resource level using a small `resource_shares` table.
- **ABAC guardrail:** always ensure `user.org == resource.org` (for tenant data), and dept constraints where applicable.
- **Principle:** use **roles** for 95% of power; use **ACL** for sharing visibility/edit of a specific resource; allow **org‑custom roles** built from a curated permission catalog.

---

## 4) Data Model Additions & Changes

### 4.1 Tenancy & directories
```sql
-- Organizations and membership
CREATE TABLE organizations (
  id           BINARY(16) PRIMARY KEY,
  name         VARCHAR(200) NOT NULL,
  slug         VARCHAR(100) UNIQUE,
  owner_user_id BINARY(16) NULL,
  created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE memberships (
  id        BINARY(16) PRIMARY KEY,
  org_id    BINARY(16) NOT NULL,
  user_id   BINARY(16) NOT NULL,
  joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(org_id, user_id)
);

-- Departments (hierarchical via parent_id + path)
CREATE TABLE departments (
  id        BINARY(16) PRIMARY KEY,
  org_id    BINARY(16) NOT NULL,
  name      VARCHAR(150) NOT NULL,
  parent_id BINARY(16) NULL,
  path      VARCHAR(500) NOT NULL  -- e.g., /<org>/<dept>/<subdept>
);

-- Groups (ad‑hoc cohorts)
CREATE TABLE groups (
  id        BINARY(16) PRIMARY KEY,
  org_id    BINARY(16) NOT NULL,
  dept_id   BINARY(16) NULL,
  name      VARCHAR(150) NOT NULL
);

CREATE TABLE group_memberships (
  id           BINARY(16) PRIMARY KEY,
  group_id     BINARY(16) NOT NULL,
  membership_id BINARY(16) NOT NULL,
  UNIQUE(group_id, membership_id)
);
```

### 4.2 Scoped role bindings (replace or augment `user_roles`)
```sql
CREATE TABLE user_role_bindings (
  id          BINARY(16) PRIMARY KEY,
  user_id     BINARY(16) NOT NULL,
  role_id     BIGINT NOT NULL,              -- references roles.id
  scope_type  VARCHAR(16) NOT NULL,         -- SYSTEM|ORG|DEPT|GROUP
  scope_id    BINARY(16) NULL,              -- org_id or dept_id or group_id when applicable
  expires_at  TIMESTAMP NULL,
  granted_by  BINARY(16) NULL
);

-- Option: keep old user_roles for SYSTEM scope only during migration, then retire.
```

### 4.3 Resource‑level sharing (ACL)
```sql
CREATE TABLE resource_shares (
  id              BINARY(16) PRIMARY KEY,
  resource_type   VARCHAR(50) NOT NULL,     -- e.g., 'Quiz'
  resource_id     BINARY(16) NOT NULL,
  principal_type  VARCHAR(16) NOT NULL,     -- USER|GROUP|DEPT|ORG
  principal_id    BINARY(16) NOT NULL,
  permission_code VARCHAR(60) NOT NULL,     -- e.g., 'QUIZ_READ' or 'QUIZ_UPDATE'
  expires_at      TIMESTAMP NULL,
  granted_by      BINARY(16) NULL
);
CREATE INDEX ix_shares_lookup ON resource_shares(resource_type, resource_id);
```

### 4.4 Tenant columns on resources
Add `org_id` (and optional `dept_id`) to tenantable resources:
- `quizzes(org_id, dept_id NULL, owner_user_id)`
- `attempts(org_id)` (*derived from quiz*)
- `questions(org_id)` (*or attach through quiz; avoid duplication if each question belongs to a quiz*)

### 4.5 Moderation metadata for public publishing
Extend quiz workflow:
```sql
ALTER TABLE quizzes
  ADD COLUMN submitted_for_review_at TIMESTAMP NULL,
  ADD COLUMN reviewed_at TIMESTAMP NULL,
  ADD COLUMN reviewed_by BINARY(16) NULL,
  ADD COLUMN rejection_reason VARCHAR(500) NULL,
  ADD COLUMN publish_on_approve BOOLEAN NULL DEFAULT FALSE,
  ADD COLUMN locked_for_review BOOLEAN NULL DEFAULT FALSE;
```

Extend enums in code:
```java
public enum QuizStatus { DRAFT, PENDING_REVIEW, PUBLISHED, REJECTED, ARCHIVED }
public enum Visibility { PUBLIC, PRIVATE } // keep as is for now
```

---

## 5) Roles & Permissions — Catalog and Bundles

- Keep `PermissionName` enum as the **registry** (stable codes):  
  `QUIZ_READ, QUIZ_LIST, QUIZ_CREATE, QUIZ_UPDATE, QUIZ_DELETE, QUIZ_PUBLISH, QUIZ_MODERATE, QUESTION_*, ATTEMPT_*, MEMBER_*, AI_*`.
- **System roles** (enum, seeded): `ROLE_USER` (default), `ROLE_QUIZ_CREATOR`, `ROLE_MODERATOR`, `ROLE_ADMIN`, `ROLE_SUPER_ADMIN`.
- **Org custom roles**: add DB‑backed roles with `scope=ORG` editable by org admins from a **curated permission set** (no system‑level perms).

**Best practice:** avoid per‑user global permission sprinkling; use role bundles + ACL only for resource exceptions.

---

## 6) Authorization Execution

### 6.1 Method‑level checks
Use Spring `@PreAuthorize` with a central `@Component("auth")` service (or expand your existing `PermissionEvaluator`) that understands scope + ACL:

```java
@PreAuthorize("@auth.can(authentication, 'QUIZ_CREATE', #orgId)")
@PostMapping("/orgs/{orgId}/quizzes")
public QuizDto create(...)
```

Object permission check:
```java
@PreAuthorize("@auth.hasPermission(authentication, #quizId, 'Quiz', 'UPDATE')")
@PutMapping("/quizzes/{quizId}")
public QuizDto update(...)
```

### 6.2 Query‑time filtering
Never leak via list endpoints. For public catalog:
```sql
WHERE status = 'PUBLISHED' AND visibility = 'PUBLIC' AND is_deleted = false
```
For “what I can see” endpoints, combine:
- org/dept membership (ABAC),
- role‑derived permissions (RBAC),
- ACL rows for explicit shares.

### 6.3 Caching
Cache **effective permissions** per `(userId, scopeType, scopeId)` in request scope; optionally short‑ttl cache for repeated calls.

---

## 7) Endpoints to Add / Adjust

### 7.1 Directory & tenancy
```
POST   /orgs                             -- create org (system/admin)
GET    /orgs/{orgId}
POST   /orgs/{orgId}/members             -- invite/add user
DELETE /orgs/{orgId}/members/{userId}

POST   /orgs/{orgId}/departments         -- create dept (supports parent_id)
GET    /orgs/{orgId}/departments
POST   /orgs/{orgId}/groups              -- create group
POST   /orgs/{orgId}/groups/{groupId}/members
```

### 7.2 Roles & bindings (scoped RBAC)
```
POST   /orgs/{orgId}/roles               -- create custom role (ORG scope)
PUT    /orgs/{orgId}/roles/{roleId}      -- update description/permissions
DELETE /orgs/{orgId}/roles/{roleId}

POST   /roles/bindings                   -- assign role (user_id, role_id, scope_type, scope_id?, expires_at?)
DELETE /roles/bindings/{bindingId}
GET    /users/{userId}/bindings          -- list bindings for a user
```

**Request (role binding) example:**
```json
{
  "userId": "…",
  "roleId": 42,
  "scopeType": "ORG",
  "scopeId": "…",
  "expiresAt": "2026-01-01T00:00:00Z"
}
```

### 7.3 Resource sharing (ACL)
```
POST   /quizzes/{quizId}/share           -- body: { principalType, principalId, permissionCode, expiresAt? }
DELETE /quizzes/{quizId}/share/{shareId}
GET    /quizzes/{quizId}/shares
```

### 7.4 Public publishing with moderation
```
POST   /quizzes/{quizId}/submit-review   -- owner request; params: publishOnApprove=true|false
POST   /quizzes/{quizId}/approve         -- moderator only; sets status=PUBLISHED; if publishOnApprove then visibility=PUBLIC
POST   /quizzes/{quizId}/reject          -- moderator only; body: { reason }
POST   /quizzes/{quizId}/unpublish       -- owner or moderator; status=DRAFT
GET    /moderation/queue                 -- list PENDING_REVIEW (moderator)
```

**Controller guards (illustrative):**
```java
@PreAuthorize("@auth.isOwner(#quizId) and @auth.can(authentication, 'QUIZ_UPDATE')")
@PostMapping("/quizzes/{quizId}/submit-review") ...

@PreAuthorize("@auth.can(authentication, 'QUIZ_MODERATE')")
@PostMapping("/quizzes/{quizId}/approve") ...

@PreAuthorize("@auth.can(authentication, 'QUIZ_MODERATE')")
@PostMapping("/quizzes/{quizId}/reject") ...

@PreAuthorize("@auth.isOwner(#quizId) or @auth.can(authentication, 'QUIZ_MODERATE')")
@PostMapping("/quizzes/{quizId}/unpublish") ...
```

---

## 8) Bootstrap Superuser (no manual DB inserts)

Add a startup runner (extend `DataInitializer` or add `BootstrapAdminRunner`):
- If `userRepository.count() == 0` and env has `APP_BOOTSTRAP_ADMIN_EMAIL` and `…_PASSWORD`, **create** a user with `ROLE_SUPER_ADMIN`.
- Force password change on first login; log a one‑time “admin created” message.
- Disable bootstrapping after success.

Env vars:
```
APP_BOOTSTRAP_ADMIN_EMAIL=…
APP_BOOTSTRAP_ADMIN_PASSWORD=…
```

---

## 9) Step‑by‑Step Implementation Plan (phased, low risk)

### Phase 1 — Foundation (1–2 days)
1. **Permission registry sanity pass**: confirm `PermissionName` covers all verbs (quiz, question, attempt, member, ai, analytics).
2. **Bootstrap admin** via env (Option A).
3. Add **new quiz statuses** (`PENDING_REVIEW`, `REJECTED`) + moderation fields; implement four moderation endpoints and service logic.  
   - Definition of Done: public listing uses `status=PUBLISHED` and `visibility=PUBLIC`; moderation queue works; events audited.

### Phase 2 — Tenancy skeleton (2–4 days)
4. Create `organizations`, `memberships`, `departments` (hierarchical), `groups` + memberships.
5. Add `org_id` (and optional `dept_id`) to `Quiz` (and `Attempt` if needed).  
   - Migrate existing data to a personal org (e.g., “Personal @ <username>”).
6. Add minimal **directory endpoints** (create org/dept/group; add members).

### Phase 3 — Scoped RBAC (3–5 days)
7. Introduce `user_role_bindings` and `ScopeType`.  
   - Migrate existing `user_roles` to **SYSTEM** scope bindings (for existing deployments).
8. Add endpoints to **create custom org roles** and **assign bindings**.  
   - Update `PermissionEvaluator` (or new `AuthorizationService`) to collect effective permissions from bindings by scope.

### Phase 4 — ACL for resource sharing (2–3 days)
9. Create `resource_shares` + service + endpoints (`POST/DELETE/GET`).  
   - Extend `@auth.hasPermission` to merge ACL with RBAC.  
   - Add repository **Specifications** for “what I can see” list endpoints (public, org/dept, group, ACL).

### Phase 5 — Hardening & DX (ongoing)
10. Add request‑scoped **permission cache**.  
11. Add **audit logging** for role bindings and shares (who granted what, when).  
12. Add **tests**: unit for authz service; integration for endpoint guards; negative tests to catch leaks.
13. Update **OpenAPI** and README with tenant & moderation docs.

---

## 10) Testing Strategy

- **Unit tests** for `AuthorizationService` decision matrix:
  - Matrix: roles × scopes × resource visibility × ACL share combinations.
- **Integration tests** around critical endpoints:
  - “Learner cannot update others’ quiz”,
  - “Creator can submit for review but not approve”,
  - “Moderator sees pending queue; approve publishes to public if requested”,
  - “ACL share grants read to specific group only”.
- **Repository tests** for list Specifications (no cross‑tenant leakage).

---

## 11) Backwards Compatibility & Migration Notes

- Existing installs without orgs: create a **personal org** for each existing user and attach their quizzes.
- Keep `user_roles` temporarily; introduce `user_role_bindings` in parallel and migrate gradually, then retire `user_roles`.
- Public behavior remains: published + public means visible to all.
- Add feature flags if rolling out to production incrementally (`feature.tenancy`, `feature.acl`, `feature.moderation`).

---

## 12) Open Questions (decide before coding)
- **Departments hierarchical?** (recommended: yes, with `path` for fast ancestor queries)
- **Can creators edit quizzes while `PENDING_REVIEW`?** (recommended: lock; edits revert to `DRAFT`)
- **Org custom roles**: do you want a UI for composing bundles now or later?
- **Attempt visibility**: who can read others’ results? (dept managers and above? via permission like `ATTEMPT_READ_DEPT`)

---

## 13) Definition of Done (for this workstream)

- Moderation flow operational with new statuses and endpoints; catalog respects publish rules.
- Tenancy entities live; resources carry `org_id`; directory endpoints exist.
- Scoped RBAC (bindings) live; permission evaluation honors scope and ACL.
- List endpoints are filtered; no cross‑tenant leaks (tests pass).
- Admin bootstrap via env in place.
- Docs/OpenAPI updated; minimal audits logged.
