# Quiz Management Logic - End-to-End Analysis

## 📋 Table of Contents
1. [🏗️ Core Models & Entities](#core-models--entities)
2. [📊 DTOs & Data Transfer Objects](#dtos--data-transfer-objects)
3. [🎮 Enums & Status Management](#enums--status-management)
4. [🔧 Services & Business Logic](#services--business-logic)
5. [🎯 Controllers & API Endpoints](#controllers--api-endpoints)
6. [🗄️ Repositories & Data Access](#repositories--data-access)
7. [🔄 Mappers & Data Transformation](#mappers--data-transformation)
8. [🤖 AI Generation & Job Management](#ai-generation--job-management)
9. [🛡️ Exceptions & Error Handling](#exceptions--error-handling)
10. [🔒 Security & Authorization](#security--authorization)
11. [🧪 Testing & Validation](#testing--validation)
12. [🔄 End-to-End Flow](#end-to-end-flow)

---

## 🏗️ Core Models & Entities

### Quiz Entity
```java
@Entity
@Table(name = "quizzes", uniqueConstraints = @UniqueConstraint(columnNames = {"creator_id", "title"}))
@SQLDelete(sql = "UPDATE quizzes SET is_deleted = true, deleted_at = CURRENT_TIMESTAMP WHERE quiz_id = ?")
@SQLRestriction("is_deleted = false")
public class Quiz {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "quiz_id")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false, updatable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private Visibility visibility;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20)
    private Difficulty difficulty;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private QuizStatus status;

    @Column(name = "estimated_time_min", nullable = false)
    private Integer estimatedTime;

    @Column(name = "is_repetition_enabled", nullable = false)
    private Boolean isRepetitionEnabled;

    @Column(name = "is_timer_enabled", nullable = false)
    private Boolean isTimerEnabled;

    @Column(name = "timer_duration_min")
    private Integer timerDuration;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "is_deleted", nullable = false, columnDefinition = "boolean default false")
    private Boolean isDeleted;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(name = "quiz_tags", ...)
    private Set<Tag> tags = new HashSet<>();

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(name = "quiz_questions", ...)
    private Set<Question> questions = new HashSet<>();
}
```

### QuizGenerationJob Entity
```java
@Entity
@Table(name = "quiz_generation_jobs")
public class QuizGenerationJob {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "username", referencedColumnName = "username", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GenerationStatus status;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "total_chunks")
    private Integer totalChunks;

    @Column(name = "processed_chunks")
    private Integer processedChunks = 0;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "generated_quiz_id")
    private UUID generatedQuizId;

    @Column(name = "request_data", columnDefinition = "JSON")
    private String requestData;

    @Column(name = "estimated_completion")
    private LocalDateTime estimatedCompletion;

    @Column(name = "progress_percentage")
    private Double progressPercentage = 0.0;

    @Column(name = "current_chunk")
    private String currentChunk;

    @Column(name = "total_questions_generated")
    private Integer totalQuestionsGenerated = 0;

    @Column(name = "generation_time_seconds")
    private Long generationTimeSeconds;
}
```

---

## 📊 DTOs & Data Transfer Objects

### CreateQuizRequest
```java
@Schema(name = "CreateQuizRequest", description = "Payload for creating a quiz")
public record CreateQuizRequest(
    @NotBlank(message = "Title must not be blank")
    @Size(min = 3, max = 100, message = "Title length must be between 3 and 100 characters")
    String title,

    @Size(max = 1000, message = "Description must be at most 1000 characters long")
    String description,

    Visibility visibility,

    Difficulty difficulty,

    boolean isRepetitionEnabled,

    boolean timerEnabled,

    @Min(value = 1, message = "Estimated time can't be less than 1 minute")
    @Max(value = 180, message = "Estimated time can't be more than 180 minutes")
    int estimatedTime,

    @Min(value = 1, message = "Timer duration must be at least 1 minute")
    @Max(value = 180, message = "Timer duration must be at most 180 minutes")
    int timerDuration,

    UUID categoryId,

    List<UUID> tagIds
) {
    public CreateQuizRequest {
        visibility = (visibility == null ? Visibility.PRIVATE : visibility);
        difficulty = (difficulty == null ? Difficulty.MEDIUM : difficulty);
        tagIds = (tagIds == null ? List.of() : tagIds);
    }
}
```

### UpdateQuizRequest
```java
@Schema(name = "UpdateQuizRequest", description = "Fields to update on an existing quiz")
public record UpdateQuizRequest(
    @Size(min = 3, max = 100, message = "Title length must be between 3 and 100 characters")
    String title,

    @Size(max = 1000, message = "Description must be at most 1000 characters long")
    String description,

    Visibility visibility,

    Difficulty difficulty,

    Boolean isRepetitionEnabled,

    Boolean timerEnabled,

    @Min(value = 1, message = "Estimated time must be at least 1 minute")
    @Max(value = 180, message = "Estimated time must be at most 180 minutes")
    Integer estimatedTime,

    @Min(value = 1, message = "Timer duration must be at least 1 minute")
    @Max(value = 180, message = "Timer duration must be at most 180 minutes")
    Integer timerDuration,

    UUID categoryId,

    List<UUID> tagIds
) {}
```

### QuizDto
```java
@Schema(name = "QuizDto", description = "Representation of a quiz")
public record QuizDto(
    UUID id,
    UUID creatorId,
    UUID categoryId,
    String title,
    String description,
    Visibility visibility,
    Difficulty difficulty,
    QuizStatus status,
    Integer estimatedTime,
    Boolean isRepetitionEnabled,
    Boolean timerEnabled,
    Integer timerDuration,
    List<UUID> tagIds,
    Instant createdAt,
    Instant updatedAt
) {}
```

### QuizSearchCriteria
```java
public record QuizSearchCriteria(
    String title,
    UUID categoryId,
    Difficulty difficulty,
    QuizStatus status,
    Visibility visibility,
    UUID creatorId
) {}
```

### GenerateQuizFromDocumentRequest
```java
@Schema(name = "GenerateQuizFromDocumentRequest", description = "Request to generate a quiz from document chunks using AI")
public record GenerateQuizFromDocumentRequest(
    @NotNull(message = "Document ID must not be null")
    UUID documentId,

    QuizScope quizScope,

    List<Integer> chunkIndices,

    String chapterTitle,

    Integer chapterNumber,

    @Size(max = 100, message = "Quiz title must not exceed 100 characters")
    String quizTitle,

    @Size(max = 500, message = "Quiz description must not exceed 500 characters")
    String quizDescription,

    @NotNull(message = "Questions per type must not be null")
    @Size(min = 1, message = "At least one question type must be specified")
    Map<QuestionType, Integer> questionsPerType,

    @NotNull(message = "Difficulty must not be null")
    Difficulty difficulty,

    @Min(value = 1, message = "Estimated time per question must be at least 1 minute")
    @Max(value = 10, message = "Estimated time per question must not exceed 10 minutes")
    Integer estimatedTimePerQuestion,

    UUID categoryId,

    List<UUID> tagIds
) {}
```

### QuizGenerationResponse
```java
@Schema(name = "QuizGenerationResponse", description = "Response from starting a quiz generation job")
public record QuizGenerationResponse(
    UUID jobId,
    GenerationStatus status,
    String message,
    Long estimatedTimeSeconds
) {
    public static QuizGenerationResponse started(UUID jobId, Long estimatedTimeSeconds) {
        return new QuizGenerationResponse(
            jobId,
            GenerationStatus.PROCESSING,
            "Quiz generation started successfully",
            estimatedTimeSeconds
        );
    }

    public static QuizGenerationResponse failed(String errorMessage) {
        return new QuizGenerationResponse(
            null,
            GenerationStatus.FAILED,
            errorMessage,
            0L
        );
    }
}
```

### QuizGenerationStatus
```java
@Schema(name = "QuizGenerationStatus", description = "Current status of a quiz generation job")
public record QuizGenerationStatus(
    String jobId,
    GenerationStatus status,
    Integer totalChunks,
    Integer processedChunks,
    Double progressPercentage,
    String currentChunk,
    LocalDateTime estimatedCompletion,
    String errorMessage,
    Integer totalQuestionsGenerated,
    Long elapsedTimeSeconds,
    Long estimatedTimeRemainingSeconds,
    String generatedQuizId,
    LocalDateTime startedAt,
    LocalDateTime completedAt
) {
    public static QuizGenerationStatus fromEntity(QuizGenerationJob job) {
        return new QuizGenerationStatus(
            job.getId().toString(),
            job.getStatus(),
            job.getTotalChunks(),
            job.getProcessedChunks(),
            job.getProgressPercentage(),
            job.getCurrentChunk(),
            job.getEstimatedCompletion(),
            job.getErrorMessage(),
            job.getTotalQuestionsGenerated(),
            job.getDurationSeconds(),
            job.getEstimatedTimeRemainingSeconds(),
            job.getGeneratedQuizId() != null ? job.getGeneratedQuizId().toString() : null,
            job.getStartedAt(),
            job.getCompletedAt()
        );
    }
}
```

### BulkQuizUpdateRequest
```java
@Schema(name = "BulkQuizUpdateRequest", description = "Request payload for updating multiple quizzes")
public record BulkQuizUpdateRequest(
    @NotNull
    @Size(min = 1, message = "At least one quizId must be provided")
    List<UUID> quizIds,

    @NotNull
    @Valid
    UpdateQuizRequest update
) {}
```

### BulkQuizUpdateOperationResultDto
```java
@Schema(name = "BulkQuizUpdateOperationResultDto", description = "Outcome of a bulk operation")
public record BulkQuizUpdateOperationResultDto(
    List<UUID> successfulIds,
    Map<UUID, String> failures
) {}
```

---

## 🎮 Enums & Status Management

### QuizStatus
```java
public enum QuizStatus {
    PUBLISHED,
    DRAFT,
    ARCHIVED
}
```

### Visibility
```java
public enum Visibility {
    PUBLIC, PRIVATE
}
```

### GenerationStatus
```java
public enum GenerationStatus {
    PENDING("Pending"),
    PROCESSING("Processing"),
    COMPLETED("Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    private final String displayName;

    GenerationStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    public boolean isActive() {
        return this == PENDING || this == PROCESSING;
    }

    public boolean isSuccess() {
        return this == COMPLETED;
    }

    public boolean isFailure() {
        return this == FAILED || this == CANCELLED;
    }
}
```

### QuizScope
```java
public enum QuizScope {
    ENTIRE_DOCUMENT,
    SPECIFIC_CHUNKS,
    SPECIFIC_CHAPTER,
    SPECIFIC_SECTION
}
```

---

## 🔧 Services & Business Logic

### QuizService Interface
```java
public interface QuizService {
    UUID createQuiz(String username, CreateQuizRequest request);
    Page<QuizDto> getQuizzes(Pageable pageable, QuizSearchCriteria quizSearchCriteria);
    QuizDto getQuizById(UUID id);
    QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest updateQuizRequest);
    void deleteQuizById(String username, UUID quizId);
    void addQuestionToQuiz(String username, UUID quizId, UUID questionId);
    void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId);
    void addTagToQuiz(String username, UUID quizId, UUID tagId);
    void removeTagFromQuiz(String username, UUID quizId, UUID tagId);
    void changeCategory(String username, UUID quizId, UUID categoryId);
    QuizDto setVisibility(String name, UUID quizId, Visibility visibility);
    QuizDto setStatus(String name, UUID quizId, QuizStatus status);
    Page<QuizDto> getPublicQuizzes(Pageable pageable);
    void deleteQuizzesByIds(String name, List<UUID> quizIds);
    BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String name, BulkQuizUpdateRequest request);
    QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request);
    QuizGenerationResponse generateQuizFromUpload(String username, MultipartFile file, GenerateQuizFromUploadRequest request);
    QuizGenerationResponse startQuizGeneration(String username, GenerateQuizFromDocumentRequest request);
    QuizGenerationStatus getGenerationStatus(UUID jobId, String username);
    QuizDto getGeneratedQuiz(UUID jobId, String username);
    QuizGenerationStatus cancelGenerationJob(UUID jobId, String username);
}
```

### QuizServiceImpl Key Methods
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class QuizServiceImpl implements QuizService {
    
    @Override
    @Transactional
    public UUID createQuiz(String username, CreateQuizRequest request) {
        User creator = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Category category = Optional.ofNullable(request.categoryId())
                .flatMap(categoryRepository::findById)
                .orElseGet(() -> categoryRepository.findByName("General")
                        .orElseThrow(() -> new ResourceNotFoundException("Default category missing")));

        Set<Tag> tags = request.tagIds().stream()
                .map(id -> tagRepository.findById(id)
                        .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " not found")))
                .collect(Collectors.toSet());

        Quiz quiz = quizMapper.toEntity(request, creator, category, tags);
        return quizRepository.save(quiz).getId();
    }

    @Override
    @Transactional
    public QuizDto setStatus(String username, UUID quizId, QuizStatus status) {
        Quiz quiz = quizRepository.findByIdWithQuestions(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        if (status == QuizStatus.PUBLISHED) {
            validateQuizForPublishing(quiz);
        }

        quiz.setStatus(status);
        return quizMapper.toDto(quizRepository.save(quiz));
    }

    private void validateQuizForPublishing(Quiz quiz) {
        List<String> validationErrors = new ArrayList<>();

        // Check if quiz has questions
        if (quiz.getQuestions().isEmpty()) {
            validationErrors.add("Cannot publish quiz without questions");
        }

        // Check minimum estimated time
        if (quiz.getEstimatedTime() == null || quiz.getEstimatedTime() < MINIMUM_ESTIMATED_TIME_MINUTES) {
            validationErrors.add("Quiz must have a minimum estimated time of " + MINIMUM_ESTIMATED_TIME_MINUTES + " minute(s)");
        }

        // Check if all questions have valid correct answers
        if (!quiz.getQuestions().isEmpty()) {
            validateQuestionsHaveCorrectAnswers(quiz, validationErrors);
        }

        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException("Cannot publish quiz: " + String.join("; ", validationErrors));
        }
    }

    @Override
    @Transactional
    public BulkQuizUpdateOperationResultDto bulkUpdateQuiz(String username, BulkQuizUpdateRequest request) {
        List<UUID> successes = new ArrayList<>();
        Map<UUID, String> failures = new HashMap<>();

        for (UUID id : request.quizIds()) {
            try {
                updateQuiz(username, id, request.update());
                successes.add(id);
            } catch (Exception ex) {
                failures.put(id, ex.getMessage());
            }
        }

        return new BulkQuizUpdateOperationResultDto(successes, failures);
    }
}
```

### QuizGenerationJobService Interface
```java
public interface QuizGenerationJobService {
    QuizGenerationJob createJob(User user, UUID documentId, String requestData, int totalChunks, int estimatedTimeSeconds);
    QuizGenerationJob getJobByIdAndUsername(UUID jobId, String username);
    Optional<QuizGenerationJob> getJobById(UUID jobId);
    QuizGenerationJob updateJobProgress(UUID jobId, int processedChunks, int currentChunk, int totalQuestionsGenerated);
    QuizGenerationJob markJobCompleted(UUID jobId, UUID generatedQuizId);
    QuizGenerationJob markJobFailed(UUID jobId, String errorMessage);
    QuizGenerationJob cancelJob(UUID jobId, String username);
    Page<QuizGenerationJob> getJobsByUser(String username, Pageable pageable);
    List<QuizGenerationJob> getJobsByStatus(GenerationStatus status);
    List<QuizGenerationJob> getActiveJobs();
    List<QuizGenerationJob> getJobsByDocument(UUID documentId);
}
```

### AiQuizGenerationService Interface
```java
public interface AiQuizGenerationService {
    void generateQuizFromDocumentAsync(UUID jobId, GenerateQuizFromDocumentRequest request);
    void generateQuizFromDocumentAsync(QuizGenerationJob job, GenerateQuizFromDocumentRequest request);
    CompletableFuture<List<Question>> generateQuestionsFromChunk(DocumentChunk chunk, Map<QuestionType, Integer> questionsPerType, Difficulty difficulty);
    List<Question> generateQuestionsByType(String chunkContent, QuestionType questionType, int questionCount, Difficulty difficulty);
    void validateDocumentForGeneration(UUID documentId, String username);
    int calculateEstimatedGenerationTime(int totalChunks, Map<QuestionType, Integer> questionsPerType);
    int calculateTotalChunks(UUID documentId, GenerateQuizFromDocumentRequest request);
}
```

---

## 🎯 Controllers & API Endpoints

### QuizController Key Endpoints
```java
@Tag(name = "Quizzes", description = "Operations for creating, reading, updating, deleting and associating quizzes.")
@RestController
@RequestMapping("/api/v1/quizzes")
@RequiredArgsConstructor
@Validated
@Slf4j
public class QuizController {

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, UUID>> createQuiz(@RequestBody @Valid CreateQuizRequest request, Authentication authentication) {
        UUID quizId = quizService.createQuiz(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("quizId", quizId));
    }

    @GetMapping
    public ResponseEntity<Page<QuizDto>> getQuizzes(
            @ParameterObject @PageableDefault(page = 0, size = 20) @SortDefault(sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
            @ParameterObject @ModelAttribute QuizSearchCriteria quizSearchCriteria
    ) {
        Page<QuizDto> quizPage = quizService.getQuizzes(pageable, quizSearchCriteria);
        return ResponseEntity.ok(quizPage);
    }

    @GetMapping("/{quizId}")
    public ResponseEntity<QuizDto> getQuiz(@PathVariable UUID quizId) {
        return ResponseEntity.ok(quizService.getQuizById(quizId));
    }

    @PatchMapping("/{quizId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizDto> updateQuiz(@PathVariable UUID quizId, @RequestBody @Valid UpdateQuizRequest request, Authentication authentication) {
        return ResponseEntity.ok(quizService.updateQuiz(authentication.getName(), quizId, request));
    }

    @DeleteMapping("/{quizId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteQuiz(@PathVariable UUID quizId, Authentication authentication) {
        quizService.deleteQuizById(authentication.getName(), quizId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{quizId}/questions/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> addQuestionToQuiz(@PathVariable UUID quizId, @PathVariable UUID questionId, Authentication authentication) {
        quizService.addQuestionToQuiz(authentication.getName(), quizId, questionId);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{quizId}/questions/{questionId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> removeQuestionFromQuiz(@PathVariable UUID quizId, @PathVariable UUID questionId, Authentication authentication) {
        quizService.removeQuestionFromQuiz(authentication.getName(), quizId, questionId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{quizId}/visibility")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizDto> updateQuizVisibility(@PathVariable UUID quizId, @RequestBody @Valid VisibilityUpdateRequest request, Authentication authentication) {
        QuizDto quizDto = quizService.setVisibility(authentication.getName(), quizId, request.isPublic() ? Visibility.PUBLIC : Visibility.PRIVATE);
        return ResponseEntity.ok(quizDto);
    }

    @PatchMapping("/{quizId}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizDto> updateQuizStatus(@PathVariable UUID quizId, @RequestBody @Valid QuizStatusUpdateRequest request, Authentication authentication) {
        QuizDto quizDto = quizService.setStatus(authentication.getName(), quizId, request.status());
        return ResponseEntity.ok(quizDto);
    }

    @PostMapping("/generate-from-document")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizGenerationResponse> generateQuizFromDocument(@RequestBody @Valid GenerateQuizFromDocumentRequest request, Authentication authentication) {
        QuizGenerationResponse response = quizService.startQuizGeneration(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @PostMapping(value = "/generate-from-upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuizGenerationResponse> generateQuizFromUpload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkingStrategy", required = false) String chunkingStrategy,
            @RequestParam(value = "maxChunkSize", required = false) Integer maxChunkSize,
            @RequestParam(value = "quizScope", required = false) String quizScope,
            @RequestParam(value = "chunkIndices", required = false) List<Integer> chunkIndices,
            @RequestParam(value = "chapterTitle", required = false) String chapterTitle,
            @RequestParam(value = "chapterNumber", required = false) Integer chapterNumber,
            @RequestParam(value = "quizTitle", required = false) String quizTitle,
            @RequestParam(value = "quizDescription", required = false) String quizDescription,
            @RequestParam("questionsPerType") String questionsPerTypeJson,
            @RequestParam("difficulty") String difficulty,
            @RequestParam(value = "estimatedTimePerQuestion", required = false) Integer estimatedTimePerQuestion,
            @RequestParam(value = "categoryId", required = false) UUID categoryId,
            @RequestParam(value = "tagIds", required = false) List<UUID> tagIds,
            Authentication authentication
    ) {
        // Parse and validate parameters, then call service
        Map<QuestionType, Integer> questionsPerType = parseQuestionsPerType(questionsPerTypeJson);
        GenerateQuizFromUploadRequest request = new GenerateQuizFromUploadRequest(/* parameters */);
        QuizGenerationResponse response = quizService.generateQuizFromUpload(authentication.getName(), file, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/generation-status/{jobId}")
    public ResponseEntity<QuizGenerationStatus> getGenerationStatus(@PathVariable UUID jobId, Authentication authentication) {
        QuizGenerationStatus status = quizService.getGenerationStatus(jobId, authentication.getName());
        return ResponseEntity.ok(status);
    }

    @GetMapping("/generated-quiz/{jobId}")
    public ResponseEntity<QuizDto> getGeneratedQuiz(@PathVariable UUID jobId, Authentication authentication) {
        QuizDto quiz = quizService.getGeneratedQuiz(jobId, authentication.getName());
        return ResponseEntity.ok(quiz);
    }

    @DeleteMapping("/generation-status/{jobId}")
    public ResponseEntity<QuizGenerationStatus> cancelGenerationJob(@PathVariable UUID jobId, Authentication authentication) {
        QuizGenerationStatus status = quizService.cancelGenerationJob(jobId, authentication.getName());
        return ResponseEntity.ok(status);
    }
}
```

---

## 🗄️ Repositories & Data Access

### QuizRepository
```java
@Repository
public interface QuizRepository extends JpaRepository<Quiz, UUID> {

    @Query("""
              SELECT q
              FROM Quiz q
              LEFT JOIN FETCH q.tags
              WHERE q.id = :id AND q.isDeleted = false
            """)
    Optional<Quiz> findByIdWithTags(@Param("id") UUID id);

    @Query("""
              SELECT q
              FROM Quiz q
              LEFT JOIN FETCH q.questions
              WHERE q.id = :id AND q.isDeleted = false
            """)
    Optional<Quiz> findByIdWithQuestions(@Param("id") UUID id);

    Page<Quiz> findAllByVisibility(Visibility visibility, Pageable pageable);
}
```

### QuizGenerationJobRepository
```java
@Repository
public interface QuizGenerationJobRepository extends JpaRepository<QuizGenerationJob, UUID> {
    List<QuizGenerationJob> findByUserUsername(String username);
    List<QuizGenerationJob> findByStatus(GenerationStatus status);
    List<QuizGenerationJob> findByDocumentId(UUID documentId);
    List<QuizGenerationJob> findByUserUsernameAndStatusIn(String username, List<GenerationStatus> statuses);
    Page<QuizGenerationJob> findByUserUsername(String username, Pageable pageable);
}
```

---

## 🔄 Mappers & Data Transformation

### QuizMapper
```java
@Component
public class QuizMapper {

    public Quiz toEntity(CreateQuizRequest req, User creator, Category category, Set<Tag> tags) {
        Quiz quiz = new Quiz();
        quiz.setCreator(creator);
        quiz.setCategory(category);
        quiz.setTitle(req.title());
        quiz.setDescription(req.description());
        quiz.setVisibility(req.visibility());
        quiz.setDifficulty(req.difficulty());
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(req.estimatedTime());
        quiz.setIsRepetitionEnabled(req.isRepetitionEnabled());
        quiz.setIsTimerEnabled(req.timerEnabled());
        quiz.setTimerDuration(req.timerDuration());
        quiz.setTags(tags);
        return quiz;
    }

    public void updateEntity(Quiz quiz, UpdateQuizRequest req, Category category, Set<Tag> tags) {
        if (category != null) {
            quiz.setCategory(category);
        }
        if (req.title() != null) {
            quiz.setTitle(req.title());
        }
        if (req.description() != null) {
            quiz.setDescription(req.description());
        }
        if (req.visibility() != null) {
            quiz.setVisibility(req.visibility());
        }
        if (req.difficulty() != null) {
            quiz.setDifficulty(req.difficulty());
        }
        if (req.estimatedTime() != null) {
            quiz.setEstimatedTime(req.estimatedTime());
        }
        if (req.isRepetitionEnabled() != null) {
            quiz.setIsRepetitionEnabled(req.isRepetitionEnabled());
        }
        if (req.timerEnabled() != null) {
            quiz.setIsTimerEnabled(req.timerEnabled());
        }
        if (req.timerDuration() != null) {
            quiz.setTimerDuration(req.timerDuration());
        }
        if (tags != null) {
            quiz.setTags(tags);
        }
    }

    public QuizDto toDto(Quiz quiz) {
        return new QuizDto(
            quiz.getId(),
            quiz.getCreator().getId(),
            quiz.getCategory().getId(),
            quiz.getTitle(),
            quiz.getDescription(),
            quiz.getVisibility(),
            quiz.getDifficulty(),
            quiz.getStatus(),
            quiz.getEstimatedTime(),
            quiz.getIsRepetitionEnabled(),
            quiz.getIsTimerEnabled(),
            quiz.getTimerDuration(),
            quiz.getTags().stream().map(Tag::getId).collect(Collectors.toList()),
            quiz.getCreatedAt(),
            quiz.getUpdatedAt()
        );
    }
}
```

---

## 🤖 AI Generation & Job Management

### AiQuizGenerationServiceImpl Key Methods
```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AiQuizGenerationServiceImpl implements AiQuizGenerationService {

    @Override
    @Async("aiTaskExecutor")
    @Transactional
    public void generateQuizFromDocumentAsync(QuizGenerationJob job, GenerateQuizFromDocumentRequest request) {
        UUID jobId = job.getId();
        Instant startTime = Instant.now();
        
        try {
            // Update job status to PROCESSING
            QuizGenerationJob freshJob = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));
            freshJob.setStatus(GenerationStatus.PROCESSING);
            jobRepository.save(freshJob);

            // Validate document
            validateDocumentForGeneration(request.documentId(), freshJob.getUser().getUsername());

            // Get document and chunks
            Document document = documentRepository.findByIdWithChunks(request.documentId())
                    .orElseThrow(() -> new DocumentNotFoundException("Document not found: " + request.documentId()));

            List<DocumentChunk> chunks = getChunksForScope(document, request);
            freshJob.setTotalChunks(chunks.size());
            jobRepository.save(freshJob);

            // Process chunks asynchronously
            List<CompletableFuture<List<Question>>> chunkFutures = chunks.stream()
                    .map(chunk -> generateQuestionsFromChunkWithJob(chunk, request.questionsPerType(), request.difficulty(), jobId))
                    .collect(Collectors.toList());

            // Collect all generated questions
            List<Question> allQuestions = new ArrayList<>();
            Map<Integer, List<Question>> chunkQuestions = new HashMap<>();
            
            // Process results and create quizzes
            // ... implementation details ...

            // Mark job as completed
            jobService.markJobCompleted(jobId, consolidatedQuiz.getId());

            // Publish completion event
            eventPublisher.publishEvent(new QuizGenerationCompletedEvent(jobId, consolidatedQuiz.getId()));

        } catch (Exception e) {
            log.error("Quiz generation failed for job {}: {}", jobId, e.getMessage(), e);
            jobService.markJobFailed(jobId, e.getMessage());
        }
    }

    @Async("aiTaskExecutor")
    @Transactional
    private CompletableFuture<List<Question>> generateQuestionsFromChunkWithJob(
            DocumentChunk chunk,
            Map<QuestionType, Integer> questionsPerType,
            Difficulty difficulty,
            UUID jobId
    ) {
        return CompletableFuture.supplyAsync(() -> {
            List<Question> allQuestions = new ArrayList<>();
            
            for (Map.Entry<QuestionType, Integer> entry : questionsPerType.entrySet()) {
                QuestionType questionType = entry.getKey();
                int questionCount = entry.getValue();
                
                if (questionCount > 0) {
                    List<Question> questions = generateQuestionsByType(
                        chunk.getContent(), questionType, questionCount, difficulty
                    );
                    allQuestions.addAll(questions);
                }
            }
            
            return allQuestions;
        });
    }
}
```

---

## 🛡️ Exceptions & Error Handling

### Quiz-Related Exceptions
```java
// ResourceNotFoundException - When quiz not found
throw new ResourceNotFoundException("Quiz " + id + " not found");

// ValidationException - When quiz cannot be published
throw new ValidationException("Cannot publish quiz: " + String.join("; ", validationErrors));

// IllegalArgumentException - When quiz validation fails
throw new IllegalArgumentException("Cannot publish quiz without questions");

// AiServiceException - When AI generation fails
throw new AiServiceException("Failed to generate questions from chunk");

// DocumentNotFoundException - When document not found for generation
throw new DocumentNotFoundException("Document not found: " + documentId);
```

### Validation Logic
```java
private void validateQuizForPublishing(Quiz quiz) {
    List<String> validationErrors = new ArrayList<>();

    // Check if quiz has questions
    if (quiz.getQuestions().isEmpty()) {
        validationErrors.add("Cannot publish quiz without questions");
    }

    // Check minimum estimated time
    if (quiz.getEstimatedTime() == null || quiz.getEstimatedTime() < MINIMUM_ESTIMATED_TIME_MINUTES) {
        validationErrors.add("Quiz must have a minimum estimated time of " + MINIMUM_ESTIMATED_TIME_MINUTES + " minute(s)");
    }

    // Check if all questions have valid correct answers
    if (!quiz.getQuestions().isEmpty()) {
        validateQuestionsHaveCorrectAnswers(quiz, validationErrors);
    }

    if (!validationErrors.isEmpty()) {
        throw new IllegalArgumentException("Cannot publish quiz: " + String.join("; ", validationErrors));
    }
}
```

---

## 🔒 Security & Authorization

### Security Annotations
```java
// Admin-only operations
@PreAuthorize("hasRole('ADMIN')")

// Authentication required
@SecurityRequirement(name = "bearerAuth")

// Method-level security
@PreAuthorize("hasRole('ADMIN') or @permissionEvaluator.hasAccess(#quizId, authentication)")
```

### Permission Evaluation
```java
// Quiz ownership check
if (!quiz.getCreator().getUsername().equals(username)) {
    throw new AccessDeniedException("User not authorized to modify this quiz");
}

// Job ownership check
QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);
```

---

## 🧪 Testing & Validation

### Quiz Creation Flow
1. **Input Validation**: Validate CreateQuizRequest fields
2. **User Lookup**: Find user by username/email
3. **Category Resolution**: Use provided category or default to "General"
4. **Tag Resolution**: Validate and resolve all tag IDs
5. **Entity Creation**: Map DTO to entity with relationships
6. **Persistence**: Save quiz and return ID

### Quiz Update Flow
1. **Quiz Lookup**: Find quiz by ID with relationships
2. **Category Update**: Update category if provided
3. **Tag Update**: Update tags if provided
4. **Field Updates**: Update only provided fields
5. **Persistence**: Save updated quiz

### Quiz Publishing Flow
1. **Status Validation**: Check if quiz can be published
2. **Question Validation**: Ensure quiz has questions
3. **Time Validation**: Check minimum estimated time
4. **Content Validation**: Validate all question content
5. **Status Update**: Set status to PUBLISHED

### AI Generation Flow
1. **Job Creation**: Create generation job with estimates
2. **Document Validation**: Validate document exists and is processed
3. **Chunk Processing**: Process document chunks asynchronously
4. **Question Generation**: Generate questions for each chunk
5. **Quiz Creation**: Create individual and consolidated quizzes
6. **Job Completion**: Mark job as completed with results

---

## 🔄 End-to-End Flow

### Quiz Creation End-to-End
```
1. Client sends CreateQuizRequest to /api/v1/quizzes
2. QuizController validates request and calls QuizService
3. QuizServiceImpl creates Quiz entity using QuizMapper
4. QuizRepository saves quiz to database
5. QuizController returns quiz ID to client
```

### Quiz AI Generation End-to-End
```
1. Client sends GenerateQuizFromDocumentRequest to /api/v1/quizzes/generate-from-document
2. QuizController validates request and calls QuizService.startQuizGeneration()
3. QuizServiceImpl creates QuizGenerationJob and starts async generation
4. AiQuizGenerationService processes document chunks asynchronously
5. Questions are generated for each chunk using AI
6. Individual chunk quizzes and consolidated quiz are created
7. QuizGenerationJob is marked as completed
8. Client can poll /api/v1/quizzes/generation-status/{jobId} for progress
9. Client retrieves generated quiz from /api/v1/quizzes/generated-quiz/{jobId}
```

### Quiz Update End-to-End
```
1. Client sends UpdateQuizRequest to /api/v1/quizzes/{quizId}
2. QuizController validates request and calls QuizService.updateQuiz()
3. QuizServiceImpl finds quiz and updates fields using QuizMapper
4. QuizRepository saves updated quiz
5. QuizController returns updated QuizDto to client
```

### Quiz Publishing End-to-End
```
1. Client sends QuizStatusUpdateRequest to /api/v1/quizzes/{quizId}/status
2. QuizController calls QuizService.setStatus()
3. QuizServiceImpl validates quiz can be published
4. Quiz status is updated to PUBLISHED
5. QuizController returns updated QuizDto to client
```

This comprehensive document covers all aspects of quiz management in the QuizMaker application, from basic CRUD operations to complex AI-powered quiz generation workflows. 