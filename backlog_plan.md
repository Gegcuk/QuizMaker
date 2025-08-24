# QuizMaker Backlog Implementation Plan

## Overview
This plan addresses critical security, reliability, and maintainability issues identified in the codebase review. Items are prioritized by launch readiness and business impact.

---

## P0 — Before Launch (Blockers / Risk-killers)

### 1. Enforce Method-Level Security at Service Layer
**Priority:** Critical  
**Effort:** 2-3 days  
**Risk:** High (security bypass potential)

**Why:** Controller-only checks can be bypassed by internal calls. Service-level `@PreAuthorize` is the safest "last gate".

**Current State:** 
- `@PreAuthorize` only on controllers (found in QuizController, DocumentController, etc.)
- No service-layer security enforcement
- Internal service calls bypass controller security

**Implementation:**
```java
// Add to all service methods that mutate or expose sensitive data
@Service
@Validated
@RequiredArgsConstructor
public class QuizService {
  @PreAuthorize("hasAuthority('quiz:write')")
  @Transactional
  public QuizDto publish(@NotNull UUID quizId) { ... }
  
  @PreAuthorize("hasAuthority('quiz:read')")
  @Transactional(readOnly = true)
  public QuizDto get(UUID id) { ... }
}
```

**Target Services:**
- `QuizServiceImpl` - all mutation methods
- `DocumentProcessingServiceImpl` - upload/processing methods  
- `QuestionServiceImpl` - CRUD operations
- `AiQuizGenerationService` - generation methods
- `UserServiceImpl` - profile updates
- `ShareLinkServiceImpl` - token operations

**Acceptance Criteria:**
- [ ] Calling a secured service method from a test without auth fails with 403
- [ ] Controller + non-controller paths both blocked
- [ ] All sensitive operations require appropriate authorities
- [ ] Integration tests verify security enforcement

---

### 2. Harden File Uploads (Content-Sniffing, Size Caps, Sanitization)
**Priority:** Critical  
**Effort:** 1-2 days  
**Risk:** High (security exposure)

**Why:** Header/extension checks can be spoofed → parsing errors or security exposure.

**Current State:**
- Basic file size limits in `application.properties`
- No content-type validation beyond extension
- No HTML sanitization before AI prompts

**Implementation:**
```java
// Add Apache Tika dependency
implementation 'org.apache.tika:tika-core:2.9.1'
implementation 'org.apache.tika:tika-parsers-standard-package:2.9.1'

// File validation service
@Service
public class FileValidationService {
  private final Tika tika = new Tika();
  private final Set<String> allowedMimeTypes = Set.of(
    "application/pdf", "text/plain", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  );
  
  public void validateFile(MultipartFile file) {
    // Magic byte detection
    String detectedType = tika.detect(file.getInputStream());
    if (!allowedMimeTypes.contains(detectedType)) {
      throw new UnsupportedFileTypeException("Detected type: " + detectedType);
    }
    
    // Size validation
    if (file.getSize() > maxFileSize) {
      throw new FileTooLargeException(maxFileSize);
    }
  }
  
  public String sanitizeHtml(String content) {
    return Jsoup.clean(content, Whitelist.basic());
  }
}
```

**Acceptance Criteria:**
- [ ] Spoofed ".pdf" with wrong magic bytes rejected
- [ ] Oversize files return 413 with clear message
- [ ] HTML cleaned (no `<script>` survives sanitization)
- [ ] All supported file types properly validated

---

### 3. Per-User AI Quotas & Subscription Guard
**Priority:** Critical  
**Effort:** 2-3 days  
**Risk:** High (cost control)

**Why:** Prevent abuse/cost spikes; enforce plan entitlements.

**Current State:**
- Basic `RateLimitService` exists but not integrated with AI operations
- No per-user quota tracking
- No subscription tier enforcement

