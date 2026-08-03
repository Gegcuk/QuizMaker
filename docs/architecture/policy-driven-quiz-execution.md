# Policy-Driven Quiz Execution

## Status and Purpose

This document is the architectural reference for evolving QuizMaker quiz execution without rewriting working practice flows. It records the target boundaries, compatibility invariants, and delivery order behind the [Quiz Execution tracker](https://github.com/Gegcuk/QuizMaker/issues/472).

It is not a commitment to implement every candidate capability listed here. Each change remains subject to its own ready criteria, product decisions, security review, and focused issue acceptance criteria. Where a linked issue is more specific, that issue takes precedence.

## Product Objective

Support practice, assessments, assignments, review, manual grading, adaptive learning, branching scenarios, spaced repetition, and live sessions without creating a separate copy of the execution engine for each mode.

An execution mode is a reusable server-owned preset. At attempt creation, the preset, a published quiz revision, assignment/share-link configuration, authorized participant accommodations, and only permitted overrides resolve into one immutable attempt policy.

```text
Run preset or policy template
    + published quiz revision
    + assignment or legacy share-link configuration
    + authorized participant accommodation
    + explicitly permitted override
    = immutable resolved attempt policy
```

The attempt executes its saved policy and saved form. It must not reinterpret mutable quiz, link, template, or algorithm data after start.

## Starting Constraints

The current application has a working practice and assessment flow that must remain compatible while this architecture is introduced:

- `Attempt` currently has a direct mutable `Quiz` relationship and a compact `AttemptMode` enum.
- `Answer` currently has a direct mutable `Question` relationship.
- Existing routes, request fields, success responses, and historical attempt rows are live client contracts.
- Legacy anonymous access uses a sentinel user and share links are current capabilities, not immutable assignments.
- Some client request shapes contain disclosure-related flags; the server must not trust them for authorization or release decisions.

The target architecture is additive. It introduces new policy-aware services alongside the current services, then adapts legacy routes. It does not perform a controller rewrite, delete historical rows, or change active attempts in place.

## Core Terms

| Term | Meaning | Owner |
| --- | --- | --- |
| Mutable quiz | Authoring resource that can change while authors work. | Quiz feature |
| Quiz revision | Immutable published source content, response contract, authored order, sections, and case blocks. | Quiz feature |
| Run preset | Reusable author/manager configuration that supplies policy defaults. It is not a runtime mode switch. | Quiz/Assignment feature |
| Resolved attempt policy | Typed, versioned rules actually applied to one attempt. Persisted as canonical JSON plus schema version and hash. | Attempt feature |
| Assignment | Immutable delivery binding to one published revision and policy configuration. | Assignment feature |
| Legacy share link | Existing capability-backed route preserved through an adapter. It is not renamed or dropped by the new assignment model. | Sharing feature |
| Attempt form | Exact ordered participant-visible content: items, option positions, progressive extensions, algorithm versions, and form hash. | Attempt feature |
| Participant subject | Server-resolved authenticated user or capability-bound guest. It is separate from the capability that authorizes access. | Attempt/Identity feature |
| Execution trace | Protected append-only evidence of accepted commands, policy/form references, decisions, and state transitions. | Attempt feature |

## Architecture Shape

```text
Mutable Quiz
  -> Published QuizRevision
       -> sections, case blocks, immutable item and response contracts

RunPreset + Assignment or LegacyShareLink + authorized accommodation
  -> PolicyResolutionService
       -> ResolvedAttemptPolicy snapshot

QuizRevision + ResolvedAttemptPolicy + participant subject
  -> AttemptFormService
       -> immutable full or progressive AttemptForm

AttemptExecutionService
  -> server-authoritative commands and allowed actions
  -> answer submission and grading/review lifecycle boundaries
  -> protected execution trace
```

The new `AttemptExecutionService` is a feature-owned interface used by controllers and other features. A Spring implementation sits behind it. The legacy `AttemptService` and existing routes are preserved initially through explicit adapters, not duplicated business rules.

## Policy Model

Policies are typed Java records/value objects at runtime. A canonical JSON representation is a persistence and compatibility boundary, not an untyped generic rules engine.

The initial policy can be composed from independent, validated dimensions:

- question selection and ordering;
- answer-option ordering and stable option identifiers;
- delivery and navigation;
- draft, submission, locking, and idempotency behavior;
- timing, pause, deadline, and accommodation behavior;
- attempt limits, retakes, and result selection;
- grading, disclosure, review, and result release;
- access/assignment restrictions.

A central validator checks incompatible combinations before an attempt exists. The policy resolver captures the preset version and whitelisted overrides transactionally with attempt creation. Unknown policy schemas, algorithms, or incompatible combinations fail closed, remain operationally visible, and are never replaced with permissive defaults.

Do not build a generic workflow engine or a growing central `switch` over modes. A conventional new behavior should be expressible as a preset/policy combination. A meaningful source of variation belongs behind a narrow strategy interface. Fundamentally different orchestration, such as live sessions or learning scheduling, belongs in a separate bounded context.

## Immutable Content and Form Evidence

Published revisions protect authoring history. Attempt forms protect participant experience and scoring evidence.

At start, a fixed-form attempt persists at least:

- revision identity or immutable participant-safe content;
- selected items, section/case membership, and display order;
- stable option identifiers and exact display order;
- selection and ordering algorithm versions;
- policy snapshot reference/version/hash;
- form hash and created timestamp.

For a progressive form, every new selected item and branch decision is persisted before delivery, with its strategy/graph version and protected reason code. A random seed alone is insufficient evidence because algorithms and source content may change.

Resume, review, grading, and dispute resolution always use the saved form and response contract. They never regenerate from mutable authoring data. Question types without options retain their manual-answer semantics; option-aware Fill Gap questions preserve options in the form without changing correct-answer extraction or scoring.

## Lifecycle Boundaries

Execution, answer, grading, and review/release are separate state models. Submission is not grading, grading is not result release, and result release is not participant review permission.

The server publishes the currently permitted actions from saved policy, lifecycle, form, and subject. Examples include save draft, request next item, go back, pause, resume, submit, and review. The client renders available actions; it never authorizes navigation, time, disclosure, or grading by changing request flags.

All state-changing commands define idempotency and concurrency behavior. A canonical retry with the same idempotency key and semantic payload returns the prior result; a different payload with that key returns conflict. Invalid transitions, expired attempts, revoked capabilities, and stale versions leave no partial writes.

## Data Ownership and Migration Rules

| Data | Primary owner | Rule |
| --- | --- | --- |
| Authoring content and revisions | Quiz | Drafts may change; published revisions are immutable. |
| Presets and assignment configuration | Quiz/Assignment | Editable drafts/configuration; published assignment bindings are immutable. |
| Policy snapshot, form, commands, and execution state | Attempt | Persist once at start or append new progressive records; never recalculate on read. |
| Answer drafts and submitted responses | Attempt answer/submission | Form-aligned, subject-scoped, and separate from grading. |
| Grades, rubrics, and release decisions | Grading/Review | Protected, auditable, and separate from participant completion. |
| Learning schedules | Learning | Never stored as assessment result data. |
| Live membership and rounds | Live | Separate orchestration; reuses immutable content/form services. |

All schema work is additive Flyway migration work. New tables/columns use appropriate foreign keys, uniqueness constraints, indexes, schema/version fields, and retention considerations. Existing attempts and requests remain readable through adapters. No migration rewrites mutable source data into historical attempt meaning, and no unknown snapshot is silently reinterpreted.

## Security and Privacy Invariants

1. The server, never the client, decides authorization, ownership, visibility, navigation, timing, grading, and disclosure.
2. Identity, participant subject, organization membership, resource ownership, and exact capability are resolved server-side. Missing or ambiguous context defaults to deny.
3. A quiz/share capability is not an attempt-review capability. Capability scope, expiry, rotation, and revocation are explicit.
4. Participant, authoring, grading, review, and reporting schemas remain separate. Do not use generic maps or authoring DTOs for participant data.
5. Correct answers, submitted answers, capability values, tokens, sensitive participant information, and sensitive location data must not appear in logs, metric labels, cache keys, traces, or public RFC 7807 details.
6. Caches vary by authorized representation and never store share capabilities in public cache keys.
7. New sensitive endpoints require authenticated/permission/ownership negative tests in addition to happy paths.

## API and Client Contract Principles

Every public endpoint belongs to one logical grouped OpenAPI document and must be discoverable through `/api/v1/api-summary`. Client-visible changes use named request and response schemas and RFC 7807 errors for expected failures.

Participant APIs expose a participant-safe form, allowed actions, permitted policy summary, answer acknowledgements, and appropriate lifecycle state. They do not expose unreleased keys, explanations, rubrics, hidden options, raw policy internals, or privileged audit data.

Author, grader, reviewer, and operator APIs are separate representations with explicit authorization. Client contracts must document idempotency, response ordering, timing, disclosure, pagination/filtering where applicable, and backward compatibility. The current frontend remains supported; future mobile/iOS clients must treat additive fields as optional and cannot reproduce server authorization logic locally.

## Offline Boundary

Offline behavior is not a general permission to execute a quiz without the server. It is limited to the explicit opt-in full-form package and replay contract in [#486](https://github.com/Gegcuk/QuizMaker/issues/486):

- a package contains only participant-safe immutable form data and no capability secret or correct answer;
- package expiry and integrity are server-verifiable;
- the client may view already delivered data while disconnected, but cannot start an attempt, select new adaptive/branch items, authorize access, grade, release results, or trust its own clock;
- on reconnect, each queued command is revalidated against authoritative lifecycle, policy, capability, timing, and idempotency state;
- stale, tampered, expired, revoked, or out-of-order replay is rejected deterministically and returns resynchronization guidance.

## Extensibility Rules

Use the smallest suitable extension point:

| Need | Extension shape |
| --- | --- |
| Conventional practice/assessment variation | Preset and typed policy values |
| Selection, ordering, grading, or release variation | Narrow versioned strategy interface |
| New immutable authored structure | Quiz revision model extension |
| Learning schedule | Separate Learning bounded context |
| Live/team coordination | Separate Live bounded context |
| Cross-feature side effect | Domain/application event where a direct dependency would create high coupling |

Candidate future capabilities include section-based assessments, deterministic random forms, manual grading, adaptive selection, branching scenarios, spaced repetition, live sessions, ungraded activities, accessibility accommodations, certificates, and review windows. They are not a bundled release and must not be implemented speculatively.

## Delivery Roadmap

The programme is intentionally sequenced by invariants rather than by UI modes.

### Foundation

1. [#447](https://github.com/Gegcuk/QuizMaker/issues/447): immutable quiz revisions.
2. [#461](https://github.com/Gegcuk/QuizMaker/issues/461): stable response and scoring contracts.
3. [#474](https://github.com/Gegcuk/QuizMaker/issues/474): versioned policy snapshots and preset resolution.
4. [#477](https://github.com/Gegcuk/QuizMaker/issues/477): reproducible participant forms and option display order.
5. [#475](https://github.com/Gegcuk/QuizMaker/issues/475): policy-aware commands, navigation, and allowed actions.
6. [#480](https://github.com/Gegcuk/QuizMaker/issues/480): separated execution, answer, grading, and review lifecycles.

### Delivery, Identity, and Evidence

1. [#476](https://github.com/Gegcuk/QuizMaker/issues/476): immutable assignments while preserving legacy share links.
2. [#489](https://github.com/Gegcuk/QuizMaker/issues/489): participant subjects without weakening capability security.
3. [#478](https://github.com/Gegcuk/QuizMaker/issues/478): protected execution trace.
4. [#479](https://github.com/Gegcuk/QuizMaker/issues/479): policy-governed drafts and safe final submission.
5. [#481](https://github.com/Gegcuk/QuizMaker/issues/481): validated v1 question-bank selection.
6. [#482](https://github.com/Gegcuk/QuizMaker/issues/482): retakes and result selection.

### Content and Assessment Extensions

1. [#473](https://github.com/Gegcuk/QuizMaker/issues/473): sections and atomic case blocks.
2. [#483](https://github.com/Gegcuk/QuizMaker/issues/483): result release and certificates.
3. [#485](https://github.com/Gegcuk/QuizMaker/issues/485): manual grading and rubrics.
4. [#492](https://github.com/Gegcuk/QuizMaker/issues/492): accommodation overlays.
5. [#491](https://github.com/Gegcuk/QuizMaker/issues/491): ungraded/self-assessment activities.

### Separate Bounded Contexts

1. [#484](https://github.com/Gegcuk/QuizMaker/issues/484): adaptive progressive selection.
2. [#487](https://github.com/Gegcuk/QuizMaker/issues/487): branching scenarios.
3. [#488](https://github.com/Gegcuk/QuizMaker/issues/488): learning and spaced repetition.
4. [#486](https://github.com/Gegcuk/QuizMaker/issues/486): offline full-form replay.
5. [#490](https://github.com/Gegcuk/QuizMaker/issues/490): live/team orchestration.

The pre-existing work remains part of the programme: [#445](https://github.com/Gegcuk/QuizMaker/issues/445) feedback privacy, [#446](https://github.com/Gegcuk/QuizMaker/issues/446) canonical idempotency, [#448](https://github.com/Gegcuk/QuizMaker/issues/448) analytics policy, [#450](https://github.com/Gegcuk/QuizMaker/issues/450) anonymous capability security, [#462](https://github.com/Gegcuk/QuizMaker/issues/462) related execution work, and [#465](https://github.com/Gegcuk/QuizMaker/issues/465) operational design.

## Decisions Already Made

- Existing user-created questions without Fill Gap distractors and legacy Fill Gap questions remain valid; option-aware questions are additive. Frontend behavior is based on presence of distractors/options.
- Generation charges only accepted valid questions when valid generation is strictly greater than 80 percent. At or below 80 percent, generation fails and charges zero. The current tariff is a configured fixed rate per valid accepted question, with a future version allowed to add a content-length component.
- Canonical identical answer-submission retry succeeds; conflicting reuse is a 409. Accepted answers are immutable. Any future correction model must use explicit supersession and audit, not silent editing.
- Current feedback remains restricted to owner practice until its separate security issue is resolved.
- Legacy analytics remain viewable but excluded from modern scoring analytics according to the agreed policy.
- There is no real iOS client scope now. New contracts remain additive and future clients must use server-computed actions and state.
- External providers are always behind project-owned ports and tests use fakes/stubs, never real paid services.

## Deliberately Deferred Decisions

The following need explicit product/operational decisions in their owning issue before code:

- exact policy schema fields and initial preset inventory;
- long-term document retention, detailed accommodation evidence, and identity-provider expansion;
- adaptive strategy thresholds and future personalization;
- live transport, capacity, roster rules, and leaderboard behavior;
- full offline client support and package key-management implementation;
- certificate public-verification scope and correction/revocation model;
- distributed rate limiting/provider outage strategy.

## Quality Gates

Every implementation issue follows [the issue writing guide](../github-issue-guide.md), adds `docs/manual-testing/issue-<number>-<slug>.md`, and remains a focused branch with local-only commits until the project owner explicitly asks for a push.

Tests assert business and contract behavior, not impossible collaborator output. Use unit tests for pure rules; MVC/OpenAPI tests for HTTP; MySQL integration tests for persistence, transactions, authorization, and concurrency; compatibility fixtures for legacy routes/data; and fake external systems. Run scoped tests first. Never call real AI, payment, email, storage, or identity services from automated tests.

## Completion Criterion

The architecture is sufficiently extensible when a new conventional behavior can be added through a preset and typed policy, a new algorithm can be added through a narrow strategy, and a fundamentally different runtime can be added as a bounded context. Historical attempts must remain reproducible and reviewable, and no feature should require expanding a central mode switch across controllers and services.
