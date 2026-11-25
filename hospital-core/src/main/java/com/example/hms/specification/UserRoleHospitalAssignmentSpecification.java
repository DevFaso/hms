package com.example.hms.specification;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public final class UserRoleHospitalAssignmentSpecification {

    /** The assignment's hospital association. */
    private static final String HOSPITAL = "hospital";

    private UserRoleHospitalAssignmentSpecification() {
    }

    @Nullable
    public static Specification<UserRoleHospitalAssignment> belongsToHospital(@Nullable UUID hospitalId) {
        if (hospitalId == null) {
            return null;
        }
        return (root, query, cb) -> {
            query.distinct(true);
            return cb.equal(root.join(HOSPITAL, JoinType.LEFT).get("id"), hospitalId);
        };
    }

    /**
     * The rows at one of these hospitals (an organisation admin's list: the
     * hospitals of its own organisation). An empty set matches nothing: it is
     * never "no filter".
     */
    public static Specification<UserRoleHospitalAssignment> belongsToAnyHospital(
            java.util.Collection<UUID> hospitalIds) {
        return (root, query, cb) -> {
            if (hospitalIds == null || hospitalIds.isEmpty()) {
                return cb.disjunction();
            }
            query.distinct(true);
            return root.join(HOSPITAL, JoinType.LEFT).get("id").in(hospitalIds);
        };
    }

    /**
     * QA D3062 / review of #1264: no row of a platform super-admin's account (one holding SUPER_ADMIN by an
     * assignment or a global role, active or not). The rule the user directory applies
     * (UserRepository.DIRECTORY_VISIBLE): a hospital-scoped listing never shows the platform's own accounts.
     */
    public static Specification<UserRoleHospitalAssignment> notAPlatformAccount() {
        return (root, query, cb) -> {
            jakarta.persistence.criteria.Subquery<UUID> byAssignment = query.subquery(UUID.class);
            jakarta.persistence.criteria.Root<UserRoleHospitalAssignment> other =
                byAssignment.from(UserRoleHospitalAssignment.class);
            byAssignment.select(other.get("id")).where(
                cb.equal(other.get("user"), root.get("user")),
                cb.upper(other.join("role").get("code")).in(PLATFORM_ROLE_CODES));
            jakarta.persistence.criteria.Subquery<UUID> byGlobalRole = query.subquery(UUID.class);
            jakarta.persistence.criteria.Root<com.example.hms.model.UserRole> global =
                byGlobalRole.from(com.example.hms.model.UserRole.class);
            byGlobalRole.select(global.get("user").get("id")).where(
                cb.equal(global.get("user"), root.get("user")),
                cb.upper(global.join("role").get("code")).in(PLATFORM_ROLE_CODES));
            return cb.and(cb.not(cb.exists(byAssignment)), cb.not(cb.exists(byGlobalRole)));
        };
    }

    private static final List<String> PLATFORM_ROLE_CODES = List.of("ROLE_SUPER_ADMIN", "SUPER_ADMIN");

    /**
     * Staff rows only: never a PATIENT assignment. An organisation admin's
     * list (platform admin rules, rule 1b): a patient row is a patient's name
     * and contact, and that role reads no patient list.
     */
    public static Specification<UserRoleHospitalAssignment> staffRowsOnly() {
        return (root, query, cb) ->
            cb.not(cb.upper(root.join("role", JoinType.LEFT).get("code")).in("PATIENT", "ROLE_PATIENT"));
    }

    @Nullable
    public static Specification<UserRoleHospitalAssignment> hasActive(@Nullable Boolean active) {
        if (active == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("active"), active);
    }

    @Nullable
    public static Specification<UserRoleHospitalAssignment> matchesSearch(@Nullable String rawTerm) {
        return matchesSearch(rawTerm, null, Set.of());
    }

    /**
     * QA D4020: {@link #matchesSearch(String)} for a caller who may not see
     * every holder. The holder's username, e-mail and names are matched only
     * on rows whose holder the caller may see (the rule of
     * {@code UserAccountAccess.HolderReach.knowsHolderOf}): an accepted row, a
     * patient row, or a holder with another accepted staff row in reach, a
     * patient row at a hospital the caller administers, or no row out of
     * reach. A pending invitation to someone the caller could not have named
     * is found by its role, code or hospital only, never by who holds it.
     *
     * @param reachHospitals   the hospitals where the caller registers staff; {@code null} for every holder
     * @param patientHospitals the hospitals whose own patients the caller may name
     */
    @Nullable
    public static Specification<UserRoleHospitalAssignment> matchesSearch(@Nullable String rawTerm,
            @Nullable Set<UUID> reachHospitals, Set<UUID> patientHospitals) {
        if (!StringUtils.hasText(rawTerm)) {
            return null;
        }

        final String pattern = "%" + rawTerm.trim().toLowerCase(Locale.ROOT) + "%";

        return (root, query, cb) -> {
            query.distinct(true);

            Join<UserRoleHospitalAssignment, User> userJoin = root.join("user", JoinType.LEFT);
            Join<UserRoleHospitalAssignment, Hospital> hospitalJoin = root.join(HOSPITAL, JoinType.LEFT);
            Join<UserRoleHospitalAssignment, Role> roleJoin = root.join("role", JoinType.LEFT);

            // The assignment's own holder, role, code and hospital only. The
            // registrar (registeredBy) is not searched: typing a username
            // also listed every row that user had registered for others,
            // which read as that user holding them (QA D193).
            Predicate holder = cb.or(
                cb.like(cb.lower(userJoin.get("username")), pattern),
                cb.like(cb.lower(userJoin.get("email")), pattern),
                cb.like(cb.lower(userJoin.get("firstName")), pattern),
                cb.like(cb.lower(userJoin.get("lastName")), pattern));
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(reachHospitals == null
                ? holder
                : cb.and(holder, holderVisible(root, query, cb, roleJoin, reachHospitals, patientHospitals)));
            predicates.add(cb.like(cb.lower(roleJoin.get("name")), pattern));
            predicates.add(cb.like(cb.lower(roleJoin.get("code")), pattern));
            predicates.add(cb.like(cb.lower(root.get("assignmentCode")), pattern));
            predicates.add(cb.like(cb.lower(hospitalJoin.get("name")), pattern));
            predicates.add(cb.like(cb.lower(hospitalJoin.get("code")), pattern));

            return cb.or(predicates.toArray(new Predicate[0]));
        };
    }

    private static final String USER = "user";
    private static final String ROLE = "role";
    private static final String CODE = "code";
    private static final String VERIFIED_AT = "confirmationVerifiedAt";
    private static final List<String> PATIENT_CODES = List.of("PATIENT", "ROLE_PATIENT");

    /** The SQL form of {@code HolderReach.knowsHolderOf} for the row at {@code root}. */
    private static Predicate holderVisible(Root<UserRoleHospitalAssignment> root, CriteriaQuery<?> query,
            CriteriaBuilder cb, Join<UserRoleHospitalAssignment, Role> roleJoin,
            Set<UUID> reach, Set<UUID> patientHospitals) {
        // Another accepted (or live) staff row of the same holder, in reach.
        Subquery<UUID> accepted = query.subquery(UUID.class);
        Root<UserRoleHospitalAssignment> a = accepted.from(UserRoleHospitalAssignment.class);
        accepted.select(a.get("id")).where(
            cb.equal(a.get(USER), root.get(USER)),
            cb.notEqual(a.get("id"), root.get("id")),
            in(cb, a.join(HOSPITAL, JoinType.LEFT).get("id"), reach),
            cb.not(cb.upper(a.join(ROLE, JoinType.LEFT).get(CODE)).in(PATIENT_CODES)),
            cb.or(cb.isNotNull(a.get(VERIFIED_AT)), cb.isTrue(a.get("active"))));
        // A patient row of the same holder at a hospital the caller administers.
        Subquery<UUID> ownPatient = query.subquery(UUID.class);
        Root<UserRoleHospitalAssignment> p = ownPatient.from(UserRoleHospitalAssignment.class);
        ownPatient.select(p.get("id")).where(
            cb.equal(p.get(USER), root.get(USER)),
            cb.notEqual(p.get("id"), root.get("id")),
            in(cb, p.join(HOSPITAL, JoinType.LEFT).get("id"), patientHospitals),
            cb.upper(p.join(ROLE, JoinType.LEFT).get(CODE)).in(PATIENT_CODES));
        // Any other row of the same holder out of reach (or global).
        Subquery<UUID> outside = query.subquery(UUID.class);
        Root<UserRoleHospitalAssignment> o = outside.from(UserRoleHospitalAssignment.class);
        Path<UUID> outsideHospital = o.join(HOSPITAL, JoinType.LEFT).get("id");
        outside.select(o.get("id")).where(
            cb.equal(o.get(USER), root.get(USER)),
            cb.notEqual(o.get("id"), root.get("id")),
            cb.or(cb.isNull(outsideHospital), cb.not(in(cb, outsideHospital, reach))));
        return cb.or(
            cb.isNotNull(root.get(VERIFIED_AT)),
            cb.upper(roleJoin.get(CODE)).in(PATIENT_CODES),
            cb.exists(accepted),
            cb.exists(ownPatient),
            cb.not(cb.exists(outside)));
    }

    /** {@code path IN ids}; an empty set matches nothing (never "no filter"). */
    private static Predicate in(CriteriaBuilder cb, Path<UUID> path, Set<UUID> ids) {
        return ids == null || ids.isEmpty() ? cb.disjunction() : path.in(ids);
    }

    @Nullable
    public static Specification<UserRoleHospitalAssignment> hasAssignmentCode(@Nullable String code) {
        if (!StringUtils.hasText(code)) {
            return null;
        }
        final String normalized = code.trim().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.get("assignmentCode")), normalized);
    }
}