**Implementation:**
```java
// Enhanced rate limiting with user quotas
@Service
public class AiQuotaService {
  private final RateLimitService rateLimit;
  private final UserRepository userRepository;
  
  public void checkUserQuota(String username, String operation) {
    User user = userRepository.findByUsername(username)
      .orElseThrow(() -> new UserNotFoundException(username));
      
    // Check subscription tier limits
    int dailyLimit = getDailyLimitForTier(user.getSubscriptionTier());
    int monthlyLimit = getMonthlyLimitForTier(user.getSubscriptionTier());
    
    // Check current usage
    if (exceedsDailyLimit(username, dailyLimit)) {
      throw new QuotaExceededException("Daily AI quota exceeded", "DAILY_QUOTA_EXCEEDED");
    }
    
    if (exceedsMonthlyLimit(username, monthlyLimit)) {
      throw new QuotaExceededException("Monthly AI quota exceeded", "MONTHLY_QUOTA_EXCEEDED");
    }
    
    // Apply rate limiting
    rateLimit.checkRateLimit(operation, username, getRateLimitForTier(user.getSubscriptionTier()));
  }
}

// Add to AI service
@PreAuthorize("isAuthenticated()")
public void generateQuiz(GenerateQuizRequest request) {
  String username = SecurityContextHolder.getContext().getAuthentication().getName();
  aiQuotaService.checkUserQuota(username, "quiz_generation");
  // ... rest of generation logic
}
```

**Acceptance Criteria:**
- [ ] Free user blocked after quota with clear message
- [ ] Premium user proceeds normally
- [ ] HTTP 429 (or 403) returned with friendly message
- [ ] Quota usage tracked and persisted

---

### 4. Set Strict Timeouts + Resilience Around AI Calls
**Priority:** Critical  
**Effort:** 1-2 days  
**Risk:** Medium (thread exhaustion)

**Why:** External model latency can exhaust threads; retries need bounds.

**Current State:**
- No explicit timeouts on AI client calls
- No circuit breaker pattern
- No retry strategy with backoff

**Implementation:**
```java
// Add Resilience4j dependencies
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.1.0'
implementation 'io.github.resilience4j:resilience4j-circuitbreaker:2.1.0'
implementation 'io.github.resilience4j:resilience4j-retry:2.1.0'

// Configure in application.properties
resilience4j.circuitbreaker.instances.ai-service:
  sliding-window-size: 10
  failure-rate-threshold: 50
  wait-duration-in-open-state: 30s
  permitted-number-of-calls-in-half-open-state: 3

resilience4j.retry.instances.ai-service:
  max-attempts: 3
  wait-duration: 1s
  enable-exponential-backoff: true
  exponential-backoff-multiplier: 2

// Enhanced AI service
@Service
public class ResilientAiService {
  private final ChatClient chatClient;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  
  public String callAi(String prompt) {
    var decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
      Retry.decorateSupplier(retry, () -> chatClient.call(prompt)));
      
    return Try.ofSupplier(decorated)
      .getOrElseThrow(() -> new AiServiceException("AI service unavailable"));
  }
}
```

**Acceptance Criteria:**
- [ ] Simulated slow/unavailable AI returns 503 quickly
- [ ] Circuit breaker opens under sustained failure
- [ ] Logs show retries capped and backoff applied
- [ ] Thread pool doesn't exhaust under load

---

### 5. Persist Job Progress (No In-Memory Truth)
**Priority:** Critical  
**Effort:** 2-3 days  
**Risk:** High (data loss)

**Why:** Multi-node deploys and restarts lose in-memory state.

**Current State:**
- AI job progress likely stored in memory
- No persistence of generation state
- Progress lost on application restart

**Implementation:**
```java
// Job progress entity
@Entity
@Table(name = "ai_jobs")
public class AiJob {
  @Id private UUID id;
  private String status; // PENDING, PROCESSING, COMPLETED, FAILED
  private int progressPercent;
  private String currentChunk;
  private String errorMessage;
  private LocalDateTime startedAt;
  private LocalDateTime completedAt;
  @Version private long version;
}

// Progress service
@Service
public class JobProgressService {
  private final AiJobRepository jobRepository;
  
  public void updateProgress(UUID jobId, int percent, String chunk) {
    AiJob job = jobRepository.findById(jobId)
      .orElseThrow(() -> new JobNotFoundException(jobId));
    job.setProgressPercent(percent);
    job.setCurrentChunk(chunk);
    jobRepository.save(job);
  }
  
  public AiJobStatus getStatus(UUID jobId) {
    return jobRepository.findById(jobId)
      .map(this::toStatus)
      .orElseThrow(() -> new JobNotFoundException(jobId));
  }
}
```

**Acceptance Criteria:**
- [ ] Restart during generation preserves progress UI
- [ ] Two app instances stay consistent
- [ ] Progress persisted to database
- [ ] SSE/WebSocket reads from persisted store

---

### 6. Replace Generic RuntimeExceptions with Domain Exceptions
**Priority:** High  
**Effort:** 1-2 days  
**Risk:** Medium (poor error handling)

