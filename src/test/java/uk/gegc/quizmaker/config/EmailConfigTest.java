package uk.gegc.quizmaker.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class EmailConfigTest {

    @Autowired
    private JavaMailSender mailSender;

    @Test
    void emailConfiguration_ShouldBeProperlyConfigured() {
        // Verify that the JavaMailSender is properly configured
        assertThat(mailSender).isNotNull();
        
        // The mailSender should be able to create messages
        assertThat(mailSender.createMimeMessage()).isNotNull();
    }
}
