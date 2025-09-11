# Structured Output Quiz Generation Implementation Plan

Here's a precise, incremental plan to add a parallel "structured-output" quiz generation endpoint that mirrors the current AI flow without changing any existing behavior. This plan references current code so you can wire into the same jobs/events/prompts, and keep everything side-by-side.

## Overview

**Goal**: Add a new endpoint that triggers the same quiz generation pipeline but uses Spring AI structured output (`BeanOutputConverter`) instead of free-text + manual parsing.

- Keep existing endpoints, services, and prompts untouched
- New code lives alongside and publishes the same completion event
- Leverage Spring AI's structured conversion for schema‑guided, typed results (not only JSON text)

## Understanding Current Flow

- **Quiz endpoints**: `src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java:536`
- **Async generation orchestration**: `src/main/java/uk/gegc/quizmaker/features/ai/application/impl/AiQuizGenerationServiceImpl.java:353`
- **Free-text call site**: `generateQuestionsByType(...)` uses `ChatClient` and parses JSON text via `QuestionResponseParser` 
- **Completion event → quiz creation**: `src/main/java/uk/gegc/quizmaker/features/quiz/application/impl/QuizServiceImpl.java:428`
- **Prompt building and templates**: `src/main/java/uk/gegc/quizmaker/features/ai/application/impl/PromptTemplateServiceImpl.java:1`, resources under `src/main/resources/prompts/**`

## Step 1: Add Structured DTOs (Records)

Create a new container for structured output schemas, e.g. `src/main/java/uk/gegc/quizmaker/features/ai/infra/structured/QuestionStructuredRecords.java`.

### Step 1.1: Top-level Records
Add a top-level record per question type, each with `@JsonProperty("questions") List<...>`.

### Step 1.2: MCQ Question Types
For `MCQ_SINGLE` and `MCQ_MULTI`, define:
- `questionText`
- `difficulty`
- `type`
- `hint`
- `explanation`
- `content` with options where each option has:
  - `id` (string)
  - `text` (string)
  - `correct` (boolean)

Match handlers' expectations (`McqSingleHandler.java:17` and `McqMultiHandler.java:17`).

### Step 1.3: True/False Questions
For `TRUE_FALSE`, define item with `content.answer` (boolean). Match `TrueFalseHandler.java:17`.

### Step 1.4: Fill Gap Questions
For `FILL_GAP`, define:
- `content.text` (with `___` markers)
- `content.gaps[]` entries with:
  - `id` (int)
  - `answer` (string)

Match `FillGapQuestionParser.java:37`.

### Step 1.5: Other Question Types
For `ORDERING`, `COMPLIANCE`, `HOTSPOT`, `MATCHING`, `OPEN`: mirror the structures used by existing parsers under `features/ai/infra/parser/*`. Keep field names identical ("questions", "questionText", "difficulty", "type", "content", etc.) so validation and downstream assumptions hold.

## Step 2: Add a New Service Interface

Create `src/main/java/uk/gegc/quizmaker/features/ai/application/StructuredAiQuizGenerationService.java` mirroring the existing `AiQuizGenerationService` signatures:

```java
void generateQuizFromDocumentAsync(UUID jobId, GenerateQuizFromDocumentRequest request);
void generateQuizFromDocumentAsync(QuizGenerationJob job, GenerateQuizFromDocumentRequest request);
CompletableFuture<List<Question>> generateQuestionsFromChunk(DocumentChunk, Map<QuestionType,Integer>, Difficulty);
List<Question> generateQuestionsByType(String chunkContent, QuestionType type, int questionCount, Difficulty);
void validateDocumentForGeneration(UUID docId, String username);
int calculateEstimatedGenerationTime(int totalChunks, Map<QuestionType,Integer>);
int calculateTotalChunks(UUID documentId, GenerateQuizFromDocumentRequest request);
```

Keep names identical so the usage pattern is familiar; this service will be used only by the new endpoint.

## Step 3: Implement Structured Service

Create `src/main/java/uk/gegc/quizmaker/features/ai/application/impl/StructuredAiQuizGenerationServiceImpl.java`.

### Step 3.1: Dependency Injection
Inject beans:
- `ChatModel` (not `ChatClient`)
- `PromptTemplateService`
- `QuizGenerationJobRepository`
- `QuizGenerationJobService`
- `DocumentRepository`
- `UserRepository`
- `ApplicationEventPublisher`
- `AiRateLimitConfig`
- `ObjectMapper`

Also mirror method annotations from the legacy service for parity:
- Annotate public async methods with `@Async("aiTaskExecutor")`
- Use `@Transactional` where applicable

### Step 3.2: Reuse Orchestration
Copy the high-level orchestration from `AiQuizGenerationServiceImpl.generateQuizFromDocumentAsync(...)` to keep:
- Chunk selection
- Progress updates
- Redistribution
- Rate limit handling
- Event publishing
- Error handling

