package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** QA D168: login must not reveal that an account is inactive to a wrong password. */
class SecurityConfigPasswordFirstTest {

    @SuppressWarnings("deprecation")
    private DaoAuthenticationProvider provider() {
        UserDetailsService users = username -> User.withUsername(username)
            .password("right").disabled(true).authorities("ROLE_NURSE").build();
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(NoOpPasswordEncoder.getInstance());
        SecurityConfig.checkPasswordBeforeAccountStatus(provider);
        return provider;
    }

    @Test
    @DisplayName("an inactive account with a wrong password is plain bad credentials")
    void wrongPasswordOnInactiveAccountIsBadCredentials() {
        DaoAuthenticationProvider provider = provider();
        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken("nurse", "wrong")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    @DisplayName("an inactive account with the right password is still told it is inactive")
    void rightPasswordOnInactiveAccountIsDisabled() {
        DaoAuthenticationProvider provider = provider();
        assertThatThrownBy(() -> provider.authenticate(new UsernamePasswordAuthenticationToken("nurse", "right")))
            .isInstanceOf(DisabledException.class);
    }
}
