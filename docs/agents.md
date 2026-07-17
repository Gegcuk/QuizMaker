# QuizMaker Development Guide (agents.md) — Code Rules Only

**Target stack:** Java 17, Spring Boot 3.x, Spring MVC, Spring Data JPA, Spring Security, Jackson, Jakarta Bean Validation, MapStruct.

**Layout:** feature-first

```
features/
  <feature>/
    api/          # controllers (web layer)
    application/  # services/use-cases, transactions, caching, security
    domain/       # entities, value objects, domain rules
    infra/        # repositories (Spring Data), integrations, mappers
shared/
  exception/ validation/ security/ util/ email/ rate_limit/
```

## 0) North Star (house style)

- **Thin controllers, business in services, lean repositories.** Controllers do I/O + validation; services own transactions and domain rules; repositories are query-centric.

- **Never expose entities in the API.** Map to DTOs (prefer record DTOs). Use MapStruct (`@Mapper(componentModel="spring")`).

- **Uniform error bodies:** use Spring's ProblemDetail (RFC 9457). Add one `@RestControllerAdvice` to convert exceptions. No stack traces to clients.

- **Validate everything at the edge:** Jakarta Bean Validation on request DTOs (`@Valid`), plus method validation on services (`@Validated`).

- **JPA discipline:** default LAZY; avoid N+1 with JOIN FETCH or `@EntityGraph`; page large reads; prefer projections for list views.

- **Concurrency:** add `@Version` for optimistic locking where concurrent writes happen.

- **Method security:** guard sensitive use-cases with `@PreAuthorize` on service methods. Keep expressions readable.

- **Logging:** use parameterized SLF4J; avoid credentials/PII (follow OWASP logging guidance).

- **Don't rely on OSIV.** Fetch what you need within transactions; OSIV only keeps the EntityManager open for view rendering and encourages leaky access patterns.

- **Optional only as return type.** Don't use Optional in fields/params (per JDK guidance).

## 1) Controllers (features/<x>/api)

### Rules

- Each endpoint = parse → `@Valid` → delegate → map → return.
- Correct HTTP semantics and codes (201 for create, 204 for delete, etc.).
- Prefer `Page<T>` / `Slice<T>` for lists; don't return large Lists. Use Slice when you don't need counts.
- Map exceptions to ProblemDetail in a single advice.

### Template

```java
@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
class QuizController {
  private final QuizService service;

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  QuizDto create(@Valid @RequestBody CreateQuizRequest req) {
    return service.create(req);
  }

  @GetMapping("/{id}")
  QuizDto get(@PathVariable UUID id) { return service.get(id); }

  @GetMapping
  Page<QuizSummaryDto> list(@ParameterObject Pageable pageable) {
    return service.list(pageable);
  }
}
```

### Global error handling

```java
@RestControllerAdvice
class ApiExceptionHandler {
  @ExceptionHandler(EntityNotFoundException.class)
  ResponseEntity<ProblemDetail> notFound(EntityNotFoundException ex, HttpServletRequest r) {
    var pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
    pd.setTitle("Resource not found");
    pd.setDetail(ex.getMessage());
    pd.setInstance(URI.create(r.getRequestURI()));
    pd.setType(URI.create("https://example.com/problems/not-found"));
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(pd);
  }
}
```

Spring Framework 6+ ships ProblemDetail aligned to RFC 9457.

### Streaming (long operations)

For long-running exports or AI streams, return `ResponseBodyEmitter` or `SseEmitter`. Keep controller method fast; push work to a separate thread/executor.

## 2) DTOs & Mapping (features/<x>/infra for mappers)

### Rules

- Use Java record for DTOs and commands; annotate constructor components with constraints.
- MapStruct for compile-time, type-safe, fast mappers.

```java
@Mapper(componentModel = "spring")
public interface QuizMapper {
  QuizDto toDto(Quiz e);
  @Mapping(target = "id", ignore = true)
  Quiz toEntity(CreateQuizRequest r);
}
```

### Validation

```java
public record CreateQuizRequest(
    @NotBlank String title,
    @Size(max = 500) String description
) {}
```

Use `@Validated` on service classes to enforce method-level constraints.

## 3) Services (features/<x>/application)

### Rules

