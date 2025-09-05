package uk.gegc.quizmaker.features.question.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.question.api.dto.CreateQuestionRequest;
import uk.gegc.quizmaker.features.question.api.dto.QuestionDto;
import uk.gegc.quizmaker.features.question.api.dto.UpdateQuestionRequest;
import uk.gegc.quizmaker.features.question.application.QuestionService;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.factory.QuestionHandlerFactory;
import uk.gegc.quizmaker.features.question.infra.handler.QuestionHandler;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionMapper;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;

import java.util.Collections;
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

    @Override
    public UUID createQuestion(String username, CreateQuestionRequest questionDto) {

        QuestionHandler questionHandler = handlerFactory.getHandler(questionDto.getType());
        questionHandler.validateContent(questionDto);

        List<Quiz> quizzes = loadQuizzesByIds(questionDto.getQuizIds());
        List<Tag> tags = loadTagsByIds(questionDto.getTagIds());

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
        QuestionHandler questionHandler = handlerFactory.getHandler(request.getType());
        questionHandler.validateContent(request);

        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));

        List<Quiz> quizzes = (request.getQuizIds() == null)
                ? null
                : loadQuizzesByIds(request.getQuizIds());
        List<Tag> tags = (request.getTagIds() == null)
                ? null
                : loadTagsByIds(request.getTagIds());

        QuestionMapper.updateEntity(question, request, quizzes, tags);

        Question updatedQuestion = questionRepository.saveAndFlush(question);

        return QuestionMapper.toDto(updatedQuestion);
    }

    @Override
    public void deleteQuestion(String username, UUID questionId) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new ResourceNotFoundException("Question " + questionId + " not found"));
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
}
