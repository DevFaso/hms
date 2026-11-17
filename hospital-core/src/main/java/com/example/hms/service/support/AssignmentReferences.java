package com.example.hms.service.support;

import com.example.hms.model.AuditEventLog;
import com.example.hms.model.UserRoleHospitalAssignment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Which kinds of record still point at a role assignment.
 *
 * <p>Twenty-odd entities carry an {@code assignment_id} (departments, their
 * translations, prescriptions, lab orders, permissions...), and on the
 * long-lived databases most of them are foreign keys. The hard delete checked
 * only staff and encounters, so deleting an assignment a department still
 * named reached the database and came back as a 500 (QA round 14). The list
 * is read from the JPA metamodel, not written by hand, so an entity added
 * later with an assignment reference is checked without anyone remembering
 * this class.
 *
 * <p>The audit log is left out on purpose: it has no foreign key on the
 * assignment and keeps the id of a deleted one ({@code @NotFound(IGNORE)},
 * #564), and almost every assignment has audit rows.
 */
@Component
@RequiredArgsConstructor
public class AssignmentReferences {

    private final EntityManager entityManager;

    /**
     * The entity names (simple class names, sorted) with at least one row
     * pointing at the assignment. Empty when nothing does.
     */
    public List<String> referencingEntities(UUID assignmentId) {
        Set<String> found = new TreeSet<>();
        for (EntityType<?> entity : referencingTypes()) {
            if (!found.contains(entity.getJavaType().getSimpleName()) && references(entity, assignmentId)) {
                found.add(entity.getJavaType().getSimpleName());
            }
        }
        return List.copyOf(found);
    }

    /** Every mapped entity, other than the assignment itself and the audit log, with an assignment attribute. */
    public List<EntityType<?>> referencingTypes() {
        return entityManager.getMetamodel().getEntities().stream()
            .filter(entity -> entity.getJavaType() != UserRoleHospitalAssignment.class)
            .filter(entity -> entity.getJavaType() != AuditEventLog.class)
            .filter(entity -> !assignmentAttributes(entity).isEmpty())
            .sorted(Comparator.comparing(entity -> entity.getJavaType().getName()))
            .toList();
    }

    private boolean references(EntityType<?> entity, UUID assignmentId) {
        for (SingularAttribute<?, ?> attribute : assignmentAttributes(entity)) {
            CriteriaBuilder cb = entityManager.getCriteriaBuilder();
            CriteriaQuery<Object> query = cb.createQuery(Object.class);
            Root<?> root = query.from(entity);
            query.select(root.get(attribute.getName()).get("id"))
                .where(cb.equal(root.get(attribute.getName()).get("id"), assignmentId));
            if (!entityManager.createQuery(query).setMaxResults(1).getResultList().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static List<SingularAttribute<?, ?>> assignmentAttributes(EntityType<?> entity) {
        List<SingularAttribute<?, ?>> attributes = new ArrayList<>();
        for (SingularAttribute<?, ?> attribute : entity.getSingularAttributes()) {
            if (attribute.getJavaType() == UserRoleHospitalAssignment.class) {
                attributes.add(attribute);
            }
        }
        attributes.sort(Comparator.comparing(SingularAttribute::getName));
        return attributes;
    }
}
