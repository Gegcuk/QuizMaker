package uk.gegc.quizmaker.shared.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class EmailConfig {

    /**
     * Provides a no-op JavaMailSender when email configuration is disabled.
     * This prevents dependency injection failures when email service is not configured.
     */
    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    @ConditionalOnProperty(name = "spring.mail.host", havingValue = "", matchIfMissing = true)
    public JavaMailSender noOpMailSender() {
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        // Configure with dummy values to prevent connection attempts
        mailSender.setHost("localhost");
        mailSender.setPort(25);
        mailSender.setUsername("noreply@localhost");
        mailSender.setPassword("");
        return mailSender;
    }
}
