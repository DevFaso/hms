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

        List<AuditEventLogResponseDTO> content = page.getContent().stream()
            .map(mapper::toDto)
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
            if (hospitalId != null) {
                predicates.add(cb.equal(
                    root.join("assignment", JoinType.LEFT)
                        .join("hospital", JoinType.LEFT)
                        .get("id"),
                    hospitalId));
            }
            if (organizationId != null) {
                predicates.add(cb.equal(
                    root.join("assignment", JoinType.LEFT)
                        .join("hospital", JoinType.LEFT)
                        .join("organization", JoinType.LEFT)
                        .get("id"),
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

            if (query != null) {
                query.distinct(true);
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
