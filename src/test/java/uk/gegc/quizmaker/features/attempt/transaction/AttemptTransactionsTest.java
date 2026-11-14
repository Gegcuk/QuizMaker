package uk.gegc.quizmaker.features.attempt.transaction;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uk.gegc.quizmaker.features.attempt.application.transaction.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static uk.gegc.quizmaker.features.attempt.application.transaction.AttemptTransactionException.Reason.*;

class AttemptTransactionsTest {
    private final DataSource source = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DataSourceTransactionManager manager = new DataSourceTransactionManager(source);

    @BeforeEach void transaction() {
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.bindResource(source, new ConnectionHolder(connection));
    }
    @AfterEach void cleanup() {
        for (var callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCompletion(1);
        TransactionSynchronizationManager.unbindResourceIfPossible(source);
        TransactionSynchronizationManager.clear();
    }

    @Test void defaultRcIsCheckedOnceForTheEnlistedConnection() throws Exception {
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        for (int i = 0; i < 500; i++) AttemptTransactions.requireCompatible(manager, true);
        verify(connection, times(1)).getTransactionIsolation();
        verifyNoInteractions(source);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        rejected(() -> AttemptTransactions.requireCompatible(manager, true), READ_ONLY);
        AttemptTransactions.requireCompatible(manager, false);
    }
    @Test void explicitRcNeedsNoDriverLookup() {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(Connection.TRANSACTION_READ_COMMITTED);
        AttemptTransactions.requireCompatible(manager, true);
        verifyNoInteractions(connection, source);
    }
    @ParameterizedTest @ValueSource(ints = {Connection.TRANSACTION_READ_UNCOMMITTED,
            Connection.TRANSACTION_REPEATABLE_READ, Connection.TRANSACTION_SERIALIZABLE, Connection.TRANSACTION_NONE})
    void unsupportedIsolationFailsBeforeWork(int isolation) throws Exception {
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(isolation);
        rejected(() -> AttemptTransactions.requireCompatible(manager, false), INCOMPATIBLE_ISOLATION);
        verifyNoInteractions(connection);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(null);
        when(connection.getTransactionIsolation()).thenReturn(isolation);
        rejected(() -> AttemptTransactions.requireCompatible(manager, false), INCOMPATIBLE_ISOLATION);
    }
    @Test void unknownManagerOrUnenlistedConnectionCannotBeAccepted() {
        rejected(() -> AttemptTransactions.requireCompatible(mock(PlatformTransactionManager.class), false), UNRESOLVABLE_ISOLATION);
        TransactionSynchronizationManager.unbindResource(source);
        rejected(() -> AttemptTransactions.requireCompatible(manager, false), UNRESOLVABLE_ISOLATION);
        verifyNoInteractions(source, connection);
    }
    @Test void driverDiagnosticsAndSuppressedErrorsCannotReachTheViolation() throws Exception {
        var raw = new SQLException("secret diagnostic", new IllegalStateException("secret cause"));
        raw.addSuppressed(new IllegalStateException("secret suppressed"));
        when(connection.getTransactionIsolation()).thenThrow(raw);
        rejected(() -> AttemptTransactions.requireCompatible(manager, false), UNRESOLVABLE_ISOLATION);
        var bounded = new AttemptTransactionException(INCOMPATIBLE_ISOLATION);
        bounded.addSuppressed(raw);
        assertThat(bounded.getSuppressed()).isEmpty();
        assertThatThrownBy(() -> bounded.initCause(raw)).isInstanceOf(IllegalStateException.class);
        rejected(() -> AttemptTransactionException.preserve(bounded), INCOMPATIBLE_ISOLATION);
    }
    @Test void standaloneTemplateExplicitlyOwnsRcWithoutChangingManager() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
        AttemptTransactions.requireCompatible(manager, true);
        var template = AttemptTransactions.readCommitted(manager);
        assertThat(template.getIsolationLevel()).isEqualTo(Connection.TRANSACTION_READ_COMMITTED);
        assertThat(template.getPropagationBehavior()).isEqualTo(0);
        assertThat(template.getTransactionManager()).isSameAs(manager);
        verifyNoInteractions(source, connection);
    }
    private static void rejected(Runnable action, AttemptTransactionException.Reason reason) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(AttemptTransactionException.class, failure -> {
            assertThat(failure.reason()).isEqualTo(reason);
            assertThat(failure.getMessage()).isEqualTo("Attempt transaction rejected: " + reason);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(failure.getStackTrace()).isEmpty();
        });
    }
}
