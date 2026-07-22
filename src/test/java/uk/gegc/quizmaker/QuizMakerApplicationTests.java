package uk.gegc.quizmaker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Tag("db-serial")
@SpringBootTest
@ActiveProfiles("test-mysql")
class QuizMakerApplicationTests {

    @Test
    void contextLoads() {
    }

}
