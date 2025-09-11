package uk.gegc.quizmaker.features.billing.api;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

/**
 * Utility class for extracting user context from security context in billing operations.
 */
public class BillingSecurityUtils {

    /**
     * Extract the current user ID from the security context.
     * 
     * @return the current user ID
     * @throws IllegalStateException if no authenticated user is found
     */
    public static UUID getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("No authenticated user found");
        }
        
        // Extract user ID from the authentication principal
        // The principal should contain the user ID (typically as a string)
        String userIdStr = authentication.getName(); // This should be the user ID
        
        if (userIdStr == null || userIdStr.isEmpty()) {
            throw new IllegalStateException("User ID not found in authentication");
        }
        
        try {
            return UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Invalid user ID format in authentication: " + userIdStr);
        }
    }
    
    /**
     * Check if the current user has a specific authority.
     * 
     * @param authority the authority to check
     * @return true if the user has the authority
     */
    public static boolean hasAuthority(String authority) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals(authority));
    }
    
    /**
     * Check if the current user is authenticated.
     * 
     * @return true if the user is authenticated
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }
}
