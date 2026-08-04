# Manual Test: Generation Metering and Tariff Snapshot (#451)

## Purpose

Verify that a quiz-generation job shows a maximum customer charge before work
starts, records the tariff that was quoted, and settles only for accepted
questions. Provider LLM token usage remains operational telemetry and does not
change the customer charge.

## Preconditions

- Run this procedure in a non-production environment with billing enabled.
- Use an account with enough billing-token balance for the quoted maximum.
- Confirm the active configuration. The defaults are:

  ```text
  BILLING_GENERATION_TARIFF_VERSION=v1-per-valid-question
  BILLING_GENERATION_TOKENS_PER_VALID_QUESTION=1
  ```

- If a different test rate is required, set
  `BILLING_GENERATION_TOKENS_PER_VALID_QUESTION` before starting the backend.
  Do not change a running production environment to perform this test.

## 1. Verify the pre-generation quote

1. Create or select a source with two selected chunks.
2. Request a generation estimate with one question type configured for three
   questions per chunk.
3. Confirm that the response remains compatible with existing clients and also
   includes these additive fields:

   ```json
   {
     "estimatedBillingTokens": 6,
     "tariffVersion": "v1-per-valid-question",
     "billingTokensPerValidQuestion": 1,
     "quotedQuestionCount": 6
   }
   ```

4. Confirm that `estimatedBillingTokens` equals
   `quotedQuestionCount * billingTokensPerValidQuestion`. It is the maximum
   charge shown to the customer, not an LLM-provider-token estimate.
5. Repeat with a different question count. The quote must change only with the
   requested question count and configured rate, not with an LLM response.

## 2. Verify a successful settlement

1. Start a generation job using the quote from the first section.
2. Wait for a successful job that produces accepted questions.
3. Inspect the resulting `quiz_generation_jobs` row in the non-production
   database. Confirm that these values are populated for the new job:

   - `billing_tariff_version`
   - `billing_tokens_per_valid_question`
   - `billing_quoted_question_count`
   - `billing_valid_question_count`

4. Confirm that the committed amount equals:

   ```text
   min(billing_valid_question_count * billing_tokens_per_valid_question,
       billing_estimated_tokens)
   ```

5. If `provider_llm_tokens` is populated, confirm that changing or observing
   that value does not change the committed customer amount.

## 3. Verify cancellation after provider work begins

1. Start another generation job and wait until it begins provider work.
2. Cancel the job before it completes.
3. Confirm that its reservation is released rather than committed.
4. Confirm the account balance has no generation charge for the cancelled job,
   including when provider token usage was recorded.

## 4. Verify compatibility and API documentation

1. Open the Billing OpenAPI group in Swagger:

   ```text
   https://www.quizzence.com/swagger-ui/index.html
   ```

2. Locate `POST /api/v1/billing/estimate/quiz-generation`.
3. Confirm the existing estimate fields remain available and the three tariff
   fields are documented as optional additive response fields.
4. Confirm that historical jobs with null tariff-snapshot columns remain
   readable. Their settlement continues to use the existing legacy path; this
   migration does not rewrite prior job data.

## Expected Result

New jobs use the tariff that was quoted at job creation, are never charged more
than that quote, and settle by valid accepted-question count. Existing clients,
historical jobs, and the previous estimate response fields remain supported.
