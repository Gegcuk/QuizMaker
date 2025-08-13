package uk.gegc.quizmaker.service.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.dto.quiz.CreateQuizRequest;
import uk.gegc.quizmaker.dto.quiz.QuizDto;
import uk.gegc.quizmaker.dto.quiz.QuizSearchCriteria;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.model.question.Difficulty;
import uk.gegc.quizmaker.model.quiz.Visibility;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.model.user.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.repository.user.UserRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class QuizServiceSearchTest {

    @Autowired
    private QuizService quizService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private TagRepository tagRepository;

    private String username;
    private Category cat;
    private Tag tagJava;
    private Tag tagMath;

    @BeforeEach
    void setup() {
        userRepository.deleteAll();
        categoryRepository.deleteAll();
        tagRepository.deleteAll();

        User user = new User();
        user.setUsername("creator");
        user.setEmail("creator@example.com");
        user.setHashedPassword("pw");
        user.setActive(true);
        user.setDeleted(false);
        userRepository.save(user);
        username = user.getUsername();

        cat = new Category();
        cat.setName("General");
        categoryRepository.save(cat);

        tagJava = new Tag();
        tagJava.setName("Java");
        tagRepository.save(tagJava);

        tagMath = new Tag();
        tagMath.setName("Math");
        tagRepository.save(tagMath);

        quizService.createQuiz(username, new CreateQuizRequest("Java Basics", "Intro", Visibility.PRIVATE, Difficulty.EASY, false, false, 10, 5, cat.getId(), List.of(tagJava.getId())));
        quizService.createQuiz(username, new CreateQuizRequest("Math 101", "Algebra", Visibility.PRIVATE, Difficulty.MEDIUM, false, false, 10, 5, cat.getId(), List.of(tagMath.getId())));
        quizService.createQuiz(username, new CreateQuizRequest("Mixed", "Java and Math", Visibility.PRIVATE, Difficulty.MEDIUM, false, false, 10, 5, cat.getId(), List.of(tagJava.getId(), tagMath.getId())));
    }

    @Test
    @DisplayName("applies search criteria across fields and returns page of QuizDto")
    void serviceAppliesCriteria() {
        Page<QuizDto> page = quizService.getQuizzes(PageRequest.of(0, 10), new QuizSearchCriteria(null, List.of("java"), null, "intro", null));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);
        assertThat(page.getContent()).anyMatch(dto -> dto.title().toLowerCase().contains("java"));
    }
}


