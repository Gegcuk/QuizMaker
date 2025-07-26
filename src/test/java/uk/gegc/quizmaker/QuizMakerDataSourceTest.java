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
            // Check for quizmakerdb (CI and local test-mysql now use same database)
            assertThat(url).contains("quizmakerdb");
        }
    }

}
