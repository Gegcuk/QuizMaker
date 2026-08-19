package uk.gegc.quizmaker.features.quiz.application.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import uk.gegc.quizmaker.features.document.api.dto.ProcessDocumentRequest;
import uk.gegc.quizmaker.features.question.domain.model.Difficulty;
import uk.gegc.quizmaker.features.question.domain.model.QuestionType;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromDocumentRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromTextRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.GenerateQuizFromUploadRequest;
import uk.gegc.quizmaker.features.quiz.api.dto.QuizScope;
import uk.gegc.quizmaker.shared.exception.ValidationException;
import uk.gegc.quizmaker.shared.validation.GenerationLanguagePolicy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Quiz generation request canonicalizer")
class QuizGenerationRequestCanonicalizerTest {

    private final QuizGenerationRequestCanonicalizer canonicalizer =
            new QuizGenerationRequestCanonicalizer(new ObjectMapper());

    @Test
    @DisplayName("Treats equivalent unordered selections and question matrices as the same command")
    void producesSameFingerprintForEquivalentUnorderedCommandFields() {
        UUID documentId = UUID.randomUUID();
        UUID firstTag = UUID.randomUUID();
        UUID secondTag = UUID.randomUUID();

        GenerateQuizFromDocumentRequest first = request(
                documentId,
                List.of(4, 1),
                List.of(firstTag, secondTag),
                Map.of(QuestionType.TRUE_FALSE, 1, QuestionType.MCQ_SINGLE, 2),
                "  Biology  "
        );
        GenerateQuizFromDocumentRequest second = request(
                documentId,
                List.of(1, 4),
                List.of(secondTag, firstTag),
                Map.of(QuestionType.MCQ_SINGLE, 2, QuestionType.TRUE_FALSE, 1),
                "Biology"
        );

        assertThat(canonicalizer.forDocument(first))
                .isEqualTo(canonicalizer.forDocument(second));
    }

    @Test
    @DisplayName("Changes the fingerprint when a material generation field changes")
    void changesFingerprintForMaterialCommandChange() {
        UUID documentId = UUID.randomUUID();
        GenerateQuizFromDocumentRequest original = request(
                documentId,
                List.of(1),
                List.of(),
                Map.of(QuestionType.MCQ_SINGLE, 2),
                "Biology"
        );
        GenerateQuizFromDocumentRequest changed = request(
                documentId,
                List.of(1),
                List.of(),
                Map.of(QuestionType.MCQ_SINGLE, 3),
                "Biology"
        );

        assertThat(canonicalizer.forDocument(original).hash())
                .isNotEqualTo(canonicalizer.forDocument(changed).hash());
    }

    @Test
    @DisplayName("Changes the fingerprint when the document source changes")
    void changesFingerprintForDifferentDocumentSource() {
        GenerateQuizFromDocumentRequest first = request(
                UUID.randomUUID(), List.of(1), List.of(), Map.of(QuestionType.MCQ_SINGLE, 2), "Biology");
        GenerateQuizFromDocumentRequest second = request(
                UUID.randomUUID(), List.of(1), List.of(), Map.of(QuestionType.MCQ_SINGLE, 2), "Biology");

        assertThat(canonicalizer.forDocument(first).hash())
                .isNotEqualTo(canonicalizer.forDocument(second).hash());
    }

