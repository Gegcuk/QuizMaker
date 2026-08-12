# Manual Test Guide: #721 Upload Chunk-Size Minimum

## Purpose

`POST /api/v1/quizzes/generate-from-upload` now enforces the chunk-size range already published by its OpenAPI schema: `1000..100000` characters. Omitting `maxChunkSize` still applies the existing `100000`-character default.

This is a validation-only correction. It does not change upload formats, file-size limits, chunking algorithms, generated questions, permissions, response schemas, or the legacy `POST /api/documents/upload` minimum.

Swagger UI: [https://www.quizzence.com/swagger-ui/index.html?urls.primaryName=quizzes](https://www.quizzence.com/swagger-ui/index.html?urls.primaryName=quizzes)

## Preconditions

1. Run the backend locally.
2. Sign in as a user with `QUIZ_CREATE` and keep the JWT only in a local shell variable.
3. Prepare a non-empty UTF-8 text file.
4. Run locally: `export API_BASE_URL=http://localhost:8080`
5. Run locally: `export ACCESS_TOKEN='<JWT>'`
6. Run locally: `export TEXT_FILE='<LOCAL_PATH>/notes.txt'`

## Below-Minimum Rejection

Run locally:

1. `curl -sS -o /tmp/chunk-minimum-error.json -w '%{http_code}\n' -X POST "$API_BASE_URL/api/v1/quizzes/generate-from-upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@$TEXT_FILE;type=text/plain" -F 'questionsPerType={"MCQ_SINGLE":1}' -F 'difficulty=MEDIUM' -F 'maxChunkSize=999'`
2. `cat /tmp/chunk-minimum-error.json`

Expected result:

- HTTP `400` with an RFC 7807 `application/problem+json` response.
- No document, generation job, billing reservation, or quiz is created.
- The uploaded file is not staged or parsed.

Repeat with `maxChunkSize=100001` and `maxChunkSize=not-a-number`; both return `400` without side effects.

## Valid Boundaries

Run locally:

1. Repeat the upload with `maxChunkSize=1000`.
2. Repeat the upload with `maxChunkSize=100000`.

Expected result: both requests return the existing HTTP `202` generation response and continue through normal processing.

## Omitted Value Compatibility

Run locally:

1. Repeat the upload without the `maxChunkSize` multipart field.

Expected result: the request returns the existing HTTP `202` response and uses the existing `100000`-character server default.

## Legacy Document-Only Compatibility

The older document-only upload contract intentionally keeps its existing `100..100000` validation range.

Run locally:

1. `curl --fail-with-body -X POST "$API_BASE_URL/api/documents/upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@$TEXT_FILE;type=text/plain" -F 'chunkingStrategy=SIZE_BASED' -F 'maxChunkSize=100'`

Expected result: the request retains its previous behavior; #721 does not tighten this separate endpoint.

## OpenAPI Verification

Run locally:

1. Open the quizzes Swagger UI link above.
2. Expand `POST /api/v1/quizzes/generate-from-upload`.
3. Inspect `maxChunkSize`.

Expected result: the field is optional, has minimum `1000`, maximum `100000`, and default `100000`.

## Automated Verification

Run locally:

1. `./mvnw -Dtest=QuizControllerGenerateFromUploadValidationTest,DocumentValidationServiceImplTest,QuizOpenApiContractTest test`

Expected result: all focused tests pass without a database or external provider.