**Why:** Wrong HTTP semantics (500s) for client errors; poor DX/UX.

**Current State:**
- Multiple `throw new RuntimeException(...)` instances found
- Generic exception handling in `GlobalExceptionHandler`
- Inconsistent error responses

**Implementation:**
```java
// Replace specific RuntimeExceptions
// Before:
throw new RuntimeException("Document has no chunks available for quiz generation");

// After:
throw new DocumentProcessingException("Document has no chunks available for quiz generation");

// Add to GlobalExceptionHandler
@ExceptionHandler(DocumentProcessingException.class)
@ResponseStatus(BAD_REQUEST)
public ProblemDetail onDocumentProcessing(DocumentProcessingException ex, HttpServletRequest r) {
  var pd = ProblemDetail.forStatus(BAD_REQUEST);
  pd.setTitle("Document Processing Error");
  pd.setDetail(ex.getMessage());
  pd.setType(URI.create("https://your.app/problems/document-processing-error"));
  pd.setProperty("code", "DOC_PROCESSING_ERROR");
  pd.setInstance(URI.create(r.getRequestURI()));
  return pd;
}
```

**Files to Update:**
- `QuizServiceImpl.java` (lines 234, 255, 267, 454)
- `DocumentProcessingServiceImpl.java` (multiple lines)
- `AuthServiceImpl.java` (lines 345, 355)
- `PromptTemplateServiceImpl.java` (line 105)

**Acceptance Criteria:**
- [ ] Known client faults return 4xx with RFC9457 ProblemDetail
- [ ] Actionable error messages provided
- [ ] No generic RuntimeException in production code
- [ ] Consistent error response format

---

### 7. Fix Package Typo: aplication → application
**Priority:** High  
**Effort:** 0.5 days  
**Risk:** Low (maintenance)

**Why:** Long-term maintenance risk; IDE/code-search confusion.

**Current State:**
- Package `uk.gegc.quizmaker.features.admin.aplication` exists
- Multiple imports reference incorrect package

**Implementation:**
```bash
# Refactor package structure
mv src/main/java/uk/gegc/quizmaker/features/admin/aplication \
   src/main/java/uk/gegc/quizmaker/features/admin/application

# Update all imports
find . -name "*.java" -exec sed -i 's/aplication/application/g' {} \;
```

**Files to Update:**
- All files in `features/admin/aplication/` directory
- Import statements in test files
- Import statements in controllers

**Acceptance Criteria:**
- [ ] Build passes
- [ ] No lingering imports with typo
- [ ] Package names consistent throughout codebase
- [ ] IDE navigation works correctly

---

### 8. Remove Test-Only Branches from Production Code
**Priority:** High  
**Effort:** 0.5 days  
**Risk:** Low (code quality)

**Why:** Surprising behavior in prod, noise for future devs.

**Current State:**
- Test-specific conditions in production code
- Debug scaffolding that should be removed

**Implementation:**
```java
// Remove conditions like:
if ("invalid/content-type".equals(...)) {
  // test-only logic
}

// Replace with proper test mocks/spies
@MockBean
private FileValidationService fileValidationService;

@Test
void shouldRejectInvalidContentType() {
  when(fileValidationService.validateFile(any())).thenThrow(new UnsupportedFileTypeException("invalid"));
  // test logic
}
```

**Acceptance Criteria:**
- [ ] Tests still cover the path
- [ ] Production code has no test sentinels
- [ ] All test-specific logic moved to test classes
- [ ] Clean separation between test and production code

---

### 9. Logging Hygiene & Correlation
**Priority:** High  
**Effort:** 1 day  
**Risk:** Medium (privacy, debugging)

**Why:** Info-level spam hides signals; privacy risk.

**Current State:**
- DEBUG level logging enabled in production
- No correlation IDs
- Potential PII in logs

**Implementation:**
```java
// Add correlation ID filter
@Component
public class CorrelationIdFilter implements Filter {
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    String correlationId = UUID.randomUUID().toString();
    MDC.put("correlationId", correlationId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }
}

// Update logging configuration
logging.level.uk.gegc.quizmaker=INFO
logging.level.org.springframework=WARN
logging.level.org.hibernate.SQL=WARN
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] [%X{correlationId}] %-5level %logger{36} - %msg%n

// Add job correlation to AI operations
@Slf4j
@Service
public class AiQuizGenerationService {
  public void generateQuiz(UUID jobId, GenerateQuizRequest request) {
    MDC.put("jobId", jobId.toString());
    try {
      log.info("Starting quiz generation for job {}", jobId);
      // generation logic
      log.info("Completed quiz generation for job {}", jobId);
    } finally {
      MDC.remove("jobId");
    }
  }
}
```

