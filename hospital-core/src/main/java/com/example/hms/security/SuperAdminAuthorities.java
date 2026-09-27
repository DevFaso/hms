package com.example.hms.security;

import com.example.hms.security.context.HospitalContext;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.List;
import java.util.Set;

/**
 * Design Q10, option A, applied by both auth filters: the authorities of the
 * request agree with the LIVE super-admin signal, so every
 * {@code @PreAuthorize} expression and {@code SecurityConfig} matcher that
 * names SUPER_ADMIN becomes live at once. The rule itself is
 * {@link RoleExpansion#reconcile}.
 */
public final class SuperAdminAuthorities {

    private SuperAdminAuthorities() {
    }

    /**
     * {@code authentication} when its authorities already agree with
     * {@code context}; otherwise an equivalent token carrying the reconciled
     * authorities. Only the two token types the filters produce are rebuilt;
     * any other is returned as is.
     */
    public static Authentication reconcile(Authentication authentication, HospitalContext context) {
        if (authentication == null) {
            return null;
        }
        List<String> current = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .toList();
        Set<String> reconciled = RoleExpansion.reconcile(current, context.isSuperAdmin(), context.getAssignedRoles());
        if (reconciled.size() == current.size()) {
            return authentication;
        }
        List<GrantedAuthority> authorities = reconciled.stream()
            .<GrantedAuthority>map(SimpleGrantedAuthority::new)
            .toList();
        AbstractAuthenticationToken rebuilt;
        if (authentication instanceof JwtAuthenticationToken jwt) {
            rebuilt = new JwtAuthenticationToken(jwt.getToken(), authorities, jwt.getName());
        } else if (authentication instanceof UsernamePasswordAuthenticationToken password) {
            rebuilt = UsernamePasswordAuthenticationToken.authenticated(
                password.getPrincipal(), password.getCredentials(), authorities);
        } else {
            return authentication;
        }
        rebuilt.setDetails(authentication.getDetails());
        return rebuilt;
    }
}
