package uk.gegc.quizmaker.features.document.infra.isolation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gegc.quizmaker.features.document.application.ConvertedDocument;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Document parser worker protocol")
class DocumentParserProtocolCodecTest {

    @TempDir
    Path operationDirectory;


    @Test
    @DisplayName("Rejects unknown fields instead of accepting an incompatible worker response")
    void rejectsUnknownResponseFields() throws IOException {
        Path response = operationDirectory.resolve(DocumentParserProtocolCodec.RESPONSE_FILE);
        Files.writeString(response,
                "{\"protocolVersion\":1,\"document\":null,\"error\":\"PROCESSING_FAILED\",\"poison\":true}");

        assertThatThrownBy(() -> new DocumentParserProtocolCodec().readResponse(response, 4_096))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Rejects trailing JSON tokens after an otherwise valid response")
    void rejectsTrailingProtocolTokens() throws IOException {
        Path response = operationDirectory.resolve(DocumentParserProtocolCodec.RESPONSE_FILE);
        Files.writeString(response,
                "{\"protocolVersion\":1,\"document\":null,\"error\":\"PROCESSING_FAILED\"} {} ");

        assertThatThrownBy(() -> new DocumentParserProtocolCodec().readResponse(response, 4_096))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Stops serialization when converted output exceeds the parent-owned byte bound")
    void rejectsOversizedSerializedOutput() {
        ConvertedDocument document = new ConvertedDocument();
        document.setFullContent("x".repeat(4_096));
        Path response = operationDirectory.resolve(DocumentParserProtocolCodec.RESPONSE_FILE);

        assertThatThrownBy(() -> new DocumentParserProtocolCodec().writeResponse(
                response, DocumentParserWorkerResponse.success(document), 1_024))
                .isInstanceOf(DocumentParserProtocolCodec.OutputLimitExceededException.class);
        assertThat(response).doesNotExist();
    }

}
