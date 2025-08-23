# QuizMaker Development Guide (agents.md) — Code Rules Only

**Target stack:** Java 17+/21, Spring Boot 3.x, Spring MVC, Spring Data JPA, Spring Security, Jackson, Jakarta Bean Validation, MapStruct.

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

- Business rules live here. Place `@Transactional` on methods (readOnly for queries).
- Method security belongs here (`@PreAuthorize`) for sensitive operations.
- Publish application/domain events for cross-feature reactions; keep payloads small and immutable.

### Template

```java
@Service
@Validated
@RequiredArgsConstructor
class QuizService {
  private final QuizRepository repo;
  private final QuizMapper mapper;
  private final ApplicationEventPublisher events;

  @Transactional
  @PreAuthorize("hasAuthority('quiz:write')")
  QuizDto create(@Valid CreateQuizRequest req) {
    var saved = repo.save(mapper.toEntity(req));
    events.publishEvent(new QuizCreated(saved.getId()));
    return mapper.toDto(saved);
  }

  @Transactional(readOnly = true)
  @PreAuthorize("hasAuthority('quiz:read')")
  QuizDto get(UUID id) {
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

Use method security (`@PreAuthorize`) at service boundary with authorities like `quiz:write`, `quiz:read`.

For JWT parsing/signing, encapsulate token logic behind one component; validate audiences/expiry; never log tokens or secrets (OWASP logging).

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

## 12) Tests (what to write with the code)

- **Controllers:** `@WebMvcTest` + MockMvc for status/body/validation paths.
- **Services:** plain JUnit + Mockito (no Spring context unless needed).
- **Repositories:** `@DataJpaTest` to assert queries, projections, and `@EntityGraph` eliminates N+1. (Keep these as close to the code as possible.)

## 13) Per-Layer Checklists (agent must self-verify)

### Controller

- [ ] DTOs with `@Valid`; no entities returned.
- [ ] Proper HTTP codes; pagination uses Page or Slice.
- [ ] Errors are ProblemDetail.

### Service

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

## 14) Feature-Specific Guidance

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

## 15) Anti-Patterns (hard NOs)

- Field injection; use constructor injection.
- Business logic in controllers or repositories.
- Returning entities from controllers / exposing lazy relations to Jackson.
- Relying on OSIV to "make LazyInitializationException go away".
- Using Optional in fields/parameters.