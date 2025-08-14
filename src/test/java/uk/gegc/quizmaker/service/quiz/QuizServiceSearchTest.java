package uk.gegc.quizmaker.service.quiz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import uk.gegc.quizmaker.features.quiz.api.dto.CreateQuizRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizDto;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizSearchCriteria;
import uk.gegc.quizmaker.features.quiz.application.QuizService;
import uk.gegc.quizmaker.model.category.Category;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.quiz.domain.model.Visibility;
import uk.gegc.quizmaker.model.tag.Tag;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.repository.category.CategoryRepository;
import uk.gegc.quizmaker.repository.tag.TagRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

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
        // Avoid deleting core tables to prevent FK violations from other tests/context data.
        // Ensure a predictable user exists (create if missing)
        User user = userRepository.findByUsername("creator").orElseGet(() -> {
            User u = new User();
            u.setUsername("creator");
            u.setEmail("creator@example.com");
            u.setHashedPassword("pw");
            u.setActive(true);
            u.setDeleted(false);
            return userRepository.save(u);
        });
        username = user.getUsername();

        // Ensure category exists (create if missing)
        cat = categoryRepository.findByName("General").orElseGet(() -> {
            Category c = new Category();
            c.setName("General");
            return categoryRepository.save(c);
        });

        // Ensure tags exist (create if missing)
        tagJava = tagRepository.findAll().stream().filter(t -> "Java".equals(t.getName())).findFirst().orElseGet(() -> {
            Tag t = new Tag();
            t.setName("Java");
            return tagRepository.save(t);
        });
        tagMath = tagRepository.findAll().stream().filter(t -> "Math".equals(t.getName())).findFirst().orElseGet(() -> {
            Tag t = new Tag();
            t.setName("Math");
            return tagRepository.save(t);
        });

        // Create sample quizzes; titles unique to avoid constraint issues
        quizService.createQuiz(username, new CreateQuizRequest("Java Basics Service", "Intro", Visibility.PRIVATE, Difficulty.EASY, false, false, 10, 5, cat.getId(), List.of(tagJava.getId())));
        quizService.createQuiz(username, new CreateQuizRequest("Math 101 Service", "Algebra", Visibility.PRIVATE, Difficulty.MEDIUM, false, false, 10, 5, cat.getId(), List.of(tagMath.getId())));
        quizService.createQuiz(username, new CreateQuizRequest("Mixed Service", "Java and Math", Visibility.PRIVATE, Difficulty.MEDIUM, false, false, 10, 5, cat.getId(), List.of(tagJava.getId(), tagMath.getId())));
    }

    @Test
    @DisplayName("applies search criteria across fields and returns page of QuizDto")
    void serviceAppliesCriteria() {
        Page<QuizDto> page = quizService.getQuizzes(PageRequest.of(0, 10), new QuizSearchCriteria(null, List.of("java"), null, "intro", null));
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(1);
        assertThat(page.getContent()).anyMatch(dto -> dto.title().toLowerCase().contains("java"));
    }
}


