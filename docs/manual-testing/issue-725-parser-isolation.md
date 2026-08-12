# Manual Test Guide: #725 Parser Isolation

## Purpose

Document conversion now runs in a bounded child JVM. A parser that exceeds its wall-time or heap budget can be killed without stopping the backend, and global/per-user capacity is returned only after process exit is confirmed.

There is no public API or frontend change. Existing PDF, EPUB, UTF-8 text, legacy MIME aliases, extension fallback, permissions, response DTOs, and `415/422/503` problem contracts remain supported.

## Preconditions

1. Run all commands in this guide on the local development machine unless a step explicitly says otherwise.
2. Use Java 17 and a writable `<LOCAL_PATH>` outside the repository for temporary document storage.
3. Prepare small valid `<LOCAL_PATH>/notes.txt`, `<LOCAL_PATH>/notes.pdf`, and `<LOCAL_PATH>/notes.epub` fixtures.
4. Keep `<JWT>` in a local shell variable only. Do not put access tokens or uploaded content in issue comments or shared logs.

## Automated process-boundary verification

1. Run locally: `JAVA_HOME=<JAVA_17_HOME> ./mvnw test -Dtest=BoundedDocumentParseExecutorTest,DocumentParserWorkerIntegrationTest,DocumentParserProcessBoundaryTest,LocalDocumentParserWorkerFactoryTest,LocalDocumentParserWorkerTest,DocumentParserWorkerMainTest,DocumentParserProtocolCodecTest,ParentProcessMonitorTest,MicrometerDocumentParserWorkerMetricsTest,DocumentProcessingLimitsTest,DeploymentDocumentParserConfigurationContractTest,DocumentProcessingServiceImplTest`
2. Verify the result reports zero failures and zero errors.

The real subprocess test installs a local SIGTERM handler that ignores graceful termination. It proves forced termination and replacement capacity without calling OpenAI, storage providers, or any external network.

## Compatible successful conversions

1. Run locally: `export DOCUMENT_STORAGE_ROOT='<LOCAL_PATH>/quizmaker-parser-test'`
2. Run locally: `mkdir -p "$DOCUMENT_STORAGE_ROOT"`
3. Run locally: `JAVA_HOME=<JAVA_17_HOME> ./mvnw spring-boot:run`
4. In another local terminal, run: `export API_BASE_URL='http://localhost:8080'`
5. In that terminal, run: `export ACCESS_TOKEN='<JWT>'`
6. Run locally: `curl --fail-with-body -X POST "$API_BASE_URL/api/documents/upload" -H "Authorization: Bearer $ACCESS_TOKEN" -F 'file=@<LOCAL_PATH>/notes.txt;type=text/plain' -F 'chunkingStrategy=SIZE_BASED' -F 'maxChunkSize=3000'`
7. Repeat step 6 with `<LOCAL_PATH>/notes.pdf;type=application/pdf` and `<LOCAL_PATH>/notes.epub;type=application/epub+zip`.
8. Run locally: `find "$DOCUMENT_STORAGE_ROOT/.parse-workers" -maxdepth 1 -type d -name 'parse-*' -print`

Expected result:

- Each upload returns the existing HTTP `201` `DocumentDto` response.
- The converted types remain `text/plain`, `application/pdf`, and `application/epub+zip`.
- No `parse-*` operation directory remains after each request.
- The backend remains healthy and no frontend field changes are needed.

## Timeout and capacity reclamation

Use a disposable local backend only. Do not lower production limits for this check.

1. Stop the local backend from the previous section.
2. Run locally: `DOCUMENT_STORAGE_ROOT='<LOCAL_PATH>/quizmaker-parser-timeout' DOCUMENT_PARSE_TIMEOUT=PT0.1S DOCUMENT_PARSER_TERMINATION_GRACE=PT0.1S DOCUMENT_PARSER_FORCE_KILL_TIMEOUT=PT2S DOCUMENT_PARSER_SHUTDOWN_TIMEOUT=PT3S JAVA_HOME=<JAVA_17_HOME> ./mvnw spring-boot:run`
3. In another local terminal, submit the valid PDF using the `curl` command from the previous section.
4. Run locally: `pgrep -af -- '--document-parser-worker=' || true`
5. Run locally: `find '<LOCAL_PATH>/quizmaker-parser-timeout/.parse-workers' -maxdepth 1 -type d -name 'parse-*' -print`
6. Restore the default timeout, restart the backend, and upload the small text fixture.

