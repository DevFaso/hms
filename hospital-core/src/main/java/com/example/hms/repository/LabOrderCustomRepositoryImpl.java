package com.example.hms.repository;

import com.example.hms.model.LabOrder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
public class LabOrderCustomRepositoryImpl implements LabOrderCustomRepository {

    private static final String ORDER_DATETIME = "orderDatetime";

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public Page<LabOrder> search(UUID patientId, LocalDateTime from, LocalDateTime to, Pageable pageable) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<LabOrder> query = cb.createQuery(LabOrder.class);
        Root<LabOrder> root = query.from(LabOrder.class);

        Predicate[] predicates = buildPredicates(cb, root, patientId, from, to);

        if (predicates.length > 0) {
            query.where(cb.and(predicates));
        }
        query.orderBy(cb.desc(root.get(ORDER_DATETIME)));

        // Fetch paginated results
        TypedQuery<LabOrder> typedQuery = entityManager.createQuery(query);
        typedQuery.setFirstResult((int) pageable.getOffset());
        typedQuery.setMaxResults(pageable.getPageSize());

        List<LabOrder> resultList = typedQuery.getResultList();

        // Fetch total count
        CriteriaQuery<Long> countQuery = cb.createQuery(Long.class);
        Root<LabOrder> countRoot = countQuery.from(LabOrder.class);
        countQuery.select(cb.count(countRoot));
        Predicate[] countPredicates = buildPredicates(cb, countRoot, patientId, from, to);
        if (countPredicates.length > 0) {
            countQuery.where(cb.and(countPredicates));
        }
        Long count = entityManager.createQuery(countQuery).getSingleResult();

        return new PageImpl<>(resultList, pageable, count);
    }

    private Predicate[] buildPredicates(CriteriaBuilder cb, Root<LabOrder> root, UUID patientId, LocalDateTime from, LocalDateTime to) {
        List<Predicate> predicates = new ArrayList<>();

        if (patientId != null) {
            predicates.add(cb.equal(root.get("patient").get("id"), patientId));
        }
        if (from != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(ORDER_DATETIME), from));
        }
        if (to != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get(ORDER_DATETIME), to));
        }

        return predicates.toArray(new Predicate[0]);
    }
}
