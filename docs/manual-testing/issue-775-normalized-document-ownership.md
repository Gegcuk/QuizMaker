# Manual Test: Issue 775 Normalized Document Ownership

## Purpose

Verify that normalized documents are private to their authenticated uploader while authorized clients retain the same successful URLs and JSON schemas. This guide does not require a real AI request for the ownership checks.

Swagger: [Document Processing API](https://www.quizzence.com/swagger-ui/index.html)

## Preconditions

- Use a local or staging environment, not production document content.
- Prepare two ordinary accounts, `OWNER_A` and `USER_B`.
- Obtain their access tokens through the normal login flow. Do not place tokens in shell history, source control, screenshots, or this document.
- Set `<API_BASE>` to `http://localhost:8080` locally or the approved staging origin.

## Automated Checks

Run locally from the repository root:

1. `export JAVA_HOME='<LOCAL_PATH_TO_JDK_17>'`
2. `./mvnw -Dtest=NormalizedDocumentAccessServiceImplTest,NormalizedDocumentAuthenticationMetricsFilterTest,NormalizedDocumentAccessMetricsConfigurationTest,MicrometerNormalizedDocumentAccessMetricsTest,NormalizedDocumentPrivacyLoggingTest,DocumentProcessControllerTest,DocumentProcessControllerStructureTest,DocumentOpenApiContractTest,NormalizedDocumentOwnerMigrationTest test`
3. `export TEST_DB_PORT='33307'`
4. `export TEST_DB_USER='quizmaker_test_user'`
5. `printf 'Temporary MySQL password: '; read -r -s TEST_DB_PASSWORD; echo`
6. `docker run --rm --detach --name quizmaker-issue-775-mysql --publish "127.0.0.1:$TEST_DB_PORT:3306" --env MYSQL_ROOT_PASSWORD="$TEST_DB_PASSWORD" --env MYSQL_DATABASE='quizmaker_test_mysql' --env MYSQL_USER="$TEST_DB_USER" --env MYSQL_PASSWORD="$TEST_DB_PASSWORD" mysql:8.0`
7. `until docker exec quizmaker-issue-775-mysql mysqladmin ping --host=127.0.0.1 --user=root --password="$TEST_DB_PASSWORD" --silent; do sleep 2; done`
8. `./mvnw -q -Dflyway.url="jdbc:mysql://localhost:$TEST_DB_PORT/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" -Dflyway.user="$TEST_DB_USER" -Dflyway.password="$TEST_DB_PASSWORD" flyway:migrate`
9. `./mvnw -Dspring.datasource.url="jdbc:mysql://localhost:$TEST_DB_PORT/quizmaker_test_mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" -Dspring.datasource.username="$TEST_DB_USER" -Dspring.datasource.password="$TEST_DB_PASSWORD" -Dtest=NormalizedDocumentOwnershipMySqlIntegrationTest test`
10. `docker stop quizmaker-issue-775-mysql`
11. `unset TEST_DB_PASSWORD TEST_DB_USER TEST_DB_PORT`

Expected result: Flyway creates a clean schema through V74, all selected tests pass, the temporary container is removed, and no network call to OpenAI occurs. These commands do not read, repair, or modify the developer's existing MySQL databases.

## Owner Upload And Read

Run locally. Supply tokens through a secure shell prompt or temporary environment variables:

1. `export API_BASE='<API_BASE>'`
2. `export OWNER_A_TOKEN='<OWNER_A_ACCESS_TOKEN>'`
3. `export USER_B_TOKEN='<USER_B_ACCESS_TOKEN>'`
4. `curl --silent --show-error --fail-with-body --request POST "$API_BASE/api/v1/documentProcess/documents" --header "Authorization: Bearer $OWNER_A_TOKEN" --header 'Content-Type: application/json' --data '{"text":"Ownership smoke-test content","language":"en"}'`
5. Record the returned `id` as `<DOCUMENT_ID>` without publishing it.
6. `export DOCUMENT_ID='<DOCUMENT_ID>'`
7. `curl --silent --show-error --fail-with-body --header "Authorization: Bearer $OWNER_A_TOKEN" "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID"`
8. `curl --silent --show-error --fail-with-body --header "Authorization: Bearer $OWNER_A_TOKEN" "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID/head"`
9. `curl --silent --show-error --fail-with-body --header "Authorization: Bearer $OWNER_A_TOKEN" "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID/text?start=0&end=9"`
10. `curl --silent --show-error --fail-with-body --header "Authorization: Bearer $OWNER_A_TOKEN" "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID/structure?format=tree"`

Expected result: upload returns `201`; owner reads return `200`; metadata/text/structure success fields remain compatible with Swagger.

## Cross-User And Authentication Denial

Run locally:

1. `curl --silent --show-error --output /tmp/owner-denial.json --write-out '%{http_code}\n' --header "Authorization: Bearer $USER_B_TOKEN" "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID"`
2. `curl --silent --show-error --output /tmp/missing-denial.json --write-out '%{http_code}\n' --header "Authorization: Bearer $USER_B_TOKEN" "$API_BASE/api/v1/documentProcess/documents/00000000-0000-0000-0000-000000000001"`
3. `curl --silent --show-error --output /tmp/text-denial.json --write-out '%{http_code}\n' --header "Authorization: Bearer $USER_B_TOKEN" "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID/text?start=0&end=9"`
4. `curl --silent --show-error --output /tmp/structure-denial.json --write-out '%{http_code}\n' --header "Authorization: Bearer $USER_B_TOKEN" "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID/structure"`
5. `curl --silent --show-error --output /tmp/unauthenticated.json --write-out '%{http_code}\n' "$API_BASE/api/v1/documentProcess/documents/$DOCUMENT_ID"`
6. `diff /tmp/owner-denial.json /tmp/missing-denial.json`

Expected result: every cross-user request returns `404`; unauthenticated access returns `401`; wrong-owner and nonexistent bodies have the same public problem detail and disclose no filename, text, owner, or existence difference.

## Legacy Quarantine Verification

Run the following read-only query in a local/staging MySQL console, or over an approved SSH tunnel. Do not insert or assign legacy owners manually:

1. `SELECT COUNT(*) AS quarantined_rows FROM normalized_documents WHERE owner_id IS NULL;`
2. If a known non-production quarantined UUID exists, request it as either user and confirm the API returns the same `404 Document not found` response.
3. `SELECT index_name, column_name FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'normalized_documents' AND index_name = 'ix_normalized_documents_owner_id';`
4. `SELECT constraint_name, delete_rule FROM information_schema.referential_constraints WHERE constraint_schema = DATABASE() AND table_name = 'normalized_documents' AND constraint_name = 'fk_normalized_documents_owner';`

Expected result: null-owner rows remain unchanged and inaccessible; the index exists; the foreign key reports `SET NULL`.

## Privacy Check

1. Upload a local test file whose filename contains a unique canary such as `<PRIVATE_CANARY>.txt`.
2. Trigger an unsupported-format or fake-provider failure in local/staging configuration.
3. Search application logs for `<PRIVATE_CANARY>`.
4. Inspect the error response and metrics labels.

Expected result: the canary, source text, username, path, anchors, and raw provider response are absent. Metrics contain only a bounded access outcome.

## Rollback Safety

Do not restore a pre-issue-775 binary while this route remains public. A safe rollback either keeps this ownership boundary in a compatibility build or blocks `/api/v1/documentProcess/documents/**` at the reverse proxy until the fixed build is restored. Leave V74 and quarantined rows in place.
