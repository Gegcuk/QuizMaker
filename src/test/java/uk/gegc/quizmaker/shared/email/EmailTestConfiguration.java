package uk.gegc.quizmaker.shared.email;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import uk.gegc.quizmaker.shared.config.AwsSesConfig;
import uk.gegc.quizmaker.shared.config.EmailProviderConfig;
import uk.gegc.quizmaker.shared.email.impl.EmailServiceImpl;

/**
 * Real email wiring and application defaults without application scanning, JPA or Flyway.
 * PropertySource reads the packaged defaults without processing Boot config imports
 * such as a developer's .env or secret.properties. Scenario properties still override them.
 * The suites retain their serial execution tag because some scenarios capture shared loggers.
 */
@TestConfiguration(proxyBeanMethods = false)
@PropertySource("classpath:application.properties")
@Import({AwsSesConfig.class, EmailProviderConfig.class, EmailServiceImpl.class})
@ImportAutoConfiguration(MailSenderAutoConfiguration.class)
public class EmailTestConfiguration {

    @Bean
    static PropertySourcesPlaceholderConfigurer emailPropertyPlaceholders() {
        return new PropertySourcesPlaceholderConfigurer();
    }
}
