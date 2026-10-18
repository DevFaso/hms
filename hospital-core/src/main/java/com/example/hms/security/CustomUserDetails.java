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

    public CustomUserDetails(UUID userId, String username, String password, boolean active,
                             Collection<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.username = username;
        this.password = password;
        this.active = active;
        this.authorities = authorities;
        this.passwordChangedAt = null;
    }

    public CustomUserDetails(User user, Collection<? extends GrantedAuthority> authorities) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.active = user.isActive();
        this.authorities = authorities;
        this.passwordChangedAt = user.getPasswordChangedAt();
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

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return active;
    }
}

