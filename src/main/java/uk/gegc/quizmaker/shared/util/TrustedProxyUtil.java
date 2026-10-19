package uk.gegc.quizmaker.shared.util;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

@Component
public class TrustedProxyUtil {
    @Value("${app.security.trusted-proxies:}")
    private String trustedProxiesConfig;
    @Value("${app.security.enable-forwarded-headers:false}")
    private boolean enableForwardedHeaders;
    private volatile List<IpAddressMatcher> trustedProxies;

    @PostConstruct
    void validateConfiguration() { trusted("127.0.0.1"); }

    /** Walk from the socket peer through trusted hops; never select an attacker-prepended address. */
    public String getClientIp(HttpServletRequest request) {
        String remote = literal(request.getRemoteAddr());
        if (remote == null) return null;
        String header = request.getHeader("X-Forwarded-For");
        if (!enableForwardedHeaders || !trusted(remote) || header == null || header.isBlank()) return remote;
        if (header.length() > 2048) return remote;
        String[] chain = header.split(",", -1);
        if (chain.length > 32) return remote;
        String current = remote;
        for (int index = chain.length - 1; index >= 0 && trusted(current); index--) {
            String next = literal(chain[index].strip());
            if (next == null) return remote;
            current = next;
        }
        return current;
    }

    private boolean trusted(String address) {
        if (trustedProxies == null) {
            synchronized (this) {
                if (trustedProxies == null) {
                    String configuration = trustedProxiesConfig == null || trustedProxiesConfig.isBlank()
                            ? "127.0.0.1,::1" : trustedProxiesConfig;
                    List<IpAddressMatcher> matchers = new ArrayList<>();
                    for (String configured : configuration.split(",")) {
                        String value = configured.strip();
                        if ("localhost".equalsIgnoreCase(value)) value = "127.0.0.1";
                        String base = value.split("/", -1)[0];
                        if (literal(base) == null) {
                            throw new IllegalStateException("Trusted proxies must be IP literals or explicit CIDRs");
                        }
                        matchers.add(new IpAddressMatcher(value));
                    }
                    trustedProxies = List.copyOf(matchers);
                }
            }
        }
        return trustedProxies.stream().anyMatch(matcher -> matcher.matches(address));
    }

    private static String literal(String input) {
        if (input == null || input.length() > 45 || input.isBlank()) return null;
        if (input.contains(":")) {
            if (!input.matches("[0-9a-fA-F:.]+")) return null;
        } else {
            String[] parts = input.split("\\.", -1);
            if (parts.length != 4) return null;
            for (String part : parts) {
                if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return null;
            }
        }
        try { return InetAddress.getByName(input).getHostAddress(); }
        catch (UnknownHostException exception) { return null; }
    }
}
