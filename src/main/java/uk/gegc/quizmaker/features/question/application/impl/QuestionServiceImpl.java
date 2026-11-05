package uk.gegc.quizmaker.features.question.application.impl;

import uk.gegc.quizmaker.features.question.application.QuestionContracts;

import lombok.RequiredArgsConstructor;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionDto;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;
import uk.gegc.quizmaker.features.question.application.QuestionService;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionMapper;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionMediaResolver;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ForbiddenException;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.dto.MediaRefDto;
import uk.gegc.quizmaker.features.media.application.MediaAssetService;
import uk.gegc.quizmaker.features.media.application.RetainedImageService;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional
@RequiredArgsConstructor
public class QuestionServiceImpl implements QuestionService {
    private final QuestionRepository questionRepository;
    private final QuizRepository quizRepository;
    private final TagRepository tagRepository;
    private final QuestionHandlerFactory handlerFactory;
    private final AppPermissionEvaluator appPermissionEvaluator;
    private final UserRepository userRepository;
    private final QuestionMediaResolver questionMediaResolver;
    private final MediaAssetService mediaAssetService;
    private final RetainedImageService retainedImages;
    private final PlatformTransactionManager transactions;
    private final EntityManagerFactory entityManagers;
    private final EntityManager entityManager;

