# AI Provider Execution

Quiz generation has two local execution boundaries:

- `aiTaskExecutor` runs post-commit job orchestration on `ai-*` threads.
- `aiProviderTaskExecutor` runs provider-bound chunk tasks on `ai-provider-*` threads.

Keeping these pools separate prevents an orchestration worker that waits for
chunk futures from occupying the workers required to complete those futures.
Provider work does not use `ForkJoinPool.commonPool`, HTTP request threads, or a
caller-runs rejection policy.

## Configuration

| Property | Production environment variable | Default | Unit | Meaning |
| --- | --- | ---: | --- | --- |
| `async.ai.provider.core-pool-size` | `ASYNC_AI_PROVIDER_CORE_POOL_SIZE` | `4` | workers | Provider workers kept available. |
| `async.ai.provider.max-pool-size` | `ASYNC_AI_PROVIDER_MAX_POOL_SIZE` | `8` | workers | Hard local maximum of concurrent chunk tasks. |
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

## Work And Batching

One executor task owns one document chunk. Within that task, generation remains
sequential by requested question type. One structured-client request asks for
the full requested count for that type and chunk; the scheduler does not create
one provider request per question. Existing same-contract fallback attempts and
redistribution remain unchanged until the shared retry/deadline budget in #442
is implemented.

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
contract are unchanged. Pre-acceptance admission, per-user/per-job fairness,
queue estimates, shared retry/deadline budgets, and distributed limits remain
separate #442 follow-ups.

## Shutdown And Recovery

The provider executor stops accepting new tasks during application shutdown and
waits up to the configured termination period for accepted tasks. Durable job
recovery remains owned by #443; this executor does not add a task table or claim
that in-memory queued work survives a process exit.

If saturation appears after deployment:

1. Confirm the bounded warning `AI provider task rejected: bounded executor capacity exhausted`.
2. Inspect thread names and counts; provider work must use `ai-provider-*`.
3. Compare generation arrival rate, provider latency, `429` responses, and queue
   pressure before changing limits.
4. Prefer reducing arrival rate or implementing #442 admission/fairness over an
   unmeasured concurrency increase.

## Privacy And Data

Executor configuration and rejection logs contain no prompts, document content,
generated questions, answers, filenames, users, jobs, credentials, or provider
responses. No document content is copied, reread, fingerprinted, or hashed. No
entity, migration, repository query, or relationship-loading path is added, so
this change cannot introduce an N+1 query.

Rollback is application-only: revert the implementation and remove the optional
provider executor properties. No database or stored-content operation is needed.
