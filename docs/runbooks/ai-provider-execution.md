# AI Provider Execution

Quiz generation separates job orchestration from provider work:

- `generationJobExecutor` runs durable FIFO job orchestration on `generation-job-*` threads.
- Legacy generation without a work record also uses `generationJobExecutor`;
  the older `aiTaskExecutor` remains available to unrelated asynchronous consumers.
- `aiProviderTaskExecutor` runs provider-bound chunk tasks on `ai-provider-*` threads.

Keeping these pools separate prevents an orchestration worker that waits for
chunk futures from occupying the workers required to complete those futures.
Provider work does not use `ForkJoinPool.commonPool`, HTTP request threads, or a
caller-runs rejection policy.

## Admission and deadline

New durable jobs receive an acceptance sequence in the same transaction as the
work record. A short MySQL singleton-row lock serializes sequence allocation and
admission. The oldest due work is claimed first, irrespective of owner or tier;
completion order can differ. Defaults admit four jobs globally, each with at most
two unfinished chunk tasks. A local executor has four workers and no task queue;
saturation leaves accepted work in MySQL for the next worker or recovery scan.
No managed job runs on the HTTP/event-publisher thread.

The first committed execution claim records `first_started_at` and `deadline_at`
(UTC instants, five minutes apart). Queue wait does not consume these five minutes.
Recovery keeps the original deadline. Every fallback, retry wait, redistribution,
and HTTP call checks the remaining time. The JDK HTTP transport uses the smaller
of its configured read timeout and remaining job time; expiry cancels the exchange
or closes its response stream, including when headers have already arrived.

At the cutoff, queued chunk tasks are evicted and no further provider call starts.
Validated output from completed types/attempts in an unfinished chunk is frozen;
repeated attempts for one type are not concatenated. Normal coverage reconciliation
still applies: strictly more than 80% may complete with partial output and the
existing tariff, while 80% or less fails and releases the reservation. Saving the
quiz and settling the result may finish after the AI deadline. For example, 81
accepted questions out of 100 can become a partial quiz; 80 cannot.

The existing allowance remains five provider dispatches per chunk/type fallback
invocation, shared across the structured client's retries and the three normal /
two reduced-count attempts. Redistribution retains its existing separate invocation
budgets. There is no aggregate `2 × planned batches` ceiling. The deadline can
interrupt remaining attempts, but never extends them or changes fallback eligibility.

## Configuration

| Property | Default | Unit | Meaning |
| --- | ---: | --- | --- |
| `quiz.jobs.execution.max-active-jobs` | `4` | jobs | Global concurrent unexpired work leases; also local worker count. |
| `quiz.jobs.execution.max-concurrent-chunks` | `2` | chunks/job | Maximum unfinished chunk tasks for one job. |
| `quiz.jobs.execution.scan-seconds` | `10` | seconds | Retry interval for durable waiting/recoverable work. |

These initial limits are not a production throughput promise. All backend instances
must use the same limits. Provider pool sizes below remain independently bounded.

| Property | Production environment variable | Default | Unit | Meaning |
| --- | --- | ---: | --- | --- |
| `async.ai.provider.core-pool-size` | `ASYNC_AI_PROVIDER_CORE_POOL_SIZE` | `8` | workers | Provider workers kept available. |
| `async.ai.provider.max-pool-size` | `ASYNC_AI_PROVIDER_MAX_POOL_SIZE` | `16` | workers | Hard local maximum of concurrent chunk tasks. |
| `async.ai.provider.queue-capacity` | `ASYNC_AI_PROVIDER_QUEUE_CAPACITY` | `50` | tasks | Tasks waiting behind active workers. |
| `async.ai.provider.keep-alive-seconds` | `ASYNC_AI_PROVIDER_KEEP_ALIVE_SECONDS` | `60` | seconds | Idle lifetime for workers above the core size. |
| `async.ai.provider.await-termination-seconds` | `ASYNC_AI_PROVIDER_AWAIT_TERMINATION_SECONDS` | `30` | seconds | Graceful shutdown wait for accepted tasks. |

