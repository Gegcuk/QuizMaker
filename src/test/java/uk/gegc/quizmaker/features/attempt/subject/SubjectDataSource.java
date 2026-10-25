package uk.gegc.quizmaker.features.attempt.subject;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/** Observes/faults actual SQL execution and commit, without replacing persistence or transactions. */
final class SubjectDataSource extends DelegatingDataSource {
    interface SqlHook { void run(String sql, int connection) throws SQLException; }
    interface CommitHook { void run(Connection connection) throws SQLException; }
    volatile SqlHook beforeSql;
    volatile CommitHook beforeCommit;
    volatile CommitHook afterCommit;
    volatile boolean failConnection;
    final List<String> selects = new CopyOnWriteArrayList<>();
    final List<Integer> sqlErrorCodes = new CopyOnWriteArrayList<>();
    private final AtomicInteger connections = new AtomicInteger();

    SubjectDataSource(DataSource target) { super(target); }

    @Override
    public Connection getConnection() throws SQLException {
        if (failConnection) throw new SQLException("Synthetic connection failure", "08006");
        Connection target = super.getConnection();
        int connectionId = connections.incrementAndGet();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("commit") && beforeCommit != null) beforeCommit.run(target);
                    try {
                        Object result = method.invoke(target, arguments);
                        if (method.getName().equals("commit") && afterCommit != null) afterCommit.run(target);
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String statement) {
                            return observe((PreparedStatement) result, statement, connectionId);
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    private PreparedStatement observe(PreparedStatement target, String sql, int connectionId) {
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[]{PreparedStatement.class}, (proxy, method, arguments) -> {
                    if (method.getName().startsWith("execute")) {
                        if (sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("SELECT")) selects.add(sql);
                        var hook = beforeSql;
                        if (hook != null) hook.run(sql, connectionId);
                    }
                    try { return method.invoke(target, arguments); }
                    catch (InvocationTargetException failure) {
                        if (failure.getCause() instanceof SQLException sqlFailure) sqlErrorCodes.add(sqlFailure.getErrorCode());
                        throw failure.getCause();
                    }
                });
    }

    void reset() {
        beforeSql = null; beforeCommit = null; afterCommit = null; failConnection = false;
        selects.clear(); sqlErrorCodes.clear();
    }
}
