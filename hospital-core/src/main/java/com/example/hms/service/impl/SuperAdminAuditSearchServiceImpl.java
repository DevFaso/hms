package com.example.hms.service.impl;

import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.superadmin.AuditSearchFilter;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.service.SuperAdminAuditSearchService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SuperAdminAuditSearchServiceImpl implements SuperAdminAuditSearchService {

    private static final String FIELD_EVENT_TIMESTAMP = "eventTimestamp";

    private final AuditEventLogRepository auditEventLogRepository;
    private final AuditEventLogMapper mapper;

    @Override
    public AuditSearchPageDTO search(AuditSearchFilter filter, Pageable pageable) {
        AuditSearchFilter effective = filter == null ? AuditSearchFilter.empty() : filter;
        Specification<AuditEventLog> spec = buildSpec(effective);

        Pageable sorted = ensureSorted(pageable);
        Page<AuditEventLog> page = auditEventLogRepository.findAll(spec, sorted);

        // PR #228 review — use the lite mapper variant: it skips the
        // per-row PatientRepository.findById lookup that the standard toDto
        // performs for PATIENT events, which would be a textbook N+1 across
        // a paged search result.
        List<AuditEventLogResponseDTO> content = page.getContent().stream()
            .map(mapper::toDtoLite)
            .toList();

        return AuditSearchPageDTO.builder()
            .content(content)
            .pageNumber(page.getNumber())
            .pageSize(page.getSize())
            .totalElements(page.getTotalElements())
            .totalPages(page.getTotalPages())
            .build();
    }

    private Pageable ensureSorted(Pageable pageable) {
        if (pageable.getSort().isUnsorted()) {
            return PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, FIELD_EVENT_TIMESTAMP));
        }
        return pageable;
    }

    /**
     * PR #228 SonarCloud review — the lambda body now delegates to small
     * per-filter helpers so the cognitive complexity of the spec builder
     * itself stays under the 15-branch ceiling.
     */
    private Specification<AuditEventLog> buildSpec(AuditSearchFilter f) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            addUserPredicates(predicates, root, cb, f);
            addEventPredicates(predicates, root, cb, f);
            addHospitalAndOrgPredicates(predicates, root, cb, f);
            addTargetPredicates(predicates, root, cb, f);
            addDatePredicates(predicates, root, cb, f);
            // distinct() was applied unconditionally before — every join here
            // is many-to-one so AuditEventLog rows can't duplicate. Removed
            // (PR #228 review).
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private void addUserPredicates(
        List<Predicate> predicates, Root<AuditEventLog> root, CriteriaBuilder cb, AuditSearchFilter f
    ) {
        if (f.userId() != null) {
            predicates.add(cb.equal(root.get("user").get("id"), f.userId()));
        }
        if (f.userNameLike() != null && !f.userNameLike().isBlank()) {
            predicates.add(cb.like(
                cb.lower(root.get("userName")),
                "%" + f.userNameLike().toLowerCase() + "%"));
        }
        if (f.impersonatorUserId() != null) {
            predicates.add(cb.equal(root.get("impersonatorUserId"), f.impersonatorUserId()));
        }
    }

    private void addEventPredicates(
        List<Predicate> predicates, Root<AuditEventLog> root, CriteriaBuilder cb, AuditSearchFilter f
    ) {
        if (f.eventTypes() != null && !f.eventTypes().isEmpty()) {
            predicates.add(root.get("eventType").in(f.eventTypes()));
        }
        if (f.status() != null) {
            predicates.add(cb.equal(root.get("status"), f.status()));
        }
    }

    /**
     * Single assignment+hospital join chain shared across the hospital and
     * organization filters (PR #228 review — was creating two redundant
     * join paths).
     */
    private void addHospitalAndOrgPredicates(
        List<Predicate> predicates, Root<AuditEventLog> root, CriteriaBuilder cb, AuditSearchFilter f
    ) {
        if (!f.needsHospitalOrOrgJoin()) {
            return;
        }
        Join<?, ?> hospitalJoin = root.join("assignment", JoinType.LEFT)
            .join("hospital", JoinType.LEFT);
        if (f.hospitalId() != null) {
            predicates.add(cb.equal(hospitalJoin.get("id"), f.hospitalId()));
        }
        if (f.organizationId() != null) {
            predicates.add(cb.equal(
                hospitalJoin.join("organization", JoinType.LEFT).get("id"),
                f.organizationId()));
        }
    }

    private void addTargetPredicates(
        List<Predicate> predicates, Root<AuditEventLog> root, CriteriaBuilder cb, AuditSearchFilter f
    ) {
        if (f.entityType() != null && !f.entityType().isBlank()) {
            predicates.add(cb.equal(
                cb.lower(root.get("entityType")),
                f.entityType().toLowerCase()));
        }
        if (f.resourceId() != null && !f.resourceId().isBlank()) {
            predicates.add(cb.equal(root.get("resourceId"), f.resourceId()));
        }
    }

    private void addDatePredicates(
        List<Predicate> predicates, Root<AuditEventLog> root, CriteriaBuilder cb, AuditSearchFilter f
    ) {
        if (f.fromDate() != null) {
            predicates.add(cb.greaterThanOrEqualTo(root.get(FIELD_EVENT_TIMESTAMP), f.fromDate()));
        }
        if (f.toDate() != null) {
            predicates.add(cb.lessThanOrEqualTo(root.get(FIELD_EVENT_TIMESTAMP), f.toDate()));
        }
    }
}
