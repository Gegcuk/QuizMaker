# Manual Test: Generation Metering and Tariff Snapshot (#451)

## Purpose

Verify that a quiz-generation job shows a maximum customer charge before work
starts, records the tariff that was quoted, and settles only for accepted
question types. Also verify that each returned provider attempt has durable,
deduplicated usage evidence. Provider LLM token usage remains operational
telemetry and does not change the customer charge.

## Preconditions

- Run this procedure in a non-production environment with billing enabled.
- Use an account with enough billing-token balance for the quoted maximum.
- Confirm the active configuration. The defaults are:

  ```text
  BILLING_GENERATION_TARIFF_VERSION=v1-content-length-per-question-type
  BILLING_GENERATION_BASE_TOKENS=3
  BILLING_GENERATION_TOKENS_PER_THOUSAND_CHARACTERS=0.35
  ```

- If a different test rate is required, set
  `BILLING_GENERATION_TOKENS_PER_THOUSAND_CHARACTERS` before starting the
  backend. The quoted amount is:

  ```text
  ceil(quotedContentCharacters / 1,000 * billingTokensPerThousandCharacters)
  * quotedQuestionTypeCount
  + billingBaseTokens
  ```

  Do not change a running production environment to perform this test.

## 1. Verify the pre-generation quote

1. Create or select a source with two selected chunks.
2. Request a generation estimate with two active question types. The requested
   question count for each type may be any positive value.
3. Confirm that the response remains compatible with existing clients and also
   includes these additive fields:

   ```json
   {
     "estimatedBillingTokens": 7,
     "tariffVersion": "v1-content-length-per-question-type",
     "billingBaseTokens": 3,
     "billingTokensPerThousandCharacters": 0.35,
     "quotedContentCharacters": 4000,
     "quotedQuestionTypeCount": 2
   }
   ```

4. Confirm that `estimatedBillingTokens` equals the configured formula above.
   It is the maximum charge shown to the customer, not an LLM-provider-token
   estimate.
5. Increase the question count while keeping the same active question types.
   The quote must not change. Add another active question type and confirm the
   quote increases by one source-length component. Neither case depends on an
   LLM response.

## 2. Verify a successful settlement

1. Start a generation job using the quote from the first section.
2. Wait for a successful job that produces accepted questions.
3. Inspect the resulting `quiz_generation_jobs` row in the non-production
   database. Confirm that these values are populated for the new job:

   - `billing_tariff_version`
   - `billing_base_tokens`
   - `billing_tokens_per_thousand_characters`
   - `billing_quoted_content_characters`
   - `billing_quoted_question_type_count`
   - `billing_accepted_question_type_count`

4. Confirm that the committed amount equals:

   ```text
   min(
       ceil(billing_quoted_content_characters / 1,000
            * billing_tokens_per_thousand_characters)
       * billing_accepted_question_type_count
       + billing_base_tokens,
       billing_estimated_tokens
   )
   ```

   A job with no accepted question type releases its whole reservation. A
   partially successful job is charged for each distinct type that produced at
   least one accepted question, never for more than the original quote.

5. If `provider_llm_tokens` is populated, confirm that changing or observing
   that value does not change the committed customer amount.

## 3. Verify durable provider usage

1. Get the completed job ID from
   `GET /api/v1/quizzes/generation-status/{jobId}`.
2. In the non-production MySQL database, run these read-only queries with that
   ID substituted for `<JOB_ID>`:

   ```sql
   SELECT provider_llm_tokens, provider_usage_state,
          billing_estimated_tokens, billing_committed_tokens
   FROM quiz_generation_jobs
   WHERE id = UUID_TO_BIN('<JOB_ID>');

   SELECT BIN_TO_UUID(provider_attempt_id) AS provider_attempt_id,
          record_state, provider_llm_tokens, recorded_at
   FROM quiz_generation_provider_usage
   WHERE job_id = UUID_TO_BIN('<JOB_ID>')
   ORDER BY recorded_at;
   ```

3. Confirm every `provider_attempt_id` occurs once. `REPORTED` rows have a
   non-negative token value; `MISSING` rows have a null value.
4. Confirm `quiz_generation_jobs.provider_llm_tokens` equals the sum of the
   `REPORTED` rows. `provider_usage_state` is `COMPLETE` when every observed
   response reported metadata and `INCOMPLETE` if any response did not.
5. Confirm the authenticated status response may include the additive nullable
   `providerUsageState` field when backend billing is enabled. An older client
   can ignore it and continue polling the same endpoint.
6. Run the deterministic concurrency and failure checks locally. They use fake
   AI responses and local MySQL; they never call OpenAI:

   ```bash
   ./mvnw test -Dtest=ProviderUsageServiceRetryTest,SpringAiStructuredClientProviderUsageTest
   ./mvnw test -Dtest=ProviderUsageServiceMySqlIntegrationTest,ProviderUsageSchemaMigrationTest
   ```

   The commands must finish with zero failures. The second command requires the
   repository's `test-mysql` database configuration.

## 4. Verify cancellation after provider work begins

1. Start another generation job and wait until it begins provider work.
2. Cancel the job before it completes.
3. Confirm that its reservation is released rather than committed.
4. Confirm the account balance has no generation charge for the cancelled job,
   including when provider token usage was recorded.

## 5. Verify compatibility, rollout, and API documentation

1. Open the Billing OpenAPI group in Swagger:

   ```text
   https://www.quizzence.com/swagger-ui/index.html
   ```

2. Locate `POST /api/v1/billing/estimate/quiz-generation`.
3. Confirm the existing estimate fields remain available and the five tariff
   fields are documented as optional additive response fields.
4. In the Quizzes OpenAPI group, confirm `QuizGenerationStatus` documents
   nullable `providerUsageState` and states that it does not determine customer
   charges.
5. Confirm historical terminal jobs with null tariff-snapshot columns remain
   readable. Migration V69 does not reinterpret or charge those jobs.
6. Confirm jobs that were `PENDING` or `PROCESSING` during migration are marked
   `INCOMPLETE` when they have a complete tariff snapshot. Active jobs with a
   null or partial snapshot are marked `LEGACY_REVIEW`; settlement must fail
   into the existing recoverable finalization path instead of guessing a
   charge.

## Expected Result

New jobs use the tariff that was quoted at job creation, are never charged more
than that quote, and settle by accepted question-type count. Provider attempts
are durable and idempotent, missing telemetry is explicit, and provider usage
never changes customer billing. Existing clients, historical terminal jobs,
and the previous estimate response fields remain supported.

## Related Frontend Work

The current frontend estimator already uses the same formula. Presentation of
the value as a maximum customer charge, including partial-success wording, is
tracked in [QuizMaker-Frontend#175](https://github.com/Gegcuk/QuizMaker-Frontend/issues/175).