Startup fails when the core size is below one, the maximum is below the core
size, the queue is negative, or a timeout is negative. Keep the provider maximum
conservative until measured latency and provider `429` data justify a change.
Increasing it can increase provider rate-limit pressure, memory use, outbound
connections, and concurrent database progress updates.

All production environment variables are optional and have the table defaults,
so an existing deployment `.env` remains compatible. Change them together only
after observing production pressure; invalid bounds fail application startup
instead of silently creating an unsafe executor.

## Generated-question schema delivery

`OpenAiConfig` selects schema delivery from the configured `ChatModel` adapter:

- `OpenAiChatModel` uses `OPENAI_STRICT`: the request-specific schema is sent as
  OpenAI `JSON_SCHEMA` with `strict=true`, retaining the existing
  `max-completion-tokens` setting. The schema body is not duplicated in the prompt.
  Spring AOP proxies, including JDK, class-based, and nested proxies, retain this
  mode by resolving the underlying adapter class.
- Other adapters use `PROMPT_SCHEMA`: the complete schema from
  `QuestionSchemaRegistry`, including the requested type and difficulty, is added
  to the trusted system message. No OpenAI-specific request options are attached;
  the adapter retains its own configured defaults. Non-AOP custom wrappers that
  do not expose an `OpenAiChatModel` also take this path.

This is adapter selection, not a remote capability probe or provider switch.
An OpenAI-compatible URL alone does not change the selected mode. The configured
OpenAI endpoint/model must support strict JSON Schema; unsupported model/schema
errors never trigger a prompt-only downgrade. Adding or activating another
provider remains a separate configuration/dependency change.

