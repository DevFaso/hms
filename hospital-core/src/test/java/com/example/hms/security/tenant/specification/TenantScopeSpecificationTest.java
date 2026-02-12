package com.example.hms.security.tenant.specification;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantScopeSpecificationTest {

    private static final String ATTRIBUTE_HOSPITAL = "hospital";
    private static final String ATTRIBUTE_ORGANIZATION = "organization";
    private static final String ATTRIBUTE_HOSPITAL_ID = "hospitalId";
    private static final String ATTRIBUTE_ORGANIZATION_ID = "organizationId";
    private static final String ATTRIBUTE_ID = "id";

    @AfterEach
    void clearHospitalContext() {
        HospitalContextHolder.clear();
    }

    @Test
    void appliesHospitalScopeViaAssociationWhenDirectColumnsMissing() {
        UUID permittedHospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .permittedHospitalIds(Set.of(permittedHospitalId))
            .build());

        TenantScopeSpecification<ScopedEntity> specification = new TenantScopeSpecification<>(ScopedEntity.class);

        Root<ScopedEntity> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);

        doThrow(new IllegalArgumentException("hospitalId column missing"))
            .when(root).get(ATTRIBUTE_HOSPITAL_ID);

    @SuppressWarnings("unchecked")
    Path<Object> hospitalIdPath = mock(Path.class);
        when(root.get(ATTRIBUTE_HOSPITAL).get(ATTRIBUTE_ID)).thenReturn(hospitalIdPath);

        Predicate hospitalPredicate = mock(Predicate.class);
        when(hospitalIdPath.in(Set.of(permittedHospitalId))).thenReturn(hospitalPredicate);

        Predicate combinedPredicate = mock(Predicate.class);
        when(criteriaBuilder.or(hospitalPredicate)).thenReturn(combinedPredicate);

        Predicate result = specification.toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(combinedPredicate);
        verify(hospitalIdPath).in(Set.of(permittedHospitalId));
    }

    @Test
    void appliesOrganizationScopeViaHospitalOrganizationRelationship() {
        UUID permittedOrganizationId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .permittedOrganizationIds(Set.of(permittedOrganizationId))
            .build());

        TenantScopeSpecification<ScopedEntity> specification = new TenantScopeSpecification<>(ScopedEntity.class);

        Root<ScopedEntity> root = mock(Root.class, RETURNS_DEEP_STUBS);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);

        doThrow(new IllegalArgumentException("organizationId column missing"))
            .when(root).get(ATTRIBUTE_ORGANIZATION_ID);
        doThrow(new IllegalArgumentException("organization relation missing"))
            .when(root).get(ATTRIBUTE_ORGANIZATION);

    @SuppressWarnings("unchecked")
    Path<Object> organizationIdPath = mock(Path.class);

        when(root.get(ATTRIBUTE_HOSPITAL).get(ATTRIBUTE_ORGANIZATION).get(ATTRIBUTE_ID))
            .thenReturn(organizationIdPath);

        Predicate organizationPredicate = mock(Predicate.class);
        when(organizationIdPath.in(Set.of(permittedOrganizationId))).thenReturn(organizationPredicate);

        Predicate combinedPredicate = mock(Predicate.class);
        when(criteriaBuilder.or(organizationPredicate)).thenReturn(combinedPredicate);

        Predicate result = specification.toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(combinedPredicate);
        verify(organizationIdPath).in(Set.of(permittedOrganizationId));
    }

    private static class ScopedEntity implements TenantScoped {
        @Override
        public UUID getTenantOrganizationId() {
            return null;
        }

        @Override
        public UUID getTenantHospitalId() {
            return null;
        }

        @Override
        public UUID getTenantDepartmentId() {
            return null;
        }

        @Override
        public void applyTenantScope(HospitalContext context) {
            // no-op for tests
        }
    }
}
