package uk.gegc.quizmaker.service.impl;

import lombok.*;
import org.springframework.stereotype.Service;
import uk.gegc.quizmaker.model.userManagment.User;
import uk.gegc.quizmaker.repository.UserRepository;
import uk.gegc.quizmaker.service.service.UserService;

@Service
@Getter
@Setter
@AllArgsConstructor
public class UserServiceImpl implements UserService {
    private final UserRepository userRepository;

    @Override
    public User createUser(User user) {
        User savedUser = userRepository.save(user);
        return savedUser;
    }
}
