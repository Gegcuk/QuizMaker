package uk.gegc.quizmaker.features.attempt.start;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.attempt.application.*;
import uk.gegc.quizmaker.features.attempt.application.impl.AttemptStartServiceImpl;
import uk.gegc.quizmaker.features.attempt.domain.model.policy.ResolvedAttemptPolicy.*;
import uk.gegc.quizmaker.features.attempt.domain.model.preset.*;
import uk.gegc.quizmaker.features.attempt.domain.model.receipt.CommandReceipt;
import uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException;
import uk.gegc.quizmaker.features.question.application.QuestionRevisionContractService;
import uk.gegc.quizmaker.features.quiz.application.QuizPracticeSourceService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.domain.model.start.AttemptStartException.Reason.*;

class AttemptStartValidationTest {
    @Test void malformedCommandsNeverResolveProtectedIdentityOrReserveAReceiptEvenWhenTelemetryFails() {
        var identity = mock(AuthenticatedSubjectResolver.class);
        var receipts = mock(CommandReceiptService.class);
        var sources = mock(QuizPracticeSourceService.class);
        var metrics = mock(MeterRegistry.class);
        when(metrics.config()).thenThrow(new IllegalStateException("Synthetic telemetry outage"));
        var service = new AttemptStartServiceImpl(identity, sources, mock(AttemptPresetService.class),
                mock(AttemptPolicyResolver.class), mock(PolicySnapshotService.class), mock(QuestionRevisionContractService.class),
                mock(QuestionSelectionService.class), mock(FixedFormMaterializer.class), mock(AttemptSubjectService.class),
                mock(AttemptLifecycleService.class), mock(ExecutionTraceService.class), receipts, metrics, mock(uk.gegc.quizmaker.features.attempt.domain.repository.NavigationRepository.class), mock(AttemptAllowanceService.class));
        var content = new ContentPolicy(UUID.randomUUID(), 1, FormMode.PRECOMPUTED, SectionMode.IMPLICIT_FLAT, CaseMode.NOT_CONFIGURED);
        UUID quiz = UUID.randomUUID(); var key = CommandReceipt.Key.from("validation");
        var preset = BuiltInPracticePreset.REFERENCE; var context = LegacyPracticeRequest.LaunchContext.ORDINARY;
        var invalid = new ArrayList<AttemptStartService.Command>(); invalid.add(null);
        invalid.addAll(List.of(
                new AttemptStartService.Command(1, null, content, 1, key, preset, context, "ALL_AT_ONCE", AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, null, 1, key, preset, context, "ALL_AT_ONCE", AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, content, 0, key, preset, context, "ALL_AT_ONCE", AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, content, 1, null, preset, context, "ALL_AT_ONCE", AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, content, 1, key, null, context, "ALL_AT_ONCE", AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, content, 1, key, preset, null, "ALL_AT_ONCE", AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, content, 1, key, preset, context, null, AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, content, 1, key, preset, context, "x".repeat(33), AnswerOrder.AUTHORED),
                new AttemptStartService.Command(1, quiz, content, 1, key, preset, context, "ALL_AT_ONCE", null)));
        invalid.add(new AttemptStartService.Command(1, quiz, content, 1, key, preset, context, "ALL_AT_ONCE",
                AnswerOrder.AUTHORED, false, new NavigationPolicy(NavigationMode.FREE), DeliveryMode.ALL_AT_ONCE, null));
        invalid.add(new AttemptStartService.Command(1, quiz, content, 1, key, preset, context, "ALL_AT_ONCE",
                AnswerOrder.AUTHORED, false, new NavigationPolicy(NavigationMode.FREE), DeliveryMode.ALL_AT_ONCE,
                uk.gegc.quizmaker.features.attempt.domain.model.selection.QuestionSelection.Configuration.all(), null));
        for (var command : invalid) assertThatThrownBy(() -> service.start(command))
                .isInstanceOfSatisfying(AttemptStartException.class, e -> assertThat(e.reason()).isEqualTo(INVALID_INPUT));
        assertThatThrownBy(() -> service.start(new AttemptStartService.Command(2, quiz, content, 1, key, preset, context, "ALL_AT_ONCE", AnswerOrder.AUTHORED)))
                .isInstanceOfSatisfying(AttemptStartException.class, e -> assertThat(e.reason()).isEqualTo(UNSUPPORTED_VERSION));
        verifyNoInteractions(identity, sources, receipts);
    }
    @Test void allowanceRepositoryCannotReadOrWriteOutsideTheSharedStartTransaction() {
        var sql = mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        var repository = new uk.gegc.quizmaker.features.attempt.infra.repository.JdbcAttemptAllowanceRepository(sql);
        var scope = new uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.Scope(UUID.randomUUID(),
                uk.gegc.quizmaker.features.attempt.domain.model.retake.RetakeEligibility.ContextKind.PUBLICATION, UUID.randomUUID(), 1);
        var policy = new AttemptLimitPolicy(AttemptLimitMode.UNLIMITED);
        List<Runnable> operations = List.of(() -> repository.lock(scope, policy), () -> repository.readCurrent(scope, policy),
                () -> repository.verifyComplete(scope), () -> repository.bind(scope, UUID.randomUUID(), UUID.randomUUID()));
        for (var operation : operations) assertThatThrownBy(operation::run).isInstanceOfSatisfying(AttemptStartException.class,
                e -> assertThat(e.reason()).isEqualTo(PERSISTENCE_UNAVAILABLE)).hasNoCause();
        verifyNoInteractions(sql);
    }

}
