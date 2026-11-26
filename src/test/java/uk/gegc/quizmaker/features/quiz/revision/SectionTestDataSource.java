package uk.gegc.quizmaker.features.quiz.revision;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts physical statement executions and injects faults after real JDBC writes. */
final class SectionTestDataSource extends DelegatingDataSource {
    interface Hook { void accept(String sql, Connection connection) throws SQLException; }
    final AtomicInteger selects = new AtomicInteger();
    volatile Hook before = (sql, connection) -> { };
    volatile Hook after = (sql, connection) -> { };

    SectionTestDataSource(DataSource target) { super(target); }
    void reset() { selects.set(0); before = (sql, connection) -> { }; after = (sql, connection) -> { }; }
    @Override public Connection getConnection() throws SQLException { return wrap(super.getConnection()); }
    @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(super.getConnection(user, password)); }

    private Connection wrap(Connection actual) {
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(actual, args);
                if (!(result instanceof Statement statement)) return result;
                String preparedSql = method.getName().equals("prepareStatement") ? (String) args[0] : null;
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{method.getReturnType()}, (p, m, a) -> {
                    try {
                        String sql = preparedSql != null ? preparedSql : a != null && a.length > 0
                                && a[0] instanceof String argument ? argument : null;
                        boolean inspect = sql != null && m.getName().startsWith("execute");
                        if (inspect) {
                            before.accept(sql, actual);
                            var sqlWithoutLeadingSpace = sql.stripLeading();
                            if (preparedSql != null
                                    && (sqlWithoutLeadingSpace.startsWith("SELECT") || sqlWithoutLeadingSpace.startsWith("(SELECT")))
                                selects.incrementAndGet();
                        }
                        Object returned = m.invoke(statement, a);
                        if (inspect) after.accept(sql, actual);
                        return returned;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
}
