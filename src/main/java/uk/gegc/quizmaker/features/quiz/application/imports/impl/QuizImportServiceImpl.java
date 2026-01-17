package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionImportMapper;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.ImportErrorDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.ImportSummaryDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.application.imports.ContentHashUtil;
import uk.gegc.quizmaker.features.quiz.application.imports.ImportParser;
import uk.gegc.quizmaker.features.quiz.application.imports.ImportParserFactory;
import uk.gegc.quizmaker.features.quiz.application.imports.QuizImportService;
import uk.gegc.quizmaker.features.quiz.application.imports.ReferenceResolutionService;
import uk.gegc.quizmaker.features.quiz.application.validation.QuizImportValidationService;
import uk.gegc.quizmaker.features.quiz.config.QuizDefaultsProperties;
import uk.gegc.quizmaker.features.quiz.domain.model.ExportFormat;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizImportOptions;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.UpsertStrategy;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizImportAssembler;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.tag.domain.repository.TagRepository;
import uk.gegc.quizmaker.features.user.domain.model.PermissionName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class QuizImportServiceImpl implements QuizImportService {

    private static final String DEFAULT_CATEGORY_DESCRIPTION = "Category created by import";

    private final ImportParserFactory parserFactory;
    private final QuizImportValidationService validationService;
    private final ReferenceResolutionService referenceResolutionService;
    private final QuizImportAssembler quizImportAssembler;
    private final QuestionImportMapper questionImportMapper;
    private final ContentHashUtil contentHashUtil;
    private final QuizRepository quizRepository;
    private final QuestionRepository questionRepository;
    private final CategoryRepository categoryRepository;
    private final TagRepository tagRepository;
    private final QuizDefaultsProperties quizDefaultsProperties;
    private final UserRepository userRepository;
    private final AccessPolicy accessPolicy;
    private final PlatformTransactionManager transactionManager;
    private final QuizHashCalculator quizHashCalculator;
    private final QuizMapper quizMapper;

    @Override
    public ImportSummaryDto importQuizzes(InputStream input,
                                          ExportFormat format,
                                          QuizImportOptions options,
                                          Authentication authentication) {
        if (input == null) {
            throw new ValidationException("Import input stream is required");
        }
        if (format == null) {
            throw new ValidationException("Import format is required");
        }
        if (options == null) {
            throw new ValidationException("Import options are required");
        }
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new ValidationException("Authenticated user is required for import");
        }

        User actor = resolveUser(authentication.getName());
        boolean hasModerationPermissions = accessPolicy.hasAny(actor, PermissionName.QUIZ_MODERATE, PermissionName.QUIZ_ADMIN);

        ImportParser parser = parserFactory.getParser(format);
        List<QuizImportDto> quizzes = parser.parse(input, options);

        List<ImportErrorDto> errors = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        DryRunCache dryRunCache = options.dryRun() ? new DryRunCache() : null;

        try {
            for (int i = 0; i < quizzes.size(); i++) {
                QuizImportDto quiz = quizzes.get(i);
                int index = i;
                try {
                    ImportOutcome outcome = template.execute(status ->
                            processSingle(quiz, actor, hasModerationPermissions, options, dryRunCache));
                    if (outcome == ImportOutcome.CREATED) {
                        created++;
                    } else if (outcome == ImportOutcome.UPDATED) {
                        updated++;
                    } else if (outcome == ImportOutcome.SKIPPED) {
                        skipped++;
                    }
                } catch (Exception ex) {
                    failed++;
                    errors.add(new ImportErrorDto(index,
                            quiz != null ? quiz.id() : null,
                            null,
                            ex.getMessage(),
                            ex.getClass().getSimpleName()));
                }
            }
        } finally {
            referenceResolutionService.clearCaches();
        }

        if (options.dryRun()) {
            created = 0;
            updated = 0;
        }

        return new ImportSummaryDto(quizzes.size(), created, updated, skipped, failed, errors);
    }

    private ImportOutcome processSingle(QuizImportDto quiz,
                                        User actor,
                                        boolean hasModerationPermissions,
                                        QuizImportOptions options,
                                        DryRunCache dryRunCache) {
        validationService.validateQuiz(quiz, actor.getUsername(), options);

        Category category = resolveCategory(quiz.category(), actor.getUsername(), options, dryRunCache);
        Set<Tag> tags = resolveTags(quiz.tags(), actor.getUsername(), options, dryRunCache);

        UpsertStrategy strategy = options.strategy();
        String importContentHash = null;
        if (strategy == UpsertStrategy.UPSERT_BY_CONTENT_HASH || strategy == UpsertStrategy.SKIP_ON_DUPLICATE) {
            importContentHash = contentHashUtil.calculateImportContentHash(quiz);
        }

        return switch (strategy) {
            case CREATE_ONLY -> handleCreateOnly(quiz, actor, category, tags, options, hasModerationPermissions, importContentHash);
            case UPSERT_BY_ID -> handleUpsertById(quiz, actor, category, tags, options, hasModerationPermissions);
            case UPSERT_BY_CONTENT_HASH -> handleUpsertByContentHash(quiz, actor, category, tags, options, hasModerationPermissions, importContentHash);
            case SKIP_ON_DUPLICATE -> handleSkipOnDuplicate(quiz, actor, category, tags, options, hasModerationPermissions, importContentHash);
        };
    }

    private ImportOutcome handleCreateOnly(QuizImportDto dto,
                                           User actor,
                                           Category category,
                                           Set<Tag> tags,
                                           QuizImportOptions options,
                                           boolean hasModerationPermissions,
                                           String importContentHash) {
        if (options.dryRun()) {
            return ImportOutcome.CREATED;
        }
        User creator = userRepository.getReferenceById(actor.getId());
        Quiz quiz = quizImportAssembler.toEntity(dto, creator, category, tags, UpsertStrategy.CREATE_ONLY);
        applyCreateRules(quiz, hasModerationPermissions);
        if (importContentHash != null) {
            quiz.setImportContentHash(importContentHash);
        }
        attachQuestions(quiz, dto, UpsertStrategy.CREATE_ONLY);
        quizRepository.save(quiz);
        return ImportOutcome.CREATED;
    }

    private ImportOutcome handleUpsertById(QuizImportDto dto,
                                          User actor,
                                          Category category,
                                          Set<Tag> tags,
                                          QuizImportOptions options,
                                          boolean hasModerationPermissions) {
        if (dto.id() == null) {
            throw new ValidationException("UPSERT_BY_ID requires quiz id");
        }

        Optional<Quiz> existing = quizRepository.findByIdWithTagsAndQuestions(dto.id());
        if (existing.isPresent()) {
            Quiz quiz = existing.get();
            accessPolicy.requireOwnerOrAny(actor,
                    quiz.getCreator() != null ? quiz.getCreator().getId() : null,
                    PermissionName.QUIZ_MODERATE,
                    PermissionName.QUIZ_ADMIN);

            if (options.dryRun()) {
                return ImportOutcome.UPDATED;
            }

            applyUpdateFields(quiz, dto, category, tags);
            applyUpdateRules(quiz, hasModerationPermissions);
            updateHashes(quiz);
            attachQuestions(quiz, dto, UpsertStrategy.UPSERT_BY_ID);

            quizRepository.save(quiz);
            return ImportOutcome.UPDATED;
        }

        if (options.dryRun()) {
            return ImportOutcome.CREATED;
        }

        User creator = userRepository.getReferenceById(actor.getId());
        Quiz quiz = quizImportAssembler.toEntity(dto, creator, category, tags, UpsertStrategy.UPSERT_BY_ID);
        applyCreateRules(quiz, hasModerationPermissions);
        attachQuestions(quiz, dto, UpsertStrategy.UPSERT_BY_ID);
        quizRepository.save(quiz);
        return ImportOutcome.CREATED;
    }

    private ImportOutcome handleUpsertByContentHash(QuizImportDto dto,
                                                   User actor,
                                                   Category category,
                                                   Set<Tag> tags,
                                                   QuizImportOptions options,
                                                   boolean hasModerationPermissions,
                                                   String importContentHash) {
        if (importContentHash == null || importContentHash.isBlank()) {
            throw new ValidationException("UPSERT_BY_CONTENT_HASH requires import content hash");
        }

        Optional<Quiz> existing = quizRepository.findByCreatorIdAndImportContentHashWithTagsAndQuestions(
                actor.getId(), importContentHash);
        if (existing.isPresent()) {
            Quiz quiz = existing.get();
            if (options.dryRun()) {
                return ImportOutcome.UPDATED;
            }
            applyUpdateFields(quiz, dto, category, tags);
            applyUpdateRules(quiz, hasModerationPermissions);
            quiz.setImportContentHash(importContentHash);
            updateHashes(quiz);
            attachQuestions(quiz, dto, UpsertStrategy.UPSERT_BY_CONTENT_HASH);

            quizRepository.save(quiz);
            return ImportOutcome.UPDATED;
        }

        if (options.dryRun()) {
            return ImportOutcome.CREATED;
        }

        User creator = userRepository.getReferenceById(actor.getId());
        Quiz quiz = quizImportAssembler.toEntity(dto, creator, category, tags, UpsertStrategy.UPSERT_BY_CONTENT_HASH);
        applyCreateRules(quiz, hasModerationPermissions);
        quiz.setImportContentHash(importContentHash);
        attachQuestions(quiz, dto, UpsertStrategy.UPSERT_BY_CONTENT_HASH);
        quizRepository.save(quiz);
        return ImportOutcome.CREATED;
    }

    private ImportOutcome handleSkipOnDuplicate(QuizImportDto dto,
                                               User actor,
                                               Category category,
                                               Set<Tag> tags,
                                               QuizImportOptions options,
                                               boolean hasModerationPermissions,
                                               String importContentHash) {
        if (dto.id() != null && quizRepository.existsById(dto.id())) {
            return ImportOutcome.SKIPPED;
        }
        if (importContentHash == null || importContentHash.isBlank()) {
            importContentHash = contentHashUtil.calculateImportContentHash(dto);
        }
        Optional<Quiz> existing = quizRepository.findByCreatorIdAndImportContentHashWithTagsAndQuestions(
                actor.getId(), importContentHash);
        if (existing.isPresent()) {
            return ImportOutcome.SKIPPED;
        }

        if (options.dryRun()) {
            return ImportOutcome.CREATED;
        }

        User creator = userRepository.getReferenceById(actor.getId());
        Quiz quiz = quizImportAssembler.toEntity(dto, creator, category, tags, UpsertStrategy.SKIP_ON_DUPLICATE);
        applyCreateRules(quiz, hasModerationPermissions);
        quiz.setImportContentHash(importContentHash);
        attachQuestions(quiz, dto, UpsertStrategy.SKIP_ON_DUPLICATE);
        quizRepository.save(quiz);
        return ImportOutcome.CREATED;
    }

    private void attachQuestions(Quiz quiz, QuizImportDto dto, UpsertStrategy strategy) {
        if (dto.questions() == null) {
            return;
        }
        Set<Question> mapped = new HashSet<>();
        Map<UUID, Question> existingById = new HashMap<>();
        if (strategy == UpsertStrategy.UPSERT_BY_ID) {
            List<UUID> ids = dto.questions().stream()
                    .map(QuestionImportDto::id)
                    .filter(id -> id != null)
                    .distinct()
                    .toList();
            if (!ids.isEmpty()) {
                existingById = questionRepository.findAllById(ids).stream()
                        .collect(Collectors.toMap(Question::getId, q -> q));
            }
        }

        for (QuestionImportDto questionDto : dto.questions()) {
            if (questionDto == null) {
                continue;
            }
            Question mappedQuestion = questionImportMapper.toEntity(questionDto, strategy);
            if (strategy == UpsertStrategy.UPSERT_BY_ID && questionDto.id() != null) {
                Question existing = existingById.get(questionDto.id());
                if (existing != null) {
                    applyQuestionUpdate(existing, mappedQuestion);
                    mapped.add(existing);
                    continue;
                }
            }
            mapped.add(mappedQuestion);
        }

        quiz.getQuestions().clear();
        quiz.getQuestions().addAll(mapped);
    }

    private void applyQuestionUpdate(Question target, Question source) {
        target.setType(source.getType());
        target.setDifficulty(source.getDifficulty());
        target.setQuestionText(source.getQuestionText());
        target.setContent(source.getContent());
        target.setHint(source.getHint());
        target.setExplanation(source.getExplanation());
        target.setAttachmentAssetId(source.getAttachmentAssetId());
        target.setAttachmentUrl(source.getAttachmentUrl());
    }

    private void applyUpdateFields(Quiz quiz, QuizImportDto dto, Category category, Set<Tag> tags) {
        if (dto.title() != null) {
            quiz.setTitle(dto.title());
        }
        if (dto.description() != null) {
            quiz.setDescription(dto.description());
        }
        if (dto.visibility() != null) {
            quiz.setVisibility(dto.visibility());
        }
        if (dto.difficulty() != null) {
            quiz.setDifficulty(dto.difficulty());
        }
        if (dto.estimatedTime() != null) {
            quiz.setEstimatedTime(dto.estimatedTime());
        }
        if (category != null) {
            quiz.setCategory(category);
        }
        if (tags != null) {
            quiz.setTags(tags);
        }
    }

    private void applyCreateRules(Quiz quiz, boolean hasModerationPermissions) {
        if (!hasModerationPermissions) {
            quiz.setVisibility(Visibility.PRIVATE);
            quiz.setStatus(QuizStatus.DRAFT);
            return;
        }
        if (quiz.getVisibility() == Visibility.PUBLIC) {
            quiz.setStatus(QuizStatus.PUBLISHED);
        } else if (quiz.getStatus() == null) {
            quiz.setStatus(QuizStatus.DRAFT);
        }
    }

    private void applyUpdateRules(Quiz quiz, boolean hasModerationPermissions) {
        if (!hasModerationPermissions && quiz.getVisibility() == Visibility.PUBLIC) {
            quiz.setVisibility(Visibility.PRIVATE);
        }
        if (quiz.getStatus() == QuizStatus.PENDING_REVIEW) {
            quiz.setStatus(QuizStatus.DRAFT);
        }
        if (hasModerationPermissions && quiz.getVisibility() == Visibility.PUBLIC) {
            quiz.setStatus(QuizStatus.PUBLISHED);
        }
    }

    private void updateHashes(Quiz quiz) {
        String beforeContentHash = quiz.getContentHash();
        var dto = quizMapper.toDto(quiz);
        String newContentHash = quizHashCalculator.calculateContentHash(dto);
        String newPresentationHash = quizHashCalculator.calculatePresentationHash(dto);
        quiz.setContentHash(newContentHash);
        quiz.setPresentationHash(newPresentationHash);

        if (beforeContentHash != null
                && quiz.getStatus() == QuizStatus.PUBLISHED
                && !beforeContentHash.equalsIgnoreCase(newContentHash)) {
            quiz.setStatus(QuizStatus.PENDING_REVIEW);
            quiz.setReviewedAt(null);
            quiz.setReviewedBy(null);
            quiz.setRejectionReason(null);
        }
    }

    private Category resolveCategory(String categoryName,
                                     String username,
                                     QuizImportOptions options,
                                     DryRunCache dryRunCache) {
        Category resolved;
        if (options.dryRun()) {
            resolved = resolveCategoryDryRun(categoryName, options.autoCreateCategory(), dryRunCache);
        } else {
            resolved = referenceResolutionService.resolveCategory(categoryName, options.autoCreateCategory(), username);
        }
        if (resolved != null) {
            return resolved;
        }
        return defaultCategory();
    }

    private Set<Tag> resolveTags(List<String> tagNames,
                                 String username,
                                 QuizImportOptions options,
                                 DryRunCache dryRunCache) {
        if (options.dryRun()) {
            return resolveTagsDryRun(tagNames, options.autoCreateTags(), dryRunCache);
        }
        return referenceResolutionService.resolveTags(tagNames, options.autoCreateTags(), username);
    }

    private Category resolveCategoryDryRun(String categoryName, boolean autoCreate, DryRunCache cache) {
        if (categoryName == null || categoryName.isBlank()) {
            return null;
        }
        String trimmed = categoryName.trim();
        String normalized = normalizeName(trimmed);
        Category cached = cache.categories.get(normalized);
        if (cached != null) {
            return cached;
        }
        Category resolved = categoryRepository.findByNameIgnoreCase(trimmed)
                .orElseGet(() -> {
                    if (!autoCreate) {
                        throw new ResourceNotFoundException("Category " + trimmed + " not found");
                    }
                    Category created = new Category();
                    created.setName(trimmed);
                    created.setDescription(DEFAULT_CATEGORY_DESCRIPTION);
                    return created;
                });
        cache.categories.put(normalized, resolved);
        return resolved;
    }

    private Set<Tag> resolveTagsDryRun(List<String> tagNames, boolean autoCreate, DryRunCache cache) {
        if (tagNames == null || tagNames.isEmpty()) {
            return new LinkedHashSet<>();
        }
        LinkedHashSet<Tag> resolved = new LinkedHashSet<>();
        LinkedHashSet<String> unresolved = new LinkedHashSet<>();
        Map<String, String> normalizedToOriginal = new HashMap<>();

        for (String tagName : tagNames) {
            if (tagName == null || tagName.isBlank()) {
                continue;
            }
            String trimmed = tagName.trim();
            String normalized = normalizeName(trimmed);

            Tag cached = cache.tags.get(normalized);
            if (cached != null) {
                resolved.add(cached);
                continue;
            }

            unresolved.add(normalized);
            normalizedToOriginal.putIfAbsent(normalized, trimmed);
        }

        if (!unresolved.isEmpty()) {
            List<Tag> existing = tagRepository.findByNameInIgnoreCase(new ArrayList<>(unresolved));
            for (Tag tag : existing) {
                String normalized = normalizeName(tag.getName());
                cache.tags.put(normalized, tag);
                resolved.add(tag);
            }
            unresolved.removeIf(cache.tags::containsKey);
        }

        if (!unresolved.isEmpty()) {
            if (!autoCreate) {
                throw new ResourceNotFoundException("Tag(s) not found: " + String.join(", ",
                        originalNames(unresolved, normalizedToOriginal)));
            }
            for (String normalized : unresolved) {
                String name = normalizedToOriginal.getOrDefault(normalized, normalized);
                Tag tag = new Tag();
                tag.setName(name);
                cache.tags.put(normalized, tag);
                resolved.add(tag);
            }
        }

        return resolved;
    }

    private Category defaultCategory() {
        UUID defaultCategoryId = quizDefaultsProperties.getDefaultCategoryId();
        return categoryRepository.findById(defaultCategoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Default category " + defaultCategoryId + " not found"));
    }

    private User resolveUser(String username) {
        return userRepository.findByUsernameWithRolesAndPermissions(username)
                .or(() -> userRepository.findByEmailWithRolesAndPermissions(username))
                .orElseThrow(() -> new ResourceNotFoundException("User " + username + " not found"));
    }

    private String normalizeName(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private List<String> originalNames(Set<String> normalized, Map<String, String> normalizedToOriginal) {
        List<String> names = new ArrayList<>();
        for (String name : normalized) {
            names.add(normalizedToOriginal.getOrDefault(name, name));
        }
        return names;
    }

    private enum ImportOutcome {
        CREATED,
        UPDATED,
        SKIPPED
    }

    private static final class DryRunCache {
        private final Map<String, Category> categories = new HashMap<>();
        private final Map<String, Tag> tags = new HashMap<>();
    }
}
