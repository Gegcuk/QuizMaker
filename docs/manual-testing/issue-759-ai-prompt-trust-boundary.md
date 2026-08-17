# Issue 759: AI Prompt Trust Boundary

## Purpose

Verify that structured quiz generation sends trusted instructions separately,
places unchanged document source behind unique untrusted-source markers, fails
before provider dispatch when prompt rendering is unsafe, and never writes raw
source, model output, or provider exception text to logs.

This change cannot eliminate prompt injection. It establishes an explicit
instruction hierarchy, preserves the source as data, keeps schema validation as
the enforcement boundary, and minimizes diagnostic disclosure.

## Deterministic Offline Verification

Run locally from the repository root. These tests use real classpath prompt
resources and fake Spring AI responses; they do not call OpenAI or require
MySQL.

1. Select JDK 17:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run the prompt and privacy tests:

   ```bash
   ./mvnw -Dtest=PromptTemplateServiceTest,PromptTemplateTrustBoundaryTest,SpringAiStructuredClientPromptPrivacyTest test
   ```

3. Run the focused structured-client compatibility tests:

   ```bash
   ./mvnw -Dtest=SpringAiStructuredClientRequestContractTest,SpringAiStructuredClientProviderUsageTest,SpringAiStructuredClientCancellationTest,SpringAiStructuredClientFillGapGenerationTest,SpringAiStructuredClientUncoveredMethodsTest,AiQuizGenerationTaskSchedulingTest,EstimationServiceTest,EstimationServiceImplTest test
   ```

4. Confirm both commands end with `BUILD SUCCESS`, no database container starts,
   and no network/provider credential is requested.

The tests prove:

- the actual system resource contains source-trust rules and no known unresolved
  placeholder;
- all supported question-type resources put trusted parameters and the
  question-type contract before source content;
- source containing command-shaped prose, marker-shaped text, or `{language}`,
  `{questionCount}`, and `{difficulty}` is preserved exactly;
- each prompt uses matching per-request start/end markers that do not occur in
  the source;
- missing resources fail closed rather than returning a weaker fallback prompt;
- prompt construction failure invokes no `ChatClient` method;
- captured `SystemMessage` and `UserMessage` values retain their separate roles;
- DEBUG logs and terminal errors do not contain private source, valid provider
  output, malformed response text, or provider exception messages;
- valid output, request-specific schema enforcement, cancellation, fill-gap
  validation, provider-usage observation, and one-request-per-type batching are
  unchanged.

Do not use `-DskipTests`, a live-provider profile, or real provider credentials.

## Static Privacy Checks

1. Confirm the old raw response preview is absent:

   ```bash
   rg -n 'raw response preview|substring\(0, 1000\)' src/main/java/uk/gegc/quizmaker/features/ai/application/impl/SpringAiStructuredClient.java
   ```

   Expected: no matches.

2. Confirm provider/parser failure logs do not interpolate exception messages or
   stack traces:

   ```bash
   rg -n 'Structured generation attempt|Failed to regenerate type|Rejected malformed|invalid JSON' src/main/java/uk/gegc/quizmaker/features/ai/application/impl/SpringAiStructuredClient.java
   ```

   Expected: messages use bounded categories or fixed text and do not include
   `e.getMessage()` or a throwable argument.

3. Confirm source delimiters are generated independently of document content:

   ```bash
   rg -n 'UUID.randomUUID|sourceContent.contains|hash|digest' src/main/java/uk/gegc/quizmaker/features/ai/application/impl/PromptTemplateServiceImpl.java
   ```

   Expected: a random boundary ID and direct collision checks are present; no
   document hash or digest is calculated.

## Manual Prompt Inspection

No live provider is needed. The `PromptTemplateTrustBoundaryTest` is the
repeatable inspection path because it loads the packaged resources and checks
all question types. If inspecting a debugger-captured prompt, use an invented
canary rather than real user content and confirm this order:

1. trusted generation parameters;
2. source trust rules;
3. question-type contract;
4. one `QUIZMAKER_UNTRUSTED_SOURCE_<id>_START` marker;
5. the exact canary source;
6. the matching `<id>_END` marker.

Never enable a live OpenAI call merely to inspect this structure, and never
paste a real document, answer, token, email, or credential into logs.

## Compatibility

- Existing generation URLs, HTTP schemas, `202` acceptance, polling, OpenAPI,
  authorization, ownership, and frontend behavior are unchanged.
- Existing manual/legacy questions and fill-gap questions with or without
  distractors are unchanged.
- All questions requested for one type in one chunk are still sent in one
  provider call.
- Retry count, cancellation, provider executor bounds, coverage threshold,
  billing settlement, persistence, model, schema, and token observation are
  unchanged.
- The target language still defaults to `en`; only the previously unresolved
  system-template placeholder is removed.

## Failure And Rollback

An unreadable template or unresolved known trusted placeholder now fails before
provider dispatch with the safe `PROMPT_CONSTRUCTION` category. Malformed model
output and provider failures keep the existing retry flow but diagnostics expose
only stable categories.

Rollback requires reverting the application/resource commit. There is no
migration, backfill, data repair, frontend rollback, or stored-prompt cleanup.

## Privacy, N+1, And Document Content

- Logs retain only bounded request type/count, schema/model metadata, token usage,
  attempt number, and failure category; no source/output preview remains.
- No repository, entity graph, relationship, or query changed. N+1 behavior is
  unchanged and no new query exists.
- Delimiter selection does not hash, fingerprint, copy for version control, or
  persist document content. It performs direct marker collision checks against
  the existing in-memory chunk.
- Automated verification calls no OpenAI, Stripe, storage, email, database, or
  other external service.
