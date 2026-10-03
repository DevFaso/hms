package com.example.hms.security;

import com.example.hms.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The login throttle: {@value #MAX_ATTEMPTS} failed logins within
 * {@value #WINDOW_MS} ms lock the account for {@value #LOCK_DURATION_MS} ms.
 *
 * <p><strong>Keyed on the account, not the name.</strong> The key for a
 * username that belongs to exactly one account is that account's id, so a
 * lock follows the account through a rename (it used to stay behind on the old
 * name, and the renamed account started with a clean slate), and an account
 * later created under the old name inherits nothing. Keying on the name could
 * not be fixed by moving state across the rename: {@code uq_user_username}
 * (V1_1) is case-sensitive, so {@code Victim} and {@code victim} can be two
 * accounts sharing one lower-cased name, and moving a lock between them would
 * let one account clear or inherit another's.
 *
 * <p><strong>Unknown names are throttled too.</strong> A name with no account
 * (or, from before #776, with two case-variant accounts, neither of which can
 * sign in) is keyed on a SHA-256 of the lower-cased name. It locks after the
 * same number of failures and answers the same 423, so the lockout cannot tell
 * a prober whether a name exists. Every call makes the same single id lookup
 * for a known and an unknown name, before any password check. Hashing keeps a
 * password typed into the username field out of the store.
 *
 * <p>The state lives in a {@link LoginAttemptStore}: Redis when it is wired,
 * so a lock and its reset hold on every instance; per JVM otherwise.
 */
@Slf4j
@Service
public class LoginAttemptService {

    static final int MAX_ATTEMPTS = 5;
    static final long LOCK_DURATION_MS = 15 * 60 * 1000L; // 15 minutes
    static final long WINDOW_MS = LOCK_DURATION_MS;

    static final String USER_KEY_PREFIX = "user:";
    static final String NAME_KEY_PREFIX = "name:";

    private final LoginAttemptStore store;
    private final UserRepository userRepository;

    public LoginAttemptService(LoginAttemptStore store, UserRepository userRepository) {
        this.store = store;
        this.userRepository = userRepository;
    }

    /** Record a failed login for the name as typed. */
    public void recordFailure(String username) {
        String key = keyFor(username);
        if (key == null) {
            return;
        }
        int failures = store.recordFailure(key, MAX_ATTEMPTS, WINDOW_MS, LOCK_DURATION_MS);
        if (failures >= MAX_ATTEMPTS) {
            // The account id when there is one; never the typed name.
            log.warn("[LOGIN-THROTTLE] {} locked after {} failed attempts",
                key.startsWith(USER_KEY_PREFIX) ? "Account " + key.substring(USER_KEY_PREFIX.length())
                    : "An unknown username", failures);
        }
    }

    /** Whether a login with this name is currently refused. */
    public boolean isLocked(String username) {
        return remainingLockMs(username) > 0;
    }

    /** Milliseconds left on the lock for this name, 0 when it is not locked. */
    public long remainingLockMs(String username) {
        String key = keyFor(username);
        return key == null ? 0 : store.remainingLockMs(key);
    }

    /**
     * Clear the account's failures and lock: after a successful login and on
     * every activation path. By id, so it clears the lock whatever name the
     * failures were typed under.
     */
    public void resetAttempts(UUID userId) {
        if (userId != null) {
            store.clear(USER_KEY_PREFIX + userId);
        }
    }

    /**
     * The throttle key for a typed name: {@code user:<id>} for the one account
     * the login lookup would find, otherwise {@code name:<sha-256>}.
     */
    String keyFor(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        List<UUID> ids = userRepository.findIdsByUsernameIgnoreCase(username);
        if (ids.size() == 1) {
            return USER_KEY_PREFIX + ids.get(0);
        }
        return NAME_KEY_PREFIX + sha256(username.toLowerCase(Locale.ROOT));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            // Every JVM ships SHA-256 (it is a required algorithm).
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
