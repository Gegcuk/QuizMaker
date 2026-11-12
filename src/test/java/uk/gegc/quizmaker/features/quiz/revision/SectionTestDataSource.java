package uk.gegc.quizmaker.features.quiz.revision;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
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
                if (!method.getName().equals("prepareStatement")) return result;
                var statement = (PreparedStatement) result;
                var sql = (String) args[0];
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{PreparedStatement.class}, (p, m, a) -> {
                    try {
                        boolean execute = m.getName().startsWith("execute");
                        if (execute) {
                            before.accept(sql, actual);
                            if (sql.stripLeading().startsWith("SELECT")) selects.incrementAndGet();
                        }
                        Object returned = m.invoke(statement, a);
                        if (execute) after.accept(sql, actual);
                        return returned;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
            } catch (InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
}
