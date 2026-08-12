package uk.gegc.quizmaker.features.document.application.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Document file reference lookup")
class DocumentFileReferenceLookupImplTest {

    private final DocumentRepository documentRepository = mock(DocumentRepository.class);
    private final DocumentFileReferenceLookupImpl referenceLookup =
            new DocumentFileReferenceLookupImpl(documentRepository);

    @Test
    @DisplayName("Returns only committed paths found for a bounded candidate batch")
    void findsReferencedCandidatePaths() {
        List<String> candidates = List.of("/storage/one.pdf", "/storage/two.pdf");
        when(documentRepository.findReferencedFilePaths(candidates))
                .thenReturn(List.of("/storage/two.pdf"));

        Set<String> referenced = referenceLookup.findReferencedPaths(candidates);

        assertThat(referenced).containsExactly("/storage/two.pdf");
    }

    @Test
    @DisplayName("Avoids an invalid empty IN query when no candidates exist")
    void emptyCandidateBatchNeedsNoQuery() {
        assertThat(referenceLookup.findReferencedPaths(List.of())).isEmpty();

        verify(documentRepository, never()).findReferencedFilePaths(List.of());
    }

    @Test
    @DisplayName("Uses an exact authoritative lookup for the final deletion check")
    void checksExactReferenceBeforeDeletion() {
        String candidate = "/storage/rechecked.pdf";
        when(documentRepository.existsByFilePath(candidate)).thenReturn(true);

        assertThat(referenceLookup.isReferenced(candidate)).isTrue();

        verify(documentRepository).existsByFilePath(candidate);
    }

    @Test
    @DisplayName("Treats an invalid exact path as referenced so ambiguity cannot trigger deletion")
    void invalidExactPathFailsClosed() {
        assertThat(referenceLookup.isReferenced(" ")).isTrue();

        verify(documentRepository, never()).existsByFilePath(" ");
    }
}
