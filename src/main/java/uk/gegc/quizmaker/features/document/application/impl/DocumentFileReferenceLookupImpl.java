package uk.gegc.quizmaker.features.document.application.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.document.application.DocumentFileReferenceLookup;
import uk.gegc.quizmaker.features.document.domain.repository.DocumentRepository;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
public class DocumentFileReferenceLookupImpl implements DocumentFileReferenceLookup {

    private final DocumentRepository documentRepository;

    @Override
    public Set<String> findReferencedPaths(Collection<String> candidatePaths) {
        if (candidatePaths == null || candidatePaths.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(documentRepository.findReferencedFilePaths(candidatePaths));
    }

    @Override
    public boolean isReferenced(String candidatePath) {
        if (candidatePath == null || candidatePath.isBlank()) {
            return true;
        }
        return documentRepository.existsByFilePath(candidatePath);
    }
}
