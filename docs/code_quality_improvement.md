# Code Quality & Maintainability Improvement Plan
_Repo scan date: 2025‑08‑10 (Europe/London)_

This plan focuses on **code quality & maintainability** (your Option 3). It’s organized as **Quick Wins (0–2 days)**, **Core Refactors (1–2 weeks)**, and **Structural/Tooling Upgrades (1–2 weeks)**, with concrete steps, file pointers, and example snippets.

---

## Snapshot of current state (what I scanned)
- Language/stack: **Java 17**, Spring Boot, JPA/Hibernate, Lombok, OpenAPI, JWT auth.
- Structure: classic layering (**controller → service → repository**), DTOs mostly as **`record`**s (great choice), global exception handler, validation annotations.
- Biggest hotspots by size:
  - `controller/QuizController.java` (**~833 LOC**) – many concerns in one class.
  - `service/quiz/impl/QuizServiceImpl.java` (**~693 LOC**).
  - `service/document/chunker/impl/UniversalChapterBasedChunker.java` (**~666 LOC**).
  - `service/attempt/impl/AttemptServiceImpl.java` (**~528 LOC**).
- Multiple classes instantiate their **own `ObjectMapper`** (10+ files), e.g. `mapper/QuestionMapper.java`, `service/ai/parser/*`, `service/question/handler/QuestionHandler.java`.
- Empty util: `util/JsonUtils.java` (dead code).

> Paths root: `/main/java/uk/gegc/quizmaker/**` and `/main/resources/**`

---

## Quick Wins (0–2 days)
These are low‑risk changes that reduce future bugs and friction.

1) **Centralize `ObjectMapper` usage**
   - **Why**: Multiple `new ObjectMapper()` means inconsistent config, duplicated modules, and harder testing.
   - **How**:
     - Create a single bean in `config` (if not already): `@Bean ObjectMapper mapper()` with Java Time, snake_case strategy (if needed), enums handling, unknown props strategy, etc.
     - **Replace** local statics/usages with constructor‑injected `ObjectMapper`.
   - **Where** (examples):
     - `mapper/QuestionMapper.java`
     - `service/ai/parser/*QuestionParser.java`
     - `service/ai/parser/impl/QuestionResponseParserImpl.java`
     - `service/question/handler/QuestionHandler.java`
     - `service/quiz/impl/QuizServiceImpl.java`

2) **Delete dead code / stubs**
   - Remove `util/JsonUtils.java` (empty). Keep the util package lean; dead stubs age badly.

3) **Controller split: start with `QuizController`**
   - **Why**: 833 LOC mixes read APIs, mutation, generation jobs, and document flow.
   - **How** (first pass, no logic change):
     - **`PublicQuizController`** – read/search endpoints (GET only) for public/anonymous access.
     - **`QuizManagementController`** – quiz CRUD, visibility/status updates (auth required).
     - **`QuizGenerationController`** – generation job lifecycle + document processing hooks (upload/validate/process).
   - **Benefit**: Smaller classes, clearer responsibilities, easier tests, fine‑grained security annotations.

4) **@ConfigurationProperties instead of scattered `@Value`**
   - **Why**: Centralized config types are easier to reason about & test.
   - **Where**:
     - `config/CorsConfig.java`, `config/AsyncConfig.java`, `config/DocumentProcessingConfig.java`, `security/JwtTokenProvider.java`.
   - **How**: Define `AppCorsProperties`, `AsyncProperties`, `DocumentProcessingProperties`, `JwtProperties`, bind with `@ConfigurationProperties(prefix="...")`, and inject the POJOs.

5) **Entity `toString` / logging safety**
   - Add Lombok `@ToString(exclude={...})` or avoid logging entities directly in services to prevent **lazy‑load** explosions and PII leaks.

6) **Pageable defaults & consistent sorting**
   - Ensure all list endpoints use `Pageable` + consistent `Sort` defaults (you already do this in many places). Document them in OpenAPI for DX.

---

## Core Refactors (1–2 weeks)
These pay back in readability and testability.

