package uk.gegc.quizmaker.features.quiz.application.imports.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import uk.gegc.quizmaker.BaseUnitTest;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.category.domain.repository.CategoryRepository;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.repository.QuestionRepository;
import uk.gegc.quizmaker.features.question.infra.mapping.QuestionImportMapper;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.ImportSummaryDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuestionImportDto;
import uk.gegc.quizmaker.features.quiz.api.dto.imports.QuizImportDto;
import uk.gegc.quizmaker.features.quiz.application.QuizHashCalculator;
import uk.gegc.quizmaker.features.quiz.application.imports.ContentHashUtil;
import uk.gegc.quizmaker.features.quiz.application.imports.ImportParser;
import uk.gegc.quizmaker.features.quiz.application.imports.ImportParserFactory;
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
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.shared.security.AccessPolicy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("QuizImportService")
class QuizImportServiceImplTest extends BaseUnitTest {

    private static final UUID ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID CATEGORY_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID TAG_ID = UUID.fromString("00000000-0000-0000-0000-000000000030");
    private static final String USERNAME = "user";

    @Mock
    ImportParserFactory parserFactory;
    @Mock
    ImportParser parser;
    @Mock
    QuizImportValidationService validationService;
    @Mock
    ReferenceResolutionService referenceResolutionService;
    @Mock
    QuizImportAssembler quizImportAssembler;
    @Mock
    QuestionImportMapper questionImportMapper;
    @Mock
    ContentHashUtil contentHashUtil;
    @Mock
    QuizRepository quizRepository;
    @Mock
    QuestionRepository questionRepository;
    @Mock
    CategoryRepository categoryRepository;
    @Mock
    TagRepository tagRepository;
    @Mock
    QuizDefaultsProperties quizDefaultsProperties;
    @Mock
    UserRepository userRepository;
    @Mock
    AccessPolicy accessPolicy;
    @Mock
    PlatformTransactionManager transactionManager;
    @Mock
    QuizHashCalculator quizHashCalculator;
    @Mock
    QuizMapper quizMapper;

    @InjectMocks
    QuizImportServiceImpl service;

    private User actor;
    private Authentication authentication;
    private Category category;
    private Set<Tag> tags;

    @BeforeEach
    void setUp() {
        actor = new User();
        actor.setId(ACTOR_ID);
        actor.setUsername(USERNAME);

        authentication = new UsernamePasswordAuthenticationToken(USERNAME, "password");

        category = new Category();
        category.setId(CATEGORY_ID);
        category.setName("Category");

        Tag tag = new Tag();
        tag.setId(TAG_ID);
        tag.setName("tag");
        tags = new HashSet<>(List.of(tag));

        when(userRepository.findByUsernameWithRolesAndPermissions(USERNAME)).thenReturn(Optional.of(actor));
        when(accessPolicy.hasAny(any(), any(), any())).thenReturn(true);
        when(parserFactory.getParser(any())).thenReturn(parser);
        lenient().when(referenceResolutionService.resolveCategory(any(), anyBoolean(), anyString())).thenReturn(category);
        lenient().when(referenceResolutionService.resolveTags(any(), anyBoolean(), anyString())).thenReturn(tags);
        lenient().when(quizImportAssembler.toEntity(any(), any(), any(), any(), any())).thenReturn(new Quiz());
        lenient().when(userRepository.getReferenceById(any())).thenReturn(actor);
        lenient().when(quizMapper.toDto(any())).thenReturn(minimalQuizDto());

        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        lenient().doNothing().when(transactionManager).commit(any());
        lenient().doNothing().when(transactionManager).rollback(any());
    }

    @Test
    @DisplayName("importQuizzes dry run does not persist quizzes")
    void importQuizzes_dryRun_doesNotPersistQuizzes() {
        QuizImportOptions options = options(UpsertStrategy.CREATE_ONLY, true, false, false);
        QuizImportDto quiz = quizDto(UUID.randomUUID(), "Category", null, List.of(), Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quiz));
        when(categoryRepository.findByNameIgnoreCase("Category")).thenReturn(Optional.of(category));

