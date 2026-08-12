package uk.gegc.quizmaker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import uk.gegc.quizmaker.features.billing.application.BillingConfigurationPreflight;
import uk.gegc.quizmaker.features.document.infra.isolation.DocumentParserWorkerMain;

@SpringBootApplication
@EnableScheduling
public class QuizMakerApplication {

    public static void main(String[] args) {
        if (DocumentParserWorkerMain.isRequested(args)) {
            DocumentParserWorkerMain.main(args);
            return;
        }

        if (BillingConfigurationPreflight.isRequested(args)) {
            BillingConfigurationPreflight.run(args);
            return;
        }

        SpringApplication.run(QuizMakerApplication.class, args);
    }


}
