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

No HTTP route, request/response schema, permission, migration, or JPA relationship
read changes. N+1 query behavior is therefore not applicable to this change.
Rollback restores the prior application version; no data migration is required.

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
