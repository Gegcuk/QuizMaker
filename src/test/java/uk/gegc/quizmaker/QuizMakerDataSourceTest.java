package uk.gegc.quizmaker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;


import javax.sql.DataSource;

@SpringBootTest
public class QuizMakerDataSourceTest {

    @Autowired
    DataSource dataSource;

    @Test
    void canConnectToDatabase() throws Exception{
        try(var conn = dataSource.getConnection()){
            String url = conn.getMetaData().getURL();
            System.out.println("Connected to: " + url);
            assertThat(url).contains("quizmakerdb");
        }
    }

}
