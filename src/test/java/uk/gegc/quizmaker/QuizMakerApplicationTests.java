package uk.gegc.quizmaker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test-mysql")
class QuizMakerApplicationTests {

    @Test
    void contextLoads() {
    }

}
