# Issue 745: AI Runtime Question Content Validation

## Purpose

Verify that AI-generated content is accepted only when the existing runtime handler for that question type can consume it. Invalid provider entries are excluded before shuffling and persistence, while valid entries in the same response continue in their original order.

- Issue: [#745](https://github.com/Gegcuk/QuizMaker/issues/745)
- Parent: [#444](https://github.com/Gegcuk/QuizMaker/issues/444)
- Deep review: [#466](https://github.com/Gegcuk/QuizMaker/issues/466)
- Architecture: [policy-driven quiz execution](../architecture/policy-driven-quiz-execution.md)

## Prerequisites

- Run all commands locally from the repository root.
- Use Java 17.
- Do not configure or call OpenAI. The focused tests construct provider DTOs in memory and use the real runtime question handlers.

## Automated Verification

1. Select Java 17 locally:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   java -version
   ```

   Expected: Java reports version 17.

2. Run the focused application-boundary tests:

   ```bash
   ./mvnw -Dtest=QuestionContentValidationServiceImplTest,AiQuizGenerationRuntimeContentValidationTest test
   ```

   Expected: all selected tests pass without MySQL, Docker, network access, or a provider API key.

3. Run the directly affected existing service tests:

   ```bash
   ./mvnw -Dtest=AiQuizGenerationServiceImplUncoveredMethodsTest,AiQuizGenerationFailureScenariosTest,AiQuizGenerationServiceProviderUsageTest,AiRateLimitTest test
   ```

   Expected: all selected legacy tests pass and existing failure, usage, and rate-limit behavior remains unchanged.

## Behavior Checks

The focused tests verify these offline scenarios:

1. Valid content for all nine supported question types survives conversion and remains valid after positional shuffling.
2. A response containing valid, invalid, and valid entries keeps the two valid entries in their original relative order.
3. Blank content, malformed JSON, a missing type, a missing question object, and broken cross-item references are rejected.
4. Rejected content is never sent to the shuffler.
5. A legacy fill-gap question without `options` remains valid for manual typing.
6. A fill-gap question with `options` remains valid when the pool contains every correct answer plus six or seven unique distractors; a pool that omits a correct answer is rejected.

Expected rejection warnings use only one of these bounded forms:

```text
Rejected generated question content at position <INDEX> for type <TYPE>: malformed_content
Rejected generated question content at position <INDEX> for type <TYPE>: runtime_validation_failed
```

The warning must not include the rejected JSON, question text, answer, prompt, document, user, or job identifier.

## Optional User Smoke Test

After deploying through the normal reviewed CI/CD path:

1. Sign in to Quizzence with a test account that has enough generation balance.
2. Generate a small quiz from a non-sensitive test document and request at least `MCQ_SINGLE`, `FILL_GAP`, `ORDERING`, and `MATCHING`.
3. Open the generated quiz and start an attempt.
4. Verify every displayed question renders and accepts an answer without a client or server error.
5. For fill-gap questions with a distractor pool, verify the pool includes every expected answer and six or seven additional unique choices.
6. Open an existing legacy fill-gap question without a distractor pool and verify it still renders as a typed blank.

Expected: provider entries that reach the quiz are runtime-consumable; old typed fill-gap content and new distractor-based content both work. A normal provider smoke test cannot force malformed output, so the automated tests remain the authoritative invalid-output check.

## Compatibility And Safety

- No endpoint, DTO, OpenAPI, authentication, permission, database, migration, grading, attempt-policy, or frontend contract changes.
- User-created and already stored questions are not revalidated, rewritten, or deleted.
- Existing manual creation supports the same question content as before.
- Runtime handler validation is read-only and makes no repository query, so it cannot introduce N+1 database access.
- No document content is read or hashed.
- Rejection logs contain only the response position, question type, and bounded reason; they do not contain prompts, source text, answers, or raw provider content.
- Quantity thresholds, billing settlement, progress accounting, and provider retry classification remain tracked by #444 and #466.

## Rollback

Revert the local commit for #745. No database or data rollback is required because this change has no migration or persisted state.