- Define a use-case-oriented service interface in `application` and place the Spring implementation in `application/impl`.
- Controllers, schedulers, listeners, and other features depend on the interface, never the implementation class.
- Business rules live here. Place `@Transactional` on methods (readOnly for queries).
- Method security belongs here (`@PreAuthorize`) for sensitive operations.
- Publish application/domain events for cross-feature reactions; keep payloads small and immutable.
- Use constructor injection. Hide external providers behind project-owned ports/interfaces and test them with fakes or stubs.

### Template

```java
public interface QuizService {
  QuizDto create(CreateQuizRequest req);
  QuizDto get(UUID id);
}

@Service
@Validated
@RequiredArgsConstructor
class QuizServiceImpl implements QuizService {
  private final QuizRepository repo;
  private final QuizMapper mapper;
  private final ApplicationEventPublisher events;

  @Override
  @Transactional
  @PreAuthorize("hasAuthority('QUIZ_CREATE')")
  public QuizDto create(@Valid CreateQuizRequest req) {
    var saved = repo.save(mapper.toEntity(req));
    events.publishEvent(new QuizCreated(saved.getId()));
    return mapper.toDto(saved);
  }

  @Override
  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('QUIZ_READ')")
  public QuizDto get(UUID id) {
    var e = repo.findById(id)
      .orElseThrow(() -> new EntityNotFoundException("Quiz %s".formatted(id)));
    return mapper.toDto(e);
  }
}
```

### Caching (optional)

Use `@Cacheable`/`@CacheEvict` at service boundary for read-mostly use-cases.

## 4) Repositories (features/<x>/infra)

### Rules

- Keep them declarative. Derived methods for simple predicates; `@Query` for complex ones.
- Use projections for list/summary views (interface or class-based).
- Kill N+1: use JOIN FETCH or `@EntityGraph` when reading associations needed by the view.
- Use Page/Slice return types for pagination.

### Example

```java
public interface QuizRepository extends JpaRepository<Quiz, UUID> {

  Optional<Quiz> findByTitle(String title);

  // Avoid N+1 when we need categories with the quiz
  @EntityGraph(attributePaths = {"categories"})
  @Query("select q from Quiz q where q.createdAt >= :since")
  Page<Quiz> findRecentWithCategories(@Param("since") Instant since, Pageable page);

  // Projection for list view
  @Query("""
     select q.id as id, q.title as title, q.updatedAt as updatedAt
     from Quiz q
  """)
  Page<QuizSummaryDto> findAllSummaries(Pageable page);
}
```

## 5) Entities & Domain (features/<x>/domain)

### Rules

- LAZY associations by default; eagerly fetch only tiny, always-needed relations.
- Optimistic locking with `@Version` on aggregates that are edited concurrently.
- Prefer value objects (`@Embeddable`) for concepts like Money, Score, Address.

### Example

```java
@Entity
@Table(name="quizzes")
@Getter @Setter
public class Quiz {
  @Id @GeneratedValue private UUID id;
  @Column(nullable=false, unique=true) private String title;
  @Column(length=500) private String description;
  @Version private long version;

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(name="quiz_categories")
  private Set<Category> categories = new HashSet<>();
}
```

## 6) AI Integration (code-level)

### Service skeleton

```java
@Service
@RequiredArgsConstructor
public class AiQuizGenerationService {
  private final ChatClient chatClient;               // Spring AI interface
  private final QuestionParserFactory parserFactory; // polymorphic parsers
  private final RateLimitService rateLimit;          // shared/rate_limit
  private final Executor aiExecutor;                 // async pool

  @Async("aiTaskExecutor")
  @Transactional
  public void generateFromDocumentAsync(QuizGenerationJob job, GenerateQuizFromDocumentRequest req) {
    // 1) pre-checks (size, type, user quota)
    // 2) chunk selection
    // 3) prompt assembly (include system + few-shot + constraints)
    // 4) call model with retry + backoff
    // 5) parse via parserFactory by type, collect errors
    // 6) persist questions atomically; emit progress events
  }
}
```

### Question generation

Support the 9 types you listed (MCQ_SINGLE, MCQ_MULTI, OPEN, FILL_GAP, COMPLIANCE, TRUE_FALSE, ORDERING, HOTSPOT, MATCHING).

Each parser: pure function `String → List<Question>` with strict validation and idempotent behavior (same prompt → same result).

Fallback strategy: downgrade to simpler types when parsing fails (e.g., MATCHING → MCQ_MULTI), but record the downgrade.

### Rate limiting & retries

