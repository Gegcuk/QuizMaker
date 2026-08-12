# Issue #735: Document Converter Test Isolation

## Purpose

Verify that document converter selection, minimal Spring bean discovery, and bounded EPUB parsing run without MySQL, JPA, Flyway, a full Spring Boot application context, provider credentials, or external network access.

This is a test-boundary change only. It does not alter document conversion, supported formats, API responses, permissions, storage, database schema, or frontend behavior.

## Preconditions

- Run every command locally from the repository root.
- Use Java 17.
- Maven dependencies may be downloaded before the offline check if they are not already cached.
- MySQL is not required. Stopping a developer-owned local MySQL instance is optional; do not stop a shared or production database.

## Verification

1. Select Java 17 locally.

   ```bash
   export JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.17/libexec/openjdk.jdk/Contents/Home
   export PATH="$JAVA_HOME/bin:$PATH"
   java -version
   ```

   Expected: Java reports version 17.

2. Optionally confirm that no local MySQL server is listening.

   ```bash
   lsof -nP -iTCP:3306 -sTCP:LISTEN
   ```

   Expected for the strongest isolation check: no output. The tests must still pass.

3. Run the three focused converter test classes.

   ```bash
   ./mvnw -Dtest=DocumentConverterFactoryTest,DocumentConverterStartupTest,uk.gegc.quizmaker.service.document.converter.impl.EpubDocumentConverterTest test
   ```

   Expected:

   - `tests-parallel` reports 37 tests with zero failures, errors, or skips;
   - `tests-db-serial` reports zero tests;
   - no datasource connection, Testcontainers, JPA, or Flyway startup appears;
   - canonical and legacy MIME aliases, extension fallback, unsupported input, converter ordering, valid EPUB extraction, malformed EPUB compatibility, and extracted-text limits are covered.

4. Confirm the tests belong to the parallel non-database lane.

   ```bash
   rg -n '@SpringBootTest|@Tag\("db-serial"\)' src/test/java/uk/gegc/quizmaker/service/document/converter
   ```

   Expected: no match in `DocumentConverterFactoryTest`, `DocumentConverterStartupTest`, or the legacy `impl/EpubDocumentConverterTest`.

5. After dependencies are cached, optionally repeat the focused verification without network access.

   ```bash
   ./mvnw -o -Dtest=DocumentConverterFactoryTest,DocumentConverterStartupTest,uk.gegc.quizmaker.service.document.converter.impl.EpubDocumentConverterTest test
   ```

   Expected: the same selected tests pass without any remote request.

## Compatibility And Query Review

- Public API/OpenAPI: unchanged.
- Runtime converter implementations and Spring production wiring: unchanged.
- Database/Flyway: unchanged and not initialized by these tests.
- Security and permissions: unchanged.
- N+1: not applicable. The changed tests and tested converter paths perform no repository or database reads.
- Privacy: fixtures are synthetic and bounded; no document content hash is calculated.