**Acceptance Criteria:**
- [ ] Typical run shows concise INFO logs
- [ ] Sensitive values never appear in logs
- [ ] Job traceable end-to-end via correlation IDs
- [ ] DEBUG level only in development

---

### 10. Optimistic Locking Coverage Audit
**Priority:** High  
**Effort:** 1 day  
**Risk:** Medium (data integrity)

**Why:** Silent overwrites under concurrent edits.

**Current State:**
- Some entities may lack `@Version` annotation
- No optimistic locking failure handling

**Implementation:**
```java
// Add @Version to all entities with concurrent writes
@Entity
public class Quiz {
  @Id private UUID id;
  @Version private long version;
  // other fields
}

// Add optimistic locking exception handler
@ExceptionHandler(OptimisticLockingFailureException.class)
@ResponseStatus(CONFLICT)
public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest r) {
  var pd = ProblemDetail.forStatus(CONFLICT);
  pd.setTitle("Concurrent Modification");
  pd.setDetail("The resource has been modified by another user. Please refresh and try again.");
  pd.setType(URI.create("https://your.app/problems/concurrent-modification"));
  pd.setProperty("code", "CONCURRENT_MODIFICATION");
  pd.setInstance(URI.create(r.getRequestURI()));
  return pd;
}
```

**Acceptance Criteria:**
- [ ] Concurrent update test yields 409
- [ ] Retry path works correctly
- [ ] All editable entities have `@Version`
- [ ] Clear error message for users

---

### 11. N+1 Query Sweep on Hot Paths
**Priority:** High  
**Effort:** 1-2 days  
**Risk:** Medium (performance)

**Why:** Hidden latency/cost under load.

**Current State:**
- Potential N+1 queries in list endpoints
- No query optimization for collections

**Implementation:**
```java
// Add @EntityGraph for collections
@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {
  @EntityGraph(attributePaths = {"categories", "tags"})
  @Query("select q from Quiz q where q.createdBy = :userId")
  Page<Quiz> findByUserWithAssociations(@Param("userId") UUID userId, Pageable pageable);
}

// Use projections for list views
@Query("""
  select q.id as id, q.title as title, q.updatedAt as updatedAt,
         count(distinct qt.id) as questionCount
  from Quiz q
  left join q.questions qt
  group by q.id, q.title, q.updatedAt
""")
Page<QuizSummaryDto> findAllSummaries(Pageable pageable);
```

**Acceptance Criteria:**
- [ ] List/detail endpoints keep queries bounded
- [ ] N+1 queries eliminated
- [ ] Performance tests show improvement
- [ ] Query count assertions in tests

---

### 12. Uniform ProblemDetail Responses
**Priority:** High  
**Effort:** 1 day  
**Risk:** Low (consistency)

**Why:** Stable client integration; easier error handling.

**Current State:**
- Mixed `ErrorResponse` and `ProblemDetail` usage
- Inconsistent error response format

**Implementation:**
```java
// Standardize on ProblemDetail (RFC9457)
@RestControllerAdvice
public class GlobalExceptionHandler {
  @ExceptionHandler(EntityNotFoundException.class)
  @ResponseStatus(NOT_FOUND)
  public ProblemDetail handleNotFound(EntityNotFoundException ex, HttpServletRequest r) {
    var pd = ProblemDetail.forStatus(NOT_FOUND);
    pd.setTitle("Resource not found");
    pd.setDetail(ex.getMessage());
    pd.setType(URI.create("https://your.app/problems/not-found"));
    pd.setProperty("code", "RESOURCE_NOT_FOUND");
    pd.setInstance(URI.create(r.getRequestURI()));
    return pd;
  }
}
```

**Acceptance Criteria:**
- [ ] All error responses use ProblemDetail format
- [ ] Consistent type URIs and error codes
- [ ] No stack traces exposed to clients
- [ ] RFC9457 compliance verified

---

### 13. Service-Level Validation (@Validated)
**Priority:** High  
**Effort:** 0.5 days  
**Risk:** Low (validation)

**Why:** Guards non-HTTP callers; catches programmer errors early.

**Current State:**
- Some services lack `@Validated` annotation
- Method parameters not validated

