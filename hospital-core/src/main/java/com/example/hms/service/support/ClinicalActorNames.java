package com.example.hms.service.support;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.UserDisplayUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.UUID;

/**
 * Current human names for stable clinical actor IDs, distinct from audit authentication identities.
 * Renaming an account updates its display label; attribution remains the stored UUID. Snapshots
 * remain the fallback when the actor no longer exists or has no human name, including new rows.
 */
@Component
@RequiredArgsConstructor
public class ClinicalActorNames {
    private final UserRepository users;

    public String display(UUID id, String snapshot) {
        return id == null ? snapshot : users.findById(id).map(user -> display(user, snapshot)).orElse(snapshot);
    }

    /** One scalar projection for a list; no eager user associations or per-row user queries. */
    public java.util.function.BiFunction<UUID, String, String> batch(java.util.Collection<UUID> ids) {
        var unique = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<UUID, User> names = new java.util.HashMap<>();
        if (!unique.isEmpty()) {
            for (var row : users.findClinicalActorNames(unique)) {
                User user = new User();
                user.setFirstName(row.getFirstName()); user.setLastName(row.getLastName());
                user.setUsername(row.getUsername());
                names.put(row.getId(), user);
            }
        }
        return (id, snapshot) -> display(names.get(id), snapshot);
    }

    public static String display(User user, String snapshot) {
        if (user == null) return snapshot;
        if (StringUtils.hasText(user.getFirstName()) || StringUtils.hasText(user.getLastName())) {
            return UserDisplayUtil.resolveDisplayName(user);
        }
        return StringUtils.hasText(snapshot) ? snapshot : user.getUsername();
    }
}