**Do not alter existing class**; duplicate the flow here to avoid regressions.

### Step 3.3: Replace Free-text with Structured Call
Inside your `generateQuestionsByType(...)`:

1. **Build the base prompt** using the existing service:
   - Call `promptTemplateService.buildPromptForChunk(content, type, count, difficulty)` (it assembles system/context/type templates internally). Note: `{chunkIndex}` in `context-template.txt` is currently not substituted; safe to ignore.

2. **Append BeanOutputConverter format string**:
   - Use `new BeanOutputConverter<>(YourQuestionSetRecord.class)` and append `outputConverter.getFormat()` to the final prompt
   - See pattern: `src/main/java/uk/gegc/quizmaker/features/documentProcess/application/OpenAiLlmClient.java:88`

3. **Call AI via ChatModel**:
   ```java
   ChatResponse chatResponse = chatModel.call(new Prompt(new UserMessage(promptWithFormat)));
   ```

4. **Convert back to typed record**:
   ```java
   YourQuestionSetRecord result = outputConverter.convert(aiResponseText);
   ```

5. **Map to domain**:
   For each question item, construct a `Question`:
   - `setQuestionText(item.questionText())`
   - `setType(expected QuestionType)`
   - `setDifficulty(Difficulty.valueOf(item.difficulty()))` with `MEDIUM` fallback on invalid
   - Serialize content sub-object to JSON with `ObjectMapper.writeValueAsString(item.content())` and `setContent(...)`
   - Optional hint/explanation if present

6. **Validate with existing handlers**:
   Either call `QuestionResponseParser.validateQuestionContent(question)` or directly resolve a `QuestionHandler` via `QuestionHandlerFactory` and validate; both are acceptable. Using the existing parser's `validateQuestionContent(...)` is simplest.

### Step 3.4: Fallback Parity
Implement `generateQuestionsByTypeWithFallbacks(...)` in the structured service mirroring the legacy flow:
- Normal attempts (multiple tries)
- Reduced `questionCount` attempts
- Easier difficulty fallback (if not already EASY)
- Alternative type fallback
- Last‑resort `MCQ_SINGLE`

### Step 3.5: Error/Exception Mapping
Wrap conversion/AI errors in `AiServiceException` and reuse the same rate‑limit detection and backoff utilities as the legacy service so status, retries, and logs behave identically.

### Step 3.6: Keep Event Publishing Identical
Publish `QuizGenerationCompletedEvent` so `QuizServiceImpl.handleQuizGenerationCompleted(...)` remains unchanged (`src/main/java/uk/gegc/quizmaker/features/quiz/application/impl/QuizServiceImpl.java:428`).

### Step 3.7: Keep Job/Progress Identical
Mirror `updateJobProgress(...)`, error recording, and `markCompleted`/`markFailed` to ensure `/generation-status` and `/generated-quiz` endpoints work the same.

### Step 3.8: Helper Methods to Port
Copy equivalents of:
- `updateJobStatusSafely(...)`
- `getChunksForScope(...)`, `matchesChapter(...)`, `matchesSection(...)`
- `findMissingQuestionTypes(...)`, `redistributeMissingQuestions(...)`
- `findAlternativeQuestionType(...)`, `getEasierDifficulty(...)`
- `formatCoverageSummary(...)`
- `isRateLimitError(...)`, `calculateBackoffDelay(...)`, `sleepForRateLimit(...)`

## Step 4: Prompt Usage (Existing Templates)

- **System prompt**: `src/main/resources/prompts/base/system-prompt.txt` enforces "valid JSON only" and "exact structure". Keep it unchanged; it helps structured output too.
- **Context**: `src/main/resources/prompts/base/context-template.txt` is used to inject content and parameters. Keep it unchanged; no behavior change.
- **Type templates**: `src/main/resources/prompts/question-types/*.txt` include explicit "JSON STRUCTURE" blocks. Keep these. They complement the `BeanOutputConverter`'s schema hint and align with handlers' validations (e.g., MCQ requires `options[].id`).
- **Assembly**: `PromptTemplateServiceImpl` already stitches these and replaces placeholders (`src/main/java/uk/gegc/quizmaker/features/ai/application/impl/PromptTemplateServiceImpl.java:20`). For structured output, append the `outputConverter.getFormat()` string after the assembled prompt (do not alter the template files).

## Step 5: Wire a New Quiz Service Entry

Add a new method to `QuizService` interface (same package as existing):

```java
QuizGenerationResponse startQuizGenerationStructured(String username, GenerateQuizFromDocumentRequest request);
```

Implement in `QuizServiceImpl`:

1. **Reuse guardrails**: Same as `startQuizGeneration(...)` (user lookup, "no active job" check)
2. **Reuse calculations**: `aiQuizGenerationService.calculateTotalChunks(...)` and `calculateEstimatedGenerationTime(...)` to compute estimates and create a `QuizGenerationJob` exactly as before
3. **Call structured service**: Instead of calling the original `aiQuizGenerationService.generateQuizFromDocumentAsync(job.getId(), request)`, call your new structured service: `structuredAiQuizGenerationService.generateQuizFromDocumentAsync(job.getId(), request)`
4. **Return response**: `QuizGenerationResponse.started(jobId, estimatedSeconds)` exactly as the original does

## Step 6: Add the New Endpoint

In `QuizController` add a new POST:

- **Path**: `/api/v1/quizzes/generate-from-document-structured`
- **Method signature**: Accepts the same `GenerateQuizFromDocumentRequest` and returns `QuizGenerationResponse`
- **Security**: Copy `@PreAuthorize("hasRole('ADMIN')")` from the existing endpoint (`src/main/java/uk/gegc/quizmaker/features/quiz/api/QuizController.java:536`)
- **OpenAPI**: Copy the same `@Operation`/`responses`; change summary/description to mention "structured output"
- **Implementation**: Delegate to `quizService.startQuizGenerationStructured(authentication.getName(), request)`

This keeps current endpoints and behavior intact, while exposing a parallel path.

## Step 7: Reuse Existing Chunk Selection

Ensure your structured service reuses the same chunk selection code (entire doc / specific chunks / chapter / section logic) by copying from `AiQuizGenerationServiceImpl.getChunksForScope(...)` and related helpers. That keeps parity with scope handling used by `QuizServiceImpl.verifyDocumentChunks(...)` and job estimate calculation.

## Step 8: Rate Limiting and Resilience

- Use the same `AiRateLimitConfig` for backoff (`getBaseDelayMs`, `getMaxDelayMs`, `getJitterFactor`) and detection of 429-style errors
- Mirror `isRateLimitError(...)` and `calculateBackoffDelay(...)` (`AiQuizGenerationServiceImpl.java:1007` and nearby)
- Maintain "no per-run file logging" for quiz generation responses (as the current unstructured path avoids it)

Also carry over `sleepForRateLimit(...)` to keep the same waiting behavior during retries.

## Step 9: Dependency Injection

- Add a field for your new structured service in `QuizServiceImpl` and constructor-inject it
- Ensure a `ChatModel` bean exists (already present since `OpenAiLlmClient` uses it). `ChatClient` remains for the legacy path.

## Step 10: Validation and Mapping

After mapping records to `Question`, validate content using the same handlers the parsers use:

- Via `QuestionResponseParser.validateQuestionContent(question)` or directly via `QuestionHandlerFactory.getHandler(type).validateContent(...)`
- This ensures created questions pass the same constraints enforced elsewhere (e.g., MCQ option IDs uniqueness, exactly one correct for `MCQ_SINGLE`, etc.)

Additionally, assert the record `type` matches the requested `QuestionType` to catch upstream drift early.

## Testing (Manual)

1. **Call both endpoints**: Call the original `/generate-from-document` and the new `/generate-from-document-structured` with the same payload; both should return 202 with job IDs and similar ETA
2. **Monitor progress**: Poll `/generation-status/{jobId}` for each job; progress should advance similarly
3. **Verify results**: When done, call `/generated-quiz/{jobId}`; both should return a quiz with valid questions. Use the existing attempt flows to verify answers validate correctly
4. **Check downstream**: Verify moderation and publishing flows remain unaffected for quizzes created by the structured path

## Notes and Caveats

- The context template currently contains `{chunkIndex}` but `PromptTemplateServiceImpl` doesn't substitute it. It's safe to leave as-is; no change needed to add the structured endpoint
- Keep prompt examples in `src/main/resources/prompts/examples/*.json` unchanged; they are helpful for model behavior but not required by the structured converter
- If you want stricter schemas, the `BeanOutputConverter` record fields become your contract; keep them aligned with the existing handlers and parser expectations to avoid downstream validation errors

## Optional Parity Extensions

- Add structured variants for other generation entrypoints for full parity:
  - `/generate-from-text` and `/generate-from-upload` (mirror existing controller methods and flows, but delegate to `startQuizGenerationStructured(...)` after document/text processing).
- Consider exposing a feature flag to switch between legacy and structured paths for A/B testing.

## Sanity Checks Before Merge

- Job lifecycle parity: Status updates, progress percentages, and `QuizGenerationCompletedEvent` publishing must match the legacy path so `/generation-status` and `/generated-quiz` behave identically.
- Guardrails parity: Enforce the same "one active job per user" constraint in `startQuizGenerationStructured(...)` as in the original `startQuizGeneration(...)`.
- Observability: Log messages and warnings comparable to legacy (without writing per‑run AI response files).

## Next Steps

If you want, I can draft the record definitions and the skeleton of `StructuredAiQuizGenerationServiceImpl` using the same orchestration and show exactly where to append `outputConverter.getFormat()` to the assembled prompt.
