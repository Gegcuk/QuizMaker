package uk.gegc.quizmaker.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import uk.gegc.quizmaker.model.user.Role;
import uk.gegc.quizmaker.model.user.RoleName;
import uk.gegc.quizmaker.repository.user.RoleRepository;

@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final RoleRepository roleRepository;

    @Override
    public void run(String... args) throws Exception {
        for (RoleName roleName : RoleName.values()) {
            String name = roleName.name();
            if (roleRepository.findByRole(name).isEmpty()) {
                Role role = new Role();
                role.setRole(name);
                roleRepository.save(role);
                System.out.println("Inserted role: " + name);
            }
        }
    }
}
