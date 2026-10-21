package uk.gegc.quizmaker;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

@Tag("db-serial")
@SpringBootTest
@ActiveProfiles("test-mysql")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class QuizMakerApplicationTests {

    @Test
    void contextLoads() {
    }

}