Enforce per-user quotas in service; on 429/5xx apply exponential backoff and jitter; surface a ProblemDetail with status=503 for exhaustion.

### Streaming responses

Long jobs: push progress via SseEmitter channel (`/api/v1/ai/jobs/{id}/events`).

## 7) Document Processing (code-level)

### Upload endpoint

```java
@PostMapping("/upload")
@ResponseStatus(HttpStatus.CREATED)
public DocumentDto upload(@RequestParam("file") MultipartFile file,
                          @Valid @ModelAttribute ProcessDocumentRequest req) {
  return documentService.processDocument(file, req);
}
```

### Chunking strategies

- **CHAPTER_BASED:** split on headings (`(?m)^(chapter|section|part)\b\s+\d+[:.)-]?\s+.*$` case-insensitive), then recursively bisect oversize blocks near sentence boundaries.
- **SECTION_BASED:** same but on `^#{1,3}\s+`/numeric headings if markdown-like.
- **SIZE_BASED:** walk forward using `BreakIterator.getSentenceInstance(Locale)` to keep ≤ max chars (hard cap); never cut in the middle of a token.
- **PAGE_BASED:** keep page numbers as metadata for traceability.

### Safety & sanitation

Strip dangerous HTML with JSoup; validate MIME via Tika before parsing. (Implementation remains local to code; no config guidance here.)

## 8) Security in Code

Use method security (`@PreAuthorize`) at service boundaries with existing `PermissionName` authority values such as `QUIZ_CREATE` and `QUIZ_READ`, or the feature's established `@RequirePermission` convention at the endpoint boundary.

Roles group permissions; permissions authorize actions. A permission check does not replace ownership, visibility, organization membership, or tenant-boundary checks. Resolve all of those from the authenticated principal and default to deny when context is missing.

Add negative tests for unauthenticated callers, insufficient permissions, wrong owners, wrong organizations, and private resources whenever those cases apply.

For JWT parsing/signing, encapsulate token logic behind one component; validate audiences/expiry; never log tokens or secrets (OWASP logging).

### Authentication And Recovery Flows

- Resolve client IP addresses through the trusted-proxy utility; never trust a forwarding header directly.
- Rate-limit keys must resist attacker-controlled rotation while avoiding unnecessary blocking of unrelated users. Consider both the operation and a stable subject such as IP, account, or email.
- Password resets, password changes, email verification, refresh, and logout must have explicit token/session invalidation semantics. Do not document a token as revoked unless the backend enforces it.
- Use the same injected `Clock` for token creation, validation, expiry cleanup, and tests.
- For rate-limit responses, document and test the RFC 7807 body and retry guidance such as `Retry-After` when supplied by the rate-limit implementation.

## 9) Validation (DTO & method)

Request DTOs: annotate with Jakarta constraints (`@NotBlank`, `@Size`, `@Email`, etc.).

Service invariants: annotate parameters/returns; enable class-level `@Validated`.

### Custom constraint example

```java
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = OrderNumberValidator.class)
public @interface OrderNumber {
  String message() default "invalid order number";
  Class<?>[] groups() default {};
  Class<? extends Payload>[] payload() default {};
}

class OrderNumberValidator implements ConstraintValidator<OrderNumber, String> {
  public boolean isValid(String v, ConstraintValidatorContext ctx) {
    return v != null && v.matches("[A-Z]{3}-\\d{6}");
  }
}
```

## 10) Pagination, Projections & Streaming

Use projections for list endpoints to reduce columns and JSON size.

Choose Slice when counts are unnecessary (cheaper than Page).

Stream long responses via ResponseBodyEmitter/SseEmitter.

Before merging a new list or aggregate query, inspect SQL or write a repository test for N+1 behaviour, correct indexes, bounded result size, stable ordering, and the query plan where the data volume warrants it. Cache only read-mostly values with a clear invalidation rule.

## 11) Logging (code snippets)

Use correlation IDs if present; never log secrets, tokens, or PII; if you must correlate on a session, log a salted hash only.

```java
@Slf4j
@Service
class QuizAuditService {
  void created(UUID quizId) {
    log.info("quiz.created id={}", quizId);
  }
}
```

## 12) Tests (what to write and how to write)

