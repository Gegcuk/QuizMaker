package uk.gegc.quizmaker.features.quiz.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uk.gegc.quizmaker.features.ai.application.AiQuizGenerationService;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.document.api.dto.DocumentDto;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingService;
import uk.gegc.quizmaker.features.question.api.dto.EntityQuestionContentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.quiz.api.dto.*;
import uk.gegc.quizmaker.features.quiz.application.QuizGenerationJobService;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.features.quiz.domain.event.QuizGenerationCompletedEvent;
import uk.gegc.quizmaker.features.quiz.domain.model.*;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizGenerationJobRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizSpecifications;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizServiceImpl implements QuizService {

    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final TagRepository tagRepository;
    private final CategoryRepository categoryRepository;
    private final QuizMapper quizMapper;
    private final UserRepository userRepository;
    private final QuestionHandlerFactory questionHandlerFactory;
    private final QuizGenerationJobRepository jobRepository;
    private final QuizGenerationJobService jobService;
    private final AiQuizGenerationService aiQuizGenerationService;
    private final DocumentProcessingService documentProcessingService;
    private final QuizHashCalculator quizHashCalculator;

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MINIMUM_ESTIMATED_TIME_MINUTES = 1;

    @PersistenceContext
    private EntityManager entityManager;

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
                        .orElseThrow(() ->
                                new ResourceNotFoundException("Tag " + id + " not found")))
                .collect(Collectors.toSet());

        Quiz quiz = quizMapper.toEntity(request, creator, category, tags);
        // Enforce visibility invariant on creation: PUBLIC quizzes are published
        if (quiz.getVisibility() == Visibility.PUBLIC) {
            quiz.setStatus(QuizStatus.PUBLISHED);
        }
        return quizRepository.save(quiz).getId();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getQuizzes(Pageable pageable,
                                    QuizSearchCriteria criteria) {
        Specification<Quiz> spec = QuizSpecifications.build(criteria);
        return quizRepository.findAll(spec, pageable).map(quizMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public QuizDto getQuizById(UUID id) {
        var quiz = quizRepository.findByIdWithTags(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + id + " not found"));
        return quizMapper.toDto(quiz);
    }

    @Override
    @Transactional
    public QuizDto updateQuiz(String username, UUID id, UpdateQuizRequest req) {
        Quiz quiz = quizRepository.findByIdWithTags(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + id + " not found"));

        // Moderation: block edits while pending review and auto-revert to DRAFT if editing pending
        if (quiz.getStatus() == QuizStatus.PENDING_REVIEW) {
            // Auto-revert to DRAFT on any edits of PENDING_REVIEW quizzes
            quiz.setStatus(QuizStatus.DRAFT);
        }

        Category category;
        if (req.categoryId() != null) {
            category = categoryRepository.findById(req.categoryId())
                    .orElseThrow(() ->
                            new ResourceNotFoundException("Category " + req.categoryId() + " not found"));
        } else {
            category = quiz.getCategory();
        }

        Set<Tag> tags = Optional.ofNullable(req.tagIds())
                .map(ids -> ids.stream()
                        .map(tagId -> tagRepository.findById(tagId)
                                .orElseThrow(() ->
                                        new ResourceNotFoundException("Tag " + tagId + " not found")))
                        .collect(Collectors.toSet()))
                .orElse(null);

        String beforeContentHash = quiz.getContentHash();

        quizMapper.updateEntity(quiz, req, category, tags);

        // Recompute hashes on save
        QuizDto draftDto = quizMapper.toDto(quiz);
        String newContentHash = quizHashCalculator.calculateContentHash(draftDto);
        String newPresentationHash = quizHashCalculator.calculatePresentationHash(draftDto);
        quiz.setContentHash(newContentHash);
        quiz.setPresentationHash(newPresentationHash);

        // If published and content hash changes, auto transition to PENDING_REVIEW
        if (beforeContentHash != null
                && quiz.getStatus() == QuizStatus.PUBLISHED
                && !beforeContentHash.equalsIgnoreCase(newContentHash)) {
            quiz.setStatus(QuizStatus.PENDING_REVIEW);
            // clear review outcome fields when moving to pending
            quiz.setReviewedAt(null);
            quiz.setReviewedBy(null);
            quiz.setRejectionReason(null);
        }

        return quizMapper.toDto(quizRepository.save(quiz));
    }

    @Override
    @Transactional
    public void deleteQuizById(String username, UUID id) {
        if (!quizRepository.existsById(id)) {
            throw new ResourceNotFoundException("Quiz " + id + " not found");
        }
        quizRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void deleteQuizzesByIds(String username, List<UUID> quizIds) {
        if (quizIds == null || quizIds.isEmpty()) {
            return;
        }
        var existing = quizRepository.findAllById(quizIds);
        if (!existing.isEmpty()) {
            quizRepository.deleteAll(existing);
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

    @Override
    @Transactional
    public QuizGenerationResponse generateQuizFromDocument(String username, GenerateQuizFromDocumentRequest request) {
        // This method is now deprecated in favor of async generation
        // For backward compatibility, start an async job and return immediately
        return startQuizGeneration(username, request);
    }

    @Override
    public QuizGenerationResponse generateQuizFromUpload(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        try {
            // Step 1: Process document completely first
            DocumentDto document = processDocumentCompletely(username, file, request);
            
            // Step 2: Verify chunks are available and sufficient
            verifyDocumentChunks(document.getId(), request);
            
            // Step 3: Generate quiz from the processed document
            GenerateQuizFromDocumentRequest quizRequest = request.toGenerateQuizFromDocumentRequest(document.getId());
            
            // Step 4: Start generation and return job ID immediately
            return startQuizGeneration(username, quizRequest);
            
        } catch (Exception e) {
            log.error("Failed to start quiz generation from upload for user: {}", username, e);
            throw new RuntimeException("Failed to generate quiz from upload: " + e.getMessage(), e);
        }
    }

    @Transactional
    public DocumentDto processDocumentCompletely(String username, MultipartFile file, GenerateQuizFromUploadRequest request) {
        try {
            log.info("Starting document processing for user: {}", username);
            
            // Process document in its own transaction
            DocumentDto document = documentProcessingService.uploadAndProcessDocument(
                    username, 
                    file.getBytes(), 
                    file.getOriginalFilename(), 
                    request.toProcessDocumentRequest()
            );
            
            log.info("Document processed successfully: {} with {} chunks", document.getId(), document.getTotalChunks());
            
            return document;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read file bytes: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public void verifyDocumentChunks(UUID documentId, GenerateQuizFromUploadRequest request) {
        log.info("Verifying document chunks for document: {}", documentId);
        
        // Calculate total chunks to verify they are available
        int totalChunks = aiQuizGenerationService.calculateTotalChunks(documentId, request.toGenerateQuizFromDocumentRequest(documentId));
        
        if (totalChunks <= 0) {
            throw new RuntimeException("Document has no chunks available for quiz generation. Please try processing the document again.");
        }
        
        log.info("Document verification successful: {} chunks available", totalChunks);
    }

    @Override
    @Transactional
    public QuizGenerationResponse startQuizGeneration(String username, GenerateQuizFromDocumentRequest request) {
        // Validate user exists
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + username));

        // Check if user already has an active generation job
        List<QuizGenerationJob> activeJobs = jobService.getActiveJobs().stream()
                .filter(job -> job.getUser().getUsername().equals(username))
                .toList();
        if (!activeJobs.isEmpty()) {
            throw new ValidationException("User already has an active generation job. Please wait for it to complete.");
        }

        try {
            // Calculate total chunks and estimated time before creating job
            int totalChunks = aiQuizGenerationService.calculateTotalChunks(request.documentId(), request);
            int estimatedSeconds = aiQuizGenerationService.calculateEstimatedGenerationTime(
                    totalChunks, request.questionsPerType());

            // Create generation job with proper estimates
            QuizGenerationJob job = jobService.createJob(user, request.documentId(),
                    objectMapper.writeValueAsString(request), totalChunks, estimatedSeconds);
            
            log.info("Created job {} for user {}, starting async generation", job.getId(), username);

            // Start async generation
            aiQuizGenerationService.generateQuizFromDocumentAsync(job.getId(), request);

            return QuizGenerationResponse.started(job.getId(), (long) estimatedSeconds);

        } catch (JsonProcessingException e) {
            throw new ValidationException("Failed to serialize request data: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public QuizGenerationStatus getGenerationStatus(UUID jobId, String username) {
        QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);
        return QuizGenerationStatus.fromEntity(job);
    }

    @Override
    @Transactional
    public QuizDto getGeneratedQuiz(UUID jobId, String username) {
        QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);

        if (job.getStatus() != GenerationStatus.COMPLETED) {
            throw new ValidationException("Generation job is not yet completed. Current status: " + job.getStatus());
        }

        if (job.getGeneratedQuizId() == null) {
            throw new ResourceNotFoundException("Generated quiz not found for job: " + jobId);
        }

        return getQuizById(job.getGeneratedQuizId());
    }

    @Override
    @Transactional
    public QuizGenerationStatus cancelGenerationJob(UUID jobId, String username) {
        QuizGenerationJob job = jobService.getJobByIdAndUsername(jobId, username);

        if (job.getStatus().isTerminal()) {
            throw new ValidationException("Cannot cancel job that is already in terminal state: " + job.getStatus());
        }

        jobService.cancelJob(jobId, username);

        // Refresh job from database
        job = jobRepository.findById(jobId).orElseThrow();
        return QuizGenerationStatus.fromEntity(job);
    }

    @Override
    @Transactional
    public Page<QuizGenerationStatus> getGenerationJobs(String username, Pageable pageable) {
        Page<QuizGenerationJob> jobs = jobService.getJobsByUser(username, pageable);
        return jobs.map(QuizGenerationStatus::fromEntity);
    }

    @Override
    @Transactional
    public QuizGenerationJobService.JobStatistics getGenerationJobStatistics(String username) {
        return jobService.getJobStatistics(username);
    }

    @EventListener
    @Async("generalTaskExecutor")
    @Transactional
    public void handleQuizGenerationCompleted(QuizGenerationCompletedEvent event) {
        try {
            createQuizCollectionFromGeneratedQuestions(
                    event.getJobId(),
                    event.getChunkQuestions(),
                    event.getOriginalRequest()
            );
        } catch (Exception e) {
            // Log error and update job status
            log.error("Failed to create quiz collection for job {}", event.getJobId(), e);
            try {
                QuizGenerationJob job = jobRepository.findById(event.getJobId()).orElse(null);
                if (job != null) {
                    job.markFailed("Quiz creation failed: " + e.getMessage());
                    jobRepository.save(job);
                }
            } catch (Exception saveError) {
                log.error("Failed to update job status after quiz creation failure for job {}", event.getJobId(), saveError);
            }
        }
    }

    @Override
    @Transactional
    public void createQuizCollectionFromGeneratedQuestions(
            UUID jobId,
            Map<Integer, List<Question>> chunkQuestions,
            GenerateQuizFromDocumentRequest originalRequest
    ) {
        try {
            QuizGenerationJob job = jobRepository.findById(jobId)
                    .orElseThrow(() -> new ResourceNotFoundException("Generation job not found: " + jobId));

            User user = job.getUser();
            UUID documentId = originalRequest.documentId();

            // Get category and tags
            Category category = getOrCreateAICategory();
            Set<Tag> tags = getTagsFromRequest(originalRequest);

            // Create individual chunk quizzes
            List<Quiz> chunkQuizzes = new ArrayList<>();
            Map<Integer, String> chunkTitles = new HashMap<>();

            for (Map.Entry<Integer, List<Question>> entry : chunkQuestions.entrySet()) {
                int chunkIndex = entry.getKey();
                List<Question> questions = entry.getValue();

                if (!questions.isEmpty()) {
                    Quiz chunkQuiz = createChunkQuiz(
                            user, questions, chunkIndex, originalRequest, category, tags, documentId
                    );
                    chunkQuizzes.add(chunkQuiz);

                    // Store chunk title for metadata
                    chunkTitles.put(chunkIndex, getChunkTitle(chunkIndex, questions));
                }
            }

            // Create consolidated quiz with all questions
            List<Question> allQuestions = chunkQuestions.values().stream()
                    .flatMap(List::stream)
                    .collect(Collectors.toList());

            Quiz consolidatedQuiz = createConsolidatedQuiz(
                    user, allQuestions, originalRequest, category, tags, documentId, chunkQuizzes.size()
            );

            // Calculate total questions
            int totalQuestions = allQuestions.size();

            // Update job with consolidated quiz ID and total questions
            job.markCompleted(consolidatedQuiz.getId(), totalQuestions);
            jobRepository.save(job);

            // TODO: Create document quiz group entity to track relationships
            // This would require additional database schema for quiz relationships

        } catch (Exception e) {
            // If quiz creation fails, mark job as failed
            try {
                QuizGenerationJob job = jobRepository.findById(jobId).orElse(null);
                if (job != null) {
                    job.markFailed("Failed to create quiz collection: " + e.getMessage());
                    jobRepository.save(job);
                }
            } catch (Exception saveError) {
                System.err.println("Failed to update job status after quiz creation failure: " + saveError.getMessage());
            }
            throw new RuntimeException("Failed to create quiz collection from generated questions", e);
        }
    }

    /**
     * Create individual quiz for a document chunk
     */
    private Quiz createChunkQuiz(
            User user,
            List<Question> questions,
            int chunkIndex,
            GenerateQuizFromDocumentRequest request,
            Category category,
            Set<Tag> tags,
            UUID documentId
    ) {
        String chunkTitle = getChunkTitle(chunkIndex, questions);
        String quizTitle = String.format("Quiz: %s", chunkTitle);
        String quizDescription = String.format("Quiz covering %s from the document", chunkTitle);

        int estimatedTimeMinutes = Math.max(MINIMUM_ESTIMATED_TIME_MINUTES,
                (int) Math.ceil(questions.size() * 1.5));

        Quiz quiz = new Quiz();
        quiz.setTitle(quizTitle);
        quiz.setDescription(quizDescription);
        quiz.setCreator(user);
        quiz.setCategory(category);
        quiz.setTags(tags);
        quiz.setStatus(QuizStatus.PUBLISHED); // Start as published
        quiz.setVisibility(Visibility.PRIVATE); // Start as private
        quiz.setEstimatedTime(estimatedTimeMinutes);
        quiz.setQuestions(new HashSet<>(questions));
        quiz.setIsTimerEnabled(false); // Default to no timer for AI-generated quizzes
        quiz.setIsRepetitionEnabled(false); // Default to no repetition for AI-generated quizzes
        quiz.setDifficulty(Difficulty.MEDIUM); // Default difficulty for AI-generated quizzes

        // Add document metadata as custom properties (if supported)
        // quiz.setCustomProperty("documentId", documentId.toString());
        // quiz.setCustomProperty("chunkIndex", String.valueOf(chunkIndex));

        return quizRepository.save(quiz);
    }

    /**
     * Create consolidated quiz containing all questions from all chunks
     */
    private Quiz createConsolidatedQuiz(
            User user,
            List<Question> allQuestions,
            GenerateQuizFromDocumentRequest request,
            Category category,
            Set<Tag> tags,
            UUID documentId,
            int chunkCount
    ) {
        String quizTitle = request.quizTitle() != null ? request.quizTitle() :
                "Complete Document Quiz";
        String quizDescription = request.quizDescription() != null ? request.quizDescription() :
                String.format("Comprehensive quiz covering all %d sections of the document", chunkCount);

        int estimatedTimeMinutes = Math.max(MINIMUM_ESTIMATED_TIME_MINUTES,
                (int) Math.ceil(allQuestions.size() * 1.5));

        Quiz quiz = new Quiz();
        quiz.setTitle(quizTitle);
        quiz.setDescription(quizDescription);
        quiz.setCreator(user);
        quiz.setCategory(category);
        quiz.setTags(tags);
        quiz.setStatus(QuizStatus.PUBLISHED); // Start as published
        quiz.setVisibility(Visibility.PRIVATE); // Start as private
        quiz.setEstimatedTime(estimatedTimeMinutes);
        quiz.setQuestions(new HashSet<>(allQuestions));
        quiz.setIsTimerEnabled(false); // Default to no timer for AI-generated quizzes
        quiz.setIsRepetitionEnabled(false); // Default to no repetition for AI-generated quizzes
        quiz.setDifficulty(Difficulty.MEDIUM); // Default difficulty for AI-generated quizzes

        // Add document metadata as custom properties (if supported)
        // quiz.setCustomProperty("documentId", documentId.toString());
        // quiz.setCustomProperty("quizType", "CONSOLIDATED");

        return quizRepository.save(quiz);
    }

    /**
     * Get or create AI Generated category
     */
    private Category getOrCreateAICategory() {
        return categoryRepository.findByName("AI Generated")
                .orElseGet(() -> categoryRepository.findByName("General")
                        .orElseGet(() -> {
                            // Create AI Generated category if it doesn't exist
                            Category aiCategory = new Category();
                            aiCategory.setName("AI Generated");
                            aiCategory.setDescription("Quizzes automatically generated by AI");
                            return categoryRepository.save(aiCategory);
                        }));
    }

    /**
     * Get tags from request or return empty set
     */
    private Set<Tag> getTagsFromRequest(GenerateQuizFromDocumentRequest request) {
        if (request.tagIds() == null) {
            return new HashSet<>();
        }

        return request.tagIds().stream()
                .map(id -> tagRepository.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Generate chunk title based on chunk index and content
     */
    private String getChunkTitle(int chunkIndex, List<Question> questions) {
        // Generate a unique identifier to prevent title conflicts in concurrent tests
        String uniqueId = UUID.randomUUID().toString().substring(0, 8);
        
        // Try to extract meaningful title from questions
        if (!questions.isEmpty()) {
            String firstQuestion = questions.get(0).getQuestionText();
            if (firstQuestion != null && firstQuestion.length() > 10) {
                // Extract first few words as potential title
                String[] words = firstQuestion.split("\\s+");
                if (words.length >= 3) {
                    String baseTitle = String.join(" ", Arrays.copyOfRange(words, 0, Math.min(5, words.length)));
                    return baseTitle + " (" + uniqueId + ")";
                }
            }
        }

        // Fallback to generic title with unique identifier
        return String.format("Section %d (%s)", chunkIndex, uniqueId);
    }

    @Override
    @Transactional
    public void addQuestionToQuiz(String username, UUID quizId, UUID questionId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        var question = questionRepository.findById(questionId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Question " + questionId + " not found"));
        quiz.getQuestions().add(question);
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void removeQuestionFromQuiz(String username, UUID quizId, UUID questionId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.getQuestions().removeIf(q -> q.getId().equals(questionId));
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void addTagToQuiz(String username, UUID quizId, UUID tagId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        var tag = tagRepository.findById(tagId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Tag " + tagId + " not found"));
        quiz.getTags().add(tag);
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void removeTagFromQuiz(String username, UUID quizId, UUID tagId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.getTags().removeIf(t -> t.getId().equals(tagId));
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public void changeCategory(String username, UUID quizId, UUID categoryId) {
        var quiz = quizRepository.findById(quizId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Quiz " + quizId + " not found"));
        var cat = categoryRepository.findById(categoryId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Category " + categoryId + " not found"));
        quiz.setCategory(cat);
        quizRepository.save(quiz);
    }

    @Override
    @Transactional
    public QuizDto setVisibility(String name, UUID quizId, Visibility visibility) {

        Quiz quiz = quizRepository.findById(quizId).orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
        quiz.setVisibility(visibility);

        return quizMapper.toDto(quizRepository.save(quiz));
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

    private void validateQuestionsHaveCorrectAnswers(Quiz quiz, List<String> validationErrors) {
        for (var question : quiz.getQuestions()) {
            try {
                // Get the appropriate handler for this question type
                QuestionHandler handler = questionHandlerFactory.getHandler(question.getType());

                // Parse the question content
                var content = objectMapper.readTree(question.getContent());
                var contentRequest = new EntityQuestionContentRequest(question.getType(), content);

                // Validate that the question content has correct answers defined
                handler.validateContent(contentRequest);

            } catch (JsonProcessingException e) {
                validationErrors.add("Question '" + question.getQuestionText() + "' has malformed content JSON");
            } catch (ValidationException e) {
                validationErrors.add("Question '" + question.getQuestionText() + "' is invalid: " + e.getMessage());
            } catch (Exception e) {
                validationErrors.add("Question '" + question.getQuestionText() + "' failed validation: " + e.getMessage());
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Page<QuizDto> getPublicQuizzes(Pageable pageable) {
        // Enforce visibility invariants: public catalog shows PUBLISHED && PUBLIC
        return quizRepository.findAllByVisibilityAndStatus(Visibility.PUBLIC, QuizStatus.PUBLISHED, pageable)
                .map(quizMapper::toDto);
    }
}