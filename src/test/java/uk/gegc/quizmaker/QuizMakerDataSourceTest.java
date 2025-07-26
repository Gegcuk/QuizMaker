package uk.gegc.quizmaker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test-mysql")
public class QuizMakerDataSourceTest {

    @Autowired
    DataSource dataSource;

    @Test
    void canConnectToDatabase() throws Exception {
        try (var conn = dataSource.getConnection()) {
            String url = conn.getMetaData().getURL();
            System.out.println("Connected to: " + url);
            // Check for either quizmaker_test_mysql (local) or quizmakerdb (CI)
            assertThat(url).satisfiesAnyOf(
                urlAssert -> urlAssert.contains("quizmaker_test_mysql"),
                urlAssert -> urlAssert.contains("quizmakerdb")
            );
        }
    }

}
