# QuizMaker Open-Issue Implementation Roadmap

Snapshot date: 2026-07-17

Source: the 183 open issues in `Gegcuk/QuizMaker`, inspected with GitHub CLI:

```bash
gh issue list --repo Gegcuk/QuizMaker --state open --limit 200 \
  --json number,title,body,labels,milestone,createdAt,updatedAt,url
```

This is a dependency and risk roadmap, not a promise that every open issue is still unimplemented. Before starting an issue, compare its acceptance criteria with the current branch, tests, migrations, and deployed OpenAPI contract.

## Executive Recommendation

Implement the backlog in this order:

1. Triage stale, duplicate, oversized, and already-implemented issues.
2. Fix active bugs and frontend-blocking API contracts.
3. Establish security, validation, testing, and observability guardrails.
4. Deliver flashcards as tested vertical slices.
5. Deliver link/audio/video ingestion behind security and billing controls.
6. Extend question management and analytics.
7. Add organization and authenticated-user capabilities.
8. Add platform administration and lower-priority community features.

Do not start all `priority:high` issues at once. The backlog currently has 144 issues with that label, so dependency order and user impact are more useful than the existing priority label alone.

## Backlog Group Index

| Group | Primary roadmap location | Purpose |
| --- | --- | --- |
| Bugs and frontend contracts | Waves 0-1 | Verify stale work, fix production defects, and make existing API contracts usable. |
| Improvements, tests, and security | Wave 2 | Establish architecture, compatibility, validation, authorization, and test guardrails. |
| Flashcards and spaced repetition | Wave 3 | Deliver decks, cards, review scheduling, analytics, sharing, import/export, and AI generation. |
| Link, audio, and video ingestion | Wave 4 | Build secure extraction pipelines with quotas, billing, jobs, and observability. |
| Questions and quiz authoring | Wave 5 | Improve question lifecycle, validation, generation, analytics, and authoring support. |
| Organizations and user experience | Wave 6 | Add membership-aware collaboration, preferences, activity, and account capabilities. |
| Platform and administration | Wave 7 | Add operational controls, audits, diagnostics, limits, and administrative APIs. |
| Community and lower-priority features | Wave 8 | Add discovery, sharing, social, and other product expansion after core safeguards. |

## Delivery Rules For Every Wave

- A deliverable should be a vertical slice with domain behavior, application-service interface, implementation, API contract, security, tests, and documentation where applicable.
- Controllers depend on service interfaces, never concrete implementations or repositories.
- External providers are hidden behind ports/interfaces and tested with fakes or stubs.
- Every endpoint has named DTOs, typed OpenAPI success/error schemas, valid examples, and one logical `GroupedOpenApi` group discoverable from `/api/v1/api-summary`.
- Roles describe user membership; permissions authorize actions. Ownership, organization membership, and visibility are enforced server-side as separate rules.
- Tests prove behavior, including negative authorization and compatibility paths. They must not mock impossible collaborator output merely to make a branch pass.
- Work remains local. Agents do not push, open/merge PRs, release, or deploy.

## Wave 0: Backlog Triage And Closure Audit

Complete this before new feature implementation. It prevents duplicate work and turns the roadmap into an accurate queue.

### Verify as potentially complete or partially complete

- #39: README has since been expanded; compare the issue criteria and close or narrow it.
- #46: service/mapper coverage has grown substantially; replace the broad checklist with measured remaining gaps.
- #164: task-level progress fields and newer progress logic exist; reproduce against the current API before changing code.
- #338: quiz import validation infrastructure exists; verify whether per-type runtime validation already satisfies the outcome.
- #413: a media OpenAPI group exists locally; determine whether the issue is a deployment/discovery gap or stale.
- #418: a bug-report OpenAPI group exists locally; validate schemas and deployed discovery before implementation.

### Consolidate overlaps

- #32 and #272 both request broad audit logging. Keep one epic and make the other a scoped implementation slice.
- #274 and #318 both address PII protection in AI prompts. Merge policy and implementation expectations.
- #269 and #302 overlap on AI/generation rate limiting. Keep #269 as the shared rate-policy contract and narrow #302 to link-generation-specific integration after that contract exists.
- #56, #311, and #312 overlap on observability. Keep #56 as the platform epic and the others as ingestion-specific metrics.
- #75 and #414 are coupled: organization context cannot be delivered safely without a real membership source.

### Reframe oversized issues

- Split #416 into preferences, paginated activity, and statistics contracts unless one small shared data model genuinely supports all three.
- Former #262 was closed as an unsafe horizontal annotation task. Each vertical slice must define authorization per use case and enforce it at the reusable service boundary, with route rules where appropriate.
- Treat #31 as an incremental convention, not a repository-wide mapper rewrite.

### Normalize labels

Adopt the type/area/priority scheme in [the issue-writing guide](github-issue-guide.md). Recent issues #411, #414, #415, #416, #417, and #418 currently lack useful labels.