- Test types and when to use them
  - Controllers: `@WebMvcTest` + MockMvc for status/body/validation paths; slice the MVC layer, mock services.
  - Services: plain JUnit 5 + Mockito (no Spring context unless needed); verify business rules and edge cases.
  - Repositories: `@DataJpaTest` for queries/projections; assert `@EntityGraph`/JOIN FETCH remove N+1; prefer projections for lists.
  - Integration: extend `BaseIntegrationTest` for full‑stack scenarios that cross layers (security, transactions, serialization).
  - Contract/DTO serialization: Jackson `ObjectMapper` round‑trip tests for request/response DTOs when APIs are stable.

- Display names and structure
  - Use `@DisplayName` with the pattern: `methodOrRoute: when <condition> then <expectation>`.
    - Examples:
      - `createQuiz: when title missing then 400 Bad Request`
      - `GET /api/v1/billing/balance: when authorized then 200`
      - `findByEmailWithRoles: when unknown email then empty`
  - Within tests, annotate sections with `// Given`, `// When`, `// Then` comments for readability.
  - Prefer parameterized tests for matrix‑like inputs (`@ParameterizedTest` with clear `@DisplayName` templates).

- Base test classes
  - Use `BaseIntegrationTest` (extends `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, transactional rollback) for integration tests.
  - For MVC slices, consider a `BaseMvcTest` (if/when introduced) to centralize common setup (global exception handler, Jackson config).
  - For JPA slices, consider a `BaseJpaTest` (if/when introduced) to standardize clock/profile and common utilities.

- Clock and time in tests
  - Prefer `Clock` over `now()` in production code; in tests, activate the `test` profile to use `TestClockConfig`’s fixed clock.
  - Avoid `Thread.sleep` and time‑sensitive assertions; inject `Clock` and compute deterministically.
  - For time comparisons, allow truncation where appropriate (e.g., compare `Instant` or truncate nanos).

- Test data and fixtures
  - Use builders/factories for entities/DTOs; keep them in `src/test/java/.../util` or dedicated factory classes.
  - Keep fixtures minimal; prefer in‑test construction over large shared fixtures to avoid coupling.
  - Seed DB only when necessary (e.g., `@Sql`) and scope seeds to a test class; otherwise build data via repositories.

- Consistency checklist
  - Class names: `XxxServiceTest`, `XxxRepositoryTest`, `XxxControllerTest` (slice), `XxxIntegrationTest` (full context).
  - One assertion subject per test; multiple assertions allowed when tied to the same behavior.
  - Use AssertJ for fluent assertions; avoid hamcrest/junit mix.
  - Verify negative paths (validation, authorization) alongside happy paths.
  - Keep tests independent and order‑agnostic; no reliance on execution order.
  - Assert business and API behavior rather than implementation details.
  - Do not mock impossible collaborator output simply to reach a defensive branch.
  - Use fakes/stubs for AI, billing, email, storage, transcription, and other external systems; never call real providers from automated tests.

## 13) OpenAPI And Swagger Contracts

- Discover existing contracts from `/api/v1/api-summary` and the relevant `/v3/api-docs/<group>` document. For question creation or AI generation, treat `/api/v1/questions/schemas/{type}` as the content-shape source of truth.
- Put each public endpoint in exactly one logical `GroupedOpenApi` group in `shared/config/OpenApiGroupConfig`.
- Keep every group discoverable through `GET /api/v1/api-summary`.
- Publish named request and response DTOs. Avoid generic `object`, raw `Page`, untyped maps, and ambiguous array/wrapper alternatives.
- Document authentication, permissions, ownership/visibility, validation, pagination, filters, sorting, units, enum values, nullability, idempotency, and partial failures where relevant.
- Document RFC 7807 `ProblemDetail` for expected errors and provide representative examples that validate against the schema.
- Add contract tests for group membership, typed schemas, examples, and backward compatibility.

## 14) Per-Layer Checklists (agent must self-verify)

### Controller

- [ ] DTOs with `@Valid`; no entities returned.
- [ ] Proper HTTP codes; pagination uses Page or Slice.
- [ ] Errors are ProblemDetail.

### Service

- [ ] Public use cases are defined by an application-service interface; callers do not depend on `*ServiceImpl`.
- [ ] Business rules only; `@Transactional` granularity correct.
- [ ] `@PreAuthorize` on sensitive operations.
- [ ] Events published for cross-feature actions where coupling would be high.

### Repository

- [ ] No business logic; queries clear and covered by tests.
- [ ] N+1 removed (JOIN FETCH/`@EntityGraph`).
- [ ] Projections for list endpoints.

### Entities

- [ ] LAZY associations by default; helper methods maintain both sides.
- [ ] `@Version` on aggregates with concurrent edits.
- [ ] No Lombok `@Data` on entities; define equality explicitly.

### Cross-cutting

- [ ] No PII in logs; parameterized logging.
- [ ] No Optional in fields/params (return types only).
- [ ] Code does not rely on OSIV behavior.
- [ ] OpenAPI group, typed schemas, valid examples, and ProblemDetail responses are accurate.
- [ ] Authorization includes applicable permission, ownership, visibility, and organization checks.

## 15) Time & Clock (consistency rules)

- Always inject and use `java.time.Clock` in services/components that read the current time.
  - Do not call `LocalDateTime.now()`, `Instant.now()`, etc. directly in business code.
  - Prefer `Instant.now(clock)` for persistence and domain timestamps; convert to `LocalDateTime` with explicit zone when needed.
- Configuration
  - Primary app clock defined in `shared/config/ClockConfig` with `app.timezone` (default UTC).
  - Additional beans: `utcClock`, `systemClock` for explicit needs (use sparingly; default to primary clock).
  - Tests run under `@ActiveProfiles("test")` which provide a fixed `Clock` via `TestClockConfig`.
- Scheduling & TTL
  - Use `Clock` for calculating TTLs/expirations; avoid embedding durations in `now()` calls.
  - When calculating windows/cutoffs, prefer `Instant` math and convert at the boundary.

## 16) Consistency & Best Practices (project‑wide)

- Naming & packaging
  - Feature‑first package layout (`features/<feature>/{api,application,domain,infra}`) and `shared/*` for cross‑cutting.
  - DTOs are Java records in `api/dto` with Jakarta validation on components.
  - MapStruct mappers live under `infra/mapping` and should be referenced via interfaces (not static helpers).

- API & errors
  - Expose ProblemDetail responses only; map domain exceptions in a single advice per feature area.
  - Avoid leaking entity internals in API; map to DTOs and keep field names stable.

- Security
  - Method security at service layer (`@PreAuthorize`) for sensitive operations; keep expressions simple and test them.

- Persistence
  - LAZY by default; annotate aggregates with `@Version` when concurrency is possible.
  - For list endpoints, prefer projections over full entities and page/slice consistently.

- Logging
  - Parameterized logging, correlation IDs if available; never log secrets/tokens/PII.

- Reviews & CI
  - Enforce these consistency rules in PR reviews; prefer small, focused changes with tests.

- SOLID, KISS, and patterns
  - Keep responsibilities cohesive and dependencies directed toward interfaces at real boundaries.
  - Use Strategy, Factory, Adapter, events, and similar patterns only when they model actual variation or reduce meaningful coupling.
  - Do not add speculative abstractions, generic base services, or pattern-heavy code where a direct implementation is clearer.

## 17) Feature-Specific Guidance

### A) Document Processing

Keep extractors and chunkers pure (side-effect free); unit test with golden files.

Persist chunk metadata (page, heading path, offsets).

Validate file type before parse; fail fast with ProblemDetail(status=415) for unsupported media.

### B) Quiz Generation (AI)

Build prompts from: system rules → content excerpt → output schema.

Parser per type with strict JSON schema; on parse failure, attempt repair once, then fallback type and record downgrade.

Expose progress over SSE.

### C) Attempts & Scoring

Model scoring in domain with deterministic, reversible rules; no I/O in domain code.

Expose summaries via projections for tables; page aggressively.

## 18) Git And Delivery Safety

- AI agents work locally only.
- Never run `git push`, create or merge pull requests, publish releases, or trigger deployments.
- Create a local commit only when explicitly requested by the repository owner.
- Never include unrelated working-tree changes in a commit.

## 19) Issue Readiness

- Follow `docs/github-issue-guide.md` for issue structure and labels.
- Check `docs/open-issue-roadmap.md` before starting implementation.
- Prefer a vertical, testable outcome over separate controller/service/repository tickets.

## 20) Anti-Patterns (hard NOs)

- Field injection; use constructor injection.
- Business logic in controllers or repositories.
- Returning entities from controllers / exposing lazy relations to Jackson.
- Relying on OSIV to "make LazyInitializationException go away".
- Using Optional in fields/parameters.
