package uk.gegc.quizmaker.features.attempt.compatibility;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Measures actual statements and injects faults/concurrent commits at the JDBC boundary. */
final class InventoryDataSource extends DelegatingDataSource {
    interface Hook { void accept(String sql, Connection connection) throws SQLException; }
    record Query(String text, List<Object> parameters) { }
    final List<Query> queries = new CopyOnWriteArrayList<>();
    volatile Hook before;
    volatile Hook after;
    InventoryDataSource(DataSource target) { super(target); }

    @Override public Connection getConnection() throws SQLException {
        Connection connection = super.getConnection();
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        if (!method.getName().equals("prepareStatement")) return result;
                        String sql = (String) args[0];
                        PreparedStatement statement = (PreparedStatement) result;
                        var parameters = new TreeMap<Integer, Object>();
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                (p, m, a) -> {
                                    if (m.getName().startsWith("set") && a != null && a.length >= 2 && a[0] instanceof Integer i)
                                        parameters.put(i, a[1]);
                                    boolean execute = m.getName().startsWith("execute");
                                    if (execute) {
                                        queries.add(new Query(sql, new ArrayList<>(parameters.values())));
                                        if (before != null) before.accept(sql, connection);
                                    }
                                    try {
                                        Object value = m.invoke(statement, a);
                                        if (execute && after != null) after.accept(sql, connection);
                                        return value;
                                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                                });
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
    void reset() { queries.clear(); before = null; after = null; }
}