Expected result:

- The timed request returns the existing HTTP `422` problem type ending in `document-resource-limit-exceeded`.
- No parser child remains after the termination budget.
- No completed/failed document is published for the timed request.
- The next normal upload succeeds, proving capacity was reclaimed.

## Shutdown behavior

1. Start a disposable local backend with `DOCUMENT_PARSER_SHUTDOWN_TIMEOUT=PT3S` and begin a sufficiently large valid document upload.
2. In the backend terminal, press `Ctrl-C` while the child process is active.
3. Run locally: `pgrep -af -- '--document-parser-worker=' || true`
4. Wait for `DOCUMENT_STAGING_RETENTION` only if the backend was forcibly killed before its shutdown hook could clean files.

Expected result:

- The backend exits within the configured shutdown budget.
- No child parser remains attached to the stopped parent.
- A crash-leftover private operation directory is not consumed as a valid response and is eligible for retention cleanup.

## Deployment configuration check

1. Run locally: `docker compose --env-file server/backend/env.production.example -f server/backend/docker-compose.yml config > /tmp/quizmaker-parser-compose.yml`
2. Run locally: `grep 'DOCUMENT_PARSER_' /tmp/quizmaker-parser-compose.yml`
3. Run locally: `grep -n 'USER quizmaker' server/backend/Dockerfile`
4. Run locally: `grep -n '/var/run/docker.sock' server/backend/docker-compose.yml || true`

Expected result:

- Every parser resource/lifecycle setting listed in the runbook is present with its documented positive default.
- The image runs as `quizmaker`, not root.
- No Docker control socket is mounted.

## Operational verification

The metrics endpoint requires `SYSTEM_ADMIN` and production binds it only to container loopback. Never put the token directly in shell history.

1. For a native local backend, run locally: `read -r -s -p "SYSTEM_ADMIN access token: " SYSTEM_ADMIN_ACCESS_TOKEN; printf '\n'`
2. Run locally: `curl --fail --silent -H "Authorization: Bearer $SYSTEM_ADMIN_ACCESS_TOKEN" 'http://127.0.0.1:8081/actuator/metrics/document.parser.workers.active'`
3. Run locally: `curl --fail --silent -H "Authorization: Bearer $SYSTEM_ADMIN_ACCESS_TOKEN" 'http://127.0.0.1:8081/actuator/metrics/document.parser.worker.events'`
4. For production, connect over SSH, change to `<DEPLOYMENT_PATH>`, and run on the Droplet: `BACKEND_CONTAINER=$(docker compose --env-file .env ps -q quizmaker-backend); test -n "$BACKEND_CONTAINER"`
5. On the Droplet, read the token: `read -r -s -p "SYSTEM_ADMIN access token: " SYSTEM_ADMIN_ACCESS_TOKEN; printf '\n'`
6. On the Droplet, inspect active workers without exposing the token as a process argument: `printf 'header = "Authorization: Bearer %s"\n' "$SYSTEM_ADMIN_ACCESS_TOKEN" | docker exec -i "$BACKEND_CONTAINER" curl --fail --silent --config - 'http://127.0.0.1:8081/actuator/metrics/document.parser.workers.active'`
7. Repeat step 6 with `document.parser.worker.events`, then run: `unset SYSTEM_ADMIN_ACCESS_TOKEN`

Expected result:

- The active value returns to zero after requests finish.
- Event tags use only the bounded outcomes listed in [the parser isolation runbook](../runbooks/document-parser-isolation.md).
- Responses contain no filename, path, owner, content, or document identifier.

## N+1 review

This change adds no repository method, entity association traversal, or persistence query. Parsing still finishes before the existing atomic publication transaction, and reprocessing uses the same bounded document/user/chunk queries as before. N+1 query testing is therefore not applicable to this process-boundary change.

## Residual security boundary

The child receives no inherited environment variables and only a private copy of the selected input, but it still shares the backend container UID and network namespace. This issue is a kill/resource boundary for trusted QuizMaker parser code, not an OS sandbox for arbitrary code or plugins. [Issue #733](https://github.com/Gegcuk/QuizMaker/issues/733) owns production filesystem, syscall, process-namespace, and network confinement.