    @Test
    @DisplayName("Changes the fingerprint when upload processing settings change")
    void changesFingerprintForMaterialUploadProcessingChange() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "biology.pdf", "application/pdf", new byte[1024]);
        GenerateQuizFromUploadRequest original = uploadRequest(
                ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, 5_000);
        GenerateQuizFromUploadRequest changed = uploadRequest(
                ProcessDocumentRequest.ChunkingStrategy.CHAPTER_BASED, 5_000);

        assertThat(canonicalizer.forUpload(original, file).hash())
                .isNotEqualTo(canonicalizer.forUpload(changed, file).hash());
    }

    @Test
    @DisplayName("Treats byte-identical uploads with equal metadata as the same source command")
    void producesSameFingerprintForByteIdenticalUploads() {
        GenerateQuizFromUploadRequest request = uploadRequest(
                ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, 5_000);
        MockMultipartFile first = new MockMultipartFile(
                "file", "biology.txt", "text/plain", "same source".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile retry = new MockMultipartFile(
                "file", "biology.txt", "text/plain", "same source".getBytes(StandardCharsets.UTF_8));

        assertThat(canonicalizer.forUpload(request, first))
                .isEqualTo(canonicalizer.forUpload(request, retry));
    }

    @Test
    @DisplayName("Changes the fingerprint for different upload bytes with identical metadata and size")
    void changesFingerprintForDifferentSameSizeUploadContent() {
        GenerateQuizFromUploadRequest request = uploadRequest(
                ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, 5_000);
        MockMultipartFile first = new MockMultipartFile(
                "file", "biology.txt", "text/plain", "alpha source".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile changed = new MockMultipartFile(
                "file", "biology.txt", "text/plain", "bravo source".getBytes(StandardCharsets.UTF_8));

        assertThat(first.getSize()).isEqualTo(changed.getSize());
        assertThat(canonicalizer.forUpload(request, first).hash())
                .isNotEqualTo(canonicalizer.forUpload(request, changed).hash());
    }

    @Test
    @DisplayName("Changes the fingerprint for different same-length text without retaining raw text")
    void changesFingerprintForDifferentSameLengthText() {
        GenerateQuizFromTextRequest first = textRequest("alpha source");
        GenerateQuizFromTextRequest changed = textRequest("bravo source");

        GenerationRequestFingerprint firstFingerprint = canonicalizer.forText(first);
        GenerationRequestFingerprint changedFingerprint = canonicalizer.forText(changed);

        assertThat(first.text()).hasSameSizeAs(changed.text());
        assertThat(firstFingerprint.hash())
                .hasSize(64)
                .doesNotContain(first.text())
                .isNotEqualTo(changedFingerprint.hash());
        assertThat(firstFingerprint.canonicalizationVersion()).isEqualTo("v2-source-digest");
    }

    @Test
    @DisplayName("Rejects an unreadable upload before an idempotency operation can be claimed")
    void rejectsUnreadableUpload() {
        MockMultipartFile unreadable = new MockMultipartFile(
                "file", "biology.txt", "text/plain", "content".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public InputStream getInputStream() throws IOException {
                throw new IOException("fixture read failure");
            }
        };

        assertThatThrownBy(() -> canonicalizer.forUpload(
                uploadRequest(ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED, 5_000), unreadable))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Unable to read upload content for idempotency validation");
    }

    @Test
    @DisplayName("Rejects invalid language instead of changing command identity")
    void rejectsInvalidLanguageInsteadOfNormalizingIt() {
        GenerateQuizFromDocumentRequest request = request(
                UUID.randomUUID(),
                List.of(1),
                List.of(),
                Map.of(QuestionType.MCQ_SINGLE, 2),
                "Biology",
                "EN"
        );

        assertThatThrownBy(() -> canonicalizer.forDocument(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(GenerationLanguagePolicy.INVALID_LANGUAGE_MESSAGE);
    }

    @Test
    @DisplayName("Preserves a supported non-English language in command identity")
    void preservesSupportedNonEnglishLanguageInCommandIdentity() {
        UUID documentId = UUID.randomUUID();
        GenerateQuizFromDocumentRequest english = request(
                documentId, List.of(1), List.of(), Map.of(QuestionType.MCQ_SINGLE, 2), "Biology", "en");
        GenerateQuizFromDocumentRequest french = request(
                documentId, List.of(1), List.of(), Map.of(QuestionType.MCQ_SINGLE, 2), "Biology", "fr");

        assertThat(canonicalizer.forDocument(french).hash())
                .isNotEqualTo(canonicalizer.forDocument(english).hash());
    }

    private GenerateQuizFromDocumentRequest request(
            UUID documentId,
            List<Integer> chunkIndices,
            List<UUID> tagIds,
            Map<QuestionType, Integer> questionsPerType,
            String title
    ) {
        return request(documentId, chunkIndices, tagIds, questionsPerType, title, "en");
    }

    private GenerateQuizFromDocumentRequest request(
            UUID documentId,
            List<Integer> chunkIndices,
            List<UUID> tagIds,
            Map<QuestionType, Integer> questionsPerType,
            String title,
            String language
    ) {
        return new GenerateQuizFromDocumentRequest(
                documentId,
                QuizScope.SPECIFIC_CHUNKS,
                chunkIndices,
                null,
                null,
                title,
                "A quiz",
                questionsPerType,
                Difficulty.MEDIUM,
                2,
                null,
                tagIds,
                language
        );
    }

    private GenerateQuizFromUploadRequest uploadRequest(
            ProcessDocumentRequest.ChunkingStrategy chunkingStrategy,
            int maxChunkSize
    ) {
        return new GenerateQuizFromUploadRequest(
                chunkingStrategy,
                maxChunkSize,
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Biology",
                "A quiz",
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of(),
                "en"
        );
    }

    private GenerateQuizFromTextRequest textRequest(String text) {
        return new GenerateQuizFromTextRequest(
                text,
                "en",
                ProcessDocumentRequest.ChunkingStrategy.SIZE_BASED,
                5_000,
                QuizScope.ENTIRE_DOCUMENT,
                null,
                null,
                null,
                "Biology",
                "A quiz",
                Map.of(QuestionType.MCQ_SINGLE, 2),
                Difficulty.MEDIUM,
                2,
                null,
                List.of()
        );
    }
}
