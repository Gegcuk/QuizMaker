# Manual Test Guide: #720 PDF Memory Policy

## Purpose

PDF parsing now starts with a bounded PDFBox mixed-memory policy instead of the library's default heap-backed policy. Each parse receives a random private directory under `<DOCUMENT_STORAGE_ROOT>/.pdf-scratch`, and the service removes owned temporary files after success or failure.

This is a server-side reliability change. The existing upload and quiz-generation request/response shapes, permissions, 150 MiB upload limit, extracted text, page/chapter detection, and HTTP `422` resource-limit response remain unchanged.

## Configuration

| Environment variable | Default | Meaning |
| --- | ---: | --- |
| `DOCUMENT_MAX_PDF_MAIN_MEMORY_BYTES` | `16777216` | PDFBox in-memory buffering allowance in bytes (16 MiB). |
| `DOCUMENT_MAX_PDF_STORAGE_BYTES` | `536870912` | Combined main-memory and scratch-file allowance in bytes (512 MiB). Must not be lower than the main-memory allowance. |
| `DOCUMENT_PDF_SCRATCH_RETENTION` | `PT24H` | Positive ISO-8601 duration for removing crash-leftover owned parse directories. |
| `DOCUMENT_STORAGE_ROOT` | `uploads/documents` | Existing document storage root; PDF scratch is stored in its private `.pdf-scratch` child. |

Never lower production limits for testing. The commands below are for a local or disposable environment only.

## Preconditions

1. Run the backend locally with Java 17 and a writable `DOCUMENT_STORAGE_ROOT`.
2. Sign in as a user who can upload documents and keep the bearer token only in a local shell variable.
3. Prepare one valid PDF larger than 4 KiB and one malformed file named `broken.pdf`.
4. Set `API_BASE_URL`, `ACCESS_TOKEN`, and `PDF_PATH` in the local shell.

## Compatible successful conversion

Run locally:

1. `export API_BASE_URL=http://localhost:8080`
2. `export ACCESS_TOKEN='<JWT>'`
3. `export PDF_PATH='<LOCAL_PATH>/study-notes.pdf'`
4. `curl --fail-with-body -X POST "$API_BASE_URL/api/documents/upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@$PDF_PATH;type=application/pdf" -F "chunkingStrategy=SIZE_BASED" -F "maxChunkSize=3000"`
5. `find "${DOCUMENT_STORAGE_ROOT:-uploads/documents}/.pdf-scratch" -maxdepth 1 -type d -name 'pdf-parse-*' -print`

Expected result:

- Upload returns HTTP `201` with the existing `DocumentDto` shape.
- Extracted text, page count, chapters, and generated chunks match the same PDF before this change.
- The final `find` command prints no per-parse directory.
- Logs contain no uploaded filename, extracted content, or scratch path from the scratch lifecycle.

## Storage ceiling failure

Start a separate local backend process with deliberately tiny limits:

1. `DOCUMENT_STORAGE_ROOT=/tmp/quizmaker-pdf-limit DOCUMENT_MAX_PDF_MAIN_MEMORY_BYTES=4096 DOCUMENT_MAX_PDF_STORAGE_BYTES=4096 DOCUMENT_PDF_SCRATCH_RETENTION=PT1H ./mvnw spring-boot:run`
2. In another local shell, upload the valid PDF using the command from the previous section.
3. `find /tmp/quizmaker-pdf-limit/.pdf-scratch -maxdepth 1 -type d -name 'pdf-parse-*' -print`

Expected result:

- Upload returns HTTP `422` with problem type ending in `document-resource-limit-exceeded`.
- No document or chunks are published.
- The final `find` command prints no per-parse directory.

## Parser failure cleanup

Run locally against the normal local backend:

1. `printf '%s\n' '%PDF-1.4' 'not-a-valid-pdf' '%%EOF' > /tmp/broken.pdf`
2. `curl -sS -o /tmp/pdf-error.json -w '%{http_code}\n' -X POST "$API_BASE_URL/api/documents/upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F "file=@/tmp/broken.pdf;type=application/pdf"`
3. `find "${DOCUMENT_STORAGE_ROOT:-uploads/documents}/.pdf-scratch" -maxdepth 1 -type d -name 'pdf-parse-*' -print`

Expected result:

- The malformed input is rejected through the existing safe document-processing error path.
- No document or chunks are published.
- The final `find` command prints no per-parse directory.

## Startup validation

Run locally:

1. `DOCUMENT_MAX_PDF_MAIN_MEMORY_BYTES=8192 DOCUMENT_MAX_PDF_STORAGE_BYTES=4096 ./mvnw spring-boot:run`
2. `DOCUMENT_PDF_SCRATCH_RETENTION=PT0S ./mvnw spring-boot:run`

Expected result: each process fails during application startup with a configuration-binding validation error. It must not accept traffic with an unbounded or contradictory PDF policy.

## Crash-leftover cleanup

Use a disposable local storage root:

1. `mkdir -p /tmp/quizmaker-pdf-cleanup/.pdf-scratch/pdf-parse-manual-leftover`
2. `touch -t 202001010000 /tmp/quizmaker-pdf-cleanup/.pdf-scratch/pdf-parse-manual-leftover`
3. In local terminal A: `DOCUMENT_STORAGE_ROOT=/tmp/quizmaker-pdf-cleanup DOCUMENT_PDF_SCRATCH_RETENTION=PT1S ./mvnw spring-boot:run`
4. After startup, in local terminal B: `test ! -e /tmp/quizmaker-pdf-cleanup/.pdf-scratch/pdf-parse-manual-leftover`

Expected result: startup succeeds and command 4 exits with status `0`. Unrelated directories without the owned `pdf-parse-` prefix remain untouched.

## Automated verification

Run locally:

1. `./mvnw -Dtest=DocumentProcessingLimitsTest,PdfScratchSpaceTest,DocumentConverterLimitsTest,PdfDocumentConverterTest test`

Expected result: 24 focused tests pass. No external service, database, or oversized fixture is required.
