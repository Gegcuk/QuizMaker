package uk.gegc.quizmaker.features.quiz.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.Question;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.features.quiz.application.generation.QuizGenerationCoverageService;
import uk.gegc.quizmaker.features.quiz.application.generation.impl.QuizAssemblyServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.impl.QuizFreshnessServiceImpl;
import uk.gegc.quizmaker.features.quiz.application.query.GenerationQueuePositionService;
import uk.gegc.quizmaker.features.quiz.application.query.impl.QuizGenerationProgressInvariantMonitor;
import uk.gegc.quizmaker.features.quiz.application.query.impl.QuizQueryServiceImpl;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizOwnerFreshnessRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.infra.mapping.QuizMapper;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.shared.config.FeatureFlags;
import uk.gegc.quizmaker.shared.exception.ResourceNotFoundException;
import uk.gegc.quizmaker.shared.security.AppPermissionEvaluator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@DataJpaTest
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("db-serial")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.query.fail_on_pagination_over_collection_fetch=true"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@WithMockUser(authorities = "QUIZ_READ")
@Import({QuizFreshnessServiceImpl.class, QuizQueryServiceImpl.class, QuizMapper.class,
        QuizAssemblyServiceImpl.class, QuizFreshnessMySqlIntegrationTest.MethodSecurity.class})
class QuizFreshnessMySqlIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class MethodSecurity {
    }

    @Autowired private QuizFreshnessService freshnessService;
    @Autowired private QuizQueryServiceImpl queryService;
    @Autowired private QuizAssemblyServiceImpl assemblyService;
    @Autowired private QuizRepository quizRepository;
    @Autowired private QuizOwnerFreshnessRepository freshnessRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AppPermissionEvaluator permissionEvaluator;
    @MockitoBean private QuizGenerationJobService jobService;
    @MockitoBean private FeatureFlags featureFlags;
    @MockitoBean private QuizGenerationProgressInvariantMonitor progressMonitor;
    @MockitoBean private QuizGenerationCoverageService coverageService;
    @MockitoBean private GenerationQueuePositionService queuePositionService;

    private TransactionTemplate transaction;
    private User owner;
    private final List<UUID> categories = new ArrayList<>();
    private final List<UUID> tags = new ArrayList<>();
    private final List<UUID> questions = new ArrayList<>();

    @BeforeEach
    void fixture() {
        transaction = new TransactionTemplate(transactionManager);
        owner = transaction.execute(status -> {
            User user = new User();
            user.setUsername("fresh-" + UUID.randomUUID());
            user.setEmail(user.getUsername() + "@example.test");
            user.setHashedPassword("fixture-only");
            entityManager.persist(user);
            return user;
        });
        when(permissionEvaluator.getCurrentUser()).thenReturn(owner);
    }

    @AfterEach
    void cleanup() {
        if (owner == null) return;
        transaction.executeWithoutResult(status -> {
            byte[] ownerKey = uuidBytes(owner.getId());
            jdbc.update("DELETE FROM quiz_questions WHERE quiz_id IN (SELECT quiz_id FROM quizzes WHERE creator_id=?)", ownerKey);
            jdbc.update("DELETE FROM quiz_tags WHERE quiz_id IN (SELECT quiz_id FROM quizzes WHERE creator_id=?)", ownerKey);
            jdbc.update("DELETE FROM quiz_owner_freshness WHERE quiz_id IN (SELECT quiz_id FROM quizzes WHERE creator_id=?)", ownerKey);
            jdbc.update("DELETE FROM quizzes WHERE creator_id=?", ownerKey);
            questions.forEach(id -> jdbc.update("DELETE FROM questions WHERE id=?", uuidBytes(id)));
            tags.forEach(id -> jdbc.update("DELETE FROM tags WHERE tag_id=?", uuidBytes(id)));
            categories.forEach(id -> jdbc.update("DELETE FROM categories WHERE category_id=?", uuidBytes(id)));
            jdbc.update("DELETE FROM users WHERE user_id=?", ownerKey);
        });
    }

    @Test
    void freshnessCannotBePublishedOutsideAssemblyTransaction() {
        assertThatThrownBy(() -> freshnessService.registerGeneratedQuiz(UUID.randomUUID()))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void everyGeneratedQuizIsFreshAndAssemblyRollbackPublishesNothing() {
        List<UUID> generated = transaction.execute(status -> {
            Quiz fixture = persistQuiz("Source");
            GenerateQuizFromDocumentRequest request = request();
            List<Question> content = List.copyOf(fixture.getQuestions());
            List<UUID> ids = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                ids.add(assemblyService.createChunkQuiz(owner, content, index, request,
                        fixture.getCategory(), Set.copyOf(fixture.getTags()), request.documentId()).getId());
            }
            ids.add(assemblyService.createConsolidatedQuiz(owner, content, request,
                    fixture.getCategory(), Set.copyOf(fixture.getTags()), request.documentId(), 3).getId());
            return ids;
        });
        assertThat(freshnessService.findNewQuizIds(owner.getId(), generated)).containsExactlyInAnyOrderElementsOf(generated);
        long before = transaction.execute(status -> (long) quizRepository.findByCreatorId(owner.getId()).size());
        long markersBefore = freshnessRepository.count();
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            Quiz fixture = quizRepository.findByIdWithTagsAndQuestions(generated.get(0)).orElseThrow();
            assemblyService.createConsolidatedQuiz(owner, List.copyOf(fixture.getQuestions()), request(),
                    fixture.getCategory(), Set.copyOf(fixture.getTags()), UUID.randomUUID(), 3);
            throw new IllegalStateException("synthetic settlement failure");
        })).isInstanceOf(IllegalStateException.class);
        long afterRollback = transaction.execute(status -> (long) quizRepository.findByCreatorId(owner.getId()).size());
        assertThat(afterRollback).isEqualTo(before);
        assertThat(freshnessRepository.count()).isEqualTo(markersBefore);
        assertThat(freshnessService.findNewQuizIds(owner.getId(), generated)).hasSize(4);
    }

    @Test
    void acknowledgementIsMonotonicAcrossConcurrentRequestsAndDoesNotEditQuiz() throws Exception {
        UUID id = transaction.execute(status -> {
            Quiz quiz = persistQuiz("Concurrent");
            freshnessService.registerGeneratedQuiz(quiz.getId());
            return quiz.getId();
        });
        Quiz before = quizRepository.findById(id).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(6);
        try {
            var futures = java.util.stream.IntStream.range(0, 6).mapToObj(index -> executor.submit(() -> {
                SecurityContextHolder.getContext().setAuthentication(authentication());
                try {
                    assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                    return freshnessService.acknowledgeSeen(id);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            })).toList();
            start.countDown();
            for (var future : futures) assertThat(future.get(10, TimeUnit.SECONDS).isNew()).isFalse();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
        transaction.executeWithoutResult(status -> freshnessService.registerGeneratedQuiz(id));
        assertThat(freshnessService.findNewQuizIds(owner.getId(), List.of(id))).isEmpty();
        Quiz after = quizRepository.findById(id).orElseThrow();
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
    }

    @Test
    void failedAcknowledgementRollsBackAndExplicitRetryCanCommit() {
        UUID id = transaction.execute(status -> {
            Quiz quiz = persistQuiz("Rollback");
            freshnessService.registerGeneratedQuiz(quiz.getId());
            return quiz.getId();
        });
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            freshnessService.acknowledgeSeen(id);
            throw new IllegalStateException("synthetic commit failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(freshnessService.findNewQuizIds(owner.getId(), List.of(id))).containsExactly(id);
        assertThat(freshnessService.acknowledgeSeen(id).isNew()).isFalse();
        assertThat(freshnessService.findNewQuizIds(owner.getId(), List.of(id))).isEmpty();
    }

    @Test
    void legacyAndManualRowsStaySeenWhileWrongOwnerAndDeletedRowsFailClosed() {
        UUID id = transaction.execute(status -> persistQuiz("Legacy").getId());
        assertThat(freshnessService.acknowledgeSeen(id).isNew()).isFalse();
        assertThat(freshnessRepository.findById(id)).isEmpty();
        User wrongOwner = new User();
        wrongOwner.setId(UUID.randomUUID());
        when(permissionEvaluator.getCurrentUser()).thenReturn(wrongOwner);
        assertThatThrownBy(() -> freshnessService.acknowledgeSeen(id)).isInstanceOf(ResourceNotFoundException.class);
        when(permissionEvaluator.getCurrentUser()).thenReturn(owner);
        transaction.executeWithoutResult(status -> quizRepository.deleteById(id));
        assertThatThrownBy(() -> freshnessService.acknowledgeSeen(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void tagFilteringKeepsDistinctPagesAndExistingSortOrder() {
        List<String> tagNames = transaction.execute(status -> {
            Quiz alpha = persistQuiz("Alpha");
            Quiz zulu = persistQuiz("Zulu");
            persistQuiz("Excluded");
            Tag common = new Tag();
            common.setName("Common " + UUID.randomUUID());
            entityManager.persist(common);
            tags.add(common.getId());
            String otherName = alpha.getTags().iterator().next().getName();
            for (Quiz quiz : List.of(alpha, zulu)) {
                quiz.setTags(new java.util.HashSet<>(quiz.getTags()));
                quiz.getTags().add(common);
            }
            return List.of(common.getName(), otherName);
        });
        var criteria = new QuizSearchCriteria(null, tagNames, null, null, null);
        var first = queryService.getQuizzes(PageRequest.of(0, 1, Sort.by("title").descending()),
                criteria, "me", authentication());
        var second = queryService.getQuizzes(PageRequest.of(1, 1, Sort.by("title").descending()),
                criteria, "me", authentication());
        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).extracting(QuizDto::title).containsExactly("Zulu");
        assertThat(second.getContent()).extracting(QuizDto::title).containsExactly("Alpha");
        assertThat(first.getContent().get(0).isNew()).isFalse();
    }

    @ParameterizedTest(name = "owned pagination supports sorting by {0}")
    @ValueSource(strings = {"category.name", "creator.username"})
    void ownedPaginationSupportsExistingToOneAssociationSorts(String property) {
        String commonTag = transaction.execute(status -> {
            Quiz zulu = persistQuiz("Zulu");
            Quiz alpha = persistQuiz("Alpha");
            Tag common = zulu.getTags().iterator().next();
            alpha.setTags(new java.util.HashSet<>(alpha.getTags()));
            alpha.getTags().add(common);
            for (Quiz quiz : List.of(alpha, zulu)) quiz.getCategory().setName(quiz.getTitle());
            return common.getName();
        });
        var criteria = new QuizSearchCriteria(null, List.of(commonTag), null, null, null);
        Sort sort = Sort.by(property).and(Sort.by("title"));

        var first = queryService.getQuizzes(PageRequest.of(0, 1, sort), criteria, "me", authentication());
        var second = queryService.getQuizzes(PageRequest.of(1, 1, sort), criteria, "me", authentication());

        assertThat(first.getTotalElements()).isEqualTo(2);
        assertThat(second.getTotalElements()).isEqualTo(2);
        assertThat(first.getContent()).extracting(QuizDto::title).containsExactly("Alpha");
        assertThat(second.getContent()).extracting(QuizDto::title).containsExactly("Zulu");
    }

    @Test
    void ownedPaginationAndSerializationHaveBoundedQueriesWithMultipleParents() {
        transaction.executeWithoutResult(status -> {
            for (int index = 0; index < 12; index++) {
                Quiz quiz = persistQuiz("Owned " + String.format("%02d", index));
                if (index % 2 == 0) freshnessService.registerGeneratedQuiz(quiz.getId());
            }
        });
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        List<Long> counts = new ArrayList<>();
        for (int pageSize : List.of(2, 8)) {
            statistics.clear();
            var page = queryService.getQuizzes(PageRequest.of(0, pageSize, Sort.by("title")),
                    new QuizSearchCriteria(null, null, null, null, null), "me", authentication());
            // DTO mapping has already traversed category, creator and tags outside this assertion.
            assertThat(page.getTotalElements()).isEqualTo(12);
            assertThat(page.getContent()).hasSize(pageSize);
            for (int index = 0; index < pageSize; index++) {
                QuizDto dto = page.getContent().get(index);
                assertThat(dto.title()).isEqualTo("Owned " + String.format("%02d", index));
                assertThat(dto.isNew()).isEqualTo(index % 2 == 0);
                assertThat(dto.questionCount()).isEqualTo(1);
                assertThat(dto.tagIds()).hasSize(1);
            }
            counts.add(statistics.getPrepareStatementCount());
        }
        assertThat(counts).containsExactly(6L, 6L);
        assertThat(freshnessService.findNewQuizIds(UUID.randomUUID(), quizRepository.findByCreatorId(owner.getId())
                .stream().map(Quiz::getId).toList())).isEmpty();
    }

    private Quiz persistQuiz(String title) {
        Category category = new Category();
        category.setName("Freshness " + UUID.randomUUID());
        entityManager.persist(category);
        categories.add(category.getId());
        Tag tag = new Tag();
        tag.setName("Freshness " + UUID.randomUUID());
        entityManager.persist(tag);
        tags.add(tag.getId());
        Question question = new Question();
        question.setType(QuestionType.TRUE_FALSE);
        question.setDifficulty(Difficulty.EASY);
        question.setQuestionText("This fixture has a correct boolean answer");
        question.setContent("{\"answer\":true}");
        entityManager.persist(question);
        questions.add(question.getId());
        Quiz quiz = new Quiz();
        quiz.setCreator(entityManager.getReference(User.class, owner.getId()));
        quiz.setCategory(category);
        quiz.setTitle(title);
        quiz.setVisibility(Visibility.PRIVATE);
        quiz.setDifficulty(Difficulty.EASY);
        quiz.setStatus(QuizStatus.DRAFT);
        quiz.setEstimatedTime(10);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setTags(Set.of(tag));
        quiz.setQuestions(Set.of(question));
        return quizRepository.save(quiz);
    }

    private GenerateQuizFromDocumentRequest request() {
        return new GenerateQuizFromDocumentRequest(UUID.randomUUID(), QuizScope.ENTIRE_DOCUMENT,
                null, null, null, "Generated", null, Map.of(QuestionType.TRUE_FALSE, 1),
                Difficulty.EASY, 1, null, List.of(), "en");
    }

    private UsernamePasswordAuthenticationToken authentication() {
        return new UsernamePasswordAuthenticationToken(owner.getUsername(), null, List.of(new SimpleGrantedAuthority("QUIZ_READ")));
    }

    private byte[] uuidBytes(UUID id) {
        return java.nio.ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
