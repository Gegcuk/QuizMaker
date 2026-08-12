# Issue 743: AI Type And Difficulty Contract

## Purpose

Verify that structured AI generation accepts only questions matching the concrete type and difficulty sent to the provider. This is an internal generation-boundary change: public question schemas, user-created questions, legacy stored questions, generation endpoints, and frontend behavior remain unchanged.

- Issue: [#743](https://github.com/Gegcuk/QuizMaker/issues/743)
- Parent: [#444](https://github.com/Gegcuk/QuizMaker/issues/444)
- Architecture: [policy-driven quiz execution](../architecture/policy-driven-quiz-execution.md)

## Prerequisites

- Run all commands locally from the repository root.
- Use Java 17.
- Do not configure or call a real OpenAI account. The focused tests use stubbed `ChatResponse` values only.

## Automated Verification

1. Select Java 17 locally:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   java -version
   ```

   Expected: Java reports version 17.

2. Run the focused schema and structured-client contract tests:

   ```bash
   ./mvnw -Dtest=QuestionSchemaRegistryTest,SpringAiStructuredClientRequestContractTest,SpringAiStructuredClientResponseParsingTest test
   ```

   Expected: all selected tests pass, with no network or provider call.

## Behavior Checks

The focused tests verify these offline scenarios:

1. A `TRUE_FALSE` / `MEDIUM` fake response is returned unchanged.
2. The provider schema contains only `TRUE_FALSE` in the type enum and only `MEDIUM` in the difficulty enum.
3. A valid `OPEN` question returned for a `TRUE_FALSE` request is rejected.
4. A valid `TRUE_FALSE` / `HARD` question returned for a `MEDIUM` request is rejected.
5. A mixed response keeps only exact matches and emits warnings containing bounded enum values, not prompts or question content.
6. The public/manual schema still accepts every supported type and difficulty, preserving legacy and user-created question behavior.

## Compatibility And Safety

- No endpoint, DTO, OpenAPI, authentication, permission, database, migration, or frontend contract changes.
- No stored question is rewritten or revalidated.
- No repository query changed; N+1 behavior is unaffected.
- No document content is read or hashed.
- No prompt, source text, answer, raw response, user identifier, or document identifier is added to warnings or metric tags.
- Existing outer fallback behavior is unchanged. This issue validates the concrete request sent by each provider attempt; broader fallback classification remains in #444.

## Rollback

Revert the local commit for #743. No database or data rollback is required because this change has no persisted state or migration.