**Implementation:**
```java
// Add @Validated to all services
@Service
@Validated
@RequiredArgsConstructor
public class QuizService {
  public QuizDto create(@Valid @NotNull CreateQuizRequest request) {
    // implementation
  }
  
  public QuizDto update(@NotNull UUID id, @Valid @NotNull UpdateQuizRequest request) {
    // implementation
  }
}
```

**Acceptance Criteria:**
- [ ] Invalid internal calls fail fast
- [ ] Method parameters validated
- [ ] Clear validation error messages
- [ ] Tests verify validation behavior

---

## P1 — Shortly After Launch (High Value)

### 14. SSE (or WebSocket) Progress Stream for AI Jobs
**Priority:** High  
**Effort:** 2-3 days  
**Risk:** Medium (complexity)

**Why:** UX; reduces wasteful polling.

**Implementation:**
```java
@GetMapping("/ai/jobs/{id}/events")
public SseEmitter stream(@PathVariable UUID id) {
  var emitter = new SseEmitter(0L);
  progressService.subscribe(id, update -> {
    try {
      emitter.send(SseEmitter.event().data(update));
    } catch (IOException e) {
      emitter.completeWithError(e);
    }
  });
  emitter.onCompletion(() -> progressService.unsubscribe(id));
  return emitter;
}
```

**Acceptance Criteria:**
- [ ] UI shows live progress
- [ ] No polling required
- [ ] Progress survives page reloads
- [ ] Graceful connection handling

---

### 15. Smarter Type Fallback (MATCHING → MCQ, etc.)
**Priority:** Medium  
**Effort:** 1-2 days  
**Risk:** Low (UX improvement)

**Why:** Turn AI parse failures into "degraded success", not error.

**Implementation:**
```java
public class QuestionTypeFallbackStrategy {
  private static final Map<QuestionType, QuestionType> FALLBACK_MAP = Map.of(
    QuestionType.MATCHING, QuestionType.MCQ_MULTI,
    QuestionType.HOTSPOT, QuestionType.MCQ_SINGLE,
    QuestionType.ORDERING, QuestionType.MCQ_MULTI
  );
  
  public QuestionType getFallbackType(QuestionType originalType) {
    return FALLBACK_MAP.getOrDefault(originalType, QuestionType.MCQ_SINGLE);
  }
}
```

**Acceptance Criteria:**
- [ ] Parser failures yield usable quiz
- [ ] Downgrade recorded in database
- [ ] Creator notified of type changes
- [ ] Fallback strategy documented

---

### 16. Source Traceability for Questions
**Priority:** Medium  
**Effort:** 1 day  
**Risk:** Low (data model)

**Why:** Trust & editing (where did this come from?).

**Implementation:**
```java
@Entity
public class Question {
  @Id private UUID id;
  private String content;
  private QuestionType type;
  
  // Source traceability
  private UUID sourceChunkId;
  private String sourceExcerpt;
  private int sourcePage;
  private String sourceHeading;
}
```

**Acceptance Criteria:**
- [ ] UI can "Show source" for any question
- [ ] Source information persisted
- [ ] Source links work correctly
- [ ] Source data searchable

---

### 17. MapStruct Adoption for DTO Mapping
**Priority:** Medium  
**Effort:** 2-3 days  
**Risk:** Low (refactoring)

**Why:** Less boilerplate, fewer mapping bugs.

**Implementation:**
```java
@Mapper(componentModel = "spring")
public interface QuizMapper {
  QuizDto toDto(Quiz entity);
  
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  Quiz toEntity(CreateQuizRequest request);
  
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  void updateEntity(@MappingTarget Quiz entity, UpdateQuizRequest request);
}
```

**Acceptance Criteria:**
- [ ] Existing tests pass
- [ ] Less custom mapping code
- [ ] Compile-time mapping validation
- [ ] Performance improvement

---

### 18. Explicitly Disable OSIV
**Priority:** Medium  
**Effort:** 0.5 days  
**Risk:** Medium (breaking changes)

**Why:** Enforce correct transactional boundaries.

**Implementation:**
```properties
# application.properties
spring.jpa.open-in-view=false
```

**Acceptance Criteria:**
- [ ] No LazyInitializationException in tests
- [ ] All DTO mapping happens in transactions
- [ ] Performance improvement
- [ ] Clear transaction boundaries

---

### 19. Global Rate Limiting & Abuse Protection
**Priority:** Medium  
**Effort:** 1-2 days  
**Risk:** Low (infrastructure)

