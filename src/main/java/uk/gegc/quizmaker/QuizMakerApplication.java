package uk.gegc.quizmaker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gegc.quizmaker.features.billing.application.BillingConfigurationPreflight;

@SpringBootApplication
@EnableScheduling
public class QuizMakerApplication {

    public static void main(String[] args) {
        if (BillingConfigurationPreflight.isRequested(args)) {
            BillingConfigurationPreflight.run(args);
            return;
        }

        SpringApplication.run(QuizMakerApplication.class, args);
    }


}
