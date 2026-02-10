package com.example.hms.security.tenant;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import lombok.extern.slf4j.Slf4j;

import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

/**
 * JPA entity listener that populates tenant identifiers from the {@link HospitalContext} when a tenant-scoped
 * entity is persisted or updated. The listener skips entities that are not {@link TenantScoped}.
 */
@Slf4j
public class TenantEntityListener {

    @PrePersist
    @PreUpdate
    public void applyTenantScope(Object entity) {
        if (!(entity instanceof TenantScoped scopedEntity)) {
            return;
        }

        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (log.isTraceEnabled()) {
            log.trace("Applying tenant scope to {} using context={}", entity.getClass().getSimpleName(), context);
        }
        scopedEntity.applyTenantScope(context);
    }
}
