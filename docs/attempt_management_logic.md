# Attempt Management Logic - End-to-End Analysis

## 📋 Table of Contents
1. [🏗️ Core Models & Entities](#core-models--entities)
2. [📊 DTOs & Data Transfer Objects](#dtos--data-transfer-objects)
3. [🎮 Enums & Status Management](#enums--status-management)
4. [🔧 Services & Business Logic](#services--business-logic)
5. [🎯 Controllers & API Endpoints](#controllers--api-endpoints)
6. [🗄️ Repositories & Data Access](#repositories--data-access)
7. [🔄 Mappers & Data Transformation](#mappers--data-transformation)
8. [📊 Scoring & Analytics](#scoring--analytics)
9. [🛡️ Security & Validation](#security--validation)
10. [🧪 Testing & Validation](#testing--validation)
11. [🔄 End-to-End Flow](#end-to-end-flow)

---

## 🏗️ Core Models & Entities

### Attempt Entity
```java
@Entity
@Table(name = "attempts")
public class Attempt {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "quiz_id", nullable = false)
    private Quiz quiz;

    @CreationTimestamp
    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AttemptStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 30)
    private AttemptMode mode;

    @OneToMany(mappedBy = "attempt", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Answer> answers = new ArrayList<>();

    @Column(name = "total_score")
    private Double totalScore;
}
```

### Answer Entity
```java
@Entity
@Table(name = "answers")
public class Answer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    private Attempt attempt;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "response", columnDefinition = "TEXT", nullable = false)
    private String response;

    @Column(name = "is_correct", nullable = false)
    private Boolean isCorrect;

    @Column(name = "score")
    private Double score;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;
}
```

---

## 📊 DTOs & Data Transfer Objects

### StartAttemptRequest
```java
@Schema(name = "StartAttemptRequest", description = "Request to start a quiz attempt with a specific mode")
public record StartAttemptRequest(
    @NotNull(message = "Mode must be provided")
    AttemptMode mode
) {}
```

### StartAttemptResponse
```java
@Schema(name = "StartAttemptResponse", description = "Response when starting an attempt")
public record StartAttemptResponse(
    UUID attemptId,
    QuestionForAttemptDto firstQuestion
) {}
```

### AttemptDto
```java
@Schema(name = "AttemptDto", description = "Summary information for an attempt")
public record AttemptDto(
    UUID attemptId,
    UUID quizId,
    UUID userId,
    Instant startedAt,
    AttemptStatus status,
    AttemptMode mode
) {}
```

### AttemptDetailsDto
```java
@Schema(name = "AttemptDetailsDto", description = "Detailed information about an attempt, including answers")
public record AttemptDetailsDto(
    UUID attemptId,
    UUID quizId,
    UUID userId,
    Instant startedAt,
    Instant completedAt,
    AttemptStatus status,
    AttemptMode mode,
    List<AnswerSubmissionDto> answers
) {}
```

### AnswerSubmissionRequest
```java
@Schema(name = "AnswerSubmissionRequest", description = "Payload for submitting an answer to a specific question")
public record AnswerSubmissionRequest(
    @NotNull(message = "Question ID is required")
    UUID questionId,

    @NotNull(message = "Response payload must not be null")
    JsonNode response
) {}
```

### AnswerSubmissionDto
```java
@Schema(name = "AnswerSubmissionDto", description = "Result of submitting an answer to a question")
public record AnswerSubmissionDto(
    UUID answerId,
    UUID questionId,
    Boolean isCorrect,
    Double score,
    Instant answeredAt,
    QuestionForAttemptDto nextQuestion
) {}
```

### BatchAnswerSubmissionRequest
```java
@Schema(name = "BatchAnswerSubmissionRequest", description = "Payload for submitting multiple answers at once")
public record BatchAnswerSubmissionRequest(
    @NotEmpty(message = "At least one answer must be submitted")
    List<@Valid AnswerSubmissionRequest> answers
) {}
```

### AttemptResultDto
```java
@Schema(name = "AttemptResultDto", description = "Summary of results after completing an attempt")
public record AttemptResultDto(
    UUID attemptId,
    UUID quizId,
    UUID userId,
    Instant startedAt,
    Instant completedAt,
    Double totalScore,
    Integer correctCount,
    Integer totalQuestions,
    List<AnswerSubmissionDto> answers
) {}
```

### AttemptStatsDto
```java
@Schema(name = "AttemptStatsDto", description = "Detailed statistics for a quiz attempt")
public record AttemptStatsDto(
    UUID attemptId,
    Duration totalTime,
    Duration averageTimePerQuestion,
    Integer questionsAnswered,
    Integer correctAnswers,
    Double accuracyPercentage,
    Double completionPercentage,
    List<QuestionTimingStatsDto> questionTimings,
    Instant startedAt,
    Instant completedAt
) {}
```

### QuizResultSummaryDto
```java
public record QuizResultSummaryDto(
    UUID quizId,
    Long attemptsCount,
    Double averageScore,
    Double bestScore,
    Double worstScore,
    Double passRate,
    List<QuestionStatsDto> questionStats
) {}
```

### LeaderboardEntryDto
```java
@Schema(name = "LeaderboardEntryDto", description = "Quiz leaderboard entry")
public record LeaderboardEntryDto(
    UUID userId,
    String username,
    Double bestScore
) {}
```

---

## 🎮 Enums & Status Management

### AttemptStatus
```java
public enum AttemptStatus {
    IN_PROGRESS,
    COMPLETED,
    ABANDONED,
    PAUSED
}
```

### AttemptMode
```java
public enum AttemptMode {
    ONE_BY_ONE,
    ALL_AT_ONCE,
    TIMED
}
```

---

## 🔧 Services & Business Logic

### AttemptService Interface
```java
public interface AttemptService {
    StartAttemptResponse startAttempt(String username, UUID quizId, AttemptMode mode);
    Page<AttemptDto> getAttempts(String username, Pageable pageable, UUID quizId, UUID userId);
    AttemptDetailsDto getAttemptDetail(String username, UUID attemptId);
    AnswerSubmissionDto submitAnswer(String username, UUID attemptId, AnswerSubmissionRequest request);
    List<AnswerSubmissionDto> submitBatch(String username, UUID attemptId, BatchAnswerSubmissionRequest request);
    AttemptResultDto completeAttempt(String username, UUID attemptId);
    QuizResultSummaryDto getQuizResultSummary(UUID quizId);
    List<LeaderboardEntryDto> getQuizLeaderboard(UUID quizId, int top);
    List<QuestionForAttemptDto> getShuffledQuestions(UUID quizId, String username);
    AttemptStatsDto getAttemptStats(UUID attemptId);
    AttemptDto pauseAttempt(String username, UUID attemptId);
    AttemptDto resumeAttempt(String username, UUID attemptId);
    List<AttemptDto> getAttemptsByDateRange(LocalDate start, LocalDate end);
    void flagSuspiciousActivity(UUID attemptId, String reason);
}
```

### AttemptServiceImpl Key Methods
```java
@Service
@Transactional
@RequiredArgsConstructor
public class AttemptServiceImpl implements AttemptService {

    @Override
    public StartAttemptResponse startAttempt(String username, UUID quizId, AttemptMode mode) {
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Quiz quiz = quizRepository.findById(quizId)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

        Attempt attempt = new Attempt();
        attempt.setUser(user);
        attempt.setQuiz(quiz);
        attempt.setMode(mode);
        attempt.setStatus(AttemptStatus.IN_PROGRESS);

        Attempt saved = attemptRepository.save(attempt);
        Question first = quiz.getQuestions().stream().findFirst().orElse(null);
        QuestionForAttemptDto dto = first != null ? safeQuestionMapper.toSafeDto(first) : null;

        return new StartAttemptResponse(saved.getId(), dto);
    }

    @Override
    @Transactional
    public AnswerSubmissionDto submitAnswer(String username, UUID attemptId, AnswerSubmissionRequest request) {
        Attempt attempt = attemptRepository.findFullyLoadedById(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot submit answer to attempt with status " + attempt.getStatus());
        }

        // Timer validation for TIMED mode
        if (attempt.getMode() == AttemptMode.TIMED && attempt.getQuiz().getIsTimerEnabled()) {
            Instant timeout = attempt.getStartedAt()
                    .plusSeconds(attempt.getQuiz().getTimerDuration() * 60L);
            if (Instant.now().isAfter(timeout)) {
                attempt.setStatus(AttemptStatus.ABANDONED);
                attempt.setCompletedAt(Instant.now());
                attemptRepository.save(attempt);
                throw new IllegalStateException("Attempt has timed out");
            }
        }

        Question question = questionRepository.findById(request.questionId())
                .orElseThrow(() -> new ResourceNotFoundException("Question " + request.questionId() + " not found"));

        // Validate question belongs to quiz
        boolean belongs = attempt.getQuiz().getQuestions().stream()
                .map(Question::getId)
                .anyMatch(id -> id.equals(question.getId()));
        if (!belongs) {
            throw new ResourceNotFoundException("Question " + question.getId() + " is not part of Quiz " + attempt.getQuiz().getId());
        }

        // Prevent duplicate answers
        boolean already = attempt.getAnswers().stream()
                .map(a -> a.getQuestion().getId())
                .anyMatch(id -> id.equals(question.getId()));
        if (already) {
            throw new IllegalStateException("Already answered question " + question.getId() + " in this attempt");
        }

        var handler = handlerFactory.getHandler(question.getType());
        Answer answer = handler.handle(attempt, question, request);
        answer = answerRepository.save(answer);

        var baseDto = answerMapper.toDto(answer);
        QuestionForAttemptDto nextQuestion = null;
        if (attempt.getMode() == AttemptMode.ONE_BY_ONE) {
            Set<UUID> done = attempt.getAnswers().stream()
                    .map(a -> a.getQuestion().getId())
                    .collect(Collectors.toSet());
            nextQuestion = attempt.getQuiz().getQuestions().stream()
                    .filter(q -> !done.contains(q.getId()))
                    .findFirst()
                    .map(safeQuestionMapper::toSafeDto)
                    .orElse(null);
        }

        return new AnswerSubmissionDto(
                baseDto.answerId(),
                baseDto.questionId(),
                baseDto.isCorrect(),
                baseDto.score(),
                baseDto.answeredAt(),
                nextQuestion
        );
    }

    @Override
    @Transactional
    public AttemptResultDto completeAttempt(String username, UUID attemptId) {
        Attempt attempt = attemptRepository.findByIdWithAllRelations(attemptId)
                .orElseThrow(() -> new ResourceNotFoundException("Attempt " + attemptId + " not found"));
        enforceOwnership(attempt, username);

        if (attempt.getStatus() != AttemptStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot complete attempt with status " + attempt.getStatus());
        }

        double totalScore = scoringService.computeAndPersistScore(attempt);
        long correctCount = scoringService.countCorrect(attempt);
        int totalQ = attempt.getQuiz().getQuestions().size();

        attempt.setStatus(AttemptStatus.COMPLETED);
        attempt.setCompletedAt(Instant.now());

        return attemptMapper.toResultDto(attempt, correctCount, totalQ);
    }

    private void enforceOwnership(Attempt attempt, String username) {
        if (!attempt.getUser().getUsername().equals(username)) {
            throw new AccessDeniedException("User not authorized to access this attempt");
        }
    }
}
```

### ScoringService
```java
@Service
@RequiredArgsConstructor
public class ScoringService {

    @Transactional
    public double computeAndPersistScore(Attempt attempt) {
        double total = attempt.getAnswers().stream()
                .mapToDouble(a -> a.getScore() != null ? a.getScore() : 0.0)
                .sum();
        attempt.setTotalScore(total);
        return total;
    }

    public long countCorrect(Attempt attempt) {
        return attempt.getAnswers().stream()
                .filter(Answer::getIsCorrect)
                .count();
    }
}
```

---

## 🎯 Controllers & API Endpoints

### AttemptController
```java
@Tag(name = "Attempts", description = "Endpoints for managing quiz attempts")
@RestController
@RequestMapping("/api/v1/attempts")
@RequiredArgsConstructor
@Validated
public class AttemptController {

    @PostMapping("/quizzes/{quizId}")
    public ResponseEntity<StartAttemptResponse> startAttempt(
            @PathVariable UUID quizId,
            @RequestBody(required = false) @Valid StartAttemptRequest request,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptMode mode = (request != null && request.mode() != null)
                ? request.mode()
                : AttemptMode.ALL_AT_ONCE;
        StartAttemptResponse dto = attemptService.startAttempt(username, quizId, mode);
        return ResponseEntity.status(HttpStatus.CREATED).body(dto);
    }

    @GetMapping
    public ResponseEntity<Page<AttemptDto>> listAttempts(
            @Min(0) @RequestParam(name = "page", defaultValue = "0") int page,
            @Min(1) @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "quizId", required = false) UUID quizId,
            @RequestParam(name = "userId", required = false) UUID userId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        Page<AttemptDto> result = attemptService.getAttempts(
                username,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt")),
                quizId,
                userId
        );
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{attemptId}")
    public ResponseEntity<AttemptDetailsDto> getAttempt(
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptDetailsDto details = attemptService.getAttemptDetail(username, attemptId);
        return ResponseEntity.ok(details);
    }

    @PostMapping("/{attemptId}/answers")
    public ResponseEntity<AnswerSubmissionDto> submitAnswer(
            @PathVariable UUID attemptId,
            @RequestBody @Valid AnswerSubmissionRequest request,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AnswerSubmissionDto result = attemptService.submitAnswer(username, attemptId, request);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{attemptId}/answers/batch")
    public ResponseEntity<List<AnswerSubmissionDto>> submitBatch(
            @PathVariable UUID attemptId,
            @RequestBody @Valid BatchAnswerSubmissionRequest request,
            Authentication authentication
    ) {
        String username = authentication.getName();
        List<AnswerSubmissionDto> answers = attemptService.submitBatch(username, attemptId, request);
        return ResponseEntity.ok(answers);
    }

    @PostMapping("/{attemptId}/complete")
    public ResponseEntity<AttemptResultDto> completeAttempt(
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptResultDto result = attemptService.completeAttempt(username, attemptId);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/{attemptId}/stats")
    public ResponseEntity<AttemptStatsDto> getAttemptStats(
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptStatsDto stats = attemptService.getAttemptStats(attemptId);
        return ResponseEntity.ok(stats);
    }

    @PostMapping("/{attemptId}/pause")
    public ResponseEntity<AttemptDto> pauseAttempt(
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptDto result = attemptService.pauseAttempt(username, attemptId);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{attemptId}/resume")
    public ResponseEntity<AttemptDto> resumeAttempt(
            @PathVariable UUID attemptId,
            Authentication authentication
    ) {
        String username = authentication.getName();
        AttemptDto result = attemptService.resumeAttempt(username, attemptId);
        return ResponseEntity.ok(result);
    }
}
```

---

## 🗄️ Repositories & Data Access

### AttemptRepository
```java
@Repository
public interface AttemptRepository extends JpaRepository<Attempt, UUID> {

    @Query(value = """
            SELECT a
            FROM Attempt a
            JOIN FETCH a.quiz q
            JOIN FETCH a.user u
            WHERE (:quizId IS NULL OR q.id = :quizId)
              AND (:userId IS NULL OR u.id = :userId)
            """,
            countQuery = """
                    SELECT COUNT(a)
                    FROM Attempt a
                    WHERE (:quizId IS NULL OR a.quiz.id = :quizId)
                      AND (:userId IS NULL OR a.user.id = :userId)
                    """
    )
    Page<Attempt> findAllByQuizAndUserEager(
            @Param("quizId") UUID quizId,
            @Param("userId") UUID userId,
            Pageable pageable
    );

    @Query("""
            SELECT a
            FROM Attempt a
            LEFT JOIN FETCH a.answers ans
            LEFT JOIN FETCH ans.question q
            WHERE a.id = :id
            """)
    Optional<Attempt> findByIdWithAnswersAndQuestion(@Param("id") UUID id);

    @Query("""
            SELECT a
            FROM Attempt a
            LEFT JOIN FETCH a.answers ans
            LEFT JOIN FETCH ans.question q
            LEFT JOIN FETCH a.quiz quiz
            LEFT JOIN FETCH quiz.questions qlist
            WHERE a.id = :id
            """)
    Optional<Attempt> findByIdWithAllRelations(@Param("id") UUID id);

    @Query("""
            SELECT COUNT(a), AVG(a.totalScore), MAX(a.totalScore), MIN(a.totalScore)
            FROM Attempt a
            WHERE a.quiz.id = :quizId
              AND a.status = 'COMPLETED'
            """)
    List<Object[]> getAttemptAggregateData(@Param("quizId") UUID quizId);

    @Query("""
            SELECT u.id, u.username, MAX(a.totalScore)
            FROM Attempt a
            JOIN a.user u
            WHERE a.quiz.id = :quizId
              AND a.status = 'COMPLETED'
            GROUP BY u.id, u.username
            ORDER BY MAX(a.totalScore) DESC
            """)
    List<Object[]> getLeaderboardData(@Param("quizId") UUID quizId);

    List<Attempt> findByQuiz_Id(UUID quizId);
    List<Attempt> findByStartedAtBetween(Instant start, Instant end);
}
```

---

## 🔄 Mappers & Data Transformation

### AttemptMapper
```java
@Component
@RequiredArgsConstructor
public class AttemptMapper {

    private final AnswerMapper answerMapper;

    public AttemptDto toDto(Attempt attempt) {
        return new AttemptDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getStatus(),
                attempt.getMode()
        );
    }

    public AttemptDetailsDto toDetailDto(Attempt attempt) {
        List<AnswerSubmissionDto> answers = attempt.getAnswers().stream()
                .sorted((a1, a2) -> a1.getAnsweredAt().compareTo(a2.getAnsweredAt()))
                .map(answerMapper::toDto)
                .toList();

        return new AttemptDetailsDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getStatus(),
                attempt.getMode(),
                answers
        );
    }

    public AttemptResultDto toResultDto(Attempt attempt, long correctCount, int totalQuestions) {
        List<AnswerSubmissionDto> answers = attempt.getAnswers().stream()
                .sorted(Comparator.comparing(Answer::getAnsweredAt))
                .map(answerMapper::toDto)
                .toList();

        return new AttemptResultDto(
                attempt.getId(),
                attempt.getQuiz().getId(),
                attempt.getUser().getId(),
                attempt.getStartedAt(),
                attempt.getCompletedAt(),
                attempt.getTotalScore(),
                Math.toIntExact(correctCount),
                totalQuestions,
                answers
        );
    }
}
```

### SafeQuestionMapper
```java
@Component
public class SafeQuestionMapper {
    
    public QuestionForAttemptDto toSafeDto(Question question) {
        // Remove correct answers and sensitive information
        JsonNode safeContent = createSafeContent(question.getContent(), question.getType());
        
        return new QuestionForAttemptDto(
                question.getId(),
                question.getType(),
                question.getDifficulty(),
                question.getQuestionText(),
                safeContent,
                question.getHint(),
                question.getAttachmentUrl()
        );
    }
    
    private JsonNode createSafeContent(String content, QuestionType type) {
        // Implementation to strip correct answers based on question type
        // Returns safe content without revealing correct answers
    }
}
```

---

## 📊 Scoring & Analytics

### Quiz Result Summary
```java
@Override
@Transactional(readOnly = true)
public QuizResultSummaryDto getQuizResultSummary(UUID quizId) {
    Quiz quiz = quizRepository.findByIdWithQuestions(quizId)
            .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

    List<Object[]> rows = attemptRepository.getAttemptAggregateData(quizId);
    Object[] agg = rows.isEmpty() ? new Object[]{0L, null, null, null} : rows.get(0);

    long attemptsCount = ((Number) agg[0]).longValue();
    double averageScore = agg[1] != null ? ((Number) agg[1]).doubleValue() : 0.0;
    double bestScore = agg[2] != null ? ((Number) agg[2]).doubleValue() : 0.0;
    double worstScore = agg[3] != null ? ((Number) agg[3]).doubleValue() : 0.0;

    List<Attempt> completed = attemptRepository.findByQuiz_Id(quizId).stream()
            .filter(a -> a.getStatus() == AttemptStatus.COMPLETED)
            .toList();

    long passing = completed.stream()
            .filter(a -> {
                long correct = a.getAnswers().stream()
                        .filter(ans -> Boolean.TRUE.equals(ans.getIsCorrect()))
                        .count();
                int totalQ = quiz.getQuestions().size();
                return totalQ > 0 && ((double) correct / totalQ) >= 0.5;
            })
            .count();
    double passRate = attemptsCount > 0 ? ((double) passing / attemptsCount) * 100.0 : 0.0;

    List<QuestionStatsDto> questionStats = quiz.getQuestions().stream()
            .map(q -> {
                UUID qid = q.getId();
                long asked = completed.stream()
                        .filter(a -> a.getAnswers().stream()
                                .anyMatch(ans -> ans.getQuestion().getId().equals(qid)))
                        .count();
                long correct = completed.stream()
                        .flatMap(a -> a.getAnswers().stream())
                        .filter(ans -> ans.getQuestion().getId().equals(qid)
                                && Boolean.TRUE.equals(ans.getIsCorrect()))
                        .count();
                double rate = asked > 0 ? ((double) correct / asked) * 100.0 : 0.0;
                return new QuestionStatsDto(qid, asked, correct, rate);
            })
            .toList();

    return new QuizResultSummaryDto(
            quizId, attemptsCount, averageScore, bestScore, worstScore, passRate, questionStats
    );
}
```

### Leaderboard Generation
```java
@Override
@Transactional
public List<LeaderboardEntryDto> getQuizLeaderboard(UUID quizId, int top) {
    if (top <= 0) {
        return List.of();
    }

    quizRepository.findById(quizId)
            .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));

    List<Object[]> rows = attemptRepository.getLeaderboardData(quizId);
    return rows.stream()
            .limit(top)
            .map(r -> new LeaderboardEntryDto(
                    (UUID) r[0],
                    (String) r[1],
                    r[2] != null ? ((Number) r[2]).doubleValue() : 0.0
            ))
            .toList();
}
```

---

## 🛡️ Security & Validation

### Ownership Enforcement
```java
private void enforceOwnership(Attempt attempt, String username) {
    if (!attempt.getUser().getUsername().equals(username)) {
        throw new AccessDeniedException("User not authorized to access this attempt");
    }
}
```

### Timer Validation
```java
if (attempt.getMode() == AttemptMode.TIMED && attempt.getQuiz().getIsTimerEnabled()) {
    Instant timeout = attempt.getStartedAt()
            .plusSeconds(attempt.getQuiz().getTimerDuration() * 60L);
    if (Instant.now().isAfter(timeout)) {
        attempt.setStatus(AttemptStatus.ABANDONED);
        attempt.setCompletedAt(Instant.now());
        attemptRepository.save(attempt);
        throw new IllegalStateException("Attempt has timed out");
    }
}
```

### Duplicate Answer Prevention
```java
boolean already = attempt.getAnswers().stream()
        .map(a -> a.getQuestion().getId())
        .anyMatch(id -> id.equals(question.getId()));
if (already) {
    throw new IllegalStateException("Already answered question " + question.getId() + " in this attempt");
}
```

---

## 🧪 Testing & Validation

### Attempt Creation Flow
1. **User Validation**: Verify user exists by username/email
2. **Quiz Validation**: Verify quiz exists and is accessible
3. **Attempt Creation**: Create attempt with IN_PROGRESS status
4. **First Question**: Return safe version of first question (no correct answers)
5. **Response**: Return attempt ID and first question

### Answer Submission Flow
1. **Attempt Validation**: Verify attempt exists and belongs to user
2. **Status Check**: Ensure attempt is IN_PROGRESS
3. **Timer Check**: Validate timer constraints for TIMED mode
4. **Question Validation**: Verify question belongs to quiz
5. **Duplicate Check**: Prevent answering same question twice
6. **Answer Processing**: Use question handler to process response
7. **Score Calculation**: Calculate and store answer score
8. **Next Question**: Return next question for ONE_BY_ONE mode

### Attempt Completion Flow
1. **Status Validation**: Ensure attempt is IN_PROGRESS
2. **Score Calculation**: Compute total score using ScoringService
3. **Correct Count**: Count correct answers
4. **Status Update**: Set status to COMPLETED
5. **Completion Time**: Set completedAt timestamp
6. **Result Return**: Return comprehensive result DTO

---

## 🔄 End-to-End Flow

### Attempt Start End-to-End
```
1. Client sends StartAttemptRequest to /api/v1/attempts/quizzes/{quizId}
2. AttemptController validates request and calls AttemptService
3. AttemptServiceImpl creates Attempt entity with IN_PROGRESS status
4. First question is retrieved and converted to safe DTO (no correct answers)
5. AttemptController returns StartAttemptResponse with attempt ID and first question
```

### Answer Submission End-to-End
```
1. Client sends AnswerSubmissionRequest to /api/v1/attempts/{attemptId}/answers
2. AttemptController validates request and calls AttemptService.submitAnswer()
3. AttemptServiceImpl validates attempt ownership and status
4. Question handler processes the answer and calculates score
5. Answer is saved to database
6. For ONE_BY_ONE mode, next question is determined and returned safely
7. AttemptController returns AnswerSubmissionDto with results
```

### Attempt Completion End-to-End
```
1. Client sends POST to /api/v1/attempts/{attemptId}/complete
2. AttemptController calls AttemptService.completeAttempt()
3. AttemptServiceImpl validates attempt can be completed
4. ScoringService calculates total score and correct count
5. Attempt status is updated to COMPLETED
6. AttemptController returns AttemptResultDto with final results
```

### Analytics Generation End-to-End
```
1. Client requests quiz results via /api/v1/quizzes/{quizId}/results
2. AttemptService.getQuizResultSummary() aggregates attempt data
3. Database queries calculate statistics (counts, averages, pass rates)
4. Question-level statistics are computed for each question
5. QuizController returns QuizResultSummaryDto with comprehensive analytics
```

This comprehensive document covers all aspects of attempt management in the QuizMaker application, from basic attempt creation to complex analytics and scoring workflows. 