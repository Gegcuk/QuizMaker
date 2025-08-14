package uk.gegc.quizmaker.repository.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizRepository;
import uk.gegc.quizmaker.features.quiz.domain.repository.QuizSpecifications;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Quiz;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.model.user.User;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@ActiveProfiles("test-mysql")
@org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase(replace = org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace.NONE)
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class QuizRepositorySearchTest {

    @Autowired
    private QuizRepository quizRepository;

    @Autowired
    private TestEntityManager em;

    private Category catGeneral;
    private Category catScience;
    private Tag tagJava;
    private Tag tagMath;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = new User();
        alice.setUsername("alice");
        alice.setEmail("alice@example.com");
        alice.setHashedPassword("pw");
        alice.setActive(true);
        alice.setDeleted(false);
        em.persist(alice);

        bob = new User();
        bob.setUsername("bob");
        bob.setEmail("bob@example.com");
        bob.setHashedPassword("pw");
        bob.setActive(true);
        bob.setDeleted(false);
        em.persist(bob);

        catGeneral = new Category();
        catGeneral.setName("General");
        catGeneral.setDescription("General desc");
        em.persist(catGeneral);

        catScience = new Category();
        catScience.setName("Science");
        catScience.setDescription("Science desc");
        em.persist(catScience);

        tagJava = new Tag();
        tagJava.setName("Java");
        tagJava.setDescription("Java tag");
        em.persist(tagJava);

        tagMath = new Tag();
        tagMath.setName("Math");
        tagMath.setDescription("Math tag");
        em.persist(tagMath);

        em.flush();

        quizRepository.save(makeQuiz("Java Basics", "Intro to Java", alice, catGeneral, Visibility.PRIVATE, Difficulty.EASY, Set.of(tagJava)));
        quizRepository.save(makeQuiz("Advanced Math", "Algebra and more", alice, catScience, Visibility.PRIVATE, Difficulty.HARD, Set.of(tagMath)));
        quizRepository.save(makeQuiz("Science Facts", "Fun science facts", bob, catScience, Visibility.PRIVATE, Difficulty.MEDIUM, Set.of(tagMath)));
        quizRepository.save(makeQuiz("Mixed Bag", "Java and math quiz", bob, catGeneral, Visibility.PRIVATE, Difficulty.MEDIUM, Set.of(tagJava, tagMath)));
    }

    private Quiz makeQuiz(String title, String desc, User creator, Category cat, Visibility vis, Difficulty diff, Set<Tag> tags) {
        Quiz q = new Quiz();
        q.setCreator(creator);
        q.setCategory(cat);
        q.setTitle(title);
        q.setDescription(desc);
        q.setVisibility(vis);
        q.setDifficulty(diff);
        q.setEstimatedTime(10);
        q.setIsRepetitionEnabled(false);
        q.setIsTimerEnabled(false);
        q.setTimerDuration(5);
        q.setTags(tags);
        return q;
    }

    @Test
    @DisplayName("filter by category names (case-insensitive)")
    void categoryFilter_byNames() {
        var spec = QuizSpecifications.build(new QuizSearchCriteria(List.of("science"), null, null, null, null));
        var result = quizRepository.findAll(spec);
        assertThat(result).extracting(Quiz::getTitle).containsExactlyInAnyOrder("Advanced Math", "Science Facts");
    }

    @Test
    @DisplayName("no criteria returns all quizzes")
    void noCriteria_returnsAll() {
        var spec = QuizSpecifications.build(new QuizSearchCriteria(null, null, null, null, null));
        var result = quizRepository.findAll(spec);
        assertThat(result).hasSize(4);
    }

    @Test
    @DisplayName("filter by tag names (any match)")
    void tagFilter_byNames_anyMatch() {
        var spec = QuizSpecifications.build(new QuizSearchCriteria(null, List.of("java"), null, null, null));
        var result = quizRepository.findAll(spec);
        assertThat(result).extracting(Quiz::getTitle).contains("Java Basics", "Mixed Bag");
    }

    @Test
    @DisplayName("filter by author username (case-insensitive exact)")
    void authorFilter() {
        var spec = QuizSpecifications.build(new QuizSearchCriteria(null, null, "ALICE", null, null));
        var result = quizRepository.findAll(spec);
        assertThat(result).allMatch(q -> q.getCreator().getUsername().equals("alice"));
    }

    @Test
    @DisplayName("full-text search on title/description")
    void fullTextSearch_onTitleOrDescription() {
        var spec = QuizSpecifications.build(new QuizSearchCriteria(null, null, null, "java", null));
        var result = quizRepository.findAll(spec);
        assertThat(result).extracting(Quiz::getTitle).contains("Java Basics", "Mixed Bag");
    }

    @Test
    @DisplayName("difficulty exact match")
    void difficultyFilter() {
        var spec = QuizSpecifications.build(new QuizSearchCriteria(null, null, null, null, Difficulty.HARD));
        var result = quizRepository.findAll(spec);
        assertThat(result).extracting(Quiz::getTitle).containsExactly("Advanced Math");
    }
}


