package uk.gegc.quizmaker.features.auth.infra.security;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Custom OAuth2User implementation that includes our User entity information
 */
@Getter
public class CustomOAuth2User implements OAuth2User {

    private final OAuth2User oauth2User;
    private final UUID userId;
    private final String username;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomOAuth2User(OAuth2User oauth2User, UUID userId, String username, 
                           Collection<? extends GrantedAuthority> authorities) {
        this.oauth2User = oauth2User;
        this.userId = userId;
        this.username = username;
        this.authorities = authorities;
    }

    @Override
    public Map<String, Object> getAttributes() {
        return oauth2User.getAttributes();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getName() {
        return username;
    }
}

