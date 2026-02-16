package com.example.hms.model.empi;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.security.context.HospitalContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EmpiMasterIdentityTest {

    @Test
    @SuppressWarnings("java:S3011")
    void normalizeTrimsAndUppercasesFields() throws Exception {
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber(" emp-101 ")
            .sourceSystem("  ehr  ")
            .mrnSnapshot("  snapshot ")
            .build();

        Method normalize = EmpiMasterIdentity.class.getDeclaredMethod("normalize");
        normalize.setAccessible(true);
        normalize.invoke(identity);

        assertThat(identity.getEmpiNumber()).isEqualTo("EMP-101");
        assertThat(identity.getSourceSystem()).isEqualTo("ehr");
        assertThat(identity.getMrnSnapshot()).isEqualTo("snapshot");
    }

    @Test
    void applyTenantScopePopulatesMissingIdentifiers() {
        UUID orgId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID deptId = UUID.randomUUID();
        HospitalContext context = HospitalContext.builder()
            .activeOrganizationId(orgId)
            .activeHospitalId(hospitalId)
            .permittedDepartmentIds(Set.of(deptId))
            .build();

        EmpiMasterIdentity identity = EmpiMasterIdentity.builder().build();
        identity.applyTenantScope(context);

        assertThat(identity.getOrganizationId()).isEqualTo(orgId);
        assertThat(identity.getHospitalId()).isEqualTo(hospitalId);
        assertThat(identity.getDepartmentId()).isEqualTo(deptId);
    }

    @Test
    void applyTenantScopeRespectsExistingValues() {
        UUID existingOrg = UUID.randomUUID();
        UUID newOrg = UUID.randomUUID();
        HospitalContext context = HospitalContext.builder()
            .activeOrganizationId(newOrg)
            .build();

        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .organizationId(existingOrg)
            .build();

        identity.applyTenantScope(context);

        assertThat(identity.getOrganizationId()).isEqualTo(existingOrg);
    }

    @Test
    void addAliasBackfillsRelationship() {
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder().build();
        EmpiIdentityAlias alias = EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue("abc")
            .build();

        identity.addAlias(alias);

        assertThat(identity.getAliases()).contains(alias);
        assertThat(alias.getMasterIdentity()).isEqualTo(identity);
    }
}
