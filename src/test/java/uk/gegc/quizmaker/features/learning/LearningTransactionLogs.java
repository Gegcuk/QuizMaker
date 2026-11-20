package uk.gegc.quizmaker.features.learning;

import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.learning.infra.repository.JdbcLearningSessionRepository;
import uk.gegc.quizmaker.shared.transaction.SafeJpaTransactionManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Capture only this request thread, including eagerly rendered causes and suppressed errors. */
public final class LearningTransactionLogs implements AutoCloseable {
    private final List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(TransactionInterceptor.class),
            (Logger) LoggerFactory.getLogger(TransactionTemplate.class),
            (Logger) LoggerFactory.getLogger(SafeJpaTransactionManager.class));
    private final Map<Logger, Level> previous = new HashMap<>();
    private final ListAppender<ILoggingEvent> captured;
    public LearningTransactionLogs() {
        Thread request = Thread.currentThread();
        captured = new ListAppender<>() {
            @Override protected void append(ILoggingEvent event) {
                if (Thread.currentThread() == request) { event.prepareForDeferredProcessing(); super.append(event); }
            }
        };
        captured.setContext(loggers.get(0).getLoggerContext()); captured.start();
        loggers.forEach(logger -> { previous.put(logger, logger.getLevel()); logger.addAppender(captured); logger.setLevel(Level.TRACE); });
    }
    void assertCleanFailure(String method) {
        assertCleanFailure(JdbcLearningSessionRepository.class, method);
    }
    public void assertCleanFailure(Class<?> boundary, String method, String... privateValues) {
        assertThat(captured.list).anySatisfy(event -> {
            assertThat(event.getLoggerName()).isEqualTo(TransactionInterceptor.class.getName());
            assertThat(event.getLevel()).isEqualTo(Level.TRACE);
            assertThat(event.getFormattedMessage()).contains(boundary.getSimpleName() + "." + method, "after exception");
        });
        var forbidden = new ArrayList<>(List.of(privateValues));
        forbidden.add("LEARNING_PRIVATE_");
        var markers = forbidden.toArray(String[]::new);
        assertThat(captured.list).allSatisfy(event -> {
            assertThat(event.getFormattedMessage()).doesNotContain(markers);
            if (event.getThrowableProxy() != null)
                assertThat(ThrowableProxyUtil.asString(event.getThrowableProxy())).doesNotContain(markers);
        });
    }
    @Override public void close() {
        loggers.forEach(logger -> { logger.detachAppender(captured); logger.setLevel(previous.get(logger)); });
        captured.stop();
    }
}
