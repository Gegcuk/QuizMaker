# AGENTS.md
# QuizMaker - Spring Boot backend guidance for Codex
# Goal: mentor-first help that matches this repo's conventions and supports safe migration to cleaner architecture.

## Scope and precedence

- This file is repo-specific guidance for this repository.
- It complements global `~/.codex/AGENTS.md`.
- If rules conflict, prefer the more specific rule for this repository.
- This file defines preferred direction for new code and safe rules for legacy code.
- If local feature conventions and this file conflict, prefer local conventions unless migration is in scope.

## Repo identity

- Stack: Java 17 + Spring Boot 3.x, REST API, Spring Security (JWT + OAuth2), JPA/Hibernate, Flyway, unit/integration tests.
- Build workflow: Maven wrapper is present; prefer `./mvnw`.
- Dominant package layout: feature-first under `features/<feature>/...` plus `shared/**`.
- Existing reality is mixed in a few places; use "Current state vs target state" below.

## Current State vs Target State

- Current state:
  - Security checks use both `@PreAuthorize` and custom `@RequirePermission`/AOP.
  - Repository interfaces exist in both `domain/repository` and `infra/repository` depending on feature.
  - Some controllers include orchestration and direct repository access.
  - Mapping is mixed: MapStruct in some features, manual mappers in others.
- Target state for new code:
  - Thin controllers, business logic in application services.
  - DTO-only API boundary.
  - Centralized, consistent `ProblemDetail` error responses.
  - Clear feature-level boundaries and scoped tests.
- Rule when touching legacy code:
  - Follow local feature conventions unless migration/refactor is explicitly requested.
- Rule for new features:
  - Follow target-state conventions by default.

## Mandatory response structure (repo-level override)

1. **Pseudocode (ordered, senior-dev sequence)**
   - Touch list: which existing classes/files will change.
   - Unchanged list: what stays as-is (to keep scope stable).
   - Happy path + key edge cases.

2. **Read-first references (repo examples first)**
   - Point to similar classes/files already in this repo before proposing code.
   - If no exact match, provide the closest analog and label it "closest match".
   - Explain what to copy: conventions, error style, test style, logging style.

3. **Implementation snippets (ordered, production-quality and comprehensive)**
   - Snippets only unless explicitly asked to edit files.
   - Default to comprehensive snippets that are implementation-ready on first pass.
   - Prefer cohesive method/class-level snippets over tiny fragments when behavior spans multiple steps.
   - For each snippet, provide exactly:
     1) Snippet
     2) Where to paste (path + insertion point)
     3) Educational notes:
        - key APIs/annotations/methods
        - decision rationale and alternatives
        - pitfalls (edge cases, performance, security)
        - quick validation

4. **Verify and next steps**
   - Scoped commands first + expected outcome.
   - Optional improvements if relevant.

## Mentor-not-autopilot rules

- Assume user may retype changes to learn.
- Prefer comprehensive, cohesive snippets that demonstrate best-practice design.
- Explain broad refactors before proposing them.
- Direct file edits without extra permission are limited to docs:
  - `*.md`, `docs/**`, `README*`, `CHANGELOG*`.
- For Java/config/migration/CI changes:
  - snippets only unless user explicitly asks to modify files.
  - snippets should include necessary surrounding context (method signature, required checks, error handling, and integration points).

## Snippet quality bar (repo-level)

- Prioritize correctness and maintainability over brevity.
- Include production-grade concerns where relevant:
  - null/empty validation,
  - clear error handling,
  - transaction boundaries,
  - security/authorization checks,
  - logging/metrics hooks,
  - tests for happy path + key edge path.
- Do not provide toy or pseudo-production snippets for implementation guidance.
- If a feature needs multiple coordinated changes, provide an ordered snippet set that is complete enough to implement end-to-end.

## Architecture and layering

### Controller scope

- New controllers should be HTTP boundary focused (validation, mapping, status codes).
- New controllers should not inject repositories directly.
- Lightweight request-shaping is acceptable; business rules belong in services.
- Do not perform wide "cleanup refactors" in legacy controllers unless requested.

### Service scope

- Business rules, orchestration, and transactions belong in application services.
- Place `@Transactional` at service methods/classes, not controllers.
- Keep transactions narrow.

### Repository scope

- Repositories should remain persistence/query-centric.
- Existing features may keep their current repository location.
- New features should standardize on one repository location (recommended: `domain/repository` interfaces).
- Do not move repository packages during unrelated work.

## Security conventions (important: mixed codebase)

