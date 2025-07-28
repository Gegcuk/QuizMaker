# Question-Related Logic - End-to-End Analysis

## 📋 Table of Contents
1. [🏗️ Core Models & Entities](#core-models--entities)
2. [📊 DTOs & Data Transfer Objects](#dtos--data-transfer-objects)
3. [🎮 Question Handlers & Factory](#question-handlers--factory)
4. [🔧 Services & Business Logic](#services--business-logic)
5. [🎯 Controllers & API Endpoints](#controllers--api-endpoints)
6. [🗄️ Repositories & Data Access](#repositories--data-access)
7. [🔄 Mappers & Data Transformation](#mappers--data-transformation)
8. [🛡️ Exceptions & Error Handling](#exceptions--error-handling)
9. [🔒 Security & Safe Content](#security--safe-content)
10. [🤖 AI Integration & Parsing](#ai-integration--parsing)
11. [🧪 Testing & Validation](#testing--validation)
12. [🔄 End-to-End Flow](#end-to-end-flow)

---

## 🏗️ Core Models & Entities

### Question Entity
```java
@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "questions")
@SQLDelete(sql = "UPDATE questions SET is_deleted = true, deleted_at = CURRENT_TIMESTAMP WHERE id = ?")
@SQLRestriction("is_deleted = false")
public class Question {
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    QuestionType type;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "difficulty", nullable = false, length = 20)
    private Difficulty difficulty;

    @Column(name = "question", nullable = false, length = 1000)
    private String questionText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content", columnDefinition = "json", nullable = false)
    private String content;

    @Column(name = "hint", length = 500)
    private String hint;

    @Column(name = "explanation", length = 2000)
    private String explanation;

    @Column(name = "attachment_url", length = 2048)
    private String attachmentUrl;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "is_deleted", nullable = false, columnDefinition = "boolean default false")
    private Boolean isDeleted = false;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
        name = "quiz_questions",
        joinColumns = @JoinColumn(name = "question_id", nullable = false),
        inverseJoinColumns = @JoinColumn(name = "quiz_id", nullable = false)
    )
    private List<Quiz> quizId = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    @JoinTable(
        name = "question_tags",
        joinColumns = @JoinColumn(name = "question_id", nullable = false),
        inverseJoinColumns = @JoinColumn(name = "tag_id", nullable = false)
    )
    private List<Tag> tags = new ArrayList<>();

    @PrePersist
    public void prePersist() {
        if (isDeleted == null) {
            isDeleted = false;
        }
    }

    @PreRemove
    private void onSoftDelete() {
        this.isDeleted = true;
        this.deletedAt = Instant.now();
    }
}
```

**Soft-Delete Implementation:**
- `@SQLDelete`: Automatically converts `delete()` calls to soft deletes
- `@SQLRestriction`: Filters out deleted questions from all queries
- Java-level initialization prevents null pointer exceptions
- `@PrePersist` ensures proper initialization on new entities
- `@PreRemove` manually sets `isDeleted = true` and `deletedAt` timestamp in Java object

### Answer Entity
```java
@Entity
@Getter
@Setter
@Table(name = "answers")
public class Answer {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "attempt_id", nullable = false)
    private Attempt attempt;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "response", columnDefinition = "json", nullable = false)
    private String response;

    @Column(name = "is_correct")
    private Boolean isCorrect;

    @Column(name = "score")
    private Double score;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;
}
```

### Enums
```java
public enum QuestionType {
    MCQ_SINGLE, MCQ_MULTI, OPEN, FILL_GAP, COMPLIANCE, TRUE_FALSE, ORDERING, HOTSPOT
}

public enum Difficulty {
    HARD, MEDIUM, EASY
}
```

---

## 📊 DTOs & Data Transfer Objects

### Core Question DTOs

#### CreateQuestionRequest
```java
@Schema(name = "CreateQuestionRequest", description = "Payload for creating a new question")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class CreateQuestionRequest implements QuestionContentRequest {
    @Schema(description = "Type of the question", requiredMode = Schema.RequiredMode.REQUIRED, example = "TRUE_FALSE")
    @NotNull(message = "Type must not be null")
    private QuestionType type;

    @Schema(description = "Difficulty level", requiredMode = Schema.RequiredMode.REQUIRED, example = "EASY")
    @NotNull(message = "Difficulty must not be null")
    private Difficulty difficulty;

    @Schema(description = "Question text", requiredMode = Schema.RequiredMode.REQUIRED, example = "What is the capital of France?")
    @NotBlank(message = "Question text must not be blank")
    @Size(min = 3, max = 1000, message = "Question text length must be between 3 and 1000 characters")
    private String questionText;

    @Schema(description = "Content JSON specific to the question type", requiredMode = Schema.RequiredMode.REQUIRED, type = "object")
    @NotNull(message = "Content must not be null")
    private JsonNode content;

    @Schema(description = "Optional hint for the question", example = "Think of the Eiffel Tower")
    @Size(max = 500, message = "Hint length must be less than 500 characters")
    private String hint;

    @Schema(description = "Optional explanation for the answer", example = "Paris is the capital of France.")
    @Size(max = 2000, message = "Explanation must be less than 2000 characters")
    private String explanation;

    @Schema(description = "Optional URL for an attachment", example = "http://example.com/image.png")
    @Size(max = 2048, message = "URL length is limited by 2048 characters")
    private String attachmentUrl;

    @Schema(description = "List of quiz IDs to associate this question with", example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]")
    private List<UUID> quizIds = new ArrayList<>();

    @Schema(description = "List of tag IDs to associate this question with")
    private List<UUID> tagIds = new ArrayList<>();
}
```

#### QuestionDto
```java
@Schema(name = "QuestionDto", description = "Data transfer object representing a question")
@Getter
@Setter
public class QuestionDto {
    @Schema(description = "UUID of the question", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Question type", example = "MCQ_SINGLE")
    private QuestionType type;

    @Schema(description = "Difficulty level", example = "MEDIUM")
    private Difficulty difficulty;

    @Schema(description = "Question text", example = "Select the correct option:")
    private String questionText;

    @Schema(description = "Content JSON for this question", type = "object")
    private JsonNode content;

    @Schema(description = "Optional hint text", example = "Remember the order")
    private String hint;

    @Schema(description = "Optional explanation text", example = "Because that option matches the criteria")
    private String explanation;

    @Schema(description = "Optional attachment URL", example = "http://example.com/diagram.png")
    private String attachmentUrl;

    @Schema(description = "Timestamp when created", example = "2025-05-21T14:30:00Z")
    private Instant createdAt;

    @Schema(description = "Timestamp when last updated", example = "2025-05-22T09:15:00Z")
    private Instant updatedAt;

    @Schema(description = "List of associated quiz UUIDs", example = "[\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"]")
    private List<UUID> quizIds;

    @Schema(description = "List of associated tag UUIDs")
    private List<UUID> tagIds;
}
```

#### QuestionForAttemptDto (Safe for Users)
```java
@Schema(name = "QuestionForAttemptDto", description = "Question data safe for users during quiz attempts (no correct answers exposed)")
@Getter
@Setter
public class QuestionForAttemptDto {
    @Schema(description = "Question UUID", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
    private UUID id;

    @Schema(description = "Question type", example = "MCQ_SINGLE")
    private QuestionType type;

    @Schema(description = "Difficulty level", example = "MEDIUM")
    private Difficulty difficulty;

    @Schema(description = "Question text", example = "Select the correct option:")
    private String questionText;

    @Schema(description = "Safe content without correct answers", type = "object")
    private JsonNode safeContent;

    @Schema(description = "Optional hint", example = "Think carefully about the options")
    private String hint;

    @Schema(description = "Optional attachment URL", example = "http://example.com/image.png")
    private String attachmentUrl;
}
```

### Content Request Interfaces
```java
public interface QuestionContentRequest {
    QuestionType getType();
    JsonNode getContent();
}

public record EntityQuestionContentRequest(
    QuestionType type,
    JsonNode content
) implements QuestionContentRequest {
    @Override
    public QuestionType getType() {
        return type;
    }

    @Override
    public JsonNode getContent() {
        return content;
    }
}
```

---

## 🎮 Question Handlers & Factory

### QuestionHandlerFactory
```java
@Component
@RequiredArgsConstructor
public class QuestionHandlerFactory {
    private final Map<QuestionType, QuestionHandler> handlerMap = new EnumMap<>(QuestionType.class);

    private final McqSingleHandler mcqSingleHandler;
    private final TrueFalseHandler trueFalseHandler;
    private final ComplianceHandler complianceHandler;
    private final FillGapHandler fillGapHandler;
    private final HotspotHandler hotspotHandler;
    private final McqMultiHandler mcqMultiHandler;
    private final OpenQuestionHandler openQuestionHandler;
    private final OrderingHandler orderingHandler;

    @PostConstruct
    private void init() {
        handlerMap.put(QuestionType.MCQ_SINGLE, mcqSingleHandler);
        handlerMap.put(QuestionType.MCQ_MULTI, mcqMultiHandler);
        handlerMap.put(QuestionType.COMPLIANCE, complianceHandler);
        handlerMap.put(QuestionType.TRUE_FALSE, trueFalseHandler);
        handlerMap.put(QuestionType.FILL_GAP, fillGapHandler);
        handlerMap.put(QuestionType.HOTSPOT, hotspotHandler);
        handlerMap.put(QuestionType.OPEN, openQuestionHandler);
        handlerMap.put(QuestionType.ORDERING, orderingHandler);
    }

    public QuestionHandler getHandler(QuestionType type) {
        QuestionHandler questionHandler = handlerMap.get(type);
        if (questionHandler == null) {
            throw new UnsupportedOperationException("No handler for type " + type);
        }
        return questionHandler;
    }
}
```

### Abstract QuestionHandler
```java
public abstract class QuestionHandler {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    public abstract void validateContent(QuestionContentRequest request) throws ValidationException;

    public Answer handle(Attempt attempt, Question question, AnswerSubmissionRequest request) {
        JsonNode content;
        try {
            content = objectMapper.readTree(question.getContent());
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Malformed question JSON for question " + question.getId(),
                e
            );
        }

        var qc = new EntityQuestionContentRequest(question.getType(), content);
        validateContent(qc);

        Answer answer = doHandle(attempt, question, content, request.response());
        answer.setAttempt(attempt);
        answer.setQuestion(question);
        answer.setResponse(request.response().toString());
        answer.setAnsweredAt(Instant.now());
        attempt.getAnswers().add(answer);
        return answer;
    }

    protected abstract Answer doHandle(
        Attempt attempt,
        Question question,
        JsonNode content,
        JsonNode response
    );
}
```

### Handler Implementations

#### MCQ Single Handler
```java
@Component
public class McqSingleHandler extends QuestionHandler {
    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for MCQ_SINGLE question");
        }

        JsonNode options = root.get("options");
        if (options == null || !options.isArray() || options.size() < 2) {
            throw new ValidationException("MCQ_SINGLE must have at least 2 options");
        }

        long correctCount = 0;
        Set<String> ids = new java.util.HashSet<>();
        for (JsonNode option : options) {
            if (!option.has("id") || !option.has("text") || option.get("text").asText().isBlank()) {
                throw new ValidationException("Each option needs an 'id' and non-empty 'text'");
            }
            String id = option.get("id").asText();
            if (ids.contains(id)) {
                throw new ValidationException("Option IDs must be unique, found duplicate ID: " + id);
            }
            ids.add(id);
            if (option.has("correct") && option.get("correct").asBoolean()) {
                correctCount++;
            }
        }
        if (correctCount != 1) {
            throw new ValidationException("MCQ_SINGLE must have exactly 1 correct answer");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt, Question question, JsonNode content, JsonNode response) {
        String correctAnswer = null;
        for (JsonNode option : content.get("options")) {
            if (option.has("correct") && option.get("correct").asBoolean()) {
                correctAnswer = option.get("id").asText();
                break;
            }
        }
        
        JsonNode userAnswerNode = response.get("answer");
        String userAnswer = userAnswerNode != null && userAnswerNode.isTextual() ? userAnswerNode.asText() : "";
        boolean isCorrect = correctAnswer != null && correctAnswer.equals(userAnswer);
        
        Answer answer = new Answer();
        answer.setIsCorrect(isCorrect);
        answer.setScore(isCorrect ? 1.0 : 0.0);
        return answer;
    }
}
```

#### True/False Handler
```java
@Component
public class TrueFalseHandler extends QuestionHandler {
    @Override
    public void validateContent(QuestionContentRequest request) throws ValidationException {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for TRUE_FALSE question");
        }

        JsonNode answer = root.get("answer");
        if (answer == null || !answer.isBoolean()) {
            throw new ValidationException("TRUE_FALSE requires an 'answer' boolean field");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt, Question question, JsonNode content, JsonNode response) {
        boolean correctAnswer = content.get("answer").asBoolean();
        JsonNode userAnswerNode = response.get("answer");
        boolean userAnswer = userAnswerNode != null && userAnswerNode.isBoolean() ? userAnswerNode.asBoolean() : false;
        boolean isCorrect = userAnswer == correctAnswer;
        
        Answer answer = new Answer();
        answer.setIsCorrect(isCorrect);
        answer.setScore(isCorrect ? 1.0 : 0.0);
        return answer;
    }
}
```

#### Open Question Handler
```java
@Component
public class OpenQuestionHandler extends QuestionHandler {
    @Override
    public void validateContent(QuestionContentRequest request) {
        JsonNode root = request.getContent();
        if (root == null || !root.isObject()) {
            throw new ValidationException("Invalid JSON for OPEN question");
        }

        JsonNode answer = root.get("answer");
        if (answer == null || answer.asText().isBlank()) {
            throw new ValidationException("OPEN question must have a non-empty 'answer' field");
        }
    }

    @Override
    protected Answer doHandle(Attempt attempt, Question question, JsonNode content, JsonNode response) {
        String correct = content.get("answer").asText().trim().toLowerCase();
        JsonNode givenNode = response.get("answer");
        String given = givenNode != null && givenNode.isTextual() ? givenNode.asText().trim().toLowerCase() : "";
        boolean isCorrect = correct.equals(given);

        Answer ans = new Answer();
        ans.setIsCorrect(isCorrect);
        ans.setScore(isCorrect ? 1.0 : 0.0);
        return ans;
    }
}
```

---

## 🔧 Services & Business Logic

### QuestionService Interface
```java
public interface QuestionService {
    UUID createQuestion(String username, CreateQuestionRequest questionDto);
    Page<QuestionDto> listQuestions(UUID quizId, Pageable pageable);
    QuestionDto getQuestion(UUID questionId);
    QuestionDto updateQuestion(String username, UUID questionId, UpdateQuestionRequest updateQuestionRequest);
    void deleteQuestion(String username, UUID questionId);
}
```

### QuestionServiceImpl
```java
@Service
@Transactional
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {
    private final QuestionRepository questionRepository;
    private final QuizRepository quizRepository;
    private final TagRepository tagRepository;
    private final QuestionHandlerFactory handlerFactory;

    @Override
    public UUID createQuestion(String username, CreateQuestionRequest questionDto) {
        // 1. Validate question content using appropriate handler
        QuestionHandler questionHandler = handlerFactory.getHandler(questionDto.getType());
        questionHandler.validateContent(questionDto);

        // 2. Resolve related entities (quizzes and tags)
        List<Quiz> quizzes = Optional.ofNullable(questionDto.getQuizIds())
            .orElse(Collections.emptyList())
            .stream()
            .map(id -> quizRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + id + " not found")))
            .toList();
            
        List<Tag> tags = Optional.ofNullable(questionDto.getTagIds())
            .orElse(Collections.emptyList())
            .stream()
            .map(id -> tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " not found")))
            .toList();

        // 3. Map to entity and save
        Question question = QuestionMapper.toEntity(questionDto, quizzes, tags);
        questionRepository.save(question);

        return question.getId();
    }

    @Override
    public Page<QuestionDto> listQuestions(UUID quizId, Pageable page) {
        Page<Question> retrievedPage = (quizId != null)
            ? questionRepository.findAllByQuizId_Id(quizId, page)
            : questionRepository.findAll(page);

        return retrievedPage.map(QuestionMapper::toDto);
    }

    @Override
    public QuestionDto getQuestion(UUID questionId) {
        Question q = questionRepository.findById(questionId)
            .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));
        return QuestionMapper.toDto(q);
    }

    @Override
    public QuestionDto updateQuestion(String username, UUID questionId, UpdateQuestionRequest request) {
        // 1. Validate updated content
        QuestionHandler questionHandler = handlerFactory.getHandler(request.getType());
        questionHandler.validateContent(request);

        // 2. Find existing question
        Question question = questionRepository.findById(questionId)
            .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));

        // 3. Resolve related entities
        List<Quiz> quizzes = Optional.ofNullable(request.getQuizIds())
            .orElse(Collections.emptyList())
            .stream()
            .map(id -> quizRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Quiz " + id + " not found")))
            .toList();
            
        List<Tag> tags = Optional.ofNullable(request.getTagIds())
            .orElse(Collections.emptyList())
            .stream()
            .map(id -> tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " not found")))
            .toList();

        // 4. Update entity and save
        QuestionMapper.updateEntity(question, request, quizzes, tags);
        Question updatedQuestion = questionRepository.save(question);

        return QuestionMapper.toDto(updatedQuestion);
    }

    @Override
    public void deleteQuestion(String username, UUID questionId) {
        Question question = questionRepository.findById(questionId)
            .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));
        questionRepository.delete(question);
    }
}
```

---

## 🎯 Controllers & API Endpoints

### QuestionController
```java
@Tag(name = "Questions", description = "Operations for managing quiz questions")
@RestController
@RequestMapping("/api/v1/questions")
@RequiredArgsConstructor
public class QuestionController {
    private final QuestionService questionService;

    @Operation(summary = "Create a question", description = "Add a new question (ADMIN only)", tags = {"Questions"})
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "Question created"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, UUID>> createQuestion(
        Authentication authentication,
        @RequestBody @Valid CreateQuestionRequest request
    ) {
        UUID id = questionService.createQuestion(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("questionId", id));
    }

    @Operation(summary = "List questions", description = "Get a page of questions, optionally filtered by quizId", tags = {"Questions"})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Page of questions returned")
    })
    @GetMapping
    public ResponseEntity<Page<QuestionDto>> getQuestions(
        @Parameter(in = ParameterIn.QUERY, description = "Filter by quiz UUID", required = false)
        @RequestParam(required = false) UUID quizId,
        @Parameter(in = ParameterIn.QUERY, description = "Page number (0-based)", example = "0")
        @RequestParam(defaultValue = "0") int pageNumber,
        @Parameter(in = ParameterIn.QUERY, description = "Page size", example = "20")
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable page = PageRequest.of(pageNumber, size, Sort.by("createdAt").descending());
        return ResponseEntity.ok(questionService.listQuestions(quizId, page));
    }

    @Operation(summary = "Get question by ID", description = "Retrieve a single question by its UUID", tags = {"Questions"})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Question returned"),
        @ApiResponse(responseCode = "404", description = "Question not found")
    })
    @GetMapping("/{id}")
    public ResponseEntity<QuestionDto> getQuestion(
        @Parameter(description = "UUID of the question", required = true)
        @PathVariable UUID id
    ) {
        return ResponseEntity.ok(questionService.getQuestion(id));
    }

    @Operation(summary = "Update question", description = "Update an existing question (ADMIN only)", tags = {"Questions"})
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Question updated"),
        @ApiResponse(responseCode = "400", description = "Validation error"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "Question not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<QuestionDto> updateQuestion(
        Authentication authentication,
        @Parameter(description = "UUID of the question", required = true)
        @PathVariable UUID id,
        @RequestBody @Valid UpdateQuestionRequest request
    ) {
        return ResponseEntity.ok(questionService.updateQuestion(authentication.getName(), id, request));
    }

    @Operation(summary = "Delete question", description = "Delete a question (ADMIN only)", tags = {"Questions"})
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Question deleted"),
        @ApiResponse(responseCode = "401", description = "Unauthorized"),
        @ApiResponse(responseCode = "403", description = "Forbidden"),
        @ApiResponse(responseCode = "404", description = "Question not found")
    })
    @SecurityRequirement(name = "bearerAuth")
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteQuestion(
        Authentication authentication,
        @Parameter(description = "UUID of the question", required = true)
        @PathVariable UUID id
    ) {
        questionService.deleteQuestion(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }
}
```

---

## 🗄️ Repositories & Data Access

### QuestionRepository
```java
@Repository
public interface QuestionRepository extends JpaRepository<Question, UUID> {
    Page<Question> findAllByQuizId_Id(UUID quizId, Pageable page);
}
```

---

## 🔄 Mappers & Data Transformation

### QuestionMapper
```java
@Component
public class QuestionMapper {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static QuestionDto toDto(Question question) {
        QuestionDto dto = new QuestionDto();
        dto.setId(question.getId());
        dto.setType(question.getType());
        dto.setDifficulty(question.getDifficulty());
        dto.setQuestionText(question.getQuestionText());

        // Parse JSON content
        String raw = question.getContent();
        if (raw != null && !raw.isBlank()) {
            try {
                dto.setContent(MAPPER.readTree(raw));
            } catch (JsonProcessingException e) {
                throw new RuntimeException("Failed to parse JSON content for question " + question.getId(), e);
            }
        } else {
            dto.setContent(null);
        }

        dto.setHint(question.getHint());
        dto.setExplanation(question.getExplanation());
        dto.setAttachmentUrl(question.getAttachmentUrl());
        dto.setCreatedAt(question.getCreatedAt());
        dto.setUpdatedAt(question.getUpdatedAt());

        dto.setQuizIds(
            question.getQuizId()
                .stream()
                .map(Quiz::getId)
                .collect(Collectors.toList())
        );
        dto.setTagIds(
            question.getTags()
                .stream()
                .map(Tag::getId)
                .collect(Collectors.toList())
        );

        return dto;
    }

    public static Question toEntity(CreateQuestionRequest req, List<Quiz> quizzes, List<Tag> tags) {
        Question question = new Question();
        question.setType(req.getType());
        question.setDifficulty(req.getDifficulty());
        question.setQuestionText(req.getQuestionText());
        question.setContent(req.getContent().toString());
        question.setHint(req.getHint());
        question.setExplanation(req.getExplanation());
        question.setAttachmentUrl(req.getAttachmentUrl());
        question.setQuizId(quizzes);
        question.setTags(tags);
        return question;
    }

    public static void updateEntity(Question question, UpdateQuestionRequest req, List<Quiz> quizzes, List<Tag> tags) {
        if (req.getType() != null) {
            question.setType(req.getType());
        }
        if (req.getDifficulty() != null) {
            question.setDifficulty(req.getDifficulty());
        }
        if (req.getQuestionText() != null) {
            question.setQuestionText(req.getQuestionText());
        }
        if (req.getContent() != null) {
            question.setContent(req.getContent().toString());
        }
        if (req.getHint() != null) {
            question.setHint(req.getHint());
        }
        if (req.getExplanation() != null) {
            question.setExplanation(req.getExplanation());
        }
        if (req.getAttachmentUrl() != null) {
            question.setAttachmentUrl(req.getAttachmentUrl());
        }
        if (quizzes != null) {
            question.getQuizId().clear();
            question.getQuizId().addAll(quizzes);
        }
        if (tags != null) {
            question.getTags().clear();
            question.getTags().addAll(tags);
        }
    }
}
```

### SafeQuestionMapper
```java
@Component
@RequiredArgsConstructor
public class SafeQuestionMapper {
    private final SafeQuestionContentBuilder contentBuilder;

    public QuestionForAttemptDto toSafeDto(Question question) {
        QuestionForAttemptDto dto = new QuestionForAttemptDto();
        dto.setId(question.getId());
        dto.setType(question.getType());
        dto.setDifficulty(question.getDifficulty());
        dto.setQuestionText(question.getQuestionText());
        dto.setHint(question.getHint());
        dto.setAttachmentUrl(question.getAttachmentUrl());

        // 🔒 Build safe content without answers
        dto.setSafeContent(contentBuilder.buildSafeContent(
            question.getType(),
            question.getContent()
        ));

        return dto;
    }

    public List<QuestionForAttemptDto> toSafeDtoList(List<Question> questions) {
        return questions.stream()
            .map(this::toSafeDto)
            .collect(Collectors.toList());
    }
}
```

---

## 🛡️ Exceptions & Error Handling

### Question-Related Exceptions
```java
public class ValidationException extends RuntimeException {
    public ValidationException(String message) {
        super(message);
    }
}

public class UnsupportedQuestionTypeException extends RuntimeException {
    public UnsupportedQuestionTypeException(String message) {
        super(message);
    }
}

public class AIResponseParseException extends RuntimeException {
    public AIResponseParseException(String message) {
        super(message);
    }

    public AIResponseParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### Global Exception Handler
```java
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler({
        ValidationException.class,
        UnsupportedQuestionTypeException.class,
        UnsupportedFileTypeException.class,
        ApiError.class,
        QuizGenerationException.class
    })
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleBadRequest(RuntimeException ex) {
        return new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            List.of(ex.getMessage())
        );
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleUnsupportedOperation(UnsupportedOperationException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "Operation not supported";
        return new ErrorResponse(
            LocalDateTime.now(),
            HttpStatus.BAD_REQUEST.value(),
            "Bad Request",
            List.of(msg)
        );
    }
}
```

---

## 🔒 Security & Safe Content

### SafeQuestionContentBuilder
```java
@Component
public class SafeQuestionContentBuilder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public JsonNode buildSafeContent(QuestionType type, String originalContent) {
        try {
            JsonNode root = MAPPER.readTree(originalContent);

            return switch (type) {
                case MCQ_SINGLE, MCQ_MULTI -> buildSafeMcqContent(root);
                case TRUE_FALSE -> buildSafeTrueFalseContent();
                case FILL_GAP -> buildSafeFillGapContent(root);
                case OPEN -> buildSafeOpenContent();
                case COMPLIANCE -> buildSafeComplianceContent(root);
                case HOTSPOT -> buildSafeHotspotContent(root);
                case ORDERING -> buildSafeOrderingContent(root);
            };
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build safe content for question", e);
        }
    }

    private JsonNode buildSafeMcqContent(JsonNode root) {
        ObjectNode safeContent = MAPPER.createObjectNode();
        ArrayNode options = MAPPER.createArrayNode();
        
        JsonNode originalOptions = root.get("options");
        if (originalOptions != null && originalOptions.isArray()) {
            for (JsonNode option : originalOptions) {
                ObjectNode safeOption = MAPPER.createObjectNode();
                safeOption.put("id", option.get("id").asText());
                safeOption.put("text", option.get("text").asText());
                // Remove correct flag for security
                options.add(safeOption);
            }
        }
        safeContent.set("options", options);
        return safeContent;
    }

    private JsonNode buildSafeTrueFalseContent() {
        ObjectNode safeContent = MAPPER.createObjectNode();
        safeContent.put("true", "True");
        safeContent.put("false", "False");
        return safeContent;
    }

    private JsonNode buildSafeOpenContent() {
        ObjectNode safeContent = MAPPER.createObjectNode();
        safeContent.put("placeholder", "Enter your answer here...");
        return safeContent;
    }

    // Additional safe content builders for other question types...
}
```

---

## 🤖 AI Integration & Parsing

### QuestionResponseParser
```java
@Component
@Slf4j
public class QuestionResponseParserImpl implements QuestionResponseParser {
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private final QuestionHandlerFactory questionHandlerFactory;
    private final QuestionParserFactory questionParserFactory;

    @Override
    public List<Question> parseQuestionsFromAIResponse(String aiResponse, QuestionType expectedType)
        throws AIResponseParseException {
        try {
            log.debug("Parsing AI response for question type: {}", expectedType);

            // Clean the response - remove any markdown formatting
            String cleanedResponse = cleanAIResponse(aiResponse);

            // Try to extract JSON from the response
            String jsonContent = extractJsonFromResponse(cleanedResponse);

            // Parse the JSON and extract questions using the factory
            JsonNode rootNode = objectMapper.readTree(jsonContent);
            List<Question> questions = questionParserFactory.parseQuestions(rootNode, expectedType);

            // Validate each question
            for (Question question : questions) {
                validateQuestionContent(question);
            }

            log.debug("Successfully parsed {} questions of type {}", questions.size(), expectedType);
            return questions;

        } catch (Exception e) {
            log.error("Failed to parse AI response for type: {}", expectedType, e);
            throw new AIResponseParseException("Failed to parse AI response: " + e.getMessage(), e);
        }
    }

    @Override
    public void validateQuestionContent(Question question) throws AIResponseParseException {
        try {
            log.debug("Validating question content for type: {}", question.getType());

            // Use the existing question handler factory to validate content
            var handler = questionHandlerFactory.getHandler(question.getType());

            // Create a content request for validation
            var contentRequest = new EntityQuestionContentRequest(
                question.getType(),
                objectMapper.readTree(question.getContent())
            );

            // Validate using the existing handler
            handler.validateContent(contentRequest);

            log.debug("Question validation successful for type: {}", question.getType());

        } catch (Exception e) {
            log.error("Question validation failed for type: {}", question.getType(), e);
            throw new AIResponseParseException("Question validation failed: " + e.getMessage(), e);
        }
    }
}
```

---

## 🧪 Testing & Validation

### QuestionServiceImplTest
```java
@Execution(ExecutionMode.CONCURRENT)
@ExtendWith(MockitoExtension.class)
@DisplayName("QuestionServiceImpl Unit Tests")
class QuestionServiceImplTest {
    private static final String DUMMY_USER = "testUser";

    @Mock
    private QuestionRepository questionRepository;
    @Mock
    private QuizRepository quizRepository;
    @Mock
    private TagRepository tagRepository;
    @Mock
    private QuestionHandlerFactory factory;
    @Mock
    private QuestionHandler handler;

    @InjectMocks
    private QuestionServiceImpl questionService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        lenient().when(factory.getHandler(any())).thenReturn(handler);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("createQuestion: happy path should save and return new UUID")
    void createQuestion_happyPath_savesAndReturnsId() {
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.TRUE_FALSE);
        req.setDifficulty(Difficulty.EASY);
        req.setQuestionText("Q?");
        req.setContent(objectMapper.createObjectNode().put("answer", true));

        when(questionRepository.save(any(Question.class)))
            .thenAnswer(inv -> {
                Question q = inv.getArgument(0);
                q.setId(UUID.randomUUID());
                return q;
            });

        UUID id = questionService.createQuestion(DUMMY_USER, req);

        assertThat(id).isNotNull();
        verify(handler).validateContent(req);
        verify(questionRepository).save(any(Question.class));
    }

    @Test
    @DisplayName("createQuestion: should validate content before saving")
    void createQuestion_shouldValidateContentBeforeSaving() {
        CreateQuestionRequest req = new CreateQuestionRequest();
        req.setType(QuestionType.MCQ_SINGLE);
        req.setDifficulty(Difficulty.MEDIUM);
        req.setQuestionText("Test question?");
        req.setContent(objectMapper.createObjectNode()
            .putArray("options")
            .add(objectMapper.createObjectNode().put("id", "A").put("text", "Option A").put("correct", true))
            .add(objectMapper.createObjectNode().put("id", "B").put("text", "Option B").put("correct", false))
        );

        when(questionRepository.save(any(Question.class)))
            .thenAnswer(inv -> {
                Question q = inv.getArgument(0);
                q.setId(UUID.randomUUID());
                return q;
            });

        questionService.createQuestion(DUMMY_USER, req);

        verify(handler).validateContent(req);
    }
}
```

---

## 🔄 End-to-End Flow

### Question Creation Flow

1. **Client Request** → `QuestionController.createQuestion()`
2. **Validation** → `@Valid CreateQuestionRequest` (Bean Validation)
3. **Authorization** → `@PreAuthorize("hasRole('ADMIN')")`
4. **Service Layer** → `QuestionServiceImpl.createQuestion()`
5. **Content Validation** → `QuestionHandlerFactory.getHandler().validateContent()`
6. **Entity Resolution** → Resolve Quiz and Tag entities
7. **Mapping** → `QuestionMapper.toEntity()`
8. **Persistence** → `QuestionRepository.save()`
9. **Response** → Return generated UUID

### Question Retrieval Flow

1. **Client Request** → `QuestionController.getQuestion()`
2. **Service Layer** → `QuestionServiceImpl.getQuestion()`
3. **Data Access** → `QuestionRepository.findById()`
4. **Mapping** → `QuestionMapper.toDto()` (includes JSON parsing)
5. **Response** → Return `QuestionDto`

### Question Update Flow

1. **Client Request** → `QuestionController.updateQuestion()`
2. **Validation** → `@Valid UpdateQuestionRequest`
3. **Authorization** → `@PreAuthorize("hasRole('ADMIN')")`
4. **Service Layer** → `QuestionServiceImpl.updateQuestion()`
5. **Content Validation** → `QuestionHandlerFactory.getHandler().validateContent()`
6. **Entity Resolution** → Resolve Quiz and Tag entities
7. **Entity Update** → `QuestionMapper.updateEntity()`
8. **Persistence** → `QuestionRepository.save()`
9. **Mapping** → `QuestionMapper.toDto()`
10. **Response** → Return updated `QuestionDto`

### Question Answering Flow (During Quiz Attempt)

1. **Client Request** → `AttemptController.submitAnswer()`
2. **Service Layer** → `AttemptServiceImpl.submitAnswer()`
3. **Question Retrieval** → Get question from attempt
4. **Handler Selection** → `QuestionHandlerFactory.getHandler()`
5. **Answer Processing** → `QuestionHandler.handle()`
6. **Content Validation** → Validate question content structure
7. **Answer Evaluation** → `QuestionHandler.doHandle()` (type-specific logic)
8. **Answer Persistence** → `AnswerRepository.save()`
9. **Safe Content Generation** → `SafeQuestionMapper.toSafeDto()` (for next question)
10. **Response** → Return `AnswerSubmissionDto`

### Key Design Patterns

1. **Strategy Pattern** → Question handlers for different question types
2. **Factory Pattern** → `QuestionHandlerFactory` for handler creation
3. **Template Method** → Abstract `QuestionHandler` with common flow
4. **Builder Pattern** → `SafeQuestionContentBuilder` for secure content
5. **Repository Pattern** → Data access abstraction
6. **Mapper Pattern** → Entity-DTO conversion
7. **Validation Pattern** → Content validation at multiple layers

### Security Considerations

1. **Content Sanitization** → Safe content generation for user-facing questions
2. **Authorization** → Admin-only question management
3. **Input Validation** → Bean validation + custom content validation
4. **JSON Security** → Proper JSON parsing with error handling
5. **Answer Isolation** → Correct answers never exposed to users

This comprehensive analysis shows the complete end-to-end flow of question creation, handling, and management in the QuizMaker system. 