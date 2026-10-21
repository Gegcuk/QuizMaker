package uk.gegc.quizmaker.features.quiz.revision;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Observes real JDBC results and pauses only the two writers using this test-local data source. */
final class QuestionInsertGateDataSource extends DelegatingDataSource {
    private final CountDownLatch ready = new CountDownLatch(2);
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger emptyReads = new AtomicInteger();
    private final AtomicInteger inserts = new AtomicInteger();
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();

    QuestionInsertGateDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        var state = new ConnectionState(connection);
        return proxy(Connection.class, (ignored, method, args) -> {
            Object result = invoke(connection, method, args);
            if (method.getName().equals("prepareStatement") && args[0] instanceof String sql) {
                String statement = sql.stripLeading().toUpperCase(Locale.ROOT);
                boolean lookup = statement.startsWith("SELECT * FROM QUESTION_REVISIONS WHERE ID IN");
                boolean insert = statement.startsWith("INSERT INTO QUESTION_REVISIONS");
                if (lookup || insert) return statement((PreparedStatement) result, state, lookup, insert);
            }
            return result;
        });
    }

    private PreparedStatement statement(PreparedStatement target, ConnectionState state, boolean lookup, boolean insert) {
        return proxy(PreparedStatement.class, (ignored, method, args) -> {
            if (insert && Set.of("executeBatch", "executeLargeBatch", "executeUpdate").contains(method.getName())) {
                state.beforeInsert();
                target.setQueryTimeout(10);
                inserts.incrementAndGet();
            }
            Object result = invoke(target, method, args);
            if (lookup && method.getName().equals("executeQuery")) return observedRows((ResultSet) result, state);
            return result;
        });
    }

    private ResultSet observedRows(ResultSet target, ConnectionState state) {
        return proxy(ResultSet.class, (ignored, method, args) -> {
            Object result = invoke(target, method, args);
            if (method.getName().equals("next")) {
                if (Boolean.TRUE.equals(result)) state.sawQuestion = true;
                else if (!state.sawQuestion && !state.emptyReadCompleted) {
                    state.emptyReadCompleted = true;
                    emptyReads.incrementAndGet();
                }
            }
            return result;
        });
    }

    void awaitBothTransactions() throws InterruptedException {
        if (!ready.await(10, TimeUnit.SECONDS)) {
            throw new AssertionError("Both transactions must finish an empty question lookup and reach INSERT; arrived="
                    + connections.size() + ", completed empty reads=" + emptyReads.get());
        }
    }

    void releaseInserts() { release.countDown(); }
    int completedEmptyReads() { return emptyReads.get(); }
    int executedInserts() { return inserts.get(); }
    Set<Connection> transactionConnections() { return Set.copyOf(connections); }

    private final class ConnectionState {
        private final Connection connection;
        private boolean sawQuestion;
        private boolean emptyReadCompleted;
        private boolean arrived;

        ConnectionState(Connection connection) { this.connection = connection; }

        void beforeInsert() throws SQLException, InterruptedException {
            if (!emptyReadCompleted || connection.getAutoCommit() || arrived) {
                throw new AssertionError("Expected one question INSERT after a completed empty lookup in a real transaction");
            }
            arrived = true;
            connections.add(connection);
            ready.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for the test to release both question INSERTs");
            }
        }
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (InvocationTargetException exception) { throw exception.getCause(); }
    }

    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
