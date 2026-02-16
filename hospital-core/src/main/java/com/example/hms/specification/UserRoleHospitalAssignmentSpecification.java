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

    private UserRoleHospitalAssignmentSpecification() {
    }

    @Nullable
    public static Specification<UserRoleHospitalAssignment> belongsToHospital(@Nullable UUID hospitalId) {
        if (hospitalId == null) {
            return null;
        }
        return (root, query, cb) -> {
            query.distinct(true);
            return cb.equal(root.join("hospital", JoinType.LEFT).get("id"), hospitalId);
        };
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
            Join<UserRoleHospitalAssignment, Hospital> hospitalJoin = root.join("hospital", JoinType.LEFT);
            Join<UserRoleHospitalAssignment, Role> roleJoin = root.join("role", JoinType.LEFT);
            Join<UserRoleHospitalAssignment, User> registrarJoin = root.join("registeredBy", JoinType.LEFT);

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
            predicates.add(cb.like(cb.lower(registrarJoin.get("username")), pattern));
            predicates.add(cb.like(cb.lower(registrarJoin.get("email")), pattern));
            predicates.add(cb.like(cb.lower(registrarJoin.get("firstName")), pattern));
            predicates.add(cb.like(cb.lower(registrarJoin.get("lastName")), pattern));

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
