package uk.gegc.quizmaker.features.auth.infra.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Optional;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;


@Component
@RequiredArgsConstructor
@Getter
@Slf4j
public class JwtTokenService {

    private final UserDetailsService userDetailsService;
    private final UserRepository userRepository;

    @Value("${jwt.secret}")
    private String base64secret;

    @Value("${jwt.access-expiration-ms}")
    private long accessTokenValidityInMs;

    @Value("${jwt.refresh-expiration-ms}")
    private long refreshTokenValidityInMs;

    private SecretKey key;

    private static final String PASSWORD_CHANGED_AT_CLAIM = "pwdChangedAt";

    @PostConstruct
    public void init() {
        byte[] keyBytes = Decoders.BASE64.decode(base64secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Authentication authentication) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenValidityInMs);
        long passwordChangedAtEpoch = resolvePasswordChangedAtEpoch(authentication.getName());

        return Jwts.builder()
                .subject(authentication.getName())
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", "access")
                .claim(PASSWORD_CHANGED_AT_CLAIM, passwordChangedAtEpoch)
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken(Authentication authentication) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenValidityInMs);
        long passwordChangedAtEpoch = resolvePasswordChangedAtEpoch(authentication.getName());

        return Jwts.builder()
                .subject(authentication.getName())
                .issuedAt(now)
                .expiration(expiry)
                .claim("type", "refresh")
                .claim(PASSWORD_CHANGED_AT_CLAIM, passwordChangedAtEpoch)
                .signWith(key)
                .compact();
    }

    public Authentication getAuthentication(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String username = claims.getSubject();
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    public boolean validateToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String username = claims.getSubject();
            if (username == null || username.isBlank()) {
                log.warn("JWT token missing subject");
                return false;
            }

            Long tokenPasswordChangedAt = claims.get(PASSWORD_CHANGED_AT_CLAIM, Long.class);
            if (tokenPasswordChangedAt == null) {
                log.warn("JWT token missing password version claim");
                return false;
            }

            Optional<Long> currentPasswordChangedAt = findUserByIdentifier(username)
                    .map(User::getPasswordChangedAt)
                    .map(this::toEpochMillis);

            if (currentPasswordChangedAt.isEmpty()) {
                log.warn("User '{}' not found or missing passwordChangedAt when validating token", username);
                return false;
            }

            if (currentPasswordChangedAt.get() > tokenPasswordChangedAt) {
                log.debug("Rejecting JWT for user '{}' because password changed after token issuance", username);
                return false;
            }

            return true;
        } catch (ExpiredJwtException ex) {
            log.debug("JWT token is expired: {}", ex.getMessage());
            return false;
        } catch (MalformedJwtException ex) {
            log.warn("Malformed JWT token received: {}", ex.getMessage());
            return false;
        } catch (SignatureException ex) {
            log.warn("Invalid JWT signature detected: {}", ex.getMessage());
            return false;
        } catch (IllegalArgumentException ex) {
            log.warn("Illegal argument passed to JWT parser: {}", ex.getMessage());
            return false;
        } catch (JwtException ex) {
            log.error("Unexpected JWT exception: {}", ex.getMessage());
            return false;
        }
    }

    public String getUsername(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public Claims getClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private long resolvePasswordChangedAtEpoch(String username) {
        return findUserByIdentifier(username)
                .map(User::getPasswordChangedAt)
                .map(this::toEpochMillis)
                .orElseThrow(() -> new IllegalStateException("Cannot generate token for unknown user: " + username));
    }

    private Optional<User> findUserByIdentifier(String identifier) {
        return userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier));
    }

    private long toEpochMillis(LocalDateTime timestamp) {
        return timestamp.atOffset(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
