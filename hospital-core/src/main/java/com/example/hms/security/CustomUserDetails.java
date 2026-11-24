package com.example.hms.security;

import com.example.hms.model.User;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

public class CustomUserDetails implements HospitalUserDetails {

    private final UUID userId;
    private final String username;
    private final String password;
    private final boolean active;
    private final Collection<? extends GrantedAuthority> authorities;
    /** QA D114: tokens issued before this instant are refused. */
    private final java.time.LocalDateTime passwordChangedAt;
    /** The holder must set their own password before any other use (PasswordChangeGate). */
    private final boolean passwordChangeRequired;
    /** When a temporary password stops working; null for a password the holder chose. */
    private final java.time.LocalDateTime temporaryPasswordExpiresAt;

    public CustomUserDetails(UUID userId, String username, String password, boolean active,
                             Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.active = active;
        this.authorities = authorities;
        this.passwordChangedAt = null;
        this.passwordChangeRequired = false;
        this.temporaryPasswordExpiresAt = null;
    }

    public CustomUserDetails(User user, Collection<? extends GrantedAuthority> authorities) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.active = user.isActive();
        this.authorities = authorities;
        this.passwordChangedAt = user.getPasswordChangedAt();
        this.passwordChangeRequired = user.isForcePasswordChange();
        this.temporaryPasswordExpiresAt = user.getTemporaryPasswordExpiresAt();
    }

    @Override
    public boolean isPasswordChangeRequired() {
        return passwordChangeRequired;
    }

    @Override
    public java.time.LocalDateTime getPasswordChangedAt() {
        return passwordChangedAt;
    }

    @Override
    public UUID getUserId() {
        return userId;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return password;
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    /**
     * False once a temporary password has expired. Spring checks it only after
     * the password matched (SecurityConfig#checkPasswordBeforeAccountStatus),
     * so the sign-in answers "expired" only to someone who knows the password.
     */
    @Override
    public boolean isCredentialsNonExpired() {
        return !com.example.hms.utility.TemporaryPasswords.expired(
            temporaryPasswordExpiresAt, java.time.LocalDateTime.now());
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}

