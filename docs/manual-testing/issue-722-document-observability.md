# Manual Test Guide: #722 Private Document Ingestion Telemetry

## Purpose

Verify that document ingestion emits bounded operational metrics while existing upload/reprocess behavior remains unchanged and private source data never enters feature logs or metric tags.

This issue adds no public endpoint, DTO, permission, database, or frontend change. Existing web and iOS clients continue to use the same operations and error responses documented in [Swagger UI](https://www.quizzence.com/swagger-ui/index.html).

## Preconditions

1. Use a local or non-production environment with a writable `DOCUMENT_STORAGE_ROOT`.
2. Use a non-production user that can upload documents and a separate `SYSTEM_ADMIN` access token for private Actuator access.
3. Keep both tokens in shell variables only. Do not paste them into source files, logs, tickets, or shared terminal output.
4. Keep the management listener on loopback. For a native local run, add `--management.server.port=8081 --management.server.address=127.0.0.1 --management.endpoints.web.exposure.include=health,info,metrics` to the existing application startup command.

## 1. Confirm The Private Boundary

Run locally:

1. `curl --silent --show-error --output /dev/null --write-out '%{http_code}\n' http://127.0.0.1:8081/actuator/metrics/document.ingestion.events`
2. `read -r -s -p "SYSTEM_ADMIN access token: " SYSTEM_ADMIN_ACCESS_TOKEN; printf '\n'`
3. `printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | curl --fail --silent --show-error --config - http://127.0.0.1:8081/actuator/metrics`

Expected result:

- The unauthenticated metric request returns `401`.
- The authorized metric catalog responds.
- The management listener is reachable only through loopback and is not exposed at `https://www.quizzence.com/actuator/metrics`.

## 2. Successful Text Ingestion

Run locally, replacing placeholders:

1. `export API_BASE_URL=http://localhost:8080`
2. `read -r -s -p "Upload-user access token: " ACCESS_TOKEN; printf '\n'`
3. `printf '1. PRIVATE_MANUAL_CHAPTER_722\n1.1 PRIVATE_MANUAL_SECTION_722\nStudy content\n' > /tmp/private-document-722.txt`
4. `curl --fail-with-body --request POST "$API_BASE_URL/api/documents/upload" --header "Authorization: Bearer $ACCESS_TOKEN" --form "file=@/tmp/private-document-722.txt;type=text/plain" --form "chunkingStrategy=SIZE_BASED" --form "maxChunkSize=3000"`
5. `printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | curl --fail --silent --show-error --config - 'http://127.0.0.1:8081/actuator/metrics/document.ingestion.events?tag=stage:processing&tag=outcome:succeeded&tag=reason:none'`
6. `printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | curl --fail --silent --show-error --config - 'http://127.0.0.1:8081/actuator/metrics/document.ingestion.extracted.characters?tag=format:text'`

Expected result:

- Upload returns the existing HTTP `201` `DocumentDto` response.
- The processing-success counter increases.
- The extracted-character summary count increases and its total amount includes this document's extracted size.
- `document.ingestion.active` returns to zero after processing completes.

## 3. Bounded Rejection Reason

Run locally:

1. `cp /tmp/private-document-722.txt /tmp/private-document-722.pdf`
2. `curl --silent --show-error --output /tmp/document-722-error.json --write-out '%{http_code}\n' --request POST "$API_BASE_URL/api/documents/upload" --header "Authorization: Bearer $ACCESS_TOKEN" --form "file=@/tmp/private-document-722.pdf;type=application/pdf"`
3. `printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | curl --fail --silent --show-error --config - 'http://127.0.0.1:8081/actuator/metrics/document.ingestion.events?tag=stage:staging&tag=outcome:rejected&tag=reason:type_mismatch'`

Expected result:

- Upload returns the existing HTTP `415` `document-type-mismatch` problem response.
- The staging rejection counter increases with only `stage=staging`, `outcome=rejected`, and `reason=type_mismatch` application tags.
- No filename, path, user, document identifier, content, or exception message appears in the metric response.

## 4. Log Privacy

Run locally against the application log produced by steps 2 and 3, replacing the log path:

1. `rg -n 'PRIVATE_MANUAL_CHAPTER_722|PRIVATE_MANUAL_SECTION_722|private-document-722' <LOCAL_PATH>/application.log`

Expected result: `rg` returns no match. Operational logs may contain fixed stage/outcome/reason values and bounded counts/sizes only.

Do not perform this check by printing production logs into a ticket or shared terminal. On the Droplet, run the same search in place and report only whether a match exists.

## 5. Production Metric Check

Run over SSH on the Droplet after a normal deployment:

1. `cd <DEPLOYMENT_PATH>`
2. `BACKEND_CONTAINER=$(docker compose --env-file .env ps -q quizmaker-backend); test -n "$BACKEND_CONTAINER"`
3. `read -r -s -p "SYSTEM_ADMIN access token: " SYSTEM_ADMIN_ACCESS_TOKEN; printf '\n'`
4. `printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | docker exec -i "$BACKEND_CONTAINER" curl --fail --silent --show-error --config - http://127.0.0.1:8081/actuator/metrics/document.ingestion.events`
5. `unset SYSTEM_ADMIN_ACCESS_TOKEN`

Expected result: the metric is available from inside the container, requires authorization, and contains only the bounded dimensions in the [document ingestion observability runbook](../runbooks/document-ingestion-observability.md).

## Automated Verification

Run locally with Java 17:

1. `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=MicrometerDocumentIngestionMetricsTest,SafeDocumentIngestionMetricsTest,DocumentLoggingPrivacyTest,DocumentValidationServiceImplTest,LocalDocumentUploadStagingServiceTest,DocumentProcessingServiceImplTest,DocumentStorageReconciliationSchedulerTest,DocumentStorageReconciliationFilesystemTest,BoundedDocumentParseExecutorTest,AfterCommitDocumentSourceFileCleanupTest test`
2. `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home ./mvnw -Dtest=DocumentControllerErrorTest,DocumentOpenApiContractTest,QuizOpenApiContractTest test`

Expected result: all focused tests pass without MySQL or an external provider. The repository owner runs the complete verification suite before release.

## N+1 Review

This change adds no repository method or persistence traversal. Metrics consume outcomes and counts already present in memory. Reconciliation still performs one batch lookup per 250 candidates plus one final exact lookup for each apparent orphan; its unit test verifies that live files do not cause per-file queries.
