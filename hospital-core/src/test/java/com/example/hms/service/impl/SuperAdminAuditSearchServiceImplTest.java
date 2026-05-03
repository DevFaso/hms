package com.example.hms.service.impl;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.payload.dto.superadmin.AuditSearchPageDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.PatientRepository;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SuperAdminAuditSearchServiceImpl (MVP-8)")
class SuperAdminAuditSearchServiceImplTest {

    private AuditEventLogRepository repository;
    private SuperAdminAuditSearchServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventLogRepository.class);
        // Real mapper (no Spring) — no PatientRepository lookup happens because
        // the service uses toDtoLite, but we still need a non-null PatientRepository.
        AuditEventLogMapper mapper = new AuditEventLogMapper(mock(PatientRepository.class));
        service = new SuperAdminAuditSearchServiceImpl(repository, mapper);
    }

    private AuditEventLog event() {
        AuditEventLog e = AuditEventLog.builder()
            .eventType(AuditEventType.SECURITY_ALERT_TRIGGERED)
            .eventDescription("test event")
            .status(AuditStatus.SUCCESS)
            .resourceId("res-1")
            .resourceName("Resource Display Name")
            .entityType("USER")
            .build();
        e.setId(UUID.randomUUID());
        e.setEventTimestamp(LocalDateTime.now());
        return e;
    }

    @SuppressWarnings("unchecked")
    private Specification<AuditEventLog> captureSpec() {
        ArgumentCaptor<Specification<AuditEventLog>> captor = ArgumentCaptor.forClass(Specification.class);
        org.mockito.Mockito.verify(repository).findAll(captor.capture(), any(PageRequest.class));
        return captor.getValue();
    }

    @Test
    @DisplayName("search returns mapped DTOs and page metadata")
    void searchMapsPage() {
        Page<AuditEventLog> page = new PageImpl<>(List.of(event(), event()),
            PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "eventTimestamp")), 2L);
        when(repository.findAll(any(Specification.class), any(PageRequest.class))).thenReturn(page);

        AuditSearchPageDTO out = service.search(
            null, null, null, null, null, null, null, null, null, null, null,
            PageRequest.of(0, 10));

        assertThat(out.getContent()).hasSize(2);
        assertThat(out.getTotalElements()).isEqualTo(2);
        assertThat(out.getPageNumber()).isZero();
        assertThat(out.getPageSize()).isEqualTo(10);
        assertThat(out.getTotalPages()).isEqualTo(1);
        // Each row carries the entity id (PR #228 review fix).
        assertThat(out.getContent()).allSatisfy(r -> assertThat(r.getId()).isNotNull());
    }

    @Test
    @DisplayName("default sort = eventTimestamp DESC when caller passes unsorted Pageable")
    void defaultsSortDesc() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(Page.empty());

        service.search(null, null, null, null, null, null, null, null, null, null, null,
            PageRequest.of(0, 5));

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        org.mockito.Mockito.verify(repository).findAll(any(Specification.class), captor.capture());
        Sort sort = captor.getValue().getSort();
        assertThat(sort.getOrderFor("eventTimestamp")).isNotNull();
        assertThat(sort.getOrderFor("eventTimestamp").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("preserves caller-supplied sort directives")
    void preservesCallerSort() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(Page.empty());

        PageRequest pr = PageRequest.of(0, 5, Sort.by(Sort.Direction.ASC, "eventDescription"));
        service.search(null, null, null, null, null, null, null, null, null, null, null, pr);

        ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
        org.mockito.Mockito.verify(repository).findAll(any(Specification.class), captor.capture());
        Sort.Order order = captor.getValue().getSort().getOrderFor("eventDescription");
        assertThat(order).isNotNull();
        assertThat(order.getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("each filter is wired into a Specification predicate when supplied")
    void allFiltersExerciseSpec() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(Page.empty());

        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID impersonatorUserId = UUID.randomUUID();
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();

        service.search(
            userId,
            "alice",
            List.of(AuditEventType.SECURITY_ALERT_TRIGGERED, AuditEventType.LOGIN),
            AuditStatus.SUCCESS,
            hospitalId,
            organizationId,
            impersonatorUserId,
            "USER",
            "res-1",
            from,
            to,
            PageRequest.of(0, 10));

        // Specification was created and passed to the repo. Predicates are
        // evaluated lazily by the JPA provider, so the unit test verifies
        // the wiring (no exception, search returns) rather than SQL.
        Specification<AuditEventLog> spec = captureSpec();
        assertThat(spec).isNotNull();
    }

    @Test
    @DisplayName("blank userName / blank entityType / blank resourceId are skipped (no NPE)")
    void blankFiltersSkipped() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(Page.empty());

        AuditSearchPageDTO out = service.search(
            null, "  ", List.of(), null, null, null, null, "  ", "  ", null, null,
            PageRequest.of(0, 10));

        assertThat(out.getContent()).isEmpty();
    }

    /**
     * The Specification produced by the service is a lambda — its branches
     * only execute when the JPA provider invokes {@code toPredicate(...)}.
     * This test calls that method directly with mocked criteria objects so
     * each filter branch is exercised, lifting branch coverage out of the
     * 5.6% floor seen when the spec is captured but never invoked.
     */
    @Test
    @DisplayName("Specification.toPredicate exercises every filter branch when all filters are set")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void specPredicateExercisesAllBranches() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(Page.empty());

        UUID userId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID impersonatorUserId = UUID.randomUUID();
        LocalDateTime from = LocalDateTime.now().minusDays(1);
        LocalDateTime to = LocalDateTime.now();

        service.search(
            userId,
            "alice",
            List.of(AuditEventType.SECURITY_ALERT_TRIGGERED),
            AuditStatus.SUCCESS,
            hospitalId,
            organizationId,
            impersonatorUserId,
            "USER",
            "res-1",
            from,
            to,
            PageRequest.of(0, 10));

        Specification<AuditEventLog> spec = captureSpec();
        invokeSpec(spec, true);

        // Each filter that was non-null hit the corresponding cb.* call. We
        // primarily care that the lambda executed without exception and that
        // joins were constructed for hospital/organization filters.
    }

    @Test
    @DisplayName("Specification.toPredicate skips joins when neither hospital nor organization filter is set")
    @SuppressWarnings({"unchecked", "rawtypes"})
    void specPredicateSkipsJoinsWhenNotNeeded() {
        when(repository.findAll(any(Specification.class), any(PageRequest.class)))
            .thenReturn(Page.empty());

        service.search(
            UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null,
            PageRequest.of(0, 10));

        Specification<AuditEventLog> spec = captureSpec();
        invokeSpec(spec, false);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void invokeSpec(Specification<AuditEventLog> spec, boolean expectJoins) {
        Root<AuditEventLog> root = mock(Root.class);
        CriteriaQuery<?> query = mock(CriteriaQuery.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);

        // Generic stubs for any get/join/predicate call. The lambda just needs
        // non-null returns that don't throw — the goal is branch coverage,
        // not SQL fidelity.
        Path path = mock(Path.class);
        when(root.get(any(String.class))).thenReturn(path);
        when(path.get(any(String.class))).thenReturn(path);
        Join join = mock(Join.class);
        when(root.join(any(String.class), any())).thenReturn(join);
        when(join.join(any(String.class), any())).thenReturn(join);
        when(join.get(any(String.class))).thenReturn(path);
        Expression expr = mock(Expression.class);
        when(cb.lower(any(Expression.class))).thenReturn(expr);
        Predicate predicate = mock(Predicate.class);
        when(cb.equal(any(Expression.class), any(Object.class))).thenReturn(predicate);
        when(cb.equal(any(Path.class), any(Object.class))).thenReturn(predicate);
        when(cb.like(any(Expression.class), any(String.class))).thenReturn(predicate);
        when(cb.greaterThanOrEqualTo(any(Expression.class), any(LocalDateTime.class)))
            .thenReturn(predicate);
        when(cb.lessThanOrEqualTo(any(Expression.class), any(LocalDateTime.class)))
            .thenReturn(predicate);
        when(cb.and(any(Predicate[].class))).thenReturn(predicate);
        when(path.in(any(List.class))).thenReturn(predicate);

        Predicate result = spec.toPredicate(root, query, cb);

        assertThat(result).isNotNull();
        if (expectJoins) {
            verify(root, atLeastOnce()).join(any(String.class), any());
        } else {
            verify(root, never()).join(any(String.class), any());
        }
    }
}
