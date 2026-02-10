package com.example.hms.security.tenant.specification;

import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data {@link Specification} that enforces tenant scoping based on the active {@link HospitalContext}.
 * This specification only applies to entities that implement {@link TenantScoped} and expose simple UUID properties
 * named {@code organizationId}, {@code hospitalId}, and {@code departmentId} respectively.
 */
public class TenantScopeSpecification<T> implements Specification<T> {

    private final Class<T> domainType;

    private static final String ATTRIBUTE_ORGANIZATION = "organization";
    private static final String ATTRIBUTE_HOSPITAL = "hospital";
    private static final String ATTRIBUTE_DEPARTMENT = "department";
    private static final String ATTRIBUTE_ID = "id";
    private static final String ATTRIBUTE_ORGANIZATION_ID = "organizationId";
    private static final String ATTRIBUTE_HOSPITAL_ID = "hospitalId";
    private static final String ATTRIBUTE_DEPARTMENT_ID = "departmentId";

    private static final List<List<String>> ORGANIZATION_PATH_CANDIDATES = List.of(
        List.of(ATTRIBUTE_ORGANIZATION_ID),
        List.of(ATTRIBUTE_ORGANIZATION, ATTRIBUTE_ID),
        List.of(ATTRIBUTE_HOSPITAL, ATTRIBUTE_ORGANIZATION, ATTRIBUTE_ID),
        List.of(ATTRIBUTE_DEPARTMENT, ATTRIBUTE_ORGANIZATION, ATTRIBUTE_ID)
    );

    private static final List<List<String>> HOSPITAL_PATH_CANDIDATES = List.of(
        List.of(ATTRIBUTE_HOSPITAL_ID),
        List.of(ATTRIBUTE_HOSPITAL, ATTRIBUTE_ID),
        List.of(ATTRIBUTE_DEPARTMENT, ATTRIBUTE_HOSPITAL, ATTRIBUTE_ID)
    );

    private static final List<List<String>> DEPARTMENT_PATH_CANDIDATES = List.of(
        List.of(ATTRIBUTE_DEPARTMENT_ID),
        List.of(ATTRIBUTE_DEPARTMENT, ATTRIBUTE_ID)
    );

    public TenantScopeSpecification(Class<T> domainType) {
        this.domainType = domainType;
    }

    @Override
    public Predicate toPredicate(@NonNull Root<T> root,
                                 @Nullable CriteriaQuery<?> query,
                                 @NonNull CriteriaBuilder criteriaBuilder) {
        if (!TenantScoped.class.isAssignableFrom(domainType)) {
            return criteriaBuilder.conjunction();
        }

        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (context.isSuperAdmin()) {
            return criteriaBuilder.conjunction();
        }

    Path<UUID> organizationPath = resolveTenantPath(root, ORGANIZATION_PATH_CANDIDATES);
    Path<UUID> hospitalPath = resolveTenantPath(root, HOSPITAL_PATH_CANDIDATES);
    Path<UUID> departmentPath = resolveTenantPath(root, DEPARTMENT_PATH_CANDIDATES);

        List<Predicate> disjunctions = new ArrayList<>();

        Set<UUID> organizationIds = context.getPermittedOrganizationIds();
        if (organizationPath != null && !organizationIds.isEmpty()) {
            disjunctions.add(organizationPath.in(organizationIds));
        } else if (organizationPath != null && context.getActiveOrganizationId() != null) {
            disjunctions.add(criteriaBuilder.equal(organizationPath, context.getActiveOrganizationId()));
        }

        Set<UUID> hospitalIds = context.getPermittedHospitalIds();
        if (hospitalPath != null && !hospitalIds.isEmpty()) {
            disjunctions.add(hospitalPath.in(hospitalIds));
        } else if (hospitalPath != null && context.getActiveHospitalId() != null) {
            disjunctions.add(criteriaBuilder.equal(hospitalPath, context.getActiveHospitalId()));
        }

        Set<UUID> departmentIds = context.getPermittedDepartmentIds();
        if (departmentPath != null && !departmentIds.isEmpty()) {
            disjunctions.add(departmentPath.in(departmentIds));
        }

        if (disjunctions.isEmpty()) {
            // No tenant scope available – deny access by returning a contradiction predicate
            return criteriaBuilder.disjunction();
        }

        return criteriaBuilder.or(disjunctions.toArray(Predicate[]::new));
    }

    private Path<UUID> resolveTenantPath(Root<T> root, List<List<String>> candidates) {
        for (List<String> candidate : candidates) {
            Path<UUID> resolved = safePath(root, candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    private Path<UUID> safePath(Root<T> root, String attribute) {
        return safePath(root, List.of(attribute));
    }

    private Path<UUID> safePath(Root<T> root, List<String> attributes) {
        try {
            Path<?> current = root;
            for (String attribute : attributes) {
                current = current.get(attribute);
            }
            @SuppressWarnings("unchecked")
            Path<UUID> uuidPath = (Path<UUID>) current;
            return uuidPath;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