**Why:** Protect infra from bursts (per IP / per route).

**Implementation:**
```java
@Component
public class GlobalRateLimitFilter implements Filter {
  private final RateLimitService rateLimit;
  
  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
    String ip = getClientIp(request);
    String endpoint = ((HttpServletRequest) request).getRequestURI();
    
    try {
      rateLimit.checkRateLimit("global:" + endpoint, ip, 100); // 100 req/min per IP
      chain.doFilter(request, response);
    } catch (RateLimitExceededException e) {
      ((HttpServletResponse) response).setStatus(429);
      response.getWriter().write("{\"error\":\"Too many requests\"}");
    }
  }
}
```

**Acceptance Criteria:**
- [ ] Attack simulation triggers 429
- [ ] Normal flows unaffected
- [ ] Rate limits configurable
- [ ] Clear retry-after headers

---

### 20. Document Parsing Memory Safety
**Priority:** Medium  
**Effort:** 1-2 days  
**Risk:** Medium (memory usage)

**Why:** Very large files.

**Implementation:**
```java
@Service
public class MemorySafeDocumentProcessor {
  private static final int MAX_PAGES = 1000;
  private static final int MAX_CHARS = 10_000_000;
  
  public void processDocument(MultipartFile file) {
    // Stream processing for large files
    try (InputStream is = file.getInputStream()) {
      // Process in chunks, not all at once
      processInChunks(is);
    }
  }
  
  private void processInChunks(InputStream is) {
    // Implementation with memory bounds
  }
}
```

**Acceptance Criteria:**
- [ ] 500-page PDF processes within memory budget
- [ ] Processing time bounded
- [ ] No OutOfMemoryError
- [ ] Progress tracking for large files

---

### 21. Pagination Strategy Audit (Page vs Slice)
**Priority:** Low  
**Effort:** 1 day  
**Risk:** Low (optimization)

**Why:** Response time & DB load.

**Implementation:**
```java
// Use Slice where counts aren't needed
@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {
  // For list views where count is expensive
  Slice<QuizSummaryDto> findRecentSummaries(Pageable pageable);
  
  // For admin views where count is needed
  Page<QuizDto> findAllForAdmin(Pageable pageable);
}
```

**Acceptance Criteria:**
- [ ] Query plans improve
- [ ] Latency lower on large datasets
- [ ] Appropriate use of Page vs Slice
- [ ] Performance tests show improvement

---

### 22. Timeouts/Circuit Breakers for Other Externals
**Priority:** Low  
**Effort:** 1 day  
**Risk:** Low (resilience)

**Why:** Prevent thread starvation.

**Implementation:**
```java
// Apply Resilience4j to email, storage services
@Service
public class ResilientEmailService {
  private final JavaMailSender mailSender;
  private final CircuitBreaker circuitBreaker;
  
  public void sendEmail(String to, String subject, String body) {
    var decorated = CircuitBreaker.decorateSupplier(circuitBreaker,
      () -> mailSender.send(createMessage(to, subject, body)));
      
    Try.ofSupplier(decorated)
      .getOrElseThrow(() -> new EmailServiceException("Email service unavailable"));
  }
}
```

**Acceptance Criteria:**
- [ ] Downstream outages don't cascade
- [ ] Circuit breakers protect thread pools
- [ ] Graceful degradation
- [ ] Monitoring of breaker states

---

### 23. Trim Any Remaining Verbose Logs
**Priority:** Low  
**Effort:** 0.5 days  
**Risk:** Low (cleanup)

**Why:** Signal/noise ratio.

**Implementation:**
```properties
# application.properties
logging.level.uk.gegc.quizmaker=INFO
logging.level.org.springframework=WARN
logging.level.org.hibernate.SQL=WARN
logging.level.org.hibernate.type.descriptor.sql.BasicBinder=OFF
```

**Acceptance Criteria:**
- [ ] Clean production logs
- [ ] Important events still logged
- [ ] Debug info available in development
- [ ] Log volume reduced

---

## P2 — Nice-to-Have / Scale & Polish

### 24. Observability Pack
**Priority:** Low  
**Effort:** 3-5 days  
**Risk:** Low (monitoring)

**Implementation:**
- Prometheus metrics
- OpenTelemetry tracing
- Grafana dashboards
- Alerting rules