## Wave 1: Production Correctness And Frontend-Blocking Contracts

These provide the fastest reduction in user-facing risk and unblock existing frontend work.

### 1. Active bugs

1. #164: reproduce, define whether progress is `0..100`, clamp/round at the backend contract, and add deterministic calculation and API tests.
2. #172: make schema delivery provider-capability based, retain strict OpenAI structured output, add prompt-schema fallback, and test without calling a real model.

### 2. Existing API contract repairs

Implement after the Wave 0 audit, in this order:

1. #418: bug-report discovery and typed public/admin contracts.
2. #413: complete media discovery and typed pagination/upload lifecycle.
3. #417: typed document list and tree/flat structure responses.
4. #415: typed quiz share-link collections, generation-job pages, and bulk-update payloads.
5. #412: public article hero/OG renditions; depends on a stable public media representation from #413.

These should normally be backward-compatible documentation/DTO refinements. Do not rename paths or remove fields unless a separate breaking-change decision is approved.

### 3. New contracts currently blocking mock UIs

1. #411: question analytics, after confirming aggregation sources, authorization scope, privacy, units, and empty-state semantics.
2. #414: minimal authenticated organization context, after #75 establishes real organization membership.
3. #416: implement as smaller settings, activity, and statistics slices with privacy-preserving self-access defaults.

## Wave 2: Engineering Guardrails And Existing-Feature Hardening

These guardrails should land before the two largest feature streams.

### Architecture and validation

1. #31: standardize mapper placement and ownership incrementally as features are touched.
2. #338: finish or close per-question-type import validation after the Wave 0 audit.
3. #358: add safe batching or explicit per-item transactions only after correctness is measured.
4. #359: preload reference caches within one import operation; verify isolation and stale-data behavior.

### Test baseline

1. #46: convert the old class checklist into measured missing service/mapper behaviors.
2. #310: billing estimation and idempotency unit tests before expanding source types.
3. #316: SSRF matrix tests before enabling public link fetch.
4. #319: rate-limit tests before exposing link ingestion or new generation-start paths.

### Security foundation

1. #267: central permission-evaluator rules with deny-by-default tests.
2. #268: visibility enforcement for `PUBLIC`, `UNLISTED`, and `PRIVATE` resources.
3. #271: document access validation before AI generation.
4. #269: shared AI generation rate-limit policy.
5. #273: user quota management for paid/expensive AI operations.
6. #274 and #318: one PII masking policy plus prompt-boundary tests.
7. #317: MIME allowlist and documented `415` behavior.
8. #32 and #272: consolidated audit foundation, followed later by the query UI in #57.

### Observability foundation

- #56 defines common metric names, cardinality rules, health signals, and alert ownership before feature-specific metrics are added.

## Wave 3: Flashcards And Spaced Repetition

The former horizontal backlog #179 through #268 and #270, plus the old flashcard AI fragments #249 through #261, was closed on 2026-08-04. It prescribed classes, migrations, and controllers rather than independently verifiable product behavior. Do not reopen individual fragments; use the current vertical slices and the [Policy-Driven Quiz Execution Architecture](architecture/policy-driven-quiz-execution.md).

### 3A. Private deck and card authoring

