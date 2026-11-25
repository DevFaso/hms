package com.example.hms.config;

import com.example.hms.model.User;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.utility.TemporaryPasswords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.CredentialsExpiredException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A password someone else chose for the holder is temporary: hashed, forced change, and it stops signing in
 * {@link TemporaryPasswords#LIFETIME} after it was issued. The sign-in says so only to someone who typed it right
 * (the provider's post-password check, QA D168), and the holder's own password never expires this way.
 */
class TemporaryPasswordExpiryTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final String PASSWORD = "Temp@Issued14c";

    private final User account = new User();

    private DaoAuthenticationProvider provider() {
        account.setUsername("awa.t");
        account.setActive(true);
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(
            username -> new CustomUserDetails(account, List.of(new SimpleGrantedAuthority("ROLE_PATIENT"))));
        provider.setPasswordEncoder(ENCODER);
        SecurityConfig.checkPasswordBeforeAccountStatus(provider);
        return provider;
    }

    @Test
    @DisplayName("assign keeps the hash only, forces the change and sets the 72-hour expiry")
    void assignMarksThePasswordTemporary() {
        LocalDateTime issuedAt = LocalDateTime.of(2026, 11, 24, 9, 0);

        TemporaryPasswords.assign(account, ENCODER.encode(PASSWORD), issuedAt);

        assertThat(account.getPasswordHash()).isNotEqualTo(PASSWORD).startsWith("$2");
        assertThat(ENCODER.matches(PASSWORD, account.getPasswordHash())).isTrue();
        assertThat(account.isForcePasswordChange()).isTrue();
        assertThat(account.getPasswordChangedAt()).isEqualTo(issuedAt);
        assertThat(account.getTemporaryPasswordExpiresAt()).isEqualTo(issuedAt.plusHours(72));
    }

    @Test
    @DisplayName("a temporary password signs in until its expiry")
    void aFreshTemporaryPasswordSignsIn() {
        DaoAuthenticationProvider provider = provider();
        TemporaryPasswords.assign(account, ENCODER.encode(PASSWORD), LocalDateTime.now());

        assertThat(provider.authenticate(new UsernamePasswordAuthenticationToken("awa.t", PASSWORD))
            .isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("after 72 hours the right temporary password is refused as expired")
    void anExpiredTemporaryPasswordIsRefused() {
        DaoAuthenticationProvider provider = provider();
        TemporaryPasswords.assign(account, ENCODER.encode(PASSWORD), LocalDateTime.now().minusHours(73));

        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken("awa.t", PASSWORD)))
            .isInstanceOf(CredentialsExpiredException.class);
    }

    @Test
    @DisplayName("a wrong password is plain bad credentials, expired or not: the expiry tells a guesser nothing")
    void aWrongPasswordNeverLearnsOfTheExpiry() {
        DaoAuthenticationProvider provider = provider();
        TemporaryPasswords.assign(account, ENCODER.encode(PASSWORD), LocalDateTime.now().minusHours(73));

        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken("awa.t", "guess")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("once the holder sets their own password nothing expires")
    void theHoldersOwnPasswordDoesNotExpire() {
        DaoAuthenticationProvider provider = provider();
        TemporaryPasswords.assign(account, ENCODER.encode(PASSWORD), LocalDateTime.now().minusHours(73));
        account.setPasswordHash(ENCODER.encode("MyOwnChoice2026"));
        account.setForcePasswordChange(false);
        TemporaryPasswords.retire(account);

        assertThat(provider.authenticate(new UsernamePasswordAuthenticationToken("awa.t", "MyOwnChoice2026"))
            .isAuthenticated()).isTrue();
        assertThat(account.getTemporaryPasswordExpiresAt()).isNull();
    }
}
