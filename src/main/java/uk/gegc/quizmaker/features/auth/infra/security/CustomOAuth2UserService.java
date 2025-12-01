package uk.gegc.quizmaker.features.auth.infra.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthAccount;
import uk.gegc.quizmaker.features.auth.domain.model.OAuthProvider;
import uk.gegc.quizmaker.features.auth.domain.repository.OAuthAccountRepository;
import uk.gegc.quizmaker.features.auth.domain.service.OAuthUsernameGenerator;
import uk.gegc.quizmaker.features.user.domain.model.Role;
import uk.gegc.quizmaker.features.user.domain.model.RoleName;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.RoleRepository;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;
import uk.gegc.quizmaker.features.billing.application.BillingService;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Custom OAuth2UserService that handles user authentication via OAuth2 providers.
 * This service creates or updates user accounts based on OAuth2 authentication.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomOAuth2UserService extends DefaultOAuth2UserService {

    private final UserRepository userRepository;
    private final OAuthAccountRepository oauthAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final OAuthTokenCryptoService tokenCryptoService;
    private final OAuthUsernameGenerator oauthUsernameGenerator;
    private final BillingService billingService;
    private final TransactionTemplate transactionTemplate;

    @Value("${app.auth.registration-bonus-tokens:100}")
    private long registrationBonusTokens;

    private static final String REGISTRATION_BONUS_REF = "registration-bonus";

    @Override
    @Transactional
    public OAuth2User loadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        OAuth2User oauth2User = callSuperLoadUser(userRequest);

        String registrationId = userRequest.getClientRegistration().getRegistrationId();
        OAuthProvider provider = mapRegistrationIdToProvider(registrationId);

        // Extract user information from OAuth2 provider
        String providerUserId = extractProviderUserId(oauth2User, provider);
        String email = extractEmail(oauth2User);
        String name = extractName(oauth2User);
        String profileImageUrl = extractProfileImageUrl(oauth2User, provider);

        log.info("OAuth2 authentication: provider={}, providerUserId={}, email={}", 
                provider, providerUserId, email);

        // Find or create user based on OAuth account
        User user = processOAuthAuthentication(
            provider, providerUserId, email, name, profileImageUrl, userRequest
        );

        // Update last login
        user.setLastLoginDate(LocalDateTime.now());
        userRepository.save(user);

        // Build authorities from user roles and permissions
        List<GrantedAuthority> authorities = buildAuthorities(user);

        return new CustomOAuth2User(oauth2User, user.getId(), user.getUsername(), authorities);
    }

    /**
     * Protected wrapper method for super.loadUser() to allow mocking in tests
     */
    protected OAuth2User callSuperLoadUser(OAuth2UserRequest userRequest) throws OAuth2AuthenticationException {
        return super.loadUser(userRequest);
    }

    private User processOAuthAuthentication(
        OAuthProvider provider,
        String providerUserId,
        String email,
        String name,
        String profileImageUrl,
        OAuth2UserRequest userRequest
    ) {
        // Try to find existing OAuth account
        Optional<OAuthAccount> existingOAuthAccount = 
            oauthAccountRepository.findByProviderAndProviderUserIdWithUser(provider, providerUserId);

        if (existingOAuthAccount.isPresent()) {
            // OAuth account exists, update token information
            OAuthAccount oauthAccount = existingOAuthAccount.get();
            updateOAuthAccountTokens(oauthAccount, userRequest);
            oauthAccountRepository.save(oauthAccount);
            
            // Auto-verify email if not already verified (OAuth provider has verified it)
            User user = oauthAccount.getUser();
            if (!user.isEmailVerified() && email != null) {
                user.setEmailVerified(true);
                user.setEmailVerifiedAt(LocalDateTime.now());
                userRepository.save(user);
                log.info("Auto-verified email for existing OAuth user: userId={}, provider={}", 
                        user.getId(), provider);
            }
            
            return user;
        }

        // OAuth account doesn't exist, try to link with existing user by email
        if (email != null) {
            Optional<User> existingUser = userRepository.findByEmailWithRoles(email);
            if (existingUser.isPresent()) {
                User user = existingUser.get();
                // Auto-verify email if not already verified (OAuth provider has verified it)
                if (!user.isEmailVerified()) {
                    user.setEmailVerified(true);
                    user.setEmailVerifiedAt(LocalDateTime.now());
                    userRepository.save(user);
                    log.info("Auto-verified email for user via OAuth: userId={}, provider={}", 
                            user.getId(), provider);
                }
                // Link OAuth account to existing user
                createOAuthAccount(user, provider, providerUserId, email, name, profileImageUrl, userRequest);
                log.info("Linked OAuth account to existing user: userId={}, provider={}", 
                        user.getId(), provider);
                return user;
            }
        }

        // Create new user with OAuth account
        User newUser = createUserFromOAuth(email, name, profileImageUrl);
        createOAuthAccount(newUser, provider, providerUserId, email, name, profileImageUrl, userRequest);
        
        // Grant registration bonus tokens
        creditRegistrationBonusTokens(newUser.getId());
        
        log.info("Created new user from OAuth: userId={}, provider={}", newUser.getId(), provider);
        
        return newUser;
    }

    private User createUserFromOAuth(String email, String name, String profileImageUrl) {
        User user = new User();
        user.setUsername(oauthUsernameGenerator.generate(email, name));
        user.setEmail(email != null ? email : generatePlaceholderEmail());
        user.setHashedPassword(generateRandomPassword()); // Random password since OAuth user
        user.setActive(true);
        user.setEmailVerified(email != null); // Auto-verify if email from OAuth provider
        if (email != null) {
            user.setEmailVerifiedAt(LocalDateTime.now());
        }
        user.setDisplayName(name);
        user.setAvatarUrl(profileImageUrl);

        // Assign default roles
        Role userRole = roleRepository.findByRoleName(RoleName.ROLE_USER.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_USER not found"));
        Role quizCreatorRole = roleRepository.findByRoleName(RoleName.ROLE_QUIZ_CREATOR.name())
                .orElseThrow(() -> new IllegalStateException("ROLE_QUIZ_CREATOR not found"));
        
        // Use mutable HashSet for Hibernate to manage the collection
        Set<Role> roles = new HashSet<>();
        roles.add(userRole);
        roles.add(quizCreatorRole);
        user.setRoles(roles);

        return userRepository.save(user);
    }

    private void createOAuthAccount(
        User user,
        OAuthProvider provider,
        String providerUserId,
        String email,
        String name,
        String profileImageUrl,
        OAuth2UserRequest userRequest
    ) {
        OAuthAccount oauthAccount = new OAuthAccount();
        oauthAccount.setUser(user);
        oauthAccount.setProvider(provider);
        oauthAccount.setProviderUserId(providerUserId);
        oauthAccount.setEmail(email);
        oauthAccount.setName(name);
        oauthAccount.setProfileImageUrl(profileImageUrl);
        updateOAuthAccountTokens(oauthAccount, userRequest);
        oauthAccountRepository.save(oauthAccount);
    }

    private void updateOAuthAccountTokens(OAuthAccount oauthAccount, OAuth2UserRequest userRequest) {
        String accessToken = userRequest.getAccessToken().getTokenValue();
        oauthAccount.setAccessToken(tokenCryptoService.encrypt(accessToken));

        Object refreshToken = userRequest.getAdditionalParameters().get("refresh_token");
        if (refreshToken instanceof String refreshTokenValue && !refreshTokenValue.isBlank()) {
            oauthAccount.setRefreshToken(tokenCryptoService.encrypt(refreshTokenValue));
        }
        
        if (userRequest.getAccessToken().getExpiresAt() != null) {
            oauthAccount.setTokenExpiresAt(
                LocalDateTime.ofInstant(
                    userRequest.getAccessToken().getExpiresAt(),
                    java.time.ZoneId.systemDefault()
                )
            );
        }
        
        // Refresh token is typically not available in standard OAuth2UserRequest
        // but can be obtained from additional scopes if needed
    }

    private String generatePlaceholderEmail() {
        return "oauth_" + UUID.randomUUID().toString().substring(0, 8) + "@oauth.placeholder";
    }

    private String generateRandomPassword() {
        // Generate secure random password for OAuth users
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String randomPassword = java.util.Base64.getEncoder().encodeToString(bytes);
        return passwordEncoder.encode(randomPassword);
    }

    private List<GrantedAuthority> buildAuthorities(User user) {
        return user.getRoles().stream()
                .flatMap(role -> {
                    // Add role authority
                    var roleAuthority = new SimpleGrantedAuthority(role.getRoleName());
                    // Add permission authorities
                    var permissionAuthorities = role.getPermissions().stream()
                            .map(permission -> new SimpleGrantedAuthority(permission.getPermissionName()));
                    return java.util.stream.Stream.concat(
                            java.util.stream.Stream.of(roleAuthority),
                            permissionAuthorities
                    );
                })
                .collect(Collectors.toList());
    }

    /**
     * Credits registration bonus tokens to a newly registered user.
     * This method is non-blocking - registration will succeed even if token credit fails.
     * Uses a separate transaction (REQUIRES_NEW) to prevent rollback of the main registration.
     * Errors are logged but do not propagate to ensure user registration always succeeds.
     *
     * @param userId the ID of the newly registered user
     */
    private void creditRegistrationBonusTokens(UUID userId) {
        try {
            // Create a new transaction template with REQUIRES_NEW propagation
            TransactionTemplate newTransactionTemplate = new TransactionTemplate(
                    transactionTemplate.getTransactionManager()
            );
            newTransactionTemplate.setPropagationBehavior(
                    org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW
            );
            
            newTransactionTemplate.executeWithoutResult(status -> {
                String idempotencyKey = REGISTRATION_BONUS_REF + ":" + userId;
                billingService.creditAdjustment(userId, registrationBonusTokens, idempotencyKey, REGISTRATION_BONUS_REF, null);
            });
        } catch (Exception e) {
            // Log error but don't fail registration if token credit fails
            // This ensures registration succeeds even if billing service has issues
            // The error will also be logged by the billing service
            log.warn("Failed to credit registration bonus tokens for OAuth user {}: {}", userId, e.getMessage());
        }
    }

    private OAuthProvider mapRegistrationIdToProvider(String registrationId) {
        return switch (registrationId.toLowerCase()) {
            case "google" -> OAuthProvider.GOOGLE;
            case "github" -> OAuthProvider.GITHUB;
            case "facebook" -> OAuthProvider.FACEBOOK;
            case "microsoft" -> OAuthProvider.MICROSOFT;
            case "apple" -> OAuthProvider.APPLE;
            default -> throw new IllegalArgumentException("Unsupported OAuth provider: " + registrationId);
        };
    }

    private String extractProviderUserId(OAuth2User oauth2User, OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> oauth2User.getAttribute("sub");
            case GITHUB -> oauth2User.getAttribute("id") != null ? 
                          oauth2User.getAttribute("id").toString() : null;
            case FACEBOOK -> oauth2User.getAttribute("id");
            case MICROSOFT -> oauth2User.getAttribute("sub");
            case APPLE -> oauth2User.getAttribute("sub");
        };
    }

    private String extractEmail(OAuth2User oauth2User) {
        return oauth2User.getAttribute("email");
    }

    private String extractName(OAuth2User oauth2User) {
        String name = oauth2User.getAttribute("name");
        if (name == null) {
            // Try to construct from first and last name
            String firstName = oauth2User.getAttribute("given_name");
            String lastName = oauth2User.getAttribute("family_name");
            if (firstName != null && lastName != null) {
                name = firstName + " " + lastName;
            } else if (firstName != null) {
                name = firstName;
            }
        }
        return name;
    }

    private String extractProfileImageUrl(OAuth2User oauth2User, OAuthProvider provider) {
        return switch (provider) {
            case GOOGLE -> oauth2User.getAttribute("picture");
            case GITHUB -> oauth2User.getAttribute("avatar_url");
            case FACEBOOK -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> picture = oauth2User.getAttribute("picture");
                if (picture != null) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) picture.get("data");
                    yield data != null ? (String) data.get("url") : null;
                }
                yield null;
            }
            case MICROSOFT -> oauth2User.getAttribute("picture");
            case APPLE -> null; // Apple doesn't provide profile pictures
        };
    }
}
