package uk.gegc.quizmaker.features.auth.application.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import uk.gegc.quizmaker.features.auth.api.dto.LinkedAccountsResponse;
import uk.gegc.quizmaker.features.auth.api.dto.OAuthAccountDto;
import uk.gegc.quizmaker.features.auth.application.OAuthAccountService;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthAccountRepository;
import uk.gegc.quizmaker.features.auth.infra.mapping.OAuthAccountMapper;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * Service implementation for managing OAuth accounts
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OAuthAccountServiceImpl implements OAuthAccountService {

    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final OAuthAccountMapper oauthAccountMapper;

    @Override
    @Transactional(readOnly = true)
    public LinkedAccountsResponse getLinkedAccounts(Authentication authentication) {
        UUID userId = extractUserId(authentication);
        
        List<OAuthAccount> accounts = oauthAccountRepository.findByUserId(userId);
        List<OAuthAccountDto> accountDtos = accounts.stream()
                .map(oauthAccountMapper::toDto)
                .toList();
        
        return new LinkedAccountsResponse(accountDtos);
    }

    @Override
    @Transactional
    public void unlinkAccount(Authentication authentication, OAuthProvider provider) {
        UUID userId = extractUserId(authentication);
        
        // Verify user has at least one other authentication method
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        
        List<OAuthAccount> allAccounts = oauthAccountRepository.findByUserId(userId);
        
        // Check if user has a password or multiple OAuth accounts
        boolean hasPassword = user.getHashedPassword() != null && !user.getHashedPassword().isEmpty();
        boolean hasMultipleOAuthAccounts = allAccounts.size() > 1;
        
        if (!hasPassword && !hasMultipleOAuthAccounts) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, 
                "Cannot unlink the only authentication method. Please set a password first or link another OAuth account."
            );
        }
        
        // Find and delete the OAuth account
        OAuthAccount accountToUnlink = allAccounts.stream()
                .filter(account -> account.getProvider().equals(provider))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.NOT_FOUND, 
                    "OAuth account not found for provider: " + provider
                ));
        
        oauthAccountRepository.delete(accountToUnlink);
        log.info("Unlinked OAuth account: userId={}, provider={}", userId, provider);
    }

    private UUID extractUserId(Authentication authentication) {
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}

