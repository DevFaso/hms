package com.example.hms.security;

import com.example.hms.config.SecurityConstants;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

/**
 * The application's own role authorities: every {@code ROLE_*} constant of {@link SecurityConstants}, plus
 * {@link RoleExpansion#ROLE_PHYSICIAN}. An authority outside it (Keycloak's stock realm roles such as
 * {@code ROLE_OFFLINE_ACCESS}, {@code ROLE_UMA_AUTHORIZATION}, {@code ROLE_DEFAULT-ROLES-<realm>}) is not an HMS
 * role and decides nothing about who the caller is.
 */
public final class HmsRoles {

    private static final String ROLE_PREFIX = SecurityConstants.ROLE_PREFIX;
    private static final Set<String> ALL = collect();

    private HmsRoles() {
    }

    /** Every HMS role authority, {@code ROLE_*}. */
    public static Set<String> all() {
        return ALL;
    }

    /** Whether the authority is one of the application's own roles. */
    public static boolean isHmsRole(String authority) {
        return authority != null && ALL.contains(authority);
    }

    private static Set<String> collect() {
        Set<String> roles = new HashSet<>();
        for (Field field : SecurityConstants.class.getFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                && field.getName().startsWith(ROLE_PREFIX)) {
                try {
                    String value = (String) field.get(null);
                    if (value != null && value.startsWith(ROLE_PREFIX) && value.length() > ROLE_PREFIX.length()) {
                        roles.add(value);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("SecurityConstants." + field.getName() + " is not readable", e);
                }
            }
        }
        roles.add(RoleExpansion.ROLE_PHYSICIAN);
        return Set.copyOf(roles);
    }
}
