# Quiz Generation Job Resilience: Pending, Failure, Cancellation

Purpose: eliminate “stuck pending” jobs that block new generations, ensure failures move to FAILED quickly with clear reasons, and give users a reliable way to cancel. This plan is implementation‑oriented for the existing Spring Boot 3 stack and current domain model.

Scope: server backend only (API + job lifecycle). UI notes included where relevant.


## Current State (from code)

- Domain model supports terminal states: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED`, `CANCELLED` (see `src/main/java/uk/gegc/quizmaker/features/quiz/domain/model/GenerationStatus.java`).
- Jobs are created as `PENDING` and picked up via `QuizGenerationRequestedEvent` → `QuizGenerationRequestedEventListener` → `AiQuizGenerationServiceImpl.generateQuizFromDocumentAsync(...)`, which flips status to `PROCESSING` as its first DB operation.
- Failures during generation mark the job as `FAILED` (catch block in `AiQuizGenerationServiceImpl`) and failures during quiz creation also mark `FAILED` (catch in `QuizServiceImpl.handleQuizGenerationCompleted(...)`).
- Users can cancel via `DELETE /api/v1/quizzes/generation-status/{jobId}` → `QuizServiceImpl.cancelGenerationJob(...)` (sets `CANCELLED` and releases billing reservation).
- DB constraint enforces one active job per user: computed column `active_username` + unique index (see `src/main/resources/db/migration/V21__add_active_job_constraint.sql`). This blocks new jobs while the prior job is `PENDING`/`PROCESSING`.
- There is a manual/admin cleanup endpoint and a service method `cleanupStalePendingJobs()` (currently using a 10‑minute cutoff) but no periodic scheduler invoking it. This method marks jobs as `FAILED` but does not release reserved billing tokens.
- Schedulers observed in code: email/token cleanup schedulers and a billing reservation sweeper (`BillingServiceImpl` has `@Scheduled(fixedDelayString = "#{@billingProperties.reservationSweeperMs}")`). There is no dedicated job sweeper for `quiz_generation_jobs` yet, which explains console logs about “sweeps every couple of minutes” not affecting jobs.


## Pain Points

- If the async worker never starts (event failure, threadpool saturation, transient infra blip), job may remain `PENDING` for a long time and block new generations due to the unique index.
- In prior bugs (e.g., duplicate title), failure could surface late or not atomically, and the job remained `PENDING` (user perceives a hang).
- Stale pending cleanup is ad‑hoc (admin only) and currently forgets to release reserved tokens.
- The user wants the ability to cancel after 1–2 minutes, or a better approach.


## Goals

- Guarantee jobs transition to `FAILED` when generation or quiz creation cannot proceed.
- Automatically retire stale `PENDING` jobs fast (O(minutes), not O(10 minutes)).
- Allow user to cancel an active job in a predictable way that also releases reservations.
- Do not weaken the one‑active‑job invariant; instead, manage stale jobs proactively.


## Chosen Approach (high level)

1) Add a short, configurable “activation timeout” for `PENDING` jobs (default 2 minutes). Jobs that don’t transition to `PROCESSING` in that window are failed automatically with a clear error message, and their billing reservation is released.

2) Add a small scheduled sweeper that runs every minute and calls the existing cleanup method (adjusted as below) to enforce the activation timeout globally.

3) In the “start job” path, if job creation is blocked by the “one active job” constraint, auto‑detect a stale `PENDING` job older than the activation timeout, cancel/fail it (with reservation release), and retry once. This avoids user dead‑ends while keeping the invariant.

4) Abuse‑resistant cancellation policy: keep API cancellation available but make it cost‑fair and cooperative.
   - Cooperative stop: the generator checks for cancellation between chunk calls and stops making new LLM requests as soon as a cancel is observed.
   - Billing on cancel: if any LLM calls have started, commit either (a) tokens used so far, or (b) a small configurable minimum “start fee” (tokens) to cover admission costs; release any remainder. If no LLM call happened yet, cancellation is free (reservation released).
   - Rate limits: add per‑user rate limits for starts and cancellations (per minute/hour/day) using the existing `RateLimitService` to prevent abuse via bursts.
   - Optional UI gating: front‑end can hide the cancel button for 60–120s to discourage knee‑jerk cancels, but the API will remain immediate for power users and scripted flows.

Why this is better than “only allow cancel after 1–2 minutes”:
- Immediate cancellation avoids trapping users in bad states when they realize they picked wrong inputs.
- Cooperative stop ensures we stop spending after the cancel signal; billing on cancel ensures we recover costs already incurred. Hard waiting does not stop costs and reduces UX.
- Rate‑limit thrash is also mitigated by: one active job per user, AI backoff (`AiRateLimitConfig`), server executors, and now explicit start/cancel rate limits.
- The activation timeout + auto cleanup eliminates the main failure mode: jobs never picked up remaining `PENDING` and blocking the constraint.


## Implementation Steps (with justification)

1) Introduce configuration knobs
   - Properties (names suggested):
     - `quiz.jobs.pendingActivationTimeoutMinutes` (default: 2)
     - `quiz.jobs.cleanupFixedDelaySeconds` (default: 60)
     - `quiz.jobs.minCancelAgeSeconds` (default: 0; keep API cancel immediate, UI can hide early)
     - `quiz.jobs.cancellation.commitOnCancel` (boolean, default: true) — when true, commit tokens on cancel if work started.
     - `quiz.jobs.cancellation.minStartFeeTokens` (default: 0) — non‑refundable minimum tokens committed when the first LLM call starts; set >0 only if “pay‑on‑start” is desired.
     - `quiz.jobs.rateLimit.start.perMinute`, `.perHour`, `.perDay` (defaults: 3, 15, 100) — limits for job starts.
     - `quiz.jobs.rateLimit.cancel.perHour`, `.perDay` (defaults: 5, 50) — limits for cancels.
     - `quiz.jobs.rateLimit.cooldownOnRapidCancelsMinutes` (default: 5) — cooldown imposed if multiple cancels occur within a short window.
   - Justification: Promote operational control per environment and simplify testing by making timeouts tunable.

2) Convert stale pending cleanup into a robust utility
   - Update `QuizGenerationJobServiceImpl.cleanupStalePendingJobs()` to:
     - Use `pendingActivationTimeoutMinutes` instead of the current hard‑coded 10 minutes.
     - When marking a job `FAILED`, also release any reserved billing tokens (mirror logic used in failure/cancellation flows):
       - Check `billingReservationId` and `billingState == RESERVED` and call `billingService.release(...)` (or `internalBillingService.release(...)` where appropriate) with a traceable idempotency key (e.g., `quiz:<jobId>:release`).
       - Record the transition in `billingState` and add the idempotency key via `job.addBillingIdempotencyKey("release", ...)`.
       - Persist `lastBillingError` if release fails; do not abort cleanup.
     - Set `errorMessage` to a clear reason, e.g., “Job timed out before processing could start”.
   - Justification: Avoid leaking reservations and give users a clear diagnostic. Using the same idempotent release pattern keeps accounting consistent.

3) Add a scheduler to invoke stale cleanup periodically
   - New class `features.quiz.application.scheduler.QuizGenerationJobCleanupScheduler` with `@Scheduled(fixedDelayString = "${quiz.jobs.cleanupFixedDelaySeconds:60}000")` that calls `jobService.cleanupStalePendingJobs()`.
   - Ensure scheduling is enabled (already via `@EnableScheduling` in `QuizMakerApplication`).
   - Justification: Automates remediation; removes the need for admin endpoints to clear stuck jobs and ensures a predictable worst‑case block window (~timeout + scheduling period).

4) Make the “start job” path self‑healing on constraint violation
   - In `QuizServiceImpl.startQuizGeneration(...)` around the existing `DataIntegrityViolationException` catch for the one‑active‑job index:
     - Query `jobRepository.findActiveJobsByUsername(username)` and look for a `PENDING` job older than the activation timeout.
     - If found, cancel/fail it (reuse service utility; ensure release of reservation), then retry `createJob(...)` once.
     - If the active job is `PROCESSING` or `PENDING` but younger than the timeout, preserve current behavior: surface a validation error (“User already has an active generation job”).
   - Justification: Users are unblocked automatically when the only thing in their way is a stale `PENDING` job; we still respect the invariant otherwise.

5) Cooperative cancellation and cost‑fairness
   - Generator cooperates: in `AiQuizGenerationServiceImpl`, before each LLM call (and after each), check job status (via `jobRepository.findById(jobId)` or a cached “canceled” flag) and abort promptly if canceled. Also stop scheduling additional chunk futures when canceled.
   - Persist “work started” and token usage:
     - Add lightweight accounting to record token usage as we go (read usage metadata from `ChatResponse` if available, or approximate via output length when provider metadata isn’t present). Accumulate into `QuizGenerationJob.actualTokens`.
     - Set a flag/timestamp on first LLM call (e.g., `firstCallAt` or infer from `status == PROCESSING` + a boolean `hasStartedAiCalls`).
   - Billing on cancel in `QuizServiceImpl.cancelGenerationJob(...)`:
     - If job has not started any LLM calls: release reservation (current behavior).
     - If work started and `commitOnCancel=true`:
       - Compute `tokensToCommit = max(actualTokensSoFar, minStartFeeTokens)` but cap at `billingEstimatedTokens`.
       - Call `internalBillingService.commit(...)`, then release any remainder. Update job billing fields: `billingCommittedTokens`, `actualTokens`, `billingState`.
     - If `commitOnCancel=false`: current behavior (release reservation), but keep this as a non‑default mode.
   - Justification: This stops further spend post‑cancel, and recovers spend already incurred. The configurable minimum fee covers admission overheads even when token usage metadata is unavailable.

6) Rate‑limit starts and cancels
   - In `QuizController` endpoints for start and cancel, invoke `rateLimitService.checkRateLimit(...)` with user key (username or userId) and the configured thresholds.
   - Consider a rolling window strategy (token bucket) and a small cooldown after rapid cancel bursts (e.g., 2 cancels in <10 minutes → 5‑minute cooldown on next start).
   - Justification: Reduces abuse by frequency, complements billing on cancel, and is simple to operate (configurable numbers per env).

7) Surface clearer status and reasons to the client
   - Confirm `QuizGenerationStatus` includes `errorMessage` (it does) and that failure paths set meaningful messages:
     - Generation errors (AI/rate limit): “Generation failed: …”
     - Quiz creation errors (persistence): “Quiz creation failed: …”
     - Stale cleanup: “Job timed out before processing could start.”
   - Justification: Users understand what happened and can retry intelligently.

8) Guardrails in async layer to reduce PENDING hangs
   - Validate `AsyncConfig` sizing (`aiTaskExecutor`) for your deployment; ensure queue capacity and thread counts fit expected concurrency, and that rejection policy (`CallerRunsPolicy`) is acceptable.
   - Consider emitting metrics for event handling latency: time from job creation to first `PROCESSING` write.
   - Justification: If events are delayed or executors saturated, you’ll detect it and tune before users feel it.

9) Test plan (server)
   - Unit: `QuizGenerationJobServiceImpl.cleanupStalePendingJobs()` marks old `PENDING` to `FAILED` and calls billing release; idempotency is respected when called twice.
   - Unit: `QuizServiceImpl.startQuizGeneration(...)` retries once after auto‑cancelling an old `PENDING` job; no retry when active job is `PROCESSING`.
   - Unit: cancellation paths — if `commitOnCancel=true` and work started, commit `max(actualTokensSoFar, minStartFeeTokens)` and release remainder; if not started, only release.
   - Unit: generator checks cancel flag before each LLM call; no further ChatClient calls after cancel.
   - Unit/Integration: start/cancel rate limits enforced by `RateLimitService` (per minute/hour/day). Rapid cancels trigger cooldown.
   - Integration: create job → block the listener (or pause executor) → verify scheduler flips to `FAILED` with error and reservation released within timeout + sweep delay.
   - Integration: generation failure path (throw in AI layer) → job becomes `FAILED` and reservation is released.
   - Integration: user cancellation after some chunks: verify generator stops quickly; tokens committed appropriately; subsequent start succeeds.

10) Rollout and ops
   - Default `pendingActivationTimeoutMinutes` to 2–3 minutes in dev/staging; observe metrics; adjust for prod based on AI queueing behavior.
   - Ship dashboards/alerts: count of PENDING older than 1 minute, average activation latency, count of auto‑cleaned jobs per hour, reservation release failures.
   - Document a safe fallback: admins can still use `POST /api/v1/quizzes/generation-jobs/cleanup-stale` and `.../{jobId}/force-cancel` in emergencies.


## Files and Touchpoints (no code here; for reference)

- Job model: `src/main/java/uk/gegc/quizmaker/features/quiz/domain/model/QuizGenerationJob.java`
- Job repo: `src/main/java/uk/gegc/quizmaker/features/quiz/domain/repository/QuizGenerationJobRepository.java`
- Job service: `src/main/java/uk/gegc/quizmaker/features/quiz/application/impl/QuizGenerationJobServiceImpl.java`
- Quiz service: `src/main/java/uk/gegc/quizmaker/features/quiz/application/impl/QuizServiceImpl.java`
- AI service: `src/main/java/uk/gegc/quizmaker/features/ai/application/impl/AiQuizGenerationServiceImpl.java`
- Event listener: `src/main/java/uk/gegc/quizmaker/features/quiz/domain/events/QuizGenerationRequestedEventListener.java`
- API endpoints: `src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java`
- Async executors: `src/main/java/uk/gegc/quizmaker/shared/config/AsyncConfig.java`
- DB constraint (one active job): `src/main/resources/db/migration/V21__add_active_job_constraint.sql`


## Alternatives Considered (and why we didn’t choose them)

- Allow multiple active jobs per user: Simplifies UX but risks heavy concurrency, higher AI costs, and rate‑limit storms. The existing invariant is good; fix stale states instead.
- Enforce “cancel button usable only after 1–2 minutes” at the API: Adds server‑side complexity and delays recovery. Prefer immediate cancel at API with optional UI gating. If business mandates a delay, implement UI gating and/or block API cancel for `PENDING` only; still allow cancel of `PROCESSING` but with commit‑on‑cancel to avoid cost leakage.
- Increase the 10‑minute stale window only: Doesn’t solve the core issue (jobs stuck at `PENDING` block users). We shorten it and make it automatic.


## Summary of Defensible Choices

- Activation timeout + scheduled cleanup: aggressively clears pathological `PENDING` jobs while preserving invariants and freeing tokens.
- Self‑healing start path: users don’t hit dead‑ends when old `PENDING` jobs exist; the system resolves them and proceeds safely.
- Immediate user cancellation with cooperative stop + billing on cancel: stops further spend and recovers costs already incurred; optional minimum start fee protects against zero‑work cancellations.
- Observability and configurability: timeouts, sweeps, and executor sizing become operational dials rather than hard‑coded assumptions.

With these changes, “pending due to rate limits” no longer traps users: jobs either start processing quickly or are failed and released within a couple of minutes; users can cancel at any time; and new jobs are unblocked automatically when prior ones are stale.

## Implementation Review Notes

The following notes review your described implementation and call out confirmations, potential edge cases, and small refinements to keep things robust, cost‑fair, and observable in production.

1) Configuration Properties
- Ensure `QuizJobProperties` uses `@ConfigurationProperties(prefix = "quiz.jobs")` plus `@Validated` for numeric bounds (e.g., >0). Provide sensible defaults so tests don’t require property files.
- Confirm the bean is enabled via `@EnableConfigurationProperties(QuizJobProperties.class)` or component scanning, and properties are referenced only from services (not controllers).
- Consider logging effective values at startup (INFO) for operational clarity.

2) Stale Job Cleanup (with billing release)
- Good: timeout made configurable; FAILED status includes a specific error message.
- Important: when releasing reservations, write idempotency keys (e.g., `quiz:<jobId>:release`) and persist `billingState = RELEASED`; store `lastBillingError` if release fails, but do not fail cleanup.
- Verify the cleanup targets only `PENDING` jobs older than the activation timeout; do not touch `PROCESSING` (those may be legitimately slow).
- Consider setting a lightweight audit marker (e.g., `errorMessage = "Job timed out before processing could start"`) only when status transitions, not on every sweep.

3) Automated Cleanup Scheduler
- Ensure the scheduler catches and logs all exceptions (so the scheduled task never dies silently). A simple try/catch around the service call with structured logging is enough.
- Fixed delay vs. cron: fixed delay is fine here. Keep the value small (e.g., 60s) relative to the activation timeout (e.g., 2–3 min).
- If you run multiple app nodes, avoid duplicate work concerns by making the cleanup idempotent. Current logic is safe (status check + save).

4) Self‑Healing Start Path
- Nice: retry once after auto‑failing stale `PENDING` jobs. Ensure you re‑read active jobs inside the same transaction and add a small jitter/backoff before retry to reduce race with parallel starts on other nodes.
- If the retry still hits the unique index, surface the standard validation message. Log a single WARN with user and document IDs for support traceability.

5) Cooperative Cancellation + Cost‑Fairness
- Generator checks: verify `AiQuizGenerationServiceImpl` checks for cancellation before each LLM call and right after responses, and does not schedule new chunk tasks once a cancel is observed. Also remove the job’s entry from any in‑memory progress maps on cancel/complete/fail to prevent memory growth.
- Token accounting: provider metadata may be missing or partial. Guard nulls and fall back to approximations (e.g., output tokens), but keep a lower bound (never negative). Persist into `job.actualTokens` cumulatively.
- Commit‑on‑cancel policy:
  - Compute `tokensToCommit = max(actualTokensSoFar, minStartFeeTokens)`, then cap at `billingEstimatedTokens` before calling `commit`. Release any remainder.
  - If `tokensToCommit == 0`: skip commit and just release to avoid writing an unnecessary COMMIT event/state.
  - Use a distinct idempotency key for cancel commits, e.g., `quiz:<jobId>:commit-cancel`, and record it via `addBillingIdempotencyKey("commit-cancel", ...)`.
  - Set `errorMessage = "Cancelled by user"` and keep `status = CANCELLED` (do not mark FAILED). Update `billingState` appropriately (`COMMITTED` when commit > 0, else `RELEASED`).
- Interrupted sleep: when rate‑limit backoff sleeps are interrupted, check job status; if `CANCELLED`, treat as a cooperative exit rather than surfacing a failure.

6) Rate Limiting
- Keys: prefer stable user identity (userId/username), not IP, for auth’d endpoints. Keep resource names consistent (e.g., `quiz-generation:start` and `quiz-generation:cancel`).
- Limits: your 3/minute start and 5/minute cancel defaults look reasonable. Also add per‑hour/day caps to smooth bursts. Log when limits trigger (INFO) with userId.
- Cooldown: if implemented, ensure it’s measured and visible in logs/metrics for support.

7) Data Model & Migration (V39)
- Defaults: `has_started_ai_calls BOOLEAN DEFAULT FALSE`, `first_ai_call_at TIMESTAMP NULL` are sensible. Keep the migration idempotent (guard with information_schema checks as in earlier migrations).
- Backfill: not required, but confirm that existing rows default to `FALSE/NULL` to avoid NPEs.
- Indexing: no new index is strictly required for these fields. Ensure they don’t interfere with the computed column `active_username` and its unique index.

8) Transactions, Locking, Idempotency
- Where you modify billing fields, consider using `findByIdForUpdate(...)` to guard against concurrent updates (success flow, cancel flow, sweeper).
- Keep idempotency on all billing operations (reserve was done; add it for cancel‑commit and remainder release). Avoid committing twice for the same job with distinct reasons.
- Persist `billingState` transitions atomically with status updates to reflect real billing outcomes in the job view.

9) API & DTOs
- Cancel response: consider including a small summary when tokens were committed on cancel (e.g., committedTokens, reservationId) in `QuizGenerationStatus` or a dedicated response body so the UI can communicate cost implications.
- OpenAPI: if behavior changed (commit on cancel), update the endpoint description to reflect the new semantics.

10) Testing
- Unit: add tests for cancel with no AI calls (release only) vs. after a few chunks (commit on cancel, remainder release). Verify idempotency (double cancel does not double‑commit).
- Integration: ensure generator halts promptly after cancel and no further ChatClient calls occur (use a spy or counter).
- Concurrency: test two nodes simulating the self‑healing start race to ensure only one job survives the retry path.

11) Observability & Ops
- Metrics to add when available: activation latency (create → PROCESSING), counts of stale cleanups, cancel occurrences (with/without commit), token deltas (estimated vs. actual), rate‑limit rejections.
- Logging: include `jobId`, `userId`, `documentId`, and billing correlation identifiers in INFO/WARN logs for commit/release paths.
- Dashboards/alerts: number of `PENDING` jobs > N, cleanups per hour, cancel spikes, commit failures.

12) Minor Polish
- Clear the in‑memory `generationProgress` entry on terminal states to prevent leaks.
- Ensure `QuizGenerationStatus.errorMessage` is set for timeouts and cancellations; keep messages user‑friendly.
- Consider a tiny UI affordance: show “cancel may incur small cost if generation already started” once work begins.

Overall, your implementation choices align with the plan’s goals and add the needed guardrails against cost abuse while preserving good UX. The items above are mostly hardening and observability to keep this robust in production.

## Real-Code Review Summary (verified in repo)

What matches the plan
- Properties: `QuizJobProperties` present with sensible defaults; application.properties includes pending-activation, cleanup-fixed-delay, cancellation and rate-limit keys (relaxed binding should map).
- Migration: `V39__add_job_resilience_fields.sql` adds `has_started_ai_calls` and `first_ai_call_at` (Flyway versioning prevents re-apply).
- Sweeper: `QuizGenerationJobCleanupScheduler` added with fixedDelay property, exception safety, and service call to `cleanupStalePendingJobs()`.
- Cleanup logic: `cleanupStalePendingJobs()` uses configurable timeout, marks `FAILED`, sets clear message, and releases reservation with idempotency key and error capture.
- Self-healing start: `QuizServiceImpl.startQuizGeneration(...)` catches constraint violation, cancels stale PENDING via `findAndCancelStaleJobForUser(...)`, retries once, otherwise releases reservation and errors as expected.
- Cooperative cancel (generator): `AiQuizGenerationServiceImpl` checks `isJobCancelled(...)` before scheduling and before each LLM call, records first call, and tracks token usage from `ChatResponse` usage metadata.
- Cost-fair cancel (service): `cancelGenerationJob(...)` commits tokens used so far for jobs that started, uses a distinct idempotency key (`commit-cancel`), and releases remainder; otherwise releases reservation. Errors are stored, and state is updated.
- Rate limiting: Controller guards starts (document/text) and cancel with `RateLimitService` (3/min start, 5/min cancel).
- Tests: scheduler unit tests cover happy path and exception swallowing.

Gaps and suggested follow-ups
- Config usage gaps:
  - `minCancelAgeSeconds` property is declared but unused. If you want API-level gating, enforce it in `cancelGenerationJob(...)` (compare `now - startedAt`). If UI-only is intended, consider removing the server property to avoid confusion.
  - `cancellation.commitOnCancel` isn’t checked; cancel path always commits if work started. If you want a switch, inject `QuizJobProperties` into `QuizServiceImpl` and gate the commit branch.
  - `cancellation.minStartFeeTokens` isn’t applied. Current logic uses `max(actualTokens, 0)`; update to `max(actualTokens, minStartFeeTokens)` and cap to `estimatedTokens` as planned.
  - Controller rate limits are hard-coded; consider reading thresholds from `QuizJobProperties.rateLimit` to avoid drift across environments.
- Rate limit coverage:
  - `generate-from-upload` lacks a start rate-limit check. Add the same guard (or stricter) there to cover all entry points.
  - Only per-minute limits are enforced. If `RateLimitService` supports it, add per-hour/day checks or a small cooldown on rapid cancels.
- In-memory progress map: `generationProgress` entries aren’t removed on terminal states. After COMPLETE/FAILED/CANCELLED, call `generationProgress.remove(jobId)` to avoid long-lived map growth.
- Interrupted backoff: `sleepForRateLimit` throws on interrupt. Optionally check cancel status after catching `InterruptedException` and treat as cooperative stop to prevent converting user cancels into failures.
- Event/finish race: `handleQuizGenerationCompleted(...)` always marks job COMPLETED after quiz creation. In a rare race where a job is cancelled right before event publication, you may want to no-op if job is CANCELLED/FAILED (re-read with lock and guard) to keep terminal state stable.
- Minor bookkeeping:
  - On cancel, consider setting `errorMessage = "Cancelled by user"` for user-visible clarity in status DTOs.
  - When committing on cancel, consider storing idempotency key under a distinct map key like `commit-cancel` for clearer audit trails (current key is `commit` with a `-cancel` suffix inside the value).

Tests to consider adding
- Cancel flows: no-work (release only) vs. started-work (commit + remainder release) with idempotency (double cancel safe).
- Rate limits: start/cancel rejected paths, and any cooldown logic if added.
- Self-healing retry: simulate stale PENDING cancel + successful retry vs. legitimate active job (no retry); ensure reservation release on failure paths.
- Progress map cleanup: verify removal after each terminal end.
