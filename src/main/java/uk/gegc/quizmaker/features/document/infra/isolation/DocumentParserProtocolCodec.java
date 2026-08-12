package uk.gegc.quizmaker.features.document.infra.isolation;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class DocumentParserProtocolCodec {

    static final int PROTOCOL_VERSION = 1;
    static final String REQUEST_FILE = "request.json";
    static final String RESPONSE_FILE = "response.json";
    static final String INPUT_FILE = "input.document";
    private static final long MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_PROTOCOL_STRING = 100_000_000;

    private final ObjectMapper objectMapper;

    DocumentParserProtocolCodec() {
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(64)
                        .maxNumberLength(32)
                        .maxStringLength(MAX_PROTOCOL_STRING)
                        .build())
                .build();
        objectMapper = new ObjectMapper(jsonFactory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    void writeRequest(Path requestPath, DocumentParserWorkerRequest request) throws IOException {
        writeAtomically(requestPath, request, MAX_REQUEST_BYTES);
    }

    DocumentParserWorkerRequest readRequest(Path requestPath) throws IOException {
        validateReadableFile(requestPath, MAX_REQUEST_BYTES);
        return objectMapper.readValue(requestPath.toFile(), DocumentParserWorkerRequest.class);
    }

    void writeResponse(Path responsePath, DocumentParserWorkerResponse response, long maxBytes) throws IOException {
        writeAtomically(responsePath, response, maxBytes);
    }

    DocumentParserWorkerResponse readResponse(Path responsePath, long maxBytes) throws IOException {
        validateReadableFile(responsePath, maxBytes);
        return objectMapper.readValue(responsePath.toFile(), DocumentParserWorkerResponse.class);
    }

    private void writeAtomically(Path destination, Object value, long maxBytes) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        try (OutputStream output = new LimitedOutputStream(Files.newOutputStream(temporary), maxBytes)) {
            objectMapper.writeValue(output, value);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void validateReadableFile(Path path, long maxBytes) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Parser protocol file is missing or invalid");
        }
        long size = Files.size(path);
        if (size <= 0 || size > maxBytes) {
            throw new IOException("Parser protocol file exceeds its configured bound");
        }
    }

    static final class OutputLimitExceededException extends IOException {

        private OutputLimitExceededException() {
            super("Parser protocol output exceeds its configured bound");
        }
    }

    private static final class LimitedOutputStream extends FilterOutputStream {

        private final long maxBytes;
        private long written;

        private LimitedOutputStream(OutputStream output, long maxBytes) {
            super(output);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int value) throws IOException {
            ensureCapacity(1);
            out.write(value);
            written++;
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            ensureCapacity(length);
            out.write(buffer, offset, length);
            written += length;
        }

        private void ensureCapacity(int bytes) throws OutputLimitExceededException {
            if (bytes < 0 || written > maxBytes - bytes) {
                throw new OutputLimitExceededException();
            }
        }
    }
}
