package com.example.hms.specification;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.lang.Nullable;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(cb.like(cb.lower(userJoin.get("username")), pattern));
            predicates.add(cb.like(cb.lower(userJoin.get("email")), pattern));
            predicates.add(cb.like(cb.lower(userJoin.get("firstName")), pattern));
            predicates.add(cb.like(cb.lower(userJoin.get("lastName")), pattern));
            predicates.add(cb.like(cb.lower(roleJoin.get("name")), pattern));
            predicates.add(cb.like(cb.lower(roleJoin.get("code")), pattern));
            predicates.add(cb.like(cb.lower(root.get("assignmentCode")), pattern));
            predicates.add(cb.like(cb.lower(hospitalJoin.get("name")), pattern));
            predicates.add(cb.like(cb.lower(hospitalJoin.get("code")), pattern));

            return cb.or(predicates.toArray(new Predicate[0]));
        };
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