### A) Break down large services into cohesive units
1. **`QuizServiceImpl` (~693 LOC)**  
   - Extract smaller domain services:
     - `QuizQueryService` (read/search only – pure queries).
     - `QuizCommandService` (create/update/delete; transactional writes).
     - `QuizGenerationJobService` (already exists – keep job‑specific logic here).
   - Expose **interfaces** in `service/quiz/` and keep impls in `service/quiz/impl/`.
   - Add thin orchestration methods in controllers; push complex branching down.

2. **`AttemptServiceImpl` (~528 LOC)**  
   - Split by responsibility:
     - `AttemptLifecycleService` (`startAttempt`, `completeAttempt`, timing/status rules).
     - `AttemptProgressService` (`getCurrentQuestion`, navigation).
     - `AnswerSubmissionService` (validate content shape by `QuestionType`, persist user answers).
     - Keep `ScoringService` focused and stateless where possible.

3. **`UniversalChapterBasedChunker` (~666 LOC)**  
   - Apply **Strategy** pattern for split heuristics:
     - `ChapterDetectionStrategy` (regex/table‑of‑contents parsing).
     - `ChunkSizingStrategy` (mid‑split w/ ±5% tolerance; sentence boundary nudging).
     - `PostProcessor` (title generation, orphan handling, min/max constraints).
   - Wire in `DocumentProcessingConfig` to supply knob values (max chunk size, min chunk size, overlap).  
   - Write **unit tests** with synthetic docs to lock behavior before refactor.

**Acceptance criteria for A)**  
- No public API changes.  
- Max class length < 300 LOC for services/handlers.  
- 80%+ branch coverage on chunking and handler logic (targeted tests).

### B) Unify question parsing/validation
- Today you’ve got multiple parsers (`service/ai/parser/*`) and handlers (`service/question/handler/*`). That’s good separation by type, but there’s **duplication** around JSON traversal and shape checks.
- Introduce:
  - `QuestionJsonValidator` (per type) with shared helpers for “required field present”, “non‑blank”, “array of …”
  - A small `JsonCursor` helper to reduce repetitive `JsonNode` boilerplate.
- **Inject `ObjectMapper`** everywhere (see Quick Wins).
- Add **property‑based tests** or table‑driven tests per type (invalid vs. valid shapes).

### C) Replace manual mappers with **MapStruct** (incrementally)
- Pros: zero‑runtime reflection, compile‑time safety, no hand‑rolled boilerplate.
- Start with `QuestionMapper`, `QuizMapper`, `AttemptMapper`, `AnswerMapper`.  
- Pattern:
  - Create `@Mapper(componentModel="spring")` interfaces.
  - Keep custom mapping logic as `@AfterMapping` hooks or dedicated helpers.
- Do it piecemeal (file‑by‑file) to keep PRs small.

### D) Arch rules to **enforce boundaries**
- Add **ArchUnit** tests:
  - Controllers must not depend on `..impl..` packages.
  - Repositories only used by services, never by controllers.
  - `dto..` package must not depend on `model..` (and vice versa).
- This prevents accidental leakage as code grows.

---

## Structural & Tooling Upgrades (1–2 weeks)
Make quality **enforceable** in CI so it sticks.

1) **Formatting & style**
   - Add **Spotless** with Google Java Format:
     ```xml
     <!-- pom.xml -->
     <plugin>
       <groupId>com.diffplug.spotless</groupId>
       <artifactId>spotless-maven-plugin</artifactId>
       <version>2.43.0</version>
       <configuration>
         <java>
           <googleJavaFormat/>
         </java>
       </configuration>
       <executions>
         <execution>
           <goals><goal>apply</goal><goal>check</goal></goals>
         </execution>
       </executions>
     </plugin>
     ```
   - Add **Checkstyle** or **PMD** with a minimal rule set (focus on complexity thresholds and forbidden APIs).

2) **Static analysis**
   - **SpotBugs** + **FindSecBugs** (even though this is “quality,” these catch bug‑smells).
   - **Error Prone** + **NullAway** (if using Maven/Java 17 build) for nullness and common mistakes.
   - Set CI to **fail on new issues**; allow baseline suppression file for existing ones.

