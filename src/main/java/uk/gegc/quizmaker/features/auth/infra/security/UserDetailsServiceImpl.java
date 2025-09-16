package uk.gegc.quizmaker.features.auth.infra.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uk.gegc.quizmaker.features.user.domain.model.User;
import uk.gegc.quizmaker.features.user.domain.repository.UserRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserDetailsServiceImpl implements UserDetailsService {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {

        User user = userRepository.findByUsernameWithRolesAndPermissions(usernameOrEmail)
                .or(() -> userRepository.findByEmailWithRolesAndPermissions(usernameOrEmail))
                .orElseThrow(() -> new UsernameNotFoundException("User not found with username or email: " + usernameOrEmail));

        List<GrantedAuthority> authorities = user.getRoles()
                .stream()
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
                .map(authority -> (GrantedAuthority) authority)
                .toList();

        // Debug logging to see what authorities are loaded
        log.info("User '{}' authenticated with authorities: {}", 
                user.getUsername(), 
                authorities.stream().map(GrantedAuthority::getAuthority).toList());
        
        // Also log roles and permissions separately for better debugging
        log.info("User '{}' roles: {}", 
                user.getUsername(), 
                user.getRoles().stream().map(role -> role.getRoleName()).toList());
        
        log.info("User '{}' permissions: {}", 
                user.getUsername(), 
                user.getRoles().stream()
                        .flatMap(role -> role.getPermissions().stream())
                        .map(permission -> permission.getPermissionName())
                        .distinct()
                        .toList());

        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),
                user.getHashedPassword(),
                user.isActive(),
                true,
                true,
                true,
                authorities
        );
    }
}
