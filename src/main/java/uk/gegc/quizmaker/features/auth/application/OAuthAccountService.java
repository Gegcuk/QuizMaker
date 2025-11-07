package uk.gegc.quizmaker.features.auth.application;

import org.springframework.security.core.Authentication;
import uk.gegc.quizmaker.features.auth.api.dto.LinkedAccountsResponse;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;

/**
 * Service interface for managing OAuth accounts
 */
public interface OAuthAccountService {
    
    /**
     * Get all OAuth accounts linked to the authenticated user
     */
    LinkedAccountsResponse getLinkedAccounts(Authentication authentication);
    
    /**
     * Unlink an OAuth account from the authenticated user
     */
    void unlinkAccount(Authentication authentication, OAuthProvider provider);
}