- Follow route-level auth rules from `SecurityFilterChain`.
- Permission checks currently use both:
  - `@RequirePermission` (project-native AOP pattern)
  - `@PreAuthorize` (Spring method security)
- For new endpoint-level checks, prefer existing feature convention first.
- For new service-level checks, use `@PreAuthorize` where service methods are reused.
- Avoid duplicated checks at multiple layers unless explicitly needed.
- Never bypass auth/permission checks in examples.

## Validation and error handling

- Use `@Valid` + Bean Validation for request DTOs.
- Prefer `ProblemDetail` responses through centralized advice patterns.
- Follow existing global/feature advice conventions (`GlobalExceptionHandler`, feature-specific handlers).
- Never expose stack traces/internal exception details to clients.

## DTO and mapping conventions

- Keep API boundaries DTO-based; do not expose entities in new public endpoints.
- Record DTOs are common and preferred where existing code does so.
- Mapping style is mixed:
  - Use MapStruct for straightforward mappings.
  - Use manual mappers for complex transformations.
- Do not mix mapping styles for the same DTO/entity pair unless necessary.

## Pagination and API shape

- Default to `Page<T>` in this repo (dominant existing pattern).
- Use `Slice<T>` only with explicit reason (count-query cost/performance).
- Avoid unbounded list responses on endpoints that should be pageable.

## Logging and sensitive data

- Match existing structured/parameterized logging style.
- Never log secrets, tokens, passwords, raw auth headers, or sensitive payloads.

## Database and migrations

- Flyway migrations path: `src/main/resources/db/migration/V*__*.sql`.
- Prefer additive, backward-compatible migrations.
- Ask before destructive or non-backward-compatible schema changes.

## Time and clocks

- Prefer injected `Clock` for new time-dependent logic.
- Avoid introducing new `now()` calls in business logic without `Clock` unless matching a strict local pattern.

## Read-first references (how to quickly find project conventions)

- Error handling:
  - Search: `@RestControllerAdvice`, `ProblemDetail`
  - Read: `shared/api/advice/GlobalExceptionHandler`
  - Read: `shared/api/problem/ProblemDetailBuilder`
  - Read feature examples: `features/billing/api/BillingErrorHandler`

- Security:
  - Search: `SecurityFilterChain`, `JwtAuthenticationFilter`, `JwtTokenService`
  - Search: `@RequirePermission`, `@PreAuthorize`
  - Read: `shared/config/SecurityConfig`
  - Read: `shared/security/aspect/PermissionAspect`

- Controllers/boundary style:
  - Search: `@RestController`, `*Request`, `*Response`, `record`

- Mapping:
  - Search: `@Mapper(componentModel = "spring")`
  - Search: `infra/mapping`

- Persistence/pagination:
  - Search: `extends JpaRepository`, `Page<`, `@EntityGraph`, `@Query`

- Transactions:
  - Search: `@Transactional` in `application` packages

- Tests:
  - Search: `@WebMvcTest`, `MockMvc`, `@DataJpaTest`, `BaseIntegrationTest`, `@SpringBootTest`

- Repo guidance:
  - Read: `docs/agents.md` for deeper templates/checklists.

## Commands and workflow (scoped-first)

### Build/run

- Prefer wrapper:
  - `./mvnw test`
  - `./mvnw spring-boot:run`

### Scoped tests first

- Single test class:
  - `./mvnw test -Dtest=SomeTest`
- Multiple test classes:
  - `./mvnw test -Dtest=SomeTest,OtherTest`

### Performance note

- Do not default to heavy full-lifecycle commands for routine changes.
- Run broader suites only when wiring/security/persistence changes justify it.

## Test execution policy

- Run the smallest relevant tests first:
  - `./mvnw test -Dtest=ClassName`
  - `./mvnw test -Dtest=ClassA,ClassB`
- Run broader integration tests only when wiring/security/persistence changed.
- Do not default to the full suite for small changes.

## Stop-and-ask triggers

Ask before:

- adding/upgrading dependencies (especially major upgrades),
- changing security rules/permission model,
- making non-backward-compatible schema changes,
- broad refactors across features/packages,
- changing public API contracts used by clients/frontend.

## Compatibility rules

- API contract changes require explicit callout (request/response/status/error shape).
- DB changes must be additive-first unless explicitly approved.
- Security behavior changes require explicit approval before implementation.

## New feature quality gates

- No new typo package names (use `application`, not variants).
- No direct repository injection in new controllers.
- No entity returns from new public API endpoints.
- Use `Clock` injection for time-based logic in new code.
- Add focused tests for:
  - happy path,
  - at least one important edge/error path.
- Keep changes small and reviewable.
