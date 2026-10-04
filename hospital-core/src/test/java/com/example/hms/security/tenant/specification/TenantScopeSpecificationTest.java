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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The repository filter (docs/security/tenant-resolution.md §3.3, Q6 option A):
 * hospitals only — a pinned super-admin to the pin, a super-admin in global
 * view unfiltered, everyone else to their live permitted hospitals. The
 * organisation alternative is gone (D13).
 */
class TenantScopeSpecificationTest {

    private static final String ATTRIBUTE_HOSPITAL = "hospital";
    private static final String ATTRIBUTE_ORGANIZATION = "organization";
    private static final String ATTRIBUTE_HOSPITAL_ID = "hospitalId";
    private static final String ATTRIBUTE_ORGANIZATION_ID = "organizationId";
    private static final String ATTRIBUTE_ID = "id";

    private final Root<ScopedEntity> root = mockRoot();
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder criteriaBuilder = mock(CriteriaBuilder.class);
    private final TenantScopeSpecification<ScopedEntity> specification =
        new TenantScopeSpecification<>(ScopedEntity.class);

    @SuppressWarnings("unchecked")
    private static Root<ScopedEntity> mockRoot() {
        return mock(Root.class, RETURNS_DEEP_STUBS);
    }

    @AfterEach
    void clearHospitalContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("staff: the permitted hospitals, through the association when the direct column is missing")
    void appliesHospitalScopeViaAssociationWhenDirectColumnsMissing() {
        UUID permittedHospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .permittedHospitalIds(Set.of(permittedHospitalId))
            .build());
        Path<Object> hospitalIdPath = hospitalPathViaAssociation();
        Predicate hospitalPredicate = mock(Predicate.class);
        when(hospitalIdPath.in(Set.of(permittedHospitalId))).thenReturn(hospitalPredicate);

        assertThat(specification.toPredicate(root, query, criteriaBuilder)).isSameAs(hospitalPredicate);
        assertThat(HospitalContextHolder.isSealed()).as("reading the scope seals it").isTrue();
    }

    @Test
    @DisplayName("an organisation grants no read: a caller with organisations but no hospital sees nothing (D13)")
    void anOrganisationGrantsNoRead() {
        UUID permittedOrganizationId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .permittedOrganizationIds(Set.of(permittedOrganizationId))
            .build());
        Predicate nothing = mock(Predicate.class);
        when(criteriaBuilder.disjunction()).thenReturn(nothing);

        assertThat(specification.toPredicate(root, query, criteriaBuilder)).isSameAs(nothing);
        verify(root, never()).get(ATTRIBUTE_ORGANIZATION_ID);
        verify(root, never()).get(ATTRIBUTE_ORGANIZATION);
    }

    @Test
    @DisplayName("a staff member at A in organisation O is filtered to A only, never to O's other hospitals")
    void staffAreFilteredToTheirHospitalsNotTheirOrganisation() {
        UUID hospitalA = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .permittedHospitalIds(Set.of(hospitalA))
            .permittedOrganizationIds(Set.of(UUID.randomUUID()))
            .activeHospitalId(hospitalA)
            .build());
        Path<Object> hospitalIdPath = hospitalPathViaAssociation();
        Predicate onlyA = mock(Predicate.class);
        when(hospitalIdPath.in(Set.of(hospitalA))).thenReturn(onlyA);

        assertThat(specification.toPredicate(root, query, criteriaBuilder)).isSameAs(onlyA);
        verify(criteriaBuilder, never()).or(any(Predicate[].class));
    }

    @Test
    @DisplayName("a super-admin who named a hospital is filtered to it; in global view, not at all")
    void superAdminIsFilteredToThePinOnly() {
        UUID pinned = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .superAdmin(true).activeHospitalId(pinned).headerOverridden(true).build());
        Path<Object> hospitalIdPath = hospitalPathViaAssociation();
        Predicate onlyPinned = mock(Predicate.class);
        when(hospitalIdPath.in(Set.of(pinned))).thenReturn(onlyPinned);
        assertThat(specification.toPredicate(root, query, criteriaBuilder)).isSameAs(onlyPinned);

        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        Predicate everything = mock(Predicate.class);
        when(criteriaBuilder.conjunction()).thenReturn(everything);
        assertThat(specification.toPredicate(root, query, criteriaBuilder)).isSameAs(everything);
    }

    @SuppressWarnings("unchecked")
    private Path<Object> hospitalPathViaAssociation() {
        doThrow(new IllegalArgumentException("hospitalId column missing")).when(root).get(ATTRIBUTE_HOSPITAL_ID);
        Path<Object> hospitalIdPath = mock(Path.class);
        when(root.get(ATTRIBUTE_HOSPITAL).get(ATTRIBUTE_ID)).thenReturn(hospitalIdPath);
        return hospitalIdPath;
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