    private TransactionTemplate writeTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // Discard read-only OSIV snapshots between preflight/storage and authoritative writes.
            var context = TransactionSynchronizationManager.getResource(entityManagers);
            if (context instanceof EntityManagerHolder holder) holder.getEntityManager().clear();
        }
        return new TransactionTemplate(transactions);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public UUID createQuestion(String username, CreateQuestionRequest request) {
        var desired = imageIds(request.getAttachmentAssetId(), sanitizeMediaForStorage(request.getContent()));
        if (!desired.isEmpty()) {
            writeTransaction().executeWithoutResult(s -> {
                var actor = author(username);
                authorizeQuizzes(actor, loadQuizzesByIds(request.getQuizIds()));
                handlerFactory.validateStoredContent(request.getType(), request.getContent());
            });
            prepareImages(null, desired, Set.of(), username);
        }
        return writeTransaction().execute(s -> createQuestionInTransaction(username, request));
    }

    private UUID createQuestionInTransaction(String username, CreateQuestionRequest questionDto) {
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        handlerFactory.validateStoredContent(questionDto.getType(), questionDto.getContent());

        JsonNode sanitizedContent = sanitizeMediaForStorage(questionDto.getContent());
        validateAttachmentAsset(questionDto.getAttachmentAssetId(), username);
        validateMediaInContent(sanitizedContent, username);

        List<Quiz> quizzes = loadQuizzesByIds(questionDto.getQuizIds());
        
        // Ownership check: ensure all referenced quizzes are owned by the user or user has moderation permissions
        for (Quiz quiz : quizzes) {
            if (!(quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId())
                    || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                    || appPermissionEvaluator.hasPermission(user, PermissionName.QUESTION_ADMIN))) {
                throw new ForbiddenException("Not allowed to create questions for quiz " + quiz.getId());
            }
        }

        List<Tag> tags = loadTagsByIds(questionDto.getTagIds());

        Question question = QuestionMapper.toEntity(questionDto, quizzes, tags);
        if (sanitizedContent != null) {
            question.setContent(sanitizedContent.toString());
        }
        // Materialize the row before returning its ID, including create-then-update in a caller transaction.
        questionRepository.saveAndFlush(question);
        bindImages(question.getId(), imageIds(question.getAttachmentAssetId(), sanitizedContent), Set.of(), username);

        return question.getId();
    }

    @Override
    public Page<QuestionDto> listQuestions(UUID quizId, Pageable page, Authentication authentication) {
        User user = null;
        if (authentication != null && authentication.isAuthenticated()) {
            user = userRepository.findByUsername(authentication.getName())
                    .or(() -> userRepository.findByEmail(authentication.getName()))
                    .orElse(null);
        }

        Page<Question> retrievedPage;
        
        if (quizId != null) {
            // Check if user can access this quiz
            Quiz quiz = quizRepository.findById(quizId)
                    .orElseThrow(() -> new ResourceNotFoundException("Quiz " + quizId + " not found"));
            
            boolean canAccess = false;
            if (user != null) {
                boolean isOwner = quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId());
                boolean hasModerationPermissions = appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                        || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN);
                canAccess = isOwner || hasModerationPermissions;
            }
            
            // Allow access to public, published quizzes even for anonymous users
            if (!canAccess && quiz.getVisibility() == Visibility.PUBLIC && quiz.getStatus() == QuizStatus.PUBLISHED) {
                canAccess = true;
            }
            
            if (!canAccess) {
                throw new ForbiddenException("Access denied: cannot view questions for this quiz");
            }
            
            retrievedPage = questionRepository.findAllByQuizId_Id(quizId, page);
        } else {
            // Without quizId: return only questions belonging to user's own quizzes
            if (user == null) {
                throw new ForbiddenException("Authentication required to list questions without quiz filter");
            }
            
            // Get all quiz IDs owned by the user
            List<UUID> userQuizIds = quizRepository.findByCreatorId(user.getId())
                    .stream()
                    .map(Quiz::getId)
                    .toList();
            
            if (userQuizIds.isEmpty()) {
                return Page.empty(page);
            }
            
            retrievedPage = questionRepository.findAllByQuizId_IdIn(userQuizIds, page);
        }

        if (!retrievedPage.isEmpty()) {
            List<UUID> ids = retrievedPage.getContent().stream().map(Question::getId).toList();
            // Fetch each bag separately after pagination; never paginate a collection join.
            questionRepository.fetchTags(ids);
            questionRepository.fetchQuizzes(ids);
        }
        var media = questionMediaResolver.resolveQuestions(retrievedPage.getContent());
        return retrievedPage.map(question -> {
            QuestionDto dto = QuestionMapper.toDto(question);
            dto.setAttachment(question.getAttachmentAssetId() == null ? null : media.get(question.getAttachmentAssetId()));
            if (dto.getAttachment() == null && question.getAttachmentUrl() != null) {
                dto.setAttachment(new MediaRefDto(null, question.getAttachmentUrl(), null, null, null, null, null));
            }
            dto.setContent(questionMediaResolver.resolveMediaInContent(dto.getContent(), media));
            validateResolvedContent(dto);
            return dto;
        });
    }

    @Override
    public QuestionDto getQuestion(UUID questionId, Authentication authentication) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));

        User user = null;
        if (authentication != null && authentication.isAuthenticated()) {
            user = userRepository.findByUsername(authentication.getName())
                    .or(() -> userRepository.findByEmail(authentication.getName()))
                    .orElse(null);
        }

        // Check if user can access this question
        boolean canAccess = false;
        
        if (user != null) {
            boolean hasModerationPermissions = appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                    || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_ADMIN);
            if (hasModerationPermissions) {
                canAccess = true;
            } else {
                // Check if user owns any of the quizzes this question belongs to
                for (Quiz quiz : question.getQuizId()) {
                    boolean isOwner = quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId());
                    if (isOwner) {
                        canAccess = true;
                        break;
                    }
                }
            }
        }
        
        // If not owner/moderator, check if question belongs to any public, published quiz
        if (!canAccess) {
            for (Quiz quiz : question.getQuizId()) {
                if (quiz.getVisibility() == Visibility.PUBLIC && quiz.getStatus() == QuizStatus.PUBLISHED) {
                    canAccess = true;
                    break;
                }
            }
        }
        
        if (!canAccess) {
            throw new ForbiddenException("Access denied: cannot view this question");
        }

        return enrichQuestionDtoWithMedia(QuestionMapper.toDto(question), question);
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS)
    public QuestionDto updateQuestion(String username, UUID questionId, UpdateQuestionRequest request) {
        var preparation = writeTransaction().execute(s -> {
            var question = questionRepository.findById(questionId)
                    .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));
            var actor = author(username);
            authorizeQuizzes(actor, question.getQuizId());
            if (request.getQuizIds() != null) authorizeQuizzes(actor, loadQuizzesByIds(request.getQuizIds()));
            handlerFactory.validateStoredContent(request.getType(), request.getContent());
            var previous = imageIds(question.getAttachmentAssetId(), storedContent(question));
            UUID attachment = Boolean.TRUE.equals(request.getClearAttachment()) ? null
                    : request.getAttachmentAssetId() == null ? question.getAttachmentAssetId() : request.getAttachmentAssetId();
            var desired = imageIds(attachment, request.getContent() == null ? storedContent(question) : sanitizeMediaForStorage(request.getContent()));
            return new ImageSelection(desired, previous);
        });
        if (!preparation.desired().isEmpty())
            prepareImages(questionId, preparation.desired(), preparation.previous(), username);
        return writeTransaction().execute(s -> updateQuestionInTransaction(username, questionId, request));
    }

    private QuestionDto updateQuestionInTransaction(String username, UUID questionId, UpdateQuestionRequest request) {
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        handlerFactory.validateStoredContent(request.getType(), request.getContent());

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));
        // A caller's transaction may already hold a stale question. Refresh only this entity
        // under the write lock; neither cached references nor an old read snapshot grant reuse.
        try { entityManager.refresh(question, LockModeType.PESSIMISTIC_WRITE); }
        catch (jakarta.persistence.EntityNotFoundException missing) {
            throw new ResourceNotFoundException("Question " + questionId + " not found");
        }
        if (Boolean.TRUE.equals(question.getIsDeleted()))
            throw new ResourceNotFoundException("Question " + questionId + " not found");

        // Ownership check: ensure all associated quizzes are owned by the user or user has moderation permissions
        for (Quiz quiz : question.getQuizId()) {
            if (!(quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId())
                    || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                    || appPermissionEvaluator.hasPermission(user, PermissionName.QUESTION_ADMIN))) {
                throw new ForbiddenException("Not allowed to update question associated with quiz " + quiz.getId());
            }
        }

        Set<UUID> previousImages = imageIds(question.getAttachmentAssetId(), storedContent(question));
        JsonNode sanitizedContent = sanitizeMediaForStorage(request.getContent());

        List<Quiz> quizzes = (request.getQuizIds() == null)
                ? null
                : loadQuizzesByIds(request.getQuizIds());
        
        // If new quizzes are being assigned, check ownership
        if (quizzes != null) {
            for (Quiz quiz : quizzes) {
                if (!(quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId())
                        || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                        || appPermissionEvaluator.hasPermission(user, PermissionName.QUESTION_ADMIN))) {
                    throw new ForbiddenException("Not allowed to assign question to quiz " + quiz.getId());
                }
            }
        }
        
        List<Tag> tags = (request.getTagIds() == null)
                ? null
                : loadTagsByIds(request.getTagIds());

        QuestionMapper.updateEntity(question, request, quizzes, tags);
        if (sanitizedContent != null) {
            question.setContent(sanitizedContent.toString());
        }

        bindImages(questionId, imageIds(question.getAttachmentAssetId(), storedContent(question)), previousImages, username);
        Question updatedQuestion = questionRepository.saveAndFlush(question);

        return enrichQuestionDtoWithMedia(QuestionMapper.toDto(updatedQuestion), updatedQuestion);
    }

    @Override
    public void deleteQuestion(String username, UUID questionId) {
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));

        // Ownership check: ensure all associated quizzes are owned by the user or user has moderation permissions
        for (Quiz quiz : question.getQuizId()) {
            if (!(quiz.getCreator() != null && user.getId().equals(quiz.getCreator().getId())
                    || appPermissionEvaluator.hasPermission(user, PermissionName.QUIZ_MODERATE)
                    || appPermissionEvaluator.hasPermission(user, PermissionName.QUESTION_ADMIN))) {
                throw new ForbiddenException("Not allowed to delete question associated with quiz " + quiz.getId());
            }
        }

        questionRepository.delete(question);
    }

    /**
     * Batch loads quizzes by IDs to avoid N+1 query problem.
     * Validates that all requested quizzes exist.
     */
    private List<Quiz> loadQuizzesByIds(List<UUID> quizIds) {
        if (quizIds == null || quizIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Quiz> quizzes = quizRepository.findAllById(quizIds);
        validateAllEntitiesFound(quizzes, quizIds, "Quiz");
        return quizzes;
    }

    /**
     * Batch loads tags by IDs to avoid N+1 query problem.
     * Validates that all requested tags exist.
     */
    private List<Tag> loadTagsByIds(List<UUID> tagIds) {
        if (tagIds == null || tagIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<Tag> tags = tagRepository.findAllById(tagIds);
        validateAllEntitiesFound(tags, tagIds, "Tag");
        return tags;
    }

    /**
     * Validates that all requested entities were found.
     * Throws ResourceNotFoundException with details about missing entities.
     */
    private <T> void validateAllEntitiesFound(List<T> foundEntities, List<UUID> requestedIds, String entityType) {
        if (foundEntities.size() != requestedIds.size()) {
            Set<UUID> foundIds = foundEntities.stream()
                    .map(entity -> {
                        if (entity instanceof Quiz) {
                            return ((Quiz) entity).getId();
                        } else if (entity instanceof Tag) {
                            return ((Tag) entity).getId();
                        }
                        throw new IllegalStateException("Unsupported entity type: " + entity.getClass());
                    })
                    .collect(Collectors.toSet());
            
            List<UUID> missingIds = requestedIds.stream()
                    .filter(id -> !foundIds.contains(id))
                    .toList();
            
            // For single missing entity, use the original format expected by tests
            if (missingIds.size() == 1) {
                String entityName = entityType.equals("Quiz") ? "Quiz" : "Tag";
                throw new ResourceNotFoundException(entityName + " " + missingIds.get(0) + " not found");
            } else {
                // For multiple missing entities, use the new format
                String entityName = entityType.equals("Quiz") ? "Quizzes" : "Tags";
                throw new ResourceNotFoundException(entityName + " not found: " + missingIds);
            }
        }
    }

    private QuestionDto enrichQuestionDtoWithMedia(QuestionDto dto, Question question) {
        if (dto == null || question == null) {
            return dto;
        }
        MediaRefDto attachment = questionMediaResolver.resolveAttachment(question.getAttachmentAssetId());
        if (attachment == null && question.getAttachmentUrl() != null) {
            attachment = new MediaRefDto(
                    null,
                    question.getAttachmentUrl(),
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
        dto.setAttachment(attachment);
        dto.setContent(questionMediaResolver.resolveMediaInContent(dto.getContent()));
        validateResolvedContent(dto);
        return dto;
    }

    private void validateResolvedContent(QuestionDto dto) {
        if (dto.getContent() == null) return;
        try { handlerFactory.validateStoredContent(dto.getType(), dto.getContent()); }
        catch (uk.gegc.quizmaker.features.question.application.QuestionContractException ex) {
            dto.setContent(null);
            dto.setContentAvailability(uk.gegc.quizmaker.features.question.api.dto.QuestionContentAvailability.UNAVAILABLE);
            dto.setContentProblem(QuestionContracts.unavailableProblem());
        }
    }

    private record ImageSelection(Set<UUID> desired, Set<UUID> previous) { }

    private void prepareImages(UUID question, Set<UUID> desired, Set<UUID> previous, String username) {
        try { retainedImages.prepareForQuestion(question, desired, previous, username); }
        catch (ResourceNotFoundException | ForbiddenException invalid) {
            throw new ValidationException(invalid.getMessage()); // Preserve the existing invalid-media HTTP 400 contract.
        }
    }

    private void bindImages(UUID question, Set<UUID> desired, Set<UUID> previous, String username) {
        try { retainedImages.bind(question, desired, previous, username); }
        catch (ResourceNotFoundException | ForbiddenException invalid) { throw new ValidationException(invalid.getMessage()); }
    }

    private User author(String username) {
        return userRepository.findByUsername(username).or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private void authorizeQuizzes(User actor, List<Quiz> quizzes) {
        for (var quiz : quizzes) {
            if (!(quiz.getCreator() != null && actor.getId().equals(quiz.getCreator().getId())
                    || appPermissionEvaluator.hasPermission(actor, PermissionName.QUIZ_MODERATE)
                    || appPermissionEvaluator.hasPermission(actor, PermissionName.QUESTION_ADMIN)))
                throw new ForbiddenException("Not allowed to modify questions for this quiz");
        }
    }

    private static JsonNode storedContent(Question question) {
        if (question.getContent() == null) return null;
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readTree(question.getContent()); }
        catch (java.io.IOException invalid) { throw new ValidationException("Stored question content is unavailable"); }
    }

    private static Set<UUID> imageIds(UUID attachment, JsonNode content) {
        Set<UUID> ids = new HashSet<>();
        if (attachment != null) ids.add(attachment);
        collectImageIds(content, ids);
        return Set.copyOf(ids);
    }

    private static void collectImageIds(JsonNode node, Set<UUID> ids) {
        if (node == null) return;
        if (node.isObject() && node.path("media").hasNonNull("assetId")) {
            try { ids.add(UUID.fromString(node.path("media").path("assetId").asText())); }
            catch (IllegalArgumentException invalid) { throw new ValidationException("Invalid media asset identifier"); }
        }
        if (node.isContainerNode()) node.elements().forEachRemaining(child -> collectImageIds(child, ids));
    }

    private void validateAttachmentAsset(UUID assetId, String username) {
        if (assetId == null) {
            return;
        }
        try {
            mediaAssetService.getByIdForValidation(assetId, username);
        } catch (ResourceNotFoundException | ForbiddenException | ValidationException ex) {
            throw new ValidationException(ex.getMessage());
        }
    }

    private void validateMediaInContent(JsonNode content, String username) {
        if (content == null) {
            return;
        }
        Set<UUID> seen = new HashSet<>();
        validateMediaNode(content, username, seen);
    }

    private void validateMediaNode(JsonNode node, String username, Set<UUID> seen) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode mediaNode = node.get("media");
            if (mediaNode != null && mediaNode.isObject()) {
                JsonNode assetIdNode = mediaNode.get("assetId");
                if (assetIdNode != null && assetIdNode.isTextual()) {
                    String raw = assetIdNode.asText();
                    try {
                        UUID assetId = UUID.fromString(raw);
                        if (seen.add(assetId)) {
                            validateAttachmentAsset(assetId, username);
                        }
                    } catch (IllegalArgumentException ex) {
                        throw new ValidationException("Invalid media assetId: " + raw);
                    }
                }
            }
            node.fields().forEachRemaining(entry -> validateMediaNode(entry.getValue(), username, seen));
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                validateMediaNode(item, username, seen);
            }
        }
    }

    private JsonNode sanitizeMediaForStorage(JsonNode content) {
        if (content == null) {
            return null;
        }
        JsonNode copy = content.deepCopy();
        stripMediaFields(copy);
        return copy;
    }

    private void stripMediaFields(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            JsonNode mediaNode = obj.get("media");
            if (mediaNode != null) {
                if (mediaNode.isObject()) {
                    JsonNode assetIdNode = mediaNode.get("assetId");
                    if (assetIdNode != null && assetIdNode.isTextual() && !assetIdNode.asText().isBlank()) {
                        ObjectNode cleanMedia = JsonNodeFactory.instance.objectNode();
                        cleanMedia.put("assetId", assetIdNode.asText());
                        obj.set("media", cleanMedia);
                    } else {
                        obj.remove("media");
                    }
                } else {
                    obj.remove("media");
                }
            }
            obj.fields().forEachRemaining(entry -> stripMediaFields(entry.getValue()));
            return;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                stripMediaFields(item);
            }
        }
    }
}
