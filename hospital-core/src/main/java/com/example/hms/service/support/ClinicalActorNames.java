package com.example.hms.service.support;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.UserDisplayUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

/** Human names for clinical actor fields, distinct from audit authentication identities. */
@Component
@RequiredArgsConstructor
public class ClinicalActorNames {
    private final UserRepository users;

    public String display(UUID id, String snapshot) {
        return id == null ? snapshot : users.findById(id).map(user -> display(user, snapshot)).orElse(snapshot);
    }

    public static String display(User user, String snapshot) {
        if (user == null) return snapshot;
        if (StringUtils.hasText(user.getFirstName()) || StringUtils.hasText(user.getLastName())) {
            return UserDisplayUtil.resolveDisplayName(user);
        }
        return StringUtils.hasText(snapshot) ? snapshot : user.getUsername();
    }
}
