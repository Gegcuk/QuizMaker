package uk.gegc.quizmaker.util;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

@Component
public class TrustedProxyUtil {
    
    @Value("${app.security.trusted-proxies:}")
    private String trustedProxiesConfig;
    
    @Value("${app.security.enable-forwarded-headers:false}")
    private boolean enableForwardedHeaders;
    
    private List<String> trustedProxies;
    
    /**
     * Safely extracts the client IP address from the request,
     * respecting X-Forwarded-For header only from trusted proxies.
     * 
     * @param request The HTTP request
     * @return The client IP address
     */
    public String getClientIp(HttpServletRequest request) {
        if (!enableForwardedHeaders) {
            return request.getRemoteAddr();
        }
        
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.trim().isEmpty()) {
            return request.getRemoteAddr();
        }
        
        // Check if the request is from a trusted proxy
        String remoteAddr = request.getRemoteAddr();
        if (!isTrustedProxy(remoteAddr)) {
            return remoteAddr; // Don't trust X-Forwarded-For from untrusted sources
        }
        
        // Extract the first (leftmost) IP from X-Forwarded-For
        String clientIp = forwardedFor.split(",")[0].trim();
        
        // Basic validation - ensure it's a valid IP format
        if (isValidIpAddress(clientIp)) {
            return clientIp;
        }
        
        return request.getRemoteAddr();
    }
    
    private boolean isTrustedProxy(String ip) {
        if (trustedProxies == null) {
            initializeTrustedProxies();
        }
        
        return trustedProxies.contains(ip) || 
               trustedProxies.stream().anyMatch(proxy -> ip.startsWith(proxy));
    }
    
    private void initializeTrustedProxies() {
        if (trustedProxiesConfig == null || trustedProxiesConfig.trim().isEmpty()) {
            trustedProxies = List.of("127.0.0.1", "::1", "localhost"); // Default trusted
        } else {
            trustedProxies = Arrays.asList(trustedProxiesConfig.split(","));
        }
    }
    
    private boolean isValidIpAddress(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }
        
        // Basic IPv4 validation
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            try {
                for (String part : parts) {
                    int num = Integer.parseInt(part);
                    if (num < 0 || num > 255) {
                        return false;
                    }
                }
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        
        // Basic IPv6 validation (simplified)
        if (ip.contains(":")) {
            return ip.matches("^[0-9a-fA-F:]+$");
        }
        
        return false;
    }
}
