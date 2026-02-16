package com.example.hms.security.permission;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCatalogTest {

    @Test
    void templatesContainUniqueKnownPermissions() {
        for (String role : PermissionCatalog.registeredRoleCodes()) {
            List<String> permissions = PermissionCatalog.permissionsForRole(role);
            assertFalse(permissions.isEmpty(), "Expected permissions for " + role);

            Set<String> seen = new HashSet<>();
            for (String permission : permissions) {
                assertTrue(seen.add(permission), "Duplicate permission '" + permission + "' for role " + role);
                assertTrue(
                    PermissionCatalog.isKnownPermissionName(permission),
                    "Unknown permission '" + permission + "' for role " + role
                );
            }
        }
    }

    @Test
    void unknownRoleFallsBackToDefault() {
        assertEquals(List.of("View Dashboard"), PermissionCatalog.permissionsForRole("ROLE_UNKNOWN"));
    }
}
