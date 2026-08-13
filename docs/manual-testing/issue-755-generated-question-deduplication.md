# Issue 755 Manual Testing: Generated Question Deduplication

## Purpose

Verify that exact repeated AI-generated questions count once within a generation job. Later copies must be discarded before coverage, final quiz assembly, and valid-question billing, while genuinely different questions and every existing manual or stored question remain unchanged.

Read the authoritative quantity contract in [Quiz Generation Coverage](../quiz-generation-coverage.md) before testing.

## Deterministic Offline Verification

Run every command locally from the repository root. These tests use in-memory questions, Mockito collaborators, and a fake structured-AI response. They do not call OpenAI, Stripe, MySQL, document parsers, or another external service.

1. Select JDK 17:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run the focused identity, coverage, and service workflow tests:

   ```bash
   ./mvnw -Dtest=GeneratedQuestionSemanticIdentityTest,GenerationCoveragePolicyTest,AiQuizGenerationFailureScenariosTest test
   ```

3. Confirm the command finishes with `BUILD SUCCESS`. Do not use `-DskipTests`, enable a live-provider profile, or provide a real API key.

The tests cover all supported question families, normalized stems, shuffled presentation order, changed answer semantics, same-batch and cross-chunk copies, deterministic first retention, the strict 80% failure boundary, redistribution, successful distinct output, billing release on failure, and successful completion without duplicate publication.

## Behavior Check

Use non-sensitive local test content if an end-to-end check is needed.

1. Generate ten `MCQ_SINGLE` questions from content that repeats the same fact in multiple chunks.
2. Inspect the completed quiz through the existing API or frontend.
3. Confirm semantically identical output with only case, whitespace, JSON property, option ID, or display-order differences appears once.
4. Confirm questions with meaningfully different stems or assessed answers remain present even when they cover related material.
5. If only eight distinct valid questions remain from a request for ten, confirm the job fails at the existing strict threshold, no quiz is published, and the reservation is released.
6. If nine distinct valid questions remain, confirm the existing eligible-partial path may complete and only those nine are published and settled.
7. Confirm the generation log contains bounded `requested`, `accepted`, `missing`, `discarded`, `duplicates`, and `outcome` counts, but no stems, answers, options, source text, prompts, provider responses, filenames, or user identifiers.

## Compatibility Check

1. Create and edit manual questions, including a legacy fill-gap question without `options`; confirm behavior is unchanged.
2. Read quizzes created before this deployment; confirm no question is removed or rewritten.
3. Confirm generation request and status JSON, HTTP statuses, authorization, ownership rules, Swagger schemas, and frontend polling remain unchanged.
4. Confirm duplicate detection is limited to one generation job. Similar questions in separate jobs or stored quizzes are not compared.
5. Confirm fuzzy or paraphrased questions are not rejected merely because they discuss the same topic.

## Privacy, Performance, And Rollback

No source document is copied, fingerprinted, or hashed. Identity is calculated only from each bounded generated question and exists only during an in-memory coverage evaluation.

No repository query, entity association, or persistence operation was added, so N+1 is not applicable. There is no migration or retained fingerprint to clean up.

Rollback the application commit if distinct questions disappear, shuffled copies bypass detection, duplicate copies satisfy coverage, generated content appears in logs, legacy/manual questions change, or the existing frontend cannot complete its polling flow. Rollback requires no database or client operation.
