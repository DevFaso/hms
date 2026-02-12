package com.example.hms.model.empi;

import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmpiMergeEventTest {

    @Test
    @SuppressWarnings("java:S3011")
    void onPersistBackfillsTimestampWhenMissing() throws Exception {
        EmpiMergeEvent event = EmpiMergeEvent.builder().build();

        Method onPersist = EmpiMergeEvent.class.getDeclaredMethod("onPersist");
        onPersist.setAccessible(true);
        onPersist.invoke(event);

        assertThat(event.getMergedAt()).isNotNull();
    }

    @Test
    void applyTenantScopePopulatesMissingIds() {
        UUID orgId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        HospitalContext context = HospitalContext.builder()
            .activeOrganizationId(orgId)
            .activeHospitalId(hospitalId)
            .permittedDepartmentIds(Set.of(deptId))
            .build();

        EmpiMergeEvent event = EmpiMergeEvent.builder()
            .mergedAt(OffsetDateTime.now())
            .build();

        event.applyTenantScope(context);

        assertThat(event.getOrganizationId()).isEqualTo(orgId);
        assertThat(event.getHospitalId()).isEqualTo(hospitalId);
        assertThat(event.getDepartmentId()).isEqualTo(deptId);
    }
}
