package com.example.hms.security.tenant.specification;

import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.TenantScoped;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Spring Data {@link Specification} that enforces tenant scoping based on the
 * active {@link HospitalContext}, for entities that implement
 * {@link TenantScoped} (docs/security/tenant-resolution.md §3.3):
 * <ul>
 *   <li>a super-admin in global view: no filter;</li>
 *   <li>a super-admin who named a hospital: that hospital only (it used to
 *       be no filter at all, pinned or not — D1);</li>
 *   <li>everyone else: their live permitted <b>hospitals</b>. The
 *       organisation alternative is gone (design Q6, option A): a doctor
 *       assigned at A no longer passes the scoped finders for rows of a
 *       sibling hospital A2 in the same organisation (D13).</li>
 * </ul>
 * Reading the scope here seals it for the rest of the request.
 */
public class TenantScopeSpecification<T> implements Specification<T> {

    private final Class<T> domainType;

    private static final String ATTRIBUTE_HOSPITAL = "hospital";
    private static final String ATTRIBUTE_DEPARTMENT = "department";
    private static final String ATTRIBUTE_ID = "id";
    private static final String ATTRIBUTE_HOSPITAL_ID = "hospitalId";

    private static final List<List<String>> HOSPITAL_PATH_CANDIDATES = List.of(
        List.of(ATTRIBUTE_HOSPITAL_ID),
        List.of(ATTRIBUTE_HOSPITAL, ATTRIBUTE_ID),
        List.of(ATTRIBUTE_DEPARTMENT, ATTRIBUTE_HOSPITAL, ATTRIBUTE_ID)
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
        HospitalContextHolder.seal();
        if (context.isGlobalView()) {
            return criteriaBuilder.conjunction();
        }

        Set<UUID> hospitalIds = ActingScopeResolver.readableHospitalIds(context);
        if (hospitalIds.isEmpty()) {
            // No tenant scope available – deny access by returning a contradiction predicate
            return criteriaBuilder.disjunction();
        }

        if (query != null && Patient.class.isAssignableFrom(domainType)) {
            return patientScope(root, query, criteriaBuilder, hospitalIds);
        }

        Path<UUID> hospitalPath = resolveTenantPath(root);
        if (hospitalPath == null) {
            return criteriaBuilder.disjunction();
        }
        return hospitalPath.in(hospitalIds);
    }

    /**
     * E9 #57 — a patient is in scope where they are REGISTERED, not where they
     * were first seen.
     *
     * <p>{@code Patient.hospitalId} holds the first hospital a patient was
     * registered at and never changes, so keying the filter on it made a
     * patient registered at A and later linked at B vanish from every scoped
     * finder at B — {@code findById}, {@code existsById}, every Specification
     * query — while the chart header, which checks the registration table,
     * still showed them. Eighty-seven call sites use those finders. The rule
     * now lives here, once: the patient is visible when a
     * {@code PatientHospitalRegistration} exists at one of the caller's
     * readable hospitals. (A registration anywhere in one of their
     * organisations no longer counts — Q6, option A.)
     */
    private Predicate patientScope(Root<T> root, CriteriaQuery<?> query, CriteriaBuilder criteriaBuilder,
                                   Set<UUID> hospitalIds) {
        Subquery<UUID> registeredAt = query.subquery(UUID.class);
        Root<PatientHospitalRegistration> registration = registeredAt.from(PatientHospitalRegistration.class);
        registeredAt.select(registration.get(ATTRIBUTE_ID)).where(
            criteriaBuilder.equal(registration.get("patient").get(ATTRIBUTE_ID), root.get(ATTRIBUTE_ID)),
            registration.get(ATTRIBUTE_HOSPITAL).get(ATTRIBUTE_ID).in(hospitalIds));
        return criteriaBuilder.exists(registeredAt);
    }

    private Path<UUID> resolveTenantPath(Root<T> root) {
        for (List<String> candidate : HOSPITAL_PATH_CANDIDATES) {
            Path<UUID> resolved = safePath(root, candidate);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
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
