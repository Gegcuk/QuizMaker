package uk.gegc.quizmaker.features.document.infra.isolation;

import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@FunctionalInterface
interface DocumentParserWorkerCommandFactory {

    List<String> create(Path operationDirectory, DocumentProcessingLimits limits);

    static DocumentParserWorkerCommandFactory currentApplication() {
        return (operationDirectory, limits) -> {
            List<String> command = new ArrayList<>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-Xms16m");
            command.add("-Xmx" + limits.getParserWorkerMaxHeapBytes());
            command.add("-XX:+ExitOnOutOfMemoryError");
            command.add("-Djava.awt.headless=true");
            command.add("-Djava.io.tmpdir=" + operationDirectory);

            String testClasspath = System.getProperty("surefire.test.class.path");
            String runtimeClasspath = System.getProperty("java.class.path");
            if ((testClasspath == null || testClasspath.isBlank()) && isExecutableJar(runtimeClasspath)) {
                command.add("-jar");
                command.add(Path.of(runtimeClasspath).toAbsolutePath().normalize().toString());
            } else {
                command.add("-cp");
                command.add(absolutizeClasspath(
                        testClasspath == null || testClasspath.isBlank() ? runtimeClasspath : testClasspath));
                command.add(DocumentParserWorkerMain.class.getName());
            }
            command.add(DocumentParserWorkerMain.WORKER_ARGUMENT + operationDirectory);
            return List.copyOf(command);
        };
    }

    private static boolean isExecutableJar(String classpath) {
        if (classpath == null || classpath.isBlank() || classpath.contains(File.pathSeparator)) {
            return false;
        }
        Path candidate = Path.of(classpath).toAbsolutePath().normalize();
        return candidate.getFileName().toString().endsWith(".jar") && Files.isRegularFile(candidate);
    }

    private static String absolutizeClasspath(String classpath) {
        if (classpath == null || classpath.isBlank()) {
            throw new IllegalStateException("Document parser worker classpath is unavailable");
        }
        String[] entries = classpath.split(java.util.regex.Pattern.quote(File.pathSeparator));
        List<String> normalized = new ArrayList<>(entries.length);
        for (String entry : entries) {
            if (!entry.isBlank()) {
                normalized.add(Path.of(entry).toAbsolutePath().normalize().toString());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalStateException("Document parser worker classpath is unavailable");
        }
        return String.join(File.pathSeparator, normalized);
    }
}