        ImportSummaryDto summary = service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        assertThat(summary.total()).isEqualTo(1);
        assertThat(summary.created()).isZero();
        verify(quizRepository, never()).save(any());
    }

    @Test
    @DisplayName("importQuizzes dry run does not persist tags or categories")
    void importQuizzes_dryRun_doesNotPersistTagsOrCategories() {
        QuizImportOptions options = options(UpsertStrategy.CREATE_ONLY, true, true, true);
        QuizImportDto quiz = quizDto(UUID.randomUUID(), "New Category", List.of("New Tag"), List.of(), Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quiz));
        when(categoryRepository.findByNameIgnoreCase("New Category")).thenReturn(Optional.empty());
        when(tagRepository.findByNameInIgnoreCase(any())).thenReturn(List.of());

        ImportSummaryDto summary = service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        assertThat(summary.total()).isEqualTo(1);
        verify(categoryRepository, never()).save(any());
        verify(tagRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("importQuizzes dry run zeroes created and updated counts")
    void importQuizzes_dryRun_zeroesCreatedAndUpdatedCounts() {
        QuizImportOptions options = options(UpsertStrategy.UPSERT_BY_ID, true, false, false);
        UUID existingId = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID newId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        QuizImportDto existingDto = quizDto(existingId, "Category", null, List.of(), Visibility.PRIVATE);
        QuizImportDto newDto = quizDto(newId, "Category", null, List.of(), Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(existingDto, newDto));
        when(categoryRepository.findByNameIgnoreCase("Category")).thenReturn(Optional.of(category));
        when(quizRepository.findByIdWithTagsAndQuestions(existingId)).thenReturn(Optional.of(existingQuiz(existingId)));
        when(quizRepository.findByIdWithTagsAndQuestions(newId)).thenReturn(Optional.empty());

        ImportSummaryDto summary = service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        assertThat(summary.total()).isEqualTo(2);
        assertThat(summary.created()).isZero();
        assertThat(summary.updated()).isZero();
        assertThat(summary.failed()).isZero();
    }

    @Test
    @DisplayName("importQuizzes reports error when UPSERT_BY_ID missing quiz id")
    void importQuizzes_upsertById_missingQuizId_reportsError() {
        QuizImportOptions options = options(UpsertStrategy.UPSERT_BY_ID, false, false, false);
        QuizImportDto quiz = quizDto(null, "Category", null, List.of(), Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quiz));

        ImportSummaryDto summary = service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        assertThat(summary.failed()).isEqualTo(1);
        assertThat(summary.errors()).hasSize(1);
        assertThat(summary.errors().get(0).message()).contains("UPSERT_BY_ID requires quiz id");
        verify(quizRepository, never()).save(any());
    }

    @Test
    @DisplayName("importQuizzes skip-on-duplicate prefers id match")
    void importQuizzes_skipOnDuplicate_prefersIdMatch() {
        QuizImportOptions options = options(UpsertStrategy.SKIP_ON_DUPLICATE, false, false, false);
        UUID quizId = UUID.fromString("00000000-0000-0000-0000-000000000201");
        QuizImportDto quiz = quizDto(quizId, "Category", null, List.of(), Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quiz));
        when(quizRepository.existsById(quizId)).thenReturn(true);

        ImportSummaryDto summary = service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        assertThat(summary.skipped()).isEqualTo(1);
        verify(quizRepository, never()).findByCreatorIdAndImportContentHashWithTagsAndQuestions(any(), any());
    }

    @Test
    @DisplayName("importQuizzes upsert-by-content-hash scopes to creator")
    void importQuizzes_upsertByContentHash_scopedToCreator() {
        QuizImportOptions options = options(UpsertStrategy.UPSERT_BY_CONTENT_HASH, false, false, false);
        QuizImportDto quiz = quizDto(UUID.randomUUID(), "Category", null, List.of(), Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quiz));
        when(contentHashUtil.calculateImportContentHash(quiz)).thenReturn("HASH");
        when(quizRepository.findByCreatorIdAndImportContentHashWithTagsAndQuestions(ACTOR_ID, "HASH"))
                .thenReturn(Optional.empty());

        ImportSummaryDto summary = service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        assertThat(summary.created()).isEqualTo(1);
        verify(quizRepository).findByCreatorIdAndImportContentHashWithTagsAndQuestions(eq(ACTOR_ID), eq("HASH"));
        verify(quizRepository).save(any());
    }

    @Test
    @DisplayName("importQuizzes skip-on-duplicate scopes to creator")
    void importQuizzes_skipOnDuplicate_scopedToCreator() {
        QuizImportOptions options = options(UpsertStrategy.SKIP_ON_DUPLICATE, false, false, false);
        QuizImportDto quiz = quizDto(null, "Category", null, List.of(), Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quiz));
        when(contentHashUtil.calculateImportContentHash(quiz)).thenReturn("HASH");
        when(quizRepository.findByCreatorIdAndImportContentHashWithTagsAndQuestions(ACTOR_ID, "HASH"))
                .thenReturn(Optional.empty());

        ImportSummaryDto summary = service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        assertThat(summary.created()).isEqualTo(1);
        verify(quizRepository).findByCreatorIdAndImportContentHashWithTagsAndQuestions(eq(ACTOR_ID), eq("HASH"));
        verify(quizRepository).save(any());
    }

    @Test
    @DisplayName("importQuizzes CREATE_ONLY accepts null questions")
    void importQuizzes_createOnly_nullQuestions_createsQuizWithNoQuestions() {
        QuizImportOptions options = options(UpsertStrategy.CREATE_ONLY, false, false, false);
        QuizImportDto quizDto = quizDto(UUID.randomUUID(), "Category", null, null, Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quizDto));
        Quiz quiz = new Quiz();
        when(quizImportAssembler.toEntity(any(), any(), any(), any(), any())).thenReturn(quiz);

        service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository).save(captor.capture());
        assertThat(captor.getValue().getQuestions()).isEmpty();
    }

    @Test
    @DisplayName("importQuizzes upsert-by-content-hash keeps existing questions when null")
    void importQuizzes_upsertByContentHash_nullQuestions_doesNotReplaceQuestions() {
        QuizImportOptions options = options(UpsertStrategy.UPSERT_BY_CONTENT_HASH, false, false, false);
        QuizImportDto quizDto = quizDto(UUID.randomUUID(), "Category", null, null, Visibility.PRIVATE);
        when(parser.parse(any(), any())).thenReturn(List.of(quizDto));
        when(contentHashUtil.calculateImportContentHash(quizDto)).thenReturn("HASH");
        when(quizHashCalculator.calculateContentHash(any())).thenReturn("NEW");
        when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("PRESENT");

        Question existingQuestion = new Question();
        existingQuestion.setId(UUID.fromString("00000000-0000-0000-0000-000000000301"));
        Set<Question> questions = new HashSet<>(Set.of(existingQuestion));
        Quiz existing = existingQuiz(UUID.fromString("00000000-0000-0000-0000-000000000300"));
        existing.setQuestions(questions);
        when(quizRepository.findByCreatorIdAndImportContentHashWithTagsAndQuestions(ACTOR_ID, "HASH"))
                .thenReturn(Optional.of(existing));

        service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository).save(captor.capture());
        assertThat(captor.getValue().getQuestions()).containsExactlyInAnyOrderElementsOf(questions);
    }

    @Test
    @DisplayName("importQuizzes content change sets published quiz to pending review")
    void importQuizzes_publishedQuiz_contentChange_setsPendingReview() {
        QuizImportOptions options = options(UpsertStrategy.UPSERT_BY_ID, false, false, false);
        UUID quizId = UUID.fromString("00000000-0000-0000-0000-000000000401");
        QuizImportDto quizDto = quizDto(quizId, "Category", null, null, Visibility.PUBLIC);
        when(parser.parse(any(), any())).thenReturn(List.of(quizDto));
        when(quizHashCalculator.calculateContentHash(any())).thenReturn("NEW");
        when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("PRESENT");

        Quiz existing = existingQuiz(quizId);
        existing.setVisibility(Visibility.PUBLIC);
        existing.setStatus(QuizStatus.PUBLISHED);
        existing.setContentHash("OLD");
        existing.setReviewedAt(Instant.parse("2024-01-01T00:00:00Z"));
        existing.setReviewedBy(new User());
        existing.setRejectionReason("Previous rejection");
        when(quizRepository.findByIdWithTagsAndQuestions(quizId)).thenReturn(Optional.of(existing));

        service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository).save(captor.capture());
        Quiz saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(QuizStatus.PENDING_REVIEW);
        assertThat(saved.getReviewedAt()).isNull();
        assertThat(saved.getReviewedBy()).isNull();
        assertThat(saved.getRejectionReason()).isNull();
    }

    @Test
    @DisplayName("importQuizzes unchanged content keeps published status")
    void importQuizzes_publishedQuiz_noContentChange_keepsPublished() {
        QuizImportOptions options = options(UpsertStrategy.UPSERT_BY_ID, false, false, false);
        UUID quizId = UUID.fromString("00000000-0000-0000-0000-000000000402");
        QuizImportDto quizDto = quizDto(quizId, "Category", null, null, Visibility.PUBLIC);
        when(parser.parse(any(), any())).thenReturn(List.of(quizDto));
        when(quizHashCalculator.calculateContentHash(any())).thenReturn("SAME");
        when(quizHashCalculator.calculatePresentationHash(any())).thenReturn("PRESENT");

        Quiz existing = existingQuiz(quizId);
        existing.setVisibility(Visibility.PUBLIC);
        existing.setStatus(QuizStatus.PUBLISHED);
        existing.setContentHash("SAME");
        when(quizRepository.findByIdWithTagsAndQuestions(quizId)).thenReturn(Optional.of(existing));

        service.importQuizzes(emptyInput(), ExportFormat.JSON_EDITABLE, options, authentication);

        ArgumentCaptor<Quiz> captor = ArgumentCaptor.forClass(Quiz.class);
        verify(quizRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(QuizStatus.PUBLISHED);
    }

    private InputStream emptyInput() {
        return new ByteArrayInputStream(new byte[0]);
    }

    private QuizImportOptions options(UpsertStrategy strategy, boolean dryRun, boolean autoTags, boolean autoCategory) {
        return new QuizImportOptions(strategy, dryRun, autoTags, autoCategory, 10);
    }

    private QuizImportDto quizDto(UUID id,
                                  String categoryName,
                                  List<String> tags,
                                  List<QuestionImportDto> questions,
                                  Visibility visibility) {
        return new QuizImportDto(
                1,
                id,
                "Quiz Title",
                "Quiz Description",
                visibility,
                Difficulty.EASY,
                10,
                tags,
                categoryName,
                ACTOR_ID,
                questions,
                Instant.parse("2024-01-01T00:00:00Z"),
                Instant.parse("2024-01-02T00:00:00Z")
        );
    }

    private Quiz existingQuiz(UUID id) {
        Quiz quiz = new Quiz();
        quiz.setId(id);
        quiz.setCreator(actor);
        quiz.setCategory(category);
        quiz.setTags(new HashSet<>(tags));
        quiz.setTitle("Quiz Title");
        quiz.setDescription("Quiz Description");
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setEstimatedTime(10);
        quiz.setQuestions(new HashSet<>());
        return quiz;
    }

    private QuizDto minimalQuizDto() {
        return new QuizDto(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                0,
                null,
                null
        );
    }
}
