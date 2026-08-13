# Issue 752 Manual Testing: Bounded Fill-Gap Options

## Purpose

Verify that AI-generated and manually created drag-and-drop fill-gap questions accept a useful bounded option pool without retrying an otherwise valid provider response.

The contract is:

- every unique correct value from `gaps[].answer` appears in `options`;
- at least six options are incorrect distractors;
- six or seven distractors are preferred;
- the complete pool never contains more than ten options;
- options remain nonblank strings that are unique after trimming and case-insensitive comparison.

For the supported one-to-three-gap shape, this permits:

| Gaps | Minimum total | Maximum total | Possible distractors |
| --- | ---: | ---: | ---: |
| 1 | 7 | 10 | 6-9 |
| 2 | 8 | 10 | 6-8 |
| 3 | 9 | 10 | 6-7 |

Existing typed-answer fill-gap questions without `options` remain valid for manual creation and stored legacy content. AI generation still requires `options`.

## Automated Verification

Run these commands locally from the repository root. They use JSON fixtures and a fake Spring AI response; they do not call OpenAI or another remote provider.

1. Select Java 17:

   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 17)
   ```

2. Run the focused validator, prompt, schema endpoint, structured-client, parser, manual-handler, grading, and extraction tests:

   ```bash
   ./mvnw -Dtest=FillGapContentValidatorTest,FillGapPromptResourceTest,QuestionSchemaRegistryTest,QuestionSchemaEndpointTest,SpringAiStructuredClientFillGapGenerationTest,FillGapQuestionParserTest,FillGapQuestionGenerationIntegrationTest,FillGapHandlerTest,CorrectAnswerExtractorTest test
   ```

3. Confirm the command finishes with `BUILD SUCCESS`. Do not use `-DskipTests`, a live-provider profile, or a real API key.

The focused tests must prove that two answers plus eight distractors are accepted as ten total options, eleven total options are rejected, fewer than six distractors are rejected, all answers remain required, AI options remain mandatory, and manual/legacy content without options remains valid.

## Fixture Check

The following content is the production failure shape that must now pass in strict AI and lenient manual validation:

```json
{
  "text": "The capital of {1} is {2}.",
  "gaps": [
    {"id": 1, "answer": "France"},
    {"id": 2, "answer": "Paris"}
  ],
  "options": [
    "France",
    "Paris",
    "Germany",
    "Berlin",
    "London",
    "Madrid",
    "Rome",
    "Italy",
    "Spain",
    "Lisbon"
  ]
}
```

Adding an eleventh option such as `Athens` must fail with a bounded structural message stating that no more than ten total items are allowed. Removing options until fewer than six distractors remain must also fail.

## Compatibility Check

1. In the public question schema, confirm `options` remains optional so a user may create typed-answer fill-gap content:

   ```json
   {
     "text": "The capital of France is {1}.",
     "gaps": [{"id": 1, "answer": "Paris"}]
   }
   ```

2. Confirm the same no-options shape is rejected at the AI structured-client/parser boundary.
3. Confirm answer extraction and grading still read only `gaps`; option count and order do not change correctness.
4. Confirm no endpoint, DTO, HTTP status, authorization rule, persistence model, migration, or frontend requirement changed.

## Operational Signal

After deployment, existing generation logs should no longer contain the former rejection for a two-gap question with ten options:

```text
Options array should have 8-9 items ... found 10
```

Pools above ten, missing answers, duplicates, blank values, and fewer than six distractors should continue to produce bounded validation errors. Do not include source text, generated answers, prompts, user details, job IDs, or provider responses in issue comments or screenshots.

## Risk And Rollback

This is a validation widening inside the already documented 7–10 item schema. It does not mutate provider output or stored questions. Roll back the commit if ten-option pools become unreadable in the existing frontend, grading changes, missing/duplicate answers are accepted, or pools above ten pass validation.

No repository query was added, so N+1 is not applicable. No document is read, copied, fingerprinted, or hashed by this change. There is no database migration; rollback requires no data operation.
