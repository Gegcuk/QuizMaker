# Manual Test Guide: #456 Document Ingestion Boundary

## Purpose

This change keeps the existing document upload APIs and success responses intact while making document ingestion bounded and atomic:

- uploads are staged by streaming instead of loading the whole multipart file into a byte array;
- the server detects PDF, EPUB, and UTF-8 text content from the staged file and rejects mismatches;
- document parsing has server-owned size, extraction, archive, page, timeout, global-capacity, and per-user-capacity limits;
- a document record and its chunks are committed only after parsing and chunking have succeeded;
- a failed reprocess retains the previously stored chunks.

Swagger operations:

- `POST /api/documents/upload`
- `POST /api/v1/quizzes/generate-from-upload`

Use the deployed Swagger UI at [https://www.quizzence.com/swagger-ui/index.html](https://www.quizzence.com/swagger-ui/index.html). Both operations require the existing bearer authentication and permissions; no frontend request-shape change is required.

## Preconditions

1. Run the backend locally or use a deployed environment where document storage is writable.
2. Sign in as a user who can upload documents. For quiz generation, use a user with `QUIZ_CREATE`.
3. Prepare a valid small `.txt`, `.pdf`, and `.epub` file. The text file must be UTF-8.
4. Use an access token only in a local shell variable. Do not place it in source files, issue comments, or terminal history that will be shared.

## Successful upload

Run locally, replacing placeholders:

1. `export API_BASE_URL=http://localhost:8080`
2. `export ACCESS_TOKEN='<JWT>'`
3. `curl --fail-with-body -X POST "$API_BASE_URL/api/documents/upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@<LOCAL_PATH>/study-notes.txt;type=text/plain" -F "chunkingStrategy=SIZE_BASED" -F "maxChunkSize=3000"`

Expected result:

- HTTP `201`.
- The response has the existing `DocumentDto` shape with `status` equal to `PROCESSED` and a positive `totalChunks` value.
- `GET /api/documents/{documentId}/chunks` returns the stored chunks for the same authenticated user.

Repeat with a valid PDF and EPUB. The returned `contentType` is server-detected (`application/pdf` or `application/epub+zip`), rather than trusted from the multipart header.

## Compatibility: existing generation flow

Run locally, using the same client fields previously sent by the frontend:

1. `curl --fail-with-body -X POST "$API_BASE_URL/api/v1/quizzes/generate-from-upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@<LOCAL_PATH>/study-notes.txt;type=application/octet-stream" -F "questionsPerTypeJson={\"MULTIPLE_CHOICE\":2}" -F "quizScope=PERSONAL"`

Expected result:

- HTTP `202` with the existing quiz-generation response shape.
- A generic multipart MIME type is accepted when the file content and filename extension identify a supported document.
- No new frontend field is required.

### Compatibility: client-extracted selected text

The current frontend extracts selected text from PDFs, EPUBs, and other supported source documents before calling this endpoint. It wraps that extracted content in a generated `selected-<source-name>.txt` file with `text/plain`; this remains supported without a frontend request-shape change.

Run locally:

1. `head -c 16383 /dev/zero | tr '\0' 'a' > /tmp/selected-source.epub.txt`
2. `printf '\320\220' >> /tmp/selected-source.epub.txt`
3. `curl --fail-with-body -X POST "$API_BASE_URL/api/v1/quizzes/generate-from-upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@/tmp/selected-source.epub.txt;type=text/plain" -F "questionsPerTypeJson={\"MULTIPLE_CHOICE\":2}" -F "quizScope=PERSONAL"`

Expected result:

- HTTP `202` with the existing quiz-generation response shape.
- The upload is processed as detected `text/plain`.
- Valid UTF-8 selected text does not cause a false `415` response when the bounded staging probe ends inside a multibyte character.
- Malformed UTF-8 content within the staged probe remains rejected with HTTP `415`.

## Rejected type mismatch

Run locally:

1. `cp <LOCAL_PATH>/study-notes.txt /tmp/study-notes.pdf`
2. `curl -sS -o /tmp/document-error.json -w '%{http_code}\n' -X POST "$API_BASE_URL/api/documents/upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@/tmp/study-notes.pdf;type=application/pdf"`

Expected result:

- HTTP `415`.
- The problem response type ends in `document-type-mismatch`.
- No document row or stored file is created.

Also upload a `.txt` file with a non-UTF-8 byte sequence after its first 16 KB. It must be rejected with the same `415` result rather than silently replacing malformed text.

## Rejected resource limits

Use a non-production local profile or temporary environment configuration. Do not lower production limits just to test this.

1. Start the backend with `DOCUMENT_MAX_UPLOAD_BYTES=1024`.
2. Upload a file larger than 1 KB through either upload endpoint.
3. Start the backend with `DOCUMENT_MAX_EXTRACTED_CHARACTERS=100`.
4. Upload a valid UTF-8 text document with more than 100 extracted characters.

Expected result:

- Oversized upload: HTTP `413`, problem type ending in `document-size-limit-exceeded`.
- Extraction, PDF-page, EPUB-entry/compression, or parse-time limit: HTTP `422`, problem type ending in `document-resource-limit-exceeded`.
- No partially processed document is visible through `GET /api/documents`.

## Temporary capacity rejection

In a local test environment only:

1. Start the backend with `DOCUMENT_MAX_CONCURRENT_PARSES=1` and `DOCUMENT_MAX_CONCURRENT_PARSES_PER_USER=1`.
2. Begin processing a sufficiently large valid document, or temporarily use a debugger to hold the parse operation.
3. While that parse is active, submit a second upload from the same user and another upload from a different user.

Expected result:

- The same-user request is rejected while the per-user slot is occupied.
- Any request beyond the global capacity is rejected.
- Rejected requests return HTTP `503` and a problem type ending in `document-processing-capacity-exceeded`.
- After the first parse completes or times out, a new upload can proceed.

## Atomic reprocessing

1. Upload a valid document and save its `documentId` and the response from `GET /api/documents/{documentId}/chunks`.
2. In a local environment, lower `DOCUMENT_MAX_EXTRACTED_CHARACTERS` below the document's extracted text length.
3. Call the existing reprocess endpoint for that document.
4. Restore the normal limit and call `GET /api/documents/{documentId}/chunks` again.

Expected result:

- Reprocessing returns HTTP `422`.
- The original chunks remain present and unchanged.
- A successful reprocess later replaces the complete chunk set in one transaction.

## Restart and storage reconciliation

1. Upload a normal document and confirm it remains available after restarting the backend.
2. Allow the configured `DOCUMENT_STAGING_RETENTION` and `DOCUMENT_RECONCILIATION_INTERVAL` to elapse in a non-production environment, or invoke the scheduler through a debugger.
3. Check the configured `DOCUMENT_STORAGE_ROOT`.

Expected result:

- Files referenced by document rows are retained.
- expired `.staging` files and expired unreferenced files under `published` are removed.
- A reconciliation failure is logged and does not interrupt uploads.

## Automated verification

Run locally:

1. `./mvnw test -Dtest=LocalDocumentUploadStagingServiceTest,BoundedDocumentParseExecutorTest,DocumentStorageReconciliationSchedulerTest,DocumentConverterLimitsTest,DocumentProcessingServiceImplTest,DocumentControllerErrorTest,DocumentOpenApiContractTest,QuizOpenApiContractTest,QuizGenerationFacadeImplTest`

Expected result: all focused tests pass. The full repository suite remains the final owner-run verification before release.
