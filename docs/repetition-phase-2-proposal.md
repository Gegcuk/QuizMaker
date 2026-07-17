# Repetition Phase 2 Proposal

Status: unapproved design proposal, not a substitute for a scoped GitHub issue.

Phase 1 currently schedules question repetition after a completed attempt. This proposal captures the remaining useful decisions from older repetition plans without retaining class-by-class implementation scripts.

## Current Behaviour To Reconcile

`RepetitionProcessingServiceImpl` currently skips question scheduling when the quiz's `isRepetitionEnabled` flag is false. Older Phase 2 material proposed that question-level repetition should run independently of that quiz-level setting, while quiz-level repetition should have its own setting.

Do not implement Phase 2 until product and engineering agree which rule is correct. The result changes existing learner behaviour and needs an explicit compatibility decision, migration plan, and tests. The current skip is also not logged, which makes a deliberately disabled quiz difficult to diagnose.

## Proposed Scope

1. Custom repetition strategies with finite and infinite interval sequences alongside the existing SM-2 strategy.
2. Quiz-level repetition entries updated separately from question-level scheduling.
3. Reminder preferences with per-item overrides and per-content-type defaults.
4. Strategy selection for question entries.

Flashcard and deck repetition remain part of the flashcard roadmap, not this proposal. Email or push notifications and persisted priority-score caching are also out of scope.

## Decisions Required Before An Issue Is Created

- Should question-level scheduling remain gated by `Quiz.isRepetitionEnabled`, or should that flag apply only to quiz-level repetition?
- Which content types may use custom strategies in the first release?
- Can a finished finite strategy be resumed, reset, or only replaced?
- What permission and ownership rules apply to user-created strategies and shared quizzes?
- Which reminder changes are immediately materialised on entries, and how are bulk updates bounded and observable?

## Invariants

- Each user has at most one question entry per question and one quiz entry per quiz.
- Attempt processing is idempotent: processing the same answer twice must not advance a schedule twice.
- A question-level failure must not roll back an independent quiz-level update, and vice versa.
- Reminder resolution is explicit: item override, then content-type default, then the product default.
- A finite strategy ends with `nextReviewAt = null` and an explicit completion state. Due queries exclude completed entries.
- Every read and write is scoped to the authenticated user; no entry, strategy, or preference may cross user boundaries.
- All time calculations use an injected `Clock` and UTC-compatible timestamps.

## Recommended Delivery Sequence

1. Create a vertical issue that records the resolved gating rule and compatibility strategy.
2. Add migrations and constraints for strategies, preferences, and any quiz entry model. Keep migrations additive and prove indexes with repository tests.
3. Implement and unit-test strategy execution with published finite, infinite, reset, and rounding examples.
4. Add reminder resolution and ownership-safe service methods with transaction boundaries.
5. Add quiz-level scheduling only after question scheduling is stable and separately retryable.
6. Publish typed API contracts, examples, authorization rules, and OpenAPI group coverage.
7. Add integration tests for attempt processing, idempotency, reminder updates, concurrency, and migration behaviour.

## Required Test Coverage

- Unit tests for strategy progression, completion, infinite intervals, grade mapping, and reminder precedence.
- Repository tests for due/history ordering, `nextReviewAt` null handling, owner scoping, indexes, and unique constraints.
- Service/integration tests showing that duplicate events do not create duplicate history or advance a schedule twice.
- MVC/contract tests for permissions, ownership, validation, pagination, and RFC 7807 failures.
- Fixed-clock tests only. Do not use sleeps, real provider calls, or production data.

The open-issue roadmap uses this document only to refine future issue scope. It does not add work ahead of the existing flashcard and security dependencies.