1. [#497](https://github.com/Gegcuk/QuizMaker/issues/497): private deck/card authoring with immutable card revisions, ownership, pagination, OpenAPI, MySQL constraints, and compatibility coverage.

This is the content foundation for learning. It intentionally excludes public sharing, tags, bulk operations, attachments, and AI generation until a future focused issue is ready.

### 3B. Learning and spaced repetition

1. [#488](https://github.com/Gegcuk/QuizMaker/issues/488): separate learning-session bounded context using immutable content/forms, deterministic versioned scheduling, answer/response abuse protection, and private progress.

Learning schedules are not assessment attempts or result data. Use deterministic `Clock`-based tests and preserve existing repetition data through explicit adapters/migration decisions.

### 3C. AI flashcard draft generation

1. [#498](https://github.com/Gegcuk/QuizMaker/issues/498): idempotent AI job, structured parsing/validation/deduplication, protected draft preview, and explicit owner acceptance into #497 card revisions.
2. [#269](https://github.com/Gegcuk/QuizMaker/issues/269): cross-generation rate-policy contract, retained separately because it applies beyond flashcards and is blocked on the distributed-limiter decision.

Use a project-owned provider port, fake providers in tests, and no automatic publishing of raw model output.

## Wave 4: Link, Audio, And Video Ingestion

Security controls must precede any feature that fetches an untrusted URL or processes untrusted media.

### 4A. Shared ingestion foundation

1. Configuration/source model: #281, #282, #283.
2. HTML extraction and sanitation: #276.
3. Transcription port and adapter: #277, #278.
4. Converter and pipeline wiring: #279, #280.
5. Unit tests with fake providers: #284, #285, #286.

Backlog gap: #284 asks for `LinkFetchService` tests, but there is no corresponding open implementation issue. Create or clarify a vertical issue for the fetch port/adapter, redirect policy, byte/time limits, and sanitized extraction before starting #290.

### 4B. Link ingestion

1. Request validation: #287.
2. SSRF guard before network access: #289.
3. Ingestion/persistence: #290, #291.
4. Rate limiting and endpoint: #292, #288.
5. Integration coverage: #293.

DNS rebinding, redirects, private/link-local ranges, custom ports, response size, content type, and timeout behavior must be covered.

### 4C. Audio/video ingestion

1. Upload contract and MIME support: #294.
2. Routing and failure semantics: #295, #296.
3. Size/time caps: #297.
4. Stubbed integration tests: #298.

### 4D. Generate quizzes from links

1. Request and API contracts: #299, #300.
2. Orchestration and protection: #301, #302, #303.
3. Job lifecycle integration tests: #304.

### 4E. Billing, progress, and source metadata

1. Estimation model: #305, #306.
2. Job source metadata: #307.
3. Reservation idempotency: #308.
4. Progress stages: #309.
5. Billing/estimation tests: #310.

### 4F. Operability and final security gates

1. Metrics: #311, #312.
2. Correlated structured logs: #313.
3. Feature flags: #314.
4. Alert thresholds and runbook: #315.
5. Security and rate-limit suites: #316, #317, #318, #319.

## Wave 5: Question Management, Portability, And Analytics

First audit #92 and #93 against the existing quiz import/export implementation; close, narrow, or rewrite them rather than creating a second path.

Recommended order:

1. Bulk delete/update with per-item authorization and explicit atomicity: #89, #90.
2. Duplication with ownership and attachment semantics: #91.
3. Import/export portability after audit: #92, #93.
4. Translation storage and management: #94.
5. Translation retrieval/fallback: #95.
6. Quiz-attempt results export: #71.
7. View/question analytics: #37, then #411 for the typed frontend contract.

Every bulk endpoint must document idempotency, partial failure, limits, transaction behavior, authorization per item, and a typed result envelope.

## Wave 6: Organizations And Authenticated User Experience

### Organizations

1. Multi-tenant ownership and membership foundation: #75.
2. Minimal authenticated moderation context: #414.
3. Directory/profile reads: #76, #77.
4. Settings/profile updates: #78.
5. Invitations and member management: #80, #81.
6. Removal/cleanup: #79.
7. Usage analytics: #82.

Organization roles do not replace permission checks. The backend must validate membership and action permissions for every organization-scoped request.

### Current-user capabilities

1. #416: split and implement preferences first, then paginated activity, then aggregate statistics/achievements.
2. #170: passwordless login as an independent security-sensitive slice with one-time use, expiry, throttling, replay protection, audit events, and no account-enumeration leak.

## Wave 7: Platform Administration And Operations

1. Consolidated audit capture: #32 and #272.
2. Admin audit search: #57.
3. Platform metrics/observability: #56.
4. Read-only application configuration contract: #62.
5. Dynamic configuration updates with strict admin permissions, allowlisted keys, validation, audit, and rollback: #63.
6. Notification foundation: #33, after event sources and delivery preferences are defined.

Dynamic configuration is high risk despite its existing `priority:medium` label. Never expose arbitrary property mutation or secrets through the API.

## Wave 8: Community And Discovery Features

These are lower priority until security, contracts, and core learning flows are stable.

1. Bookmarking: #34.
2. Ratings and discussion: #35.
3. Following: #36.
4. Notifications tied to follows/discussions: extend #33 only after those domain events exist.
5. Engagement analytics: #37 if not already completed with Wave 5.

Moderation, abuse prevention, pagination, privacy, deletion behavior, and notification fan-out must be designed before public social features are enabled.

## Definition Of Ready For Any Issue

An issue is ready only when:

- its current behavior has been verified against the latest local code and, when relevant, the deployed API;
- it has one observable outcome and explicit out-of-scope boundaries;
- dependencies and duplicate issues are linked;
- API, data, compatibility, security, and test expectations are clear;
- the permission/ownership/visibility model is named;
- OpenAPI grouping and discovery requirements are named for API changes;
- no real external API is required by automated tests.

## Definition Of Done For Any Issue

- The implementation follows the feature-first architecture and service-interface boundary.
- Acceptance criteria and important negative paths are covered by logically correct tests.
- Security checks are enforced server-side and tested.
- Migrations are additive and backward compatible unless explicitly approved otherwise.
- DTOs, RFC 7807 errors, OpenAPI group, examples, and `/api/v1/api-summary` discovery are accurate.
- User, developer, and AI-agent documentation is updated.
- Focused tests pass, followed by `./mvnw verify` when practical.
- Changes are committed locally only when explicitly requested; nothing is pushed, merged, released, or deployed by an agent.
