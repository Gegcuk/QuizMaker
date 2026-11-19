package uk.gegc.quizmaker.features.learning;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.openspacedrepetition.Card;
import io.github.openspacedrepetition.Scheduler;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import uk.gegc.quizmaker.features.learning.application.LearningSchedulingStrategy;
import uk.gegc.quizmaker.features.learning.application.impl.FsrsSchedulingStrategyImpl;
import uk.gegc.quizmaker.features.learning.domain.model.FsrsManifest;
import uk.gegc.quizmaker.features.learning.domain.model.LearningSchedule.Rating;
import uk.gegc.quizmaker.features.learning.infra.scheduling.FsrsStateFormat;

import static org.assertj.core.api.Assertions.*;
import static uk.gegc.quizmaker.features.learning.FsrsFixtures.*;

class FsrsSpringCompatibilityTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withUserConfiguration(FsrsSchedulingStrategyImpl.class);

    @Test
    void springWiresTheInterfaceAndBootJacksonPreservesTheProjectState() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(LearningSchedulingStrategy.class).hasSingleBean(ObjectMapper.class);
            var strategy = context.getBean(LearningSchedulingStrategy.class);
            var state = strategy.initial(IDENTITY, FsrsManifest.approved(), TIME);
            var result = strategy.review(state, command(state, Rating.EASY, TIME));
            var json = context.getBean(ObjectMapper.class).writeValueAsString(result);
            assertThat(new FsrsStateFormat().decodeTransition(json)).isEqualTo(result);
            assertThat(result.state().dueAt()).isEqualTo(TIME.plus(Duration.ofDays(16)));
        });
    }

    @Test
    void actualUpstreamCardAndSchedulerSerializationWorkWithBootManagedJacksonDependencies() {
        runner.run(context -> {
            var manifest = FsrsManifest.approved();
            var card = Card.builder().cardId(0).due(TIME).build();
            var scheduler = Scheduler.builder()
                    .parameters(manifest.parameters().stream().mapToDouble(Double::doubleValue).toArray())
                    .desiredRetention(.9).learningSteps(new Duration[]{Duration.ofSeconds(60), Duration.ofSeconds(600)})
                    .relearningSteps(new Duration[]{Duration.ofSeconds(600)}).maximumInterval(3650)
                    .enableFuzzing(false).randomSeedNumber(42).build();
            var restoredCard = Card.fromJson(card.toJson());
            var restoredScheduler = Scheduler.fromJson(scheduler.toJson());
            assertThat(restoredCard).isEqualTo(card);
            var expected = scheduler.reviewCard(card, io.github.openspacedrepetition.Rating.EASY, TIME, null);
            assertThat(restoredScheduler.reviewCard(restoredCard, io.github.openspacedrepetition.Rating.EASY, TIME, null))
                    .isEqualTo(expected);
            // Upstream retains mutable arrays; changing a separate scheduler must not affect our adapter.
            scheduler.getParameters()[3] = 999;
            scheduler.getLearningSteps()[0] = Duration.ofDays(1);
            var state = fresh(TIME);
            assertThat(context.getBean(LearningSchedulingStrategy.class).review(state, command(state, Rating.EASY, TIME))
                    .state().dueAt()).isEqualTo(TIME.plus(Duration.ofDays(16)));
        });
    }
}