**Acceptance Criteria:**
- [ ] AI latency metrics
- [ ] Job throughput tracking
- [ ] Failure rate monitoring
- [ ] Circuit breaker state visibility

---

### 25. Internationalization of User Messages
**Priority:** Low  
**Effort:** 2-3 days  
**Risk:** Low (UX)

**Implementation:**
- Message bundles for main locales
- Friendly error messages
- Localized validation messages

**Acceptance Criteria:**
- [ ] Error messages localized
- [ ] Validation messages translated
- [ ] Locale detection works
- [ ] Message bundles complete

---

### 26. Feature Flagging for AI Providers
**Priority:** Low  
**Effort:** 1-2 days  
**Risk:** Low (flexibility)

**Implementation:**
- Toggle between AI providers
- A/B testing capability
- Graceful degradation

**Acceptance Criteria:**
- [ ] Provider switching works
- [ ] A/B testing possible
- [ ] Degradation graceful
- [ ] Configuration flexible

---

### 27. Background Queue for Heavy Jobs
**Priority:** Low  
**Effort:** 3-4 days  
**Risk:** Medium (complexity)

**Implementation:**
- RabbitMQ/SQS integration
- Worker processes
- Job status tracking

**Acceptance Criteria:**
- [ ] Heavy jobs offloaded
- [ ] Backpressure handled
- [ ] Job status visible
- [ ] Scalable architecture

---

### 28. Admin Tooling
**Priority:** Low  
**Effort:** 2-3 days  
**Risk:** Low (operations)

**Implementation:**
- Job requeue functionality
- Manual moderation queue
- System health dashboard

**Acceptance Criteria:**
- [ ] Stuck jobs can be requeued
- [ ] Manual moderation works
- [ ] System health visible
- [ ] Admin actions audited

---

### 29. Notifications on Completion
**Priority:** Low  
**Effort:** 1-2 days  
**Risk:** Low (UX)

**Implementation:**
- Email notifications
- Web push notifications
- Notification preferences

**Acceptance Criteria:**
- [ ] Email sent on completion
- [ ] Push notifications work
- [ ] Preferences configurable
- [ ] Notification history

---

### 30. Static Analysis & Security Scanning
**Priority:** Low  
**Effort:** 1 day  
**Risk:** Low (quality)

**Implementation:**
- SpotBugs integration
- OWASP Dependency Check
- SAST in CI pipeline

**Acceptance Criteria:**
- [ ] Static analysis passes
- [ ] Vulnerabilities detected
- [ ] CI pipeline integrated
- [ ] Regular scanning

---

### 31. Security Headers & CSP
**Priority:** Low  
**Effort:** 1 day  
**Risk:** Low (security)

**Implementation:**
- Content Security Policy
- HSTS headers
- Frame-ancestors policy

**Acceptance Criteria:**
- [ ] Security headers set
- [ ] CSP configured
- [ ] Security scan passes
- [ ] Headers documented

---

### 32. Data Retention & Cleanup
**Priority:** Low  
**Effort:** 2-3 days  
**Risk:** Low (maintenance)

**Implementation:**
- Retention policies
- Scheduled cleanup jobs
- Data archival strategy

**Acceptance Criteria:**
- [ ] Old data cleaned up
- [ ] Retention policies enforced
- [ ] Cleanup jobs scheduled
- [ ] Data archived properly

---

### 33. Performance & Load Test Suite
**Priority:** Low  
**Effort:** 3-4 days  
**Risk:** Low (testing)

**Implementation:**
- K6/JMeter scenarios
- Load testing automation
- SLO baseline capture

**Acceptance Criteria:**
- [ ] Realistic load scenarios
- [ ] Performance baselines
- [ ] Automated testing
- [ ] SLO monitoring

---

## Quick Wins (≤ 1 hour each)

1. **Rename aplication → application**
   - Refactor package structure
   - Update all imports
   - Verify build passes

2. **Replace RuntimeException with typed exceptions**
   - Create specific exception classes
   - Update exception handlers
   - Add proper HTTP status codes

3. **Downgrade debug logs**
   - Set logging levels to INFO/WARN
   - Add correlation IDs
   - Remove PII from logs

4. **Add @Validated to core services**
   - Annotate service classes
   - Add validation annotations to methods
   - Test validation behavior

5. **Disable OSIV**
   - Set `spring.jpa.open-in-view=false`
   - Fix any LazyInitializationException
   - Verify transaction boundaries

6. **Add HTTP client timeouts**
   - Configure AI client timeouts
   - Add email service timeouts
   - Test timeout behavior

