package com.example.hms.specification;
import com.example.hms.model.Permission;
import com.example.hms.payload.dto.PermissionFilterDTO;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

public class PermissionSpecification {

    public static Specification<Permission> build(PermissionFilterDTO filter) {
        return (root, query, cb) -> {
            // Apply eager fetches only for the main query to avoid LazyInitializationException during DTO mapping.
            if (Permission.class.equals(query.getResultType())) {
                var assignmentFetch = root.fetch("assignment", JoinType.LEFT);
                assignmentFetch.fetch("role", JoinType.LEFT);
                assignmentFetch.fetch("hospital", JoinType.LEFT);
                query.distinct(true);
            }

            Predicate predicate = cb.conjunction();

            if (filter.getAssignmentId() != null) {
                predicate = cb.and(predicate, cb.equal(root.get("assignment").get("id"), filter.getAssignmentId()));
            }

            if (filter.getName() != null && !filter.getName().isBlank()) {
                predicate = cb.and(predicate, cb.like(cb.lower(root.get("name")), "%" + filter.getName().toLowerCase() + "%"));
            }

            return predicate;
        };
    }
}