3) **Test discipline**
   - Add **mutation testing** (Pitest) for the high‑risk modules (`handlers`, `chunker`, `mappers`). Even limited runs raise confidence.
   - Ensure **Testcontainers** cover integration tests with MySQL; prefer lightweight **service tests** with H2 for logic that doesn’t rely on MySQL JSON features.

4) **API error shape (Problem+JSON)** – optional but recommended
   - Consider `application/problem+json` (RFC 7807) using a small wrapper or `problem-spring-web`. Your current `GlobalExceptionHandler` is solid; this would standardize the contract for clients.

5) **Logging improvements**
   - Adopt **MDC correlation id** (`X-Request-ID`) and add it in responses for cross‑service traces later.
   - Keep logs at **INFO** in production; the current `application.properties` sets a lot of DEBUG/TRACE (great for dev, noisy for prod). Move these to `application-dev.properties`.

6) **Configuration hygiene**
   - Move local DB creds from `application.properties` into `application-dev.properties` (the prod default should not point to localhost creds).
   - Don’t keep `secret.properties` in resources with a JWT secret. Prefer env vars or a vault. (Quality overlap with security, but it reduces accidental misuse and config drift.)

---

## File‑level Notes (actionable)
- `controller/QuizController.java` (**833 LOC**): split by concern (see Quick Wins). While splitting, also move **OpenAPI annotations** to each new controller so docs remain intact.
- `service/quiz/impl/QuizServiceImpl.java` (**693 LOC**): extract read/write services; keep transactions on command side. Add package‑private helpers for small rules (title normalization, tag upserts).
- `service/attempt/impl/AttemptServiceImpl.java` (**528 LOC**): separate navigation vs submission vs lifecycle. Add explicit guards for **status transitions** (IN_PROGRESS → COMPLETED) in one place.
- `service/document/chunker/impl/UniversalChapterBasedChunker.java` (**666 LOC**): apply Strategy split; add tests around “no chapters” vs “chapters too big” vs “ideal sized” docs; surface tunables via properties.
- `service/ai/parser/*`: inject `ObjectMapper`; introduce shared validation helpers to reduce duplication.
- `mapper/*`: convert to MapStruct over time (start with `QuestionMapper`, `QuizMapper`). Until then, centralize all Jackson usage via injected `ObjectMapper`.
- `validation/*`: already good; keep messages in `ValidationMessages.properties`. Consider **validation groups** for create vs update flows.
- `config/*`: switch `@Value` to `@ConfigurationProperties`; reduce `application.properties` verbosity; add `@Validated` on properties classes.

---

## Step‑by‑Step Implementation Plan
Use small PRs (≤300 LOC diff where possible).

### Phase 1: Foundations (Day 1–2)
1. **Add Spotless + Checkstyle/PMD** to `pom.xml`. Commit the initial reformat.
2. Create `ObjectMapperConfig` with a single, shared bean. Replace local `new ObjectMapper()` usages in:
   - `mapper/QuestionMapper.java`
   - `service/ai/parser/*QuestionParser.java`
   - `service/ai/parser/impl/QuestionResponseParserImpl.java`
   - `service/question/handler/QuestionHandler.java`
   - `service/quiz/impl/QuizServiceImpl.java`
3. Remove `util/JsonUtils.java`.
4. Create `CorsProperties`, `AsyncProperties`, `DocumentProcessingProperties`, `JwtProperties` and refactor configs to use them.

### Phase 2: Controller & Service decomposition (Week 1)
5. Split `QuizController` into:
   - `PublicQuizController` (GET/search/public reads)
   - `QuizManagementController` (CRUD, status/visibility – auth)
   - `QuizGenerationController` (generation jobs + document endpoints)
   Preserve routes to avoid breaking clients.
6. Extract **`QuizQueryService`** and **`QuizCommandService`** from `QuizServiceImpl`. Keep small, cohesive methods.

### Phase 3: Parsers/Handlers cleanup (Week 1–2)
7. Create `QuestionJsonValidator` helpers (shared glue for `JsonNode` checks).
8. Refactor each `*QuestionParser` to use validators + injected `ObjectMapper`.
9. In handlers, factor out common grading/normalization helpers (e.g., trimming, case sensitivity flags) to reduce duplication.

