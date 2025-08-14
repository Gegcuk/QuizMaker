package uk.gegc.quizmaker.features.user.application;

import org.springframework.web.multipart.MultipartFile;

public interface AvatarService {
    String uploadAndAssignAvatar(String username, MultipartFile file);
}


