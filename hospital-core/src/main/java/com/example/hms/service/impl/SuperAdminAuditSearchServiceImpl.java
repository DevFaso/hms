package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.service.SuperAdminAuditSearchService;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SuperAdminAuditSearchServiceImpl implements SuperAdminAuditSearchService {

    private final AuditEventLogRepository auditEventLogRepository;
    private final AuditEventLogMapper mapper;

    @Override
    public AuditSearchPageDTO search(
        UUID userId,
        String userNameLike,
        List<AuditEventType> eventTypes,
        AuditStatus status,
        UUID hospitalId,
        UUID organizationId,
        UUID impersonatorUserId,
        String entityType,
        String resourceId,
        LocalDateTime fromDate,
        LocalDateTime toDate,
        Pageable pageable
    ) {
        Specification<AuditEventLog> spec = buildSpec(
            userId, userNameLike, eventTypes, status, hospitalId, organizationId,
            impersonatorUserId, entityType, resourceId, fromDate, toDate);

        Pageable effective = ensureSorted(pageable);
        Page<AuditEventLog> page = auditEventLogRepository.findAll(spec, effective);

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
                Sort.by(Sort.Direction.DESC, "eventTimestamp"));
        }
        return pageable;
    }

    private Specification<AuditEventLog> buildSpec(
        UUID userId,
        String userNameLike,
        List<AuditEventType> eventTypes,
        AuditStatus status,
        UUID hospitalId,
        UUID organizationId,
        UUID impersonatorUserId,
        String entityType,
        String resourceId,
        LocalDateTime fromDate,
        LocalDateTime toDate
    ) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (userId != null) {
                predicates.add(cb.equal(root.get("user").get("id"), userId));
            }
            if (userNameLike != null && !userNameLike.isBlank()) {
                predicates.add(cb.like(
                    cb.lower(root.get("userName")),
                    "%" + userNameLike.toLowerCase() + "%"));
            }
            if (eventTypes != null && !eventTypes.isEmpty()) {
                predicates.add(root.get("eventType").in(eventTypes));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            // PR #228 review — share a single assignment+hospital join
            // chain across the hospital and organization filters instead of
            // creating two redundant join paths in the generated SQL.
            jakarta.persistence.criteria.Join<?, ?> assignmentJoin = null;
            jakarta.persistence.criteria.Join<?, ?> hospitalJoin = null;
            if (hospitalId != null || organizationId != null) {
                assignmentJoin = root.join("assignment", JoinType.LEFT);
                hospitalJoin = assignmentJoin.join("hospital", JoinType.LEFT);
            }
            if (hospitalId != null) {
                predicates.add(cb.equal(hospitalJoin.get("id"), hospitalId));
            }
            if (organizationId != null) {
                predicates.add(cb.equal(
                    hospitalJoin.join("organization", JoinType.LEFT).get("id"),
                    organizationId));
            }
            if (impersonatorUserId != null) {
                predicates.add(cb.equal(root.get("impersonatorUserId"), impersonatorUserId));
            }
            if (entityType != null && !entityType.isBlank()) {
                predicates.add(cb.equal(
                    cb.lower(root.get("entityType")),
                    entityType.toLowerCase()));
            }
            if (resourceId != null && !resourceId.isBlank()) {
                predicates.add(cb.equal(root.get("resourceId"), resourceId));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("eventTimestamp"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("eventTimestamp"), toDate));
            }

            // PR #228 review — distinct(true) was applied unconditionally
            // even when no multi-valued joins are used. All joins above are
            // many-to-one (assignment → hospital → organization), so an
            // AuditEventLog row cannot duplicate. Removed to keep count
            // queries fast and let the planner pick the obvious path.
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
