package uk.gegc.quizmaker.features.documentProcess.application;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Captures statement shapes only; no bind values or source content. */
public class StructureSqlCapture implements StatementInspector {
    private static final ThreadLocal<List<String>> SQL = ThreadLocal.withInitial(ArrayList::new);
    @Override public String inspect(String sql) { SQL.get().add(sql.toLowerCase(Locale.ROOT)); return sql; }
    static void clear() { SQL.remove(); }
    static List<String> statements() { return List.copyOf(SQL.get()); }
}
