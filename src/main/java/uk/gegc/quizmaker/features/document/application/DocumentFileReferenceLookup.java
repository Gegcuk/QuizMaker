package uk.gegc.quizmaker.features.document.application;

import java.util.Collection;
import java.util.Set;

/** Resolves committed document rows that own absolute normalized published paths. */
public interface DocumentFileReferenceLookup {

    Set<String> findReferencedPaths(Collection<String> candidatePaths);

    boolean isReferenced(String candidatePath);
}