In both modes the user document remains in the existing untrusted-source
delimiters, separately from schema instructions. Existing response parsing,
question-content validation, requested type/difficulty checks, cancellation,
provider-attempt accounting, and job coverage/billing rules remain in force.
Prompt instructions do not provide provider-enforced schema adherence.
See the [official Structured Outputs guide](https://developers.openai.com/api/docs/guides/structured-outputs)
for the strict-format contract and supported-schema limitations.

Schema lookup, serialization, or strict-option preparation failure stops the
affected structured-client call with the safe `SCHEMA_PREPARATION` category.
The call is not dispatched, consumes no provider-attempt permit, emits no
provider-usage event, and is not retried inside the structured client. Existing
outer job orchestration/failure handling remains unchanged. In particular, a
preparation error cannot cause an unconstrained paid call. Serializer/provider
exception details and user content are not included in errors or logs.

For example, generating ten multiple-choice questions through an adapter without
OpenAI strict support now supplies the full response schema in the system
message. If schema preparation fails for the OpenAI adapter, that invocation
stops before contacting the provider instead of silently omitting its options.

Offline coverage uses the real Spring AI `ChatClient` with local `ChatModel`
fakes and packaged prompts. `OpenAiSchemaDeliveryConfigurationTest` verifies
Spring wiring for direct and proxied adapters and the serialized HTTP request
through the native OpenAI adapter with a local transport stub;
`SpringAiStructuredClientSchemaDeliveryTest` covers all nine
question families in both modes, preparation failures, accounting, cancellation,
and malformed output. `SpringAiStructuredClientPromptPrivacyTest` checks DEBUG
log redaction in both modes. Run these alongside the existing structured-output
and token-limit tests before the complete Maven verification gate.

## Work And Batching

One executor task owns one document chunk. Within that task, generation remains
sequential by requested question type. One structured-client request asks for
the full requested count for that type and chunk; the scheduler does not create
one provider request per question. Types remain sequential inside each chunk. Results are consumed as chunks finish,
so a slow first chunk does not prevent a free slot from starting the next one.

## Saturation

The provider executor has a bounded queue and uses `AbortPolicy`. When both the
workers and queue are full:

1. the rejected task does not run on the submitting thread;
2. no provider call is made for that rejected chunk task;
3. its future fails with `AiProviderCapacityException`;
4. the generation workflow applies its existing coverage policy to any valid
   output that did complete;
5. insufficient coverage follows the existing failed-generation and reservation
   release path, so invalid or missing output is not charged as successful work.

This is post-acceptance protection. The generation endpoints and their `202`
contract are unchanged. The durable FIFO sequence is internal; queue-position estimates remain a separate
API change. Database failure during acceptance rolls back the command; after
acceptance, scan/dispatch failures retain work for retry. Provider `429`, `5xx`,
malformed responses and transport errors retain their existing classification and
fallback rules, bounded by the job deadline.

## Shutdown And Recovery

The dispatcher stops claiming new jobs during shutdown. Active orchestration keeps
the existing 30-second graceful wait; any interrupted durable execution retains its
persisted deadline. Legacy dispatch without a work record uses explicit rejection
and the existing stale-job cleanup if the generation executor is saturated.

The provider executor stops accepting new tasks during application shutdown and
waits up to the configured termination period for accepted tasks. The durable work service recovers expired leases using the persisted request,
source revision, execution fence and original deadline. Expired work without a
checkpoint fails and releases its reservation; an existing checkpoint continues
through the established finalization recovery path without calling AI again.

If saturation appears after deployment:

1. Confirm the bounded warning `AI provider task rejected: bounded executor capacity exhausted`.
2. Inspect thread names and counts; provider work must use `ai-provider-*`.
3. Compare generation arrival rate, provider latency, `429` responses, and queue
   pressure before changing limits.
4. Inspect the durable outstanding count/oldest age and deadline logs before
   changing the four-job/two-chunk limits.

## Signals and offline evidence

`quiz.generation.execution.waiting` and `.active` measure the durable queue and
unexpired claims. `.outstanding` / `.oldest.seconds` retain backlog visibility;
`.queue.wait` measures acceptance-to-first-claim time. `.deadlines` counts observed
AI cutoffs once per execution; `.operations{outcome="deadline_before_recovery"}`
counts expired recovery candidates. `quiz.generation.admission.deferred` counts
local saturation. Existing provider-attempt records and executor instrumentation
remain the source for provider usage, errors and active worker pressure.

Offline tests exercise 1/5/25 jobs through the real dispatcher, execution context,
orchestration and provider scheduler with a fake AI port. Latches require an initial
wave of `min(jobs, 4) × 2` calls, assert peaks of at most four jobs/eight calls and
two calls per job, and require every job to complete within ten seconds after
release. These are deterministic local correctness thresholds, not capacity claims.
MySQL tests independently verify FIFO ordering, cross-thread admission, recovery,
legacy migration and bounded relationship queries. Local HTTP tests cover header
and response-body stalls, plus a request prepared before and dispatched after expiry.

## Migration, rollout and privacy

`V80__generation_fifo_and_deadline.sql` adds scalar work metadata and the admission
counter. Existing work is ordered by its recorded acceptance time and UUID for
ties; timestamps alone cannot reconstruct a historical tie. New acceptance order
is exact. Rows with `executions > 0` are exempt from the new deadline because their
actual first execution time was not recorded. Existing never-started rows and new
rows acquire the deadline on their first execution. Legacy jobs without a work
record retain their existing lifecycle.

Apply the additive migration and replace all old execution instances before
relying on global admission/deadline guarantees: old binaries do not honor the
new gate. A mixed-version rolling window cannot enforce the new limits. Reverting
the application leaves the additive columns/table intact but disables the new
guarantees; do not drop work metadata to perform rollback.

The admission scan reads scalar IDs; source validation compares persisted revision
metadata without reading/hashing document content. Multi-owner/multi-chunk MySQL
tests verify the bounded fetch plan used by generation. Logs and metric tags do
not contain prompts, generated answers or credentials. Use job IDs only for log
correlation, never metric dimensions.