---

## Minimal Code Patterns (Copy/Paste as Needed)

### Service-Level Guard
```java
@Service
@Validated
@RequiredArgsConstructor
public class QuizService {
  @PreAuthorize("hasAuthority('quiz:write')")
  @Transactional
  public QuizDto publish(@NotNull UUID quizId) { ... }
}
```

### Tika File Sniffing & Size Cap
```java
var detector = new Tika();
String detected = detector.detect(file.getInputStream());
if (!allowedTypes.contains(detected)) {
  throw new UnsupportedFileTypeException(detected);
}
if (file.getSize() > maxBytes) {
  throw new FileTooLargeException(maxBytes);
}
```

### Resilience4j Around AI
```java
var decorated = CircuitBreaker.decorateSupplier(cb,
  Retry.decorateSupplier(retry, () -> chatClient.call(prompt)));
var response = Try.ofSupplier(decorated)
  .getOrElseThrow(() -> new AiServiceException("AI unavailable"));
```

### SSE Endpoint Skeleton
```java
@GetMapping("/ai/jobs/{id}/events")
public SseEmitter stream(@PathVariable UUID id) {
  var emitter = new SseEmitter(0L);
  progressService.subscribe(id, update -> emitter.send(SseEmitter.event().data(update)));
  emitter.onCompletion(() -> progressService.unsubscribe(id));
  return emitter;
}
```

### ProblemDetail Mapping Example
```java
@ExceptionHandler(DocumentProcessingException.class)
@ResponseStatus(BAD_REQUEST)
ProblemDetail onDoc(DocumentProcessingException ex, HttpServletRequest r) {
  var pd = ProblemDetail.forStatus(BAD_REQUEST);
  pd.setTitle("Invalid document");
  pd.setDetail(ex.getMessage());
  pd.setType(URI.create("https://your.app/problems/invalid-document"));
  pd.setProperty("code", "DOC_INVALID");
  pd.setInstance(URI.create(r.getRequestURI()));
  return pd;
}
```

---

## Implementation Timeline

### Week 1: P0 Critical Items
- Method-level security enforcement
- File upload hardening
- AI quotas and rate limiting
- Job progress persistence

### Week 2: P0 Remaining Items
- Exception handling cleanup
- Package typo fix
- Logging hygiene
- Optimistic locking audit

### Week 3: P1 High-Value Items
- SSE progress streaming
- Type fallback strategy
- Source traceability
- MapStruct adoption

### Week 4: P1 Polish Items
- OSIV disable
- Global rate limiting
- Memory safety
- Pagination optimization

### Week 5+: P2 Nice-to-Have
- Observability pack
- Internationalization
- Feature flags
- Background queues

---

## Risk Mitigation

### High-Risk Items
- **Method Security**: Extensive testing required
- **File Uploads**: Security testing mandatory
- **AI Quotas**: Cost monitoring essential
- **Job Persistence**: Data integrity critical

### Medium-Risk Items
- **Exception Handling**: Breaking changes possible
- **OSIV Disable**: May expose lazy loading issues
- **Memory Safety**: Performance impact possible

### Low-Risk Items
- **Package Rename**: Mechanical change
- **Logging**: No functional impact
- **Validation**: Defensive programming

---

## Success Metrics

### Security
- [ ] Zero security bypasses in penetration testing
- [ ] All sensitive operations properly secured
- [ ] File uploads validated and sanitized
- [ ] Rate limiting prevents abuse

### Reliability
- [ ] 99.9% uptime maintained
- [ ] No data loss on restarts
- [ ] Circuit breakers prevent cascading failures
- [ ] Timeouts prevent thread exhaustion

### Performance
- [ ] Response times < 2s for 95% of requests
- [ ] Memory usage within bounds
- [ ] N+1 queries eliminated
- [ ] Database connections optimized

### Maintainability
- [ ] Consistent error handling
- [ ] Clean logging without PII
- [ ] Proper package structure
- [ ] Comprehensive test coverage

---

## Conclusion

This backlog plan addresses the critical issues identified in the code review while maintaining a clear path to launch. The P0 items are essential for security and reliability, while P1 and P2 items provide incremental value and polish.

The implementation should be done incrementally, with each item fully tested before moving to the next. Special attention should be paid to the high-risk items, particularly around security and data integrity.

Regular reviews of progress against this plan will ensure that the application meets production readiness standards and provides a solid foundation for future development.
