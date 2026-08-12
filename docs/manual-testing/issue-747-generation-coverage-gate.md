# Issue 747 Manual Testing: Generation Coverage Gate

## Purpose

Verify that quiz generation uses the full selected-chunk quantity target, never silently substitutes another type or difficulty, and completes only when valid accepted output is strictly greater than 80% of the request.

This change does not alter generation endpoints, request fields, polling responses, permissions, question schemas, or the customer tariff. Existing clients continue to work without changes.

## Required Outcomes

- For `C` selected chunks and per-chunk count `Q`, the requested count is `C x Q`.
- Exactly 80% accepted output fails.
- More than 80% accepted output may complete as a partial quiz.
- Surplus output in one type cannot replace a missing requested type.
- Retries preserve the requested type and difficulty. Reduced quantity is allowed; easier difficulty and alternate question types are not silently generated.
- Failed coverage publishes no completion event, creates no quiz/checkpoint, and uses the existing full-reservation release path.
- Successful billing remains based on distinct accepted question types under the job's immutable tariff snapshot and cannot exceed the original quote.

## Deterministic Offline Verification

Run these commands locally from the repository root. They use JDK 17 and in-memory fakes/mocks; they do not need MySQL and cannot call OpenAI.

1. Select JDK 17 for the shell if it is not already active:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run the coverage policy and orchestration tests:

   ```bash
   ./mvnw -Dtest=GenerationCoveragePolicyTest,AiQuizGenerationFailureScenariosTest,AiQuizGenerationServiceFallbackTest test
   ```

3. Verify the Maven result contains:

   ```text
   BUILD SUCCESS
   ```

The focused tests cover multi-chunk multiplication, zero-count exclusion, arithmetic overflow, exact and above-80% boundaries, exact success, type surplus, wrong difficulty/type, null output, deterministic capping, immutable decisions, reservation release, completion publication, normal retry, reduced-count retry, and rejection of semantic substitutions.

## Optional Local User Flow

This flow uses the normal configured AI provider and is not required for automated verification. Do not place provider keys, JWTs, prompts, document text, or generated answers in screenshots or issue comments.

1. Start the backend and frontend with their normal local development configuration.
2. Sign in as a user with quiz-generation permission and a sufficient token balance.
3. Upload or select a processed document with at least two selected chunks.
4. Request one question type with five questions per chunk at a fixed difficulty.
5. Start generation and poll the existing job-status endpoint through the current frontend.
6. Verify a completed quiz contains no question with another type or difficulty.
7. Verify the generated count never exceeds ten for that type; extra provider output is not persisted.
8. If provider output is partial but completes, verify at least nine of ten requested questions exist. Eight of ten is not a successful result.
9. For a failed job, verify no generated quiz becomes available and the billing reservation is released rather than committed.
10. For a successful job, verify the committed charge does not exceed the displayed quote. Changing the count within the same active type must not introduce a new tariff formula.

Because real provider output is nondeterministic, use the focused offline tests for exact `8/10` and `9/10` boundary evidence.

## Compatibility Checks

- Existing `/api/v1/quizzes/generate-from-*` requests still return the same `202` response shape.
- Existing status polling continues to use `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, and `CANCELLED` without a new required field or enum value.
- Manually authored questions and legacy persisted quizzes are not read or rewritten by this policy.
- Fill-gap questions with and without distractor `options` remain governed by the existing runtime validator and are both compatible where their established question schema allows them.
- No migration is introduced, so existing generation jobs and checkpoints remain readable.

## Privacy And Query Audit

- Coverage logs contain only requested, accepted, missing, discarded, threshold, and outcome values. They contain no source text, prompt, question, answer, filename, or provider response.
- Coverage reconciliation is in-memory over the already generated questions. It performs no repository query and adds no N+1 query path.
- The policy does not hash document content or otherwise scan source documents beyond the existing generation flow.

## Rollback Signal

Rollback the application commit if exact-80% output completes, an alternate type/difficulty appears in a completed quiz, a failed-coverage job creates a quiz or commits billing, or the unchanged generation request/status schema becomes incompatible with the existing frontend.
