package com.example.hms.service;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.support.HospitalScopeUtils;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;
import java.util.UUID;

/** Facility roles must be held at the hospital whose inventory or instruments are accessed. */
final class LabFacilityAccess {
    private static final Set<String> READERS = Set.of("ROLE_LAB_DIRECTOR", "ROLE_LAB_MANAGER",
        "ROLE_LAB_TECHNICIAN", "ROLE_LAB_SCIENTIST", "ROLE_HOSPITAL_ADMIN");
    private static final Set<String> MANAGERS = Set.of("ROLE_LAB_DIRECTOR", "ROLE_LAB_MANAGER", "ROLE_HOSPITAL_ADMIN");

    private LabFacilityAccess() { }

    static void require(UUID hospitalId, boolean manage) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (hospitalId == null) throw new AccessDeniedException("access.denied");
        if (context.isSuperAdmin()) return;
        if (!HospitalScopeUtils.resolveScope(context).contains(hospitalId)) {
            throw new AccessDeniedException("access.denied");
        }
        Set<String> held = context.getHospitalRoles().getOrDefault(hospitalId, Set.of());
        Set<String> allowed = manage ? MANAGERS : READERS;
        if (held.stream().noneMatch(allowed::contains)) throw new AccessDeniedException("access.denied");
    }
}
