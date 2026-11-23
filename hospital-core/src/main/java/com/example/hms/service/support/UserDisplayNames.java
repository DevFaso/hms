package com.example.hms.service.support;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * QA D3150 (r70a): a stored login shown as the person's name.
 *
 * <p>Some stamps keep the actor's login ({@code PatientInsurance.verifiedBy}),
 * and a login is not what a colleague reads on the screen. This resolves a
 * login to the user's first and last name; a login that names no user, or a
 * user with no name, resolves to null, so the screen shows no name rather than
 * the login.
 */
@Component
@RequiredArgsConstructor
public class UserDisplayNames {

    private final UserRepository userRepository;

    /** The person's name behind one login, or null. */
    public String ofUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return ofUsernames(List.of(username)).get(key(username));
    }

    /**
     * The names behind several logins, read at once; keyed by the login lower-cased. The map answers
     * null for a login it does not hold, a null one included.
     */
    public Map<String, String> ofUsernames(Collection<String> usernames) {
        Map<String, String> names = new HashMap<>();
        List<String> keys = usernames.stream().filter(Objects::nonNull).filter(u -> !u.isBlank())
            .map(UserDisplayNames::key).distinct().toList();
        if (keys.isEmpty()) {
            return names;
        }
        for (User user : userRepository.findByUsernameInIgnoreCase(keys)) {
            String name = nameOf(user);
            if (name != null && user.getUsername() != null) {
                names.put(key(user.getUsername()), name);
            }
        }
        return names;
    }

    /** The lookup key of a login. */
    public static String key(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    private static String nameOf(User user) {
        String name = Stream.of(user.getFirstName(), user.getLastName())
            .filter(part -> part != null && !part.isBlank())
            .map(String::trim)
            .collect(Collectors.joining(" "));
        return name.isBlank() ? null : name;
    }
}
