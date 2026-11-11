package uk.gegc.quizmaker.shared.testing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.util.Map;

/** Enabled only in the isolated context execution. Never logs connection credentials. */
public final class ContextWorkerEvidence implements BeforeAllCallback {
    private static boolean recorded;

    @Override
    public synchronized void beforeAll(ExtensionContext context) throws Exception {
        String fork = System.getProperty("quizmaker.tests.context-fork");
        if (fork == null) return;
        if (!fork.matches("[1-4]") || !("3390" + fork).equals(System.getenv("TEST_MYSQL_PORT")))
            throw new IllegalStateException("Context fork " + fork + " is not using its isolated MySQL port: " + System.getenv("TEST_MYSQL_PORT"));
        Path directory = Path.of("target/context-workers");
        Files.createDirectories(directory);
        if (!recorded) {
            try (var connection = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3390" + fork
                    + "/quizmaker_test?useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=5000",
                    System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "bestuser"),
                    System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", "bestuser"));
                 var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT @@server_uuid, VERSION(), @@transaction_isolation")) {
                result.next();
                new ObjectMapper().writeValue(directory.resolve("worker-" + fork + ".json").toFile(), Map.of(
                        "fork", fork, "pid", ProcessHandle.current().pid(), "server_uuid", result.getString(1),
                        "mysql_version", result.getString(2), "isolation", result.getString(3)));
            }
            recorded = true;
        }
        Files.writeString(directory.resolve("worker-" + fork + ".classes"), context.getRequiredTestClass().getName() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
