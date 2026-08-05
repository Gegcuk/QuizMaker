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
import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
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

    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";
    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String PASSWORD_CHANGED_AT_CLAIM = "pwdChangedAt";
    private static final String USER_ID_CLAIM = "uid";
    private static final String SESSION_ID_CLAIM = "sid";

    @PostConstruct
    public void init() {
        byte[] keyBytes = Decoders.BASE64.decode(base64secret);
        this.key = Keys.hmacShaKeyFor(keyBytes);
    }

    public String generateAccessToken(Authentication authentication, UUID sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenValidityInMs);
        return generateToken(authentication, sessionId, ACCESS_TOKEN_TYPE, now, expiry);
    }

    public String generateRefreshToken(Authentication authentication, UUID sessionId, Date expiry) {
        Date now = new Date();
        return generateToken(authentication, sessionId, REFRESH_TOKEN_TYPE, now, expiry);
    }

    private String generateToken(
            Authentication authentication,
            UUID sessionId,
            String tokenType,
            Date issuedAt,
            Date expiry
    ) {
        User user = findUserByIdentifier(authentication.getName())
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot generate token for unknown user: " + authentication.getName()));
        long passwordChangedAtEpoch = toEpochMillis(user.getPasswordChangedAt());
        return Jwts.builder()
                .subject(authentication.getName())
                .issuedAt(issuedAt)
                .expiration(expiry)
                .claim(TOKEN_TYPE_CLAIM, tokenType)
                .claim(PASSWORD_CHANGED_AT_CLAIM, passwordChangedAtEpoch)
                .claim(USER_ID_CLAIM, user.getId().toString())
                .claim(SESSION_ID_CLAIM, sessionId.toString())
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
        return validateAccessToken(token).isPresent();
    }

    public Optional<ValidatedJwt> validateAccessToken(String token) {
        return validateTokenForType(token, ACCESS_TOKEN_TYPE);
    }

    public Optional<ValidatedJwt> validateRefreshToken(String token) {
        return validateTokenForType(token, REFRESH_TOKEN_TYPE);
    }

    private Optional<ValidatedJwt> validateTokenForType(String token, String expectedType) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            String username = claims.getSubject();
            if (username == null || username.isBlank()) {
                log.warn("JWT token missing subject");
                return Optional.empty();
            }

            if (!expectedType.equals(claims.get(TOKEN_TYPE_CLAIM, String.class))) {
                log.debug("JWT token purpose is not valid for this operation");
                return Optional.empty();
            }

            Long tokenPasswordChangedAt = claims.get(PASSWORD_CHANGED_AT_CLAIM, Long.class);
            if (tokenPasswordChangedAt == null) {
                log.warn("JWT token missing password version claim");
                return Optional.empty();
            }

            Optional<User> user = findUserByIdentifier(username);
            if (user.isEmpty() || user.get().getPasswordChangedAt() == null) {
                log.warn("JWT subject is not associated with an active user or password version");
                return Optional.empty();
            }

            if (toEpochMillis(user.get().getPasswordChangedAt()) > tokenPasswordChangedAt) {
                log.debug("Rejecting JWT because password changed after token issuance");
                return Optional.empty();
            }

            UUID claimedUserId = readUuidClaim(claims, USER_ID_CLAIM);
            UUID sessionId = readUuidClaim(claims, SESSION_ID_CLAIM);
            if (claimedUserId == null || sessionId == null || !claimedUserId.equals(user.get().getId())) {
                log.debug("JWT token is missing or has inconsistent session identity claims");
                return Optional.empty();
            }

            return Optional.of(new ValidatedJwt(
                    username,
                    claimedUserId,
                    sessionId,
                    claims.getExpiration().toInstant()
            ));
        } catch (ExpiredJwtException ex) {
            log.debug("JWT token is expired: {}", ex.getMessage());
            return Optional.empty();
        } catch (MalformedJwtException ex) {
            log.warn("Malformed JWT token received: {}", ex.getMessage());
            return Optional.empty();
        } catch (SignatureException ex) {
            log.warn("Invalid JWT signature detected: {}", ex.getMessage());
            return Optional.empty();
        } catch (IllegalArgumentException ex) {
            log.warn("Illegal argument passed to JWT parser: {}", ex.getMessage());
            return Optional.empty();
        } catch (JwtException ex) {
            log.error("Unexpected JWT exception: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    public Authentication getAuthentication(ValidatedJwt validatedJwt) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(validatedJwt.username());
        return new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    /**
     * Produces a fixed-length verifier for server-side refresh-token comparison.
     */
    public String fingerprintRefreshToken(String refreshToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to fingerprint refresh token", ex);
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

    private UUID readUuidClaim(Claims claims, String claimName) {
        String value = claims.get(claimName, String.class);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private Optional<User> findUserByIdentifier(String identifier) {
        return userRepository.findByUsername(identifier)
                .or(() -> userRepository.findByEmail(identifier));
    }

    private long toEpochMillis(LocalDateTime timestamp) {
        return timestamp.atOffset(ZoneOffset.UTC).toInstant().toEpochMilli();
    }
}
