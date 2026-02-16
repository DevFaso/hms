package com.example.hms.security;

import com.example.hms.model.User;
import lombok.AllArgsConstructor;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

@AllArgsConstructor
public class CustomUserDetails implements HospitalUserDetails {

    private final UUID userId;
    private final String username;
    private final String password;
    private final boolean active;
    private final Collection<? extends GrantedAuthority> authorities;

    public CustomUserDetails(User user, Collection<? extends GrantedAuthority> authorities) {
        this.userId = user.getId();
        this.username = user.getUsername();
        this.password = user.getPasswordHash();
        this.active = user.isActive();
        this.authorities = authorities;
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