### Phase 4: Chunker modularization (Week 2)
10. Introduce `ChapterDetectionStrategy`, `ChunkSizingStrategy`, `ChunkPostProcessor`. Move heuristics out of `UniversalChapterBasedChunker`.
11. Add focused tests for tricky docs (no chapters, long chapters, headings noise, mixed numbering). Target 80%+ branch coverage here.

### Phase 5: Architectural guardrails & static analysis (Week 2)
12. Add **ArchUnit** tests for boundaries (controllers ↔ services ↔ repositories; dto/model segregation).
13. Add SpotBugs/FindSecBugs and (optionally) Error Prone + NullAway. Configure CI to fail on **new** violations only.

### Phase 6: Mapping modernization (Ongoing, low‑risk)
14. Introduce **MapStruct** for `QuestionMapper` and `QuizMapper` first. Keep manual mappers beside them temporarily; delete when fully migrated.
15. Add unit tests around mapper behavior (IDs, nested collections, enums).

---

## Example snippets

### Shared `ObjectMapper` bean
```java
// config/ObjectMapperConfig.java
@Configuration
public class ObjectMapperConfig {
  @Bean
  public ObjectMapper objectMapper() {
    return Jackson2ObjectMapperBuilder.json()
        .findModulesViaServiceLoader(true) // Java time, JDK8 types, etc.
        .featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }
}
```
Then inject:
```java
@RequiredArgsConstructor
@Component
class McqQuestionParser {
  private final ObjectMapper mapper;
}
```

### `@ConfigurationProperties` example
```java
// config/properties/JwtProperties.java
@ConfigurationProperties(prefix = "app.jwt")
@Validated
public class JwtProperties {
  @NotBlank String secret;
  @DurationMin(seconds = 300) Duration accessTtl;
  @DurationMin(seconds = 3600) Duration refreshTtl;
  // getters/setters or record + @ConstructorBinding
}
```
Enable in `@EnableConfigurationProperties(JwtProperties.class)`.

### ArchUnit guardrails
```java
// test/java/.../ArchitectureTest.java
@AnalyzeClasses(packages = "uk.gegc.quizmaker")
public class ArchitectureTest {
  @Test void controllers_should_not_depend_on_impl() {
    Architectures.layeredArchitecture()
      .layer("Controllers").definedBy("..controller..")
      .layer("Services").definedBy("..service..")
      .layer("ServiceImpl").definedBy("..service..impl..")
      .layer("Repositories").definedBy("..repository..")
      .whereLayer("Controllers").mayOnlyAccessLayers("Services")
      .whereLayer("Services").mayOnlyAccessLayers("Repositories", "ServiceImpl")
      .whereLayer("Controllers").should().notDependOnClassesThat().resideInAnyPackage("..impl..");
  }
}
```

### MapStruct mapper
```java
@Mapper(componentModel = "spring")
public interface QuizMapper {
  QuizDto toDto(Quiz entity);
  @Mapping(target = "id", ignore = true)
  Quiz toEntity(CreateQuizRequest req, @Context User creator, @Context Category category);
  // Use @AfterMapping for tag resolution etc.
}
```

---

## Definition of Done (for this workstream)
- Max class length under 300 LOC for services/controllers (except DTOs/records).
- No `new ObjectMapper()` in code; single bean usage.
- Controllers split by concern; OpenAPI still accurate.
- ArchUnit tests pass; boundary violations fail CI.
- Spotless + Checkstyle/PMD + SpotBugs enabled; CI fails on new issues.
- Chunking and handlers covered by focused unit tests.
- MapStruct introduced for at least 2 core mappers.

---

## Nice‑to‑Have (later)
- Feature‑based packaging (by bounded context) if the codebase keeps growing.
- Adopt **Problem+JSON** error format.
- Gradually adopt **records** for more simple internal DTOs/configs where appropriate.
- Consider **sealed interfaces** for question types/handlers in Java 17 to encode exhaustiveness.

---

## Notes
This plan avoids API behavior changes. Each step is designed to be incremental and PR‑friendly. I can help produce PR‑ready diffs for each phase on request.
