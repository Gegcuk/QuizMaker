package uk.gegc.quizmaker.features.document.infra.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.DocumentProcessingLimits;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Document parser worker entry point validation")
class DocumentParserWorkerMainTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    @DisplayName("Rejects a protocol request that points outside its private operation directory")
    void rejectsSourceOutsidePrivateOperation() throws Exception {
        Path operation = Files.createDirectory(temporaryDirectory.resolve("operation"));
        Path unrelated = Files.writeString(temporaryDirectory.resolve("unrelated.upload"), "Private notes\n");
        DocumentProcessingLimits limits = limits(operation);
        DocumentParserWorkerRequest request = new DocumentParserWorkerRequest(
                DocumentParserProtocolCodec.PROTOCOL_VERSION,
                ProcessHandle.current().pid(),
                unrelated.toString(),
                temporaryDirectory.toString(),
                "notes.txt",
                "text/plain",
                Files.size(unrelated),
                DocumentParserWorkerRequest.ParserLimits.from(limits, operation)
        );
        new DocumentParserProtocolCodec().writeRequest(
                operation.resolve(DocumentParserProtocolCodec.REQUEST_FILE), request);

        int exitCode = DocumentParserWorkerMain.run(new String[]{
                DocumentParserWorkerMain.WORKER_ARGUMENT + operation
        });

        assertThat(exitCode).isEqualTo(64);
        assertThat(operation.resolve(DocumentParserProtocolCodec.RESPONSE_FILE)).doesNotExist();
    }

    @Test
    @DisplayName("Rejects an incompatible request protocol version before conversion")
    void rejectsIncompatibleRequestVersion() throws Exception {
        Path operation = Files.createDirectory(temporaryDirectory.resolve("operation"));
        Path source = Files.writeString(
                operation.resolve(DocumentParserProtocolCodec.INPUT_FILE), "Private notes\n");
        DocumentProcessingLimits limits = limits(operation);
        DocumentParserWorkerRequest request = new DocumentParserWorkerRequest(
                DocumentParserProtocolCodec.PROTOCOL_VERSION + 1,
                ProcessHandle.current().pid(),
                source.toString(),
                operation.toString(),
                "notes.txt",
                "text/plain",
                Files.size(source),
                DocumentParserWorkerRequest.ParserLimits.from(limits, operation)
        );
        new DocumentParserProtocolCodec().writeRequest(
                operation.resolve(DocumentParserProtocolCodec.REQUEST_FILE), request);

        int exitCode = DocumentParserWorkerMain.run(new String[]{
                DocumentParserWorkerMain.WORKER_ARGUMENT + operation
        });

        assertThat(exitCode).isEqualTo(64);
        assertThat(operation.resolve(DocumentParserProtocolCodec.RESPONSE_FILE)).doesNotExist();
    }

    private DocumentProcessingLimits limits(Path operation) {
        DocumentProcessingLimits limits = DocumentProcessingLimits.defaults();
        limits.setStorageRoot(operation.toString());
        limits.setMaxExtractedCharacters(10_000);
        limits.setParserWorkerMaxOutputBytes(1_000_000);
        return limits;
    }
}
