package uk.gegc.quizmaker.features.quiz.domain.repository;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import uk.gegc.quizmaker.features.category.domain.model.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.api.dto.export.QuizExportFilter;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.QuizStatus;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.features.tag.domain.model.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test-mysql")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create"
})
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("QuizExportSpecifications Tests")
class QuizExportSpecificationsTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private QuizRepository quizRepository;

    private User testUser;
    private Category testCategory;

    @BeforeEach
    void setUp() {
        // Create test user
        testUser = new User();
        testUser.setUsername("testuser");
        testUser.setEmail("test@example.com");
        testUser.setHashedPassword("hashed");
        testUser.setActive(true);
        testUser.setDeleted(false);
        entityManager.persist(testUser);

        // Create test category
        testCategory = new Category();
        testCategory.setName("General");
        entityManager.persist(testCategory);

        entityManager.flush();
    }

    @Test
    @DisplayName("build: public scope adds Visibility.PUBLIC and QuizStatus.PUBLISHED predicates")
    void build_publicScope_addsVisibilityAndStatusPredicates() {
        // Given - create quizzes with different visibility and status
        Quiz publicPublished = createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        Quiz publicDraft = createQuiz("Public Draft", Visibility.PUBLIC, QuizStatus.DRAFT);
        Quiz privatePublished = createQuiz("Private Published", Visibility.PRIVATE, QuizStatus.PUBLISHED);
        Quiz privateDraft = createQuiz("Private Draft", Visibility.PRIVATE, QuizStatus.DRAFT);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - only public + published should be returned
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Public Published");
        assertThat(results.get(0).getVisibility()).isEqualTo(Visibility.PUBLIC);
        assertThat(results.get(0).getStatus()).isEqualTo(QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("build: me scope does not add visibility/status predicates")
    void build_meScope_noVisibilityStatusPredicates() {
        // Given
        Quiz publicPublished = createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        Quiz privateDraft = createQuiz("Private Draft", Visibility.PRIVATE, QuizStatus.DRAFT);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, testUser.getId(), null, "me", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - both quizzes should be returned (no visibility/status filter)
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("build: all scope does not add visibility/status predicates")
    void build_allScope_noVisibilityStatusPredicates() {
        // Given
        Quiz publicPublished = createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        Quiz privateDraft = createQuiz("Private Draft", Visibility.PRIVATE, QuizStatus.DRAFT);
        Quiz rejected = createQuiz("Rejected", Visibility.PRIVATE, QuizStatus.REJECTED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "all", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - all quizzes should be returned
        assertThat(results).hasSize(3);
    }

    @Test
    @DisplayName("build: filters by authorId when provided")
    void build_withAuthorId_filtersCorrectly() {
        // Given
        User otherUser = new User();
        otherUser.setUsername("other");
        otherUser.setEmail("other@example.com");
        otherUser.setHashedPassword("hashed");
        otherUser.setActive(true);
        otherUser.setDeleted(false);
        entityManager.persist(otherUser);
        entityManager.flush();

        // Create quizzes with different creators initially (creator is immutable)
        Quiz userQuiz = new Quiz();
        userQuiz.setTitle("User Quiz");
        userQuiz.setDescription("Description");
        userQuiz.setVisibility(Visibility.PUBLIC);
        userQuiz.setDifficulty(Difficulty.MEDIUM);
        userQuiz.setStatus(QuizStatus.PUBLISHED);
        userQuiz.setEstimatedTime(10);
        userQuiz.setIsRepetitionEnabled(false);
        userQuiz.setIsTimerEnabled(false);
        userQuiz.setCreator(testUser);
        userQuiz.setCategory(testCategory);
        userQuiz.setIsDeleted(false);
        entityManager.persist(userQuiz);
        
        Quiz otherQuiz = new Quiz();
        otherQuiz.setTitle("Other Quiz");
        otherQuiz.setDescription("Description");
        otherQuiz.setVisibility(Visibility.PUBLIC);
        otherQuiz.setDifficulty(Difficulty.MEDIUM);
        otherQuiz.setStatus(QuizStatus.PUBLISHED);
        otherQuiz.setEstimatedTime(10);
        otherQuiz.setIsRepetitionEnabled(false);
        otherQuiz.setIsTimerEnabled(false);
        otherQuiz.setCreator(otherUser);
        otherQuiz.setCategory(testCategory);
        otherQuiz.setIsDeleted(false);
        entityManager.persist(otherQuiz);
        
        entityManager.flush();
        entityManager.clear();

        // Use scope=me (or all) so it doesn't filter by visibility/status
        QuizExportFilter filter = new QuizExportFilter(null, null, testUser.getId(), null, "me", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - only testUser's quiz
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("User Quiz");
    }

    @Test
    @DisplayName("build: filters by categoryIds when provided")
    void build_withCategoryIds_filtersCorrectly() {
        // Given
        Category cat1 = new Category();
        cat1.setName("Category 1");
        entityManager.persist(cat1);

        Category cat2 = new Category();
        cat2.setName("Category 2");
        entityManager.persist(cat2);

        Quiz quiz1 = createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz1.setCategory(cat1);
        
        Quiz quiz2 = createQuiz("Quiz 2", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz2.setCategory(cat2);
        
        Quiz quiz3 = createQuiz("Quiz 3", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz3.setCategory(testCategory);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(List.of(cat1.getId(), cat2.getId()), null, null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - only cat1 and cat2 quizzes
        assertThat(results).hasSize(2);
        assertThat(results).extracting("title").containsExactlyInAnyOrder("Quiz 1", "Quiz 2");
    }

    @Test
    @DisplayName("build: filters by tags case-insensitively")
    void build_withTags_filtersCaseInsensitively() {
        // Given
        Tag javaTag = new Tag();
        javaTag.setName("Java");
        entityManager.persist(javaTag);

        Tag springTag = new Tag();
        springTag.setName("Spring");
        entityManager.persist(springTag);

        Tag pythonTag = new Tag();
        pythonTag.setName("Python");
        entityManager.persist(pythonTag);

        Quiz quiz1 = createQuiz("Java Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz1.setTags(Set.of(javaTag));
        
        Quiz quiz2 = createQuiz("Spring Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz2.setTags(Set.of(springTag));
        
        Quiz quiz3 = createQuiz("Python Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz3.setTags(Set.of(pythonTag));
        
        entityManager.flush();
        entityManager.clear();

        // Search with different case
        QuizExportFilter filter = new QuizExportFilter(null, List.of("java", "SPRING"), null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - case-insensitive match
        assertThat(results).hasSize(2);
        assertThat(results).extracting("title").containsExactlyInAnyOrder("Java Quiz", "Spring Quiz");
    }

    @Test
    @DisplayName("build: filters by difficulty when provided")
    void build_withDifficulty_filtersCorrectly() {
        // Given
        Quiz easyQuiz = createQuiz("Easy Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        easyQuiz.setDifficulty(Difficulty.EASY);
        
        Quiz mediumQuiz = createQuiz("Medium Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        mediumQuiz.setDifficulty(Difficulty.MEDIUM);
        
        Quiz hardQuiz = createQuiz("Hard Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        hardQuiz.setDifficulty(Difficulty.HARD);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, Difficulty.HARD, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Hard Quiz");
        assertThat(results.get(0).getDifficulty()).isEqualTo(Difficulty.HARD);
    }

    @Test
    @DisplayName("build: search filters by title case-insensitively")
    void build_withSearch_filtersByTitle() {
        // Given
        createQuiz("Java Programming Basics", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Python Advanced", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("JavaScript Fundamentals", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", "java", null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - matches "Java" and "JavaScript"
        assertThat(results).hasSize(2);
        assertThat(results).extracting("title").containsExactlyInAnyOrder("Java Programming Basics", "JavaScript Fundamentals");
    }

    @Test
    @DisplayName("build: search filters by description case-insensitively")
    void build_withSearch_filtersByDescription() {
        // Given
        Quiz quiz1 = createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz1.setDescription("Learn advanced Python programming");
        
        Quiz quiz2 = createQuiz("Quiz 2", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz2.setDescription("Introduction to Java");
        
        Quiz quiz3 = createQuiz("Quiz 3", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz3.setDescription("JavaScript basics");
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", "PYTHON", null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Quiz 1");
    }

    @Test
    @DisplayName("build: search matches both title and description")
    void build_withSearch_matchesTitleOrDescription() {
        // Given
        Quiz quiz1 = createQuiz("Spring Framework", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz1.setDescription("Java basics");
        
        Quiz quiz2 = createQuiz("Python Basics", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz2.setDescription("Learn Spring Boot");
        
        Quiz quiz3 = createQuiz("Unrelated", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz3.setDescription("Completely different");
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", "spring", null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - matches title OR description
        assertThat(results).hasSize(2);
        assertThat(results).extracting("title").containsExactlyInAnyOrder("Spring Framework", "Python Basics");
    }

    @Test
    @DisplayName("build: filters by specific quiz IDs")
    void build_withQuizIds_filtersCorrectly() {
        // Given
        Quiz quiz1 = createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        Quiz quiz2 = createQuiz("Quiz 2", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        Quiz quiz3 = createQuiz("Quiz 3", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, List.of(quiz1.getId(), quiz3.getId()));
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results).extracting("title").containsExactlyInAnyOrder("Quiz 1", "Quiz 3");
    }

    @Test
    @DisplayName("build: combines multiple filters with AND logic")
    void build_multipleFilters_combinesWithAnd() {
        // Given
        Tag javaTag = new Tag();
        javaTag.setName("java");
        entityManager.persist(javaTag);

        Tag springTag = new Tag();
        springTag.setName("spring");
        entityManager.persist(springTag);

        Category devCategory = new Category();
        devCategory.setName("Development");
        entityManager.persist(devCategory);

        Quiz match = createQuiz("Java Spring Tutorial", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        match.setCategory(devCategory);
        match.setDifficulty(Difficulty.MEDIUM);
        match.setTags(Set.of(javaTag));
        
        Quiz noMatch1 = createQuiz("Java Tutorial", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        noMatch1.setCategory(devCategory);
        noMatch1.setDifficulty(Difficulty.EASY); // Wrong difficulty
        noMatch1.setTags(Set.of(javaTag));
        
        Quiz noMatch2 = createQuiz("Spring Tutorial", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        noMatch2.setCategory(testCategory); // Wrong category
        noMatch2.setDifficulty(Difficulty.MEDIUM);
        noMatch2.setTags(Set.of(springTag));
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(
                List.of(devCategory.getId()),
                List.of("java"),
                null,
                Difficulty.MEDIUM,
                "public",
                null,
                null
        );
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - only the one matching all criteria
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Java Spring Tutorial");
    }

    @Test
    @DisplayName("build: always excludes deleted quizzes")
    void build_alwaysExcludesDeleted() {
        // Given
        Quiz active = createQuiz("Active Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        Quiz deleted = createQuiz("Deleted Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        deleted.setIsDeleted(true);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - deleted quiz excluded
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Active Quiz");
    }

    @Test
    @DisplayName("build: null filter returns only non-deleted quizzes")
    void build_nullFilter_returnsAllNonDeleted() {
        // Given
        createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Quiz 2", Visibility.PRIVATE, QuizStatus.DRAFT);
        
        Quiz deleted = createQuiz("Deleted", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        deleted.setIsDeleted(true);
        
        entityManager.flush();
        entityManager.clear();

        Specification<Quiz> spec = QuizExportSpecifications.build(null);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results).extracting("title").containsExactlyInAnyOrder("Quiz 1", "Quiz 2");
    }

    @Test
    @DisplayName("build: empty filter lists are ignored")
    void build_emptyFilterLists_ignored() {
        // Given
        createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Quiz 2", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(
                List.of(), // Empty categoryIds
                List.of(), // Empty tags
                null,
                null,
                "public",
                null,
                List.of() // Empty quizIds
        );
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - returns all public published quizzes
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("build: handles null values in filter")
    void build_nullFilterValues_ignored() {
        // Given
        createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, null, null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - defaults to public scope behavior
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("build: blank search string is ignored")
    void build_blankSearch_ignored() {
        // Given
        createQuiz("Quiz 1", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Quiz 2", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", "   ", null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - returns all
        assertThat(results).hasSize(2);
    }

    @Test
    @DisplayName("build: handles quiz with null tags")
    void build_quizWithNullTags_handlesGracefully() {
        // Given
        Quiz quiz = createQuiz("Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz.setTags(null);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, List.of("java"), null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - no match (quiz has no tags)
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("build: tag filter with ANY matching tag qualifies quiz")
    void build_tagFilter_anyMatchQualifies() {
        // Given
        Tag javaTag = new Tag();
        javaTag.setName("java");
        entityManager.persist(javaTag);

        Tag springTag = new Tag();
        springTag.setName("spring");
        entityManager.persist(springTag);

        Tag pythonTag = new Tag();
        pythonTag.setName("python");
        entityManager.persist(pythonTag);

        Quiz quiz1 = createQuiz("Java Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz1.setTags(Set.of(javaTag, springTag));
        
        Quiz quiz2 = createQuiz("Python Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz2.setTags(Set.of(pythonTag));
        
        entityManager.flush();
        entityManager.clear();

        // Search for java OR spring
        QuizExportFilter filter = new QuizExportFilter(null, List.of("java", "spring"), null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - quiz1 has both tags, qualifies
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Java Quiz");
    }

    @Test
    @DisplayName("build: returns distinct results when quiz has multiple matching tags")
    void build_multipleMatchingTags_returnsDistinct() {
        // Given
        Tag tag1 = new Tag();
        tag1.setName("java");
        entityManager.persist(tag1);

        Tag tag2 = new Tag();
        tag2.setName("spring");
        entityManager.persist(tag2);

        Quiz quiz = createQuiz("Java Spring Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz.setTags(Set.of(tag1, tag2));
        
        entityManager.flush();
        entityManager.clear();

        // Search for both tags the quiz has
        QuizExportFilter filter = new QuizExportFilter(null, List.of("java", "spring"), null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - should return quiz only once (distinct)
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("build: handles search with special characters")
    void build_searchWithSpecialChars_handlesCorrectly() {
        // Given
        createQuiz("C++ Programming", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Java Programming", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", "C++", null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("C++ Programming");
    }

    @Test
    @DisplayName("build: scope defaults to public when null")
    void build_nullScope_defaultsToPublic() {
        // Given
        createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Private Draft", Visibility.PRIVATE, QuizStatus.DRAFT);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, null, null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - defaults to public scope (only public + published)
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Public Published");
    }

    @Test
    @DisplayName("build: scope case-insensitive")
    void build_scopeCaseInsensitive_handlesCorrectly() {
        // Given
        createQuiz("Public Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Private Draft", Visibility.PRIVATE, QuizStatus.DRAFT);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "PUBLIC", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("build: filters null tag names from list")
    void build_nullTagNamesInList_filtered() {
        // Given
        Tag javaTag = new Tag();
        javaTag.setName("java");
        entityManager.persist(javaTag);

        Quiz quiz = createQuiz("Java Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        quiz.setTags(Set.of(javaTag));
        
        entityManager.flush();
        entityManager.clear();

        // Use ArrayList to allow nulls
        List<String> tagsWithNull = new ArrayList<>();
        tagsWithNull.add("java");
        tagsWithNull.add(null);
        tagsWithNull.add("spring");
        
        QuizExportFilter filter = new QuizExportFilter(null, tagsWithNull, null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - null is filtered out by spec, only matches "java"
        assertThat(results).hasSize(1);
    }

    @Test
    @DisplayName("build: no results when no quizzes match criteria")
    void build_noMatches_returnsEmpty() {
        // Given
        createQuiz("Java Quiz", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", "python", null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("build: handles archived status in public scope")
    void build_publicScope_excludesArchived() {
        // Given
        createQuiz("Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Archived", Visibility.PUBLIC, QuizStatus.ARCHIVED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then - archived excluded in public scope
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTitle()).isEqualTo("Published");
    }

    @Test
    @DisplayName("build: handles pending review status in public scope")
    void build_publicScope_excludesPendingReview() {
        // Given
        createQuiz("Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Pending", Visibility.PUBLIC, QuizStatus.PENDING_REVIEW);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(QuizStatus.PUBLISHED);
    }

    @Test
    @DisplayName("build: handles rejected status in public scope")
    void build_publicScope_excludesRejected() {
        // Given
        createQuiz("Published", Visibility.PUBLIC, QuizStatus.PUBLISHED);
        createQuiz("Rejected", Visibility.PUBLIC, QuizStatus.REJECTED);
        
        entityManager.flush();
        entityManager.clear();

        QuizExportFilter filter = new QuizExportFilter(null, null, null, null, "public", null, null);
        Specification<Quiz> spec = QuizExportSpecifications.build(filter);

        // When
        List<Quiz> results = quizRepository.findAll(spec);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getStatus()).isEqualTo(QuizStatus.PUBLISHED);
    }

    // Helper methods

    private Quiz createQuiz(String title, Visibility visibility, QuizStatus status) {
        Quiz quiz = new Quiz();
        quiz.setTitle(title);
        quiz.setDescription("Description");
        quiz.setVisibility(visibility);
        quiz.setDifficulty(Difficulty.MEDIUM);
        quiz.setStatus(status);
        quiz.setEstimatedTime(10);
        quiz.setIsRepetitionEnabled(false);
        quiz.setIsTimerEnabled(false);
        quiz.setCreator(testUser);
        quiz.setCategory(testCategory);
        quiz.setIsDeleted(false);
        return entityManager.persist(quiz);
    }
}

