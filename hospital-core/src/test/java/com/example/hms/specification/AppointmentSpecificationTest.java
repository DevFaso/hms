package com.example.hms.specification;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.model.Appointment;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppointmentSpecificationTest {

    private static final String PATIENT = "patient";
    private static final String STAFF = "staff";
    private static final String HOSPITAL = "hospital";
    private static final String USER = "user";

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private Root<Appointment> root;

    @Mock
    private CriteriaQuery<Appointment> query;

    @Mock
    private CriteriaBuilder criteriaBuilder;

    @BeforeEach
    void setUp() {
        // No default stubbing to keep Mockito strictness satisfied.
    }

    @Test
    void withFilterNullReturnsConjunction() {
        Predicate conjunction = mock(Predicate.class);
        when(criteriaBuilder.conjunction()).thenReturn(conjunction);

        Predicate result = AppointmentSpecification.withFilter(null).toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(conjunction);
        verify(criteriaBuilder).conjunction();
        verify(query, never()).distinct(true);
    }

    @Test
    void withFilterPopulatesPredicatesForAllBranches() {
        var filter = com.example.hms.payload.dto.AppointmentFilterDTO.builder()
            .hospitalId(UUID.randomUUID())
            .departmentId(UUID.randomUUID())
            .patientId(UUID.randomUUID())
            .staffId(UUID.randomUUID())
            .createdById(UUID.randomUUID())
            .statuses(Set.of(AppointmentStatus.SCHEDULED))
            .fromDate(LocalDate.now().minusDays(2))
            .toDate(LocalDate.now().plusDays(3))
            .fromStartTime(LocalTime.of(8, 0))
            .toEndTime(LocalTime.of(18, 0))
            .upcomingOnly(true)
            .patientEmail("patient@example.com")
            .patientName("Patient X")
            .staffEmail("doctor@example.com")
            .staffName("Dr Who")
            .hospitalName("Central Hospital")
            .search("Checkup")
            .build();

    Predicate finalPredicate = mock(Predicate.class);
        when(criteriaBuilder.and(any(Predicate[].class))).thenReturn(finalPredicate);
        lenient().when(criteriaBuilder.equal(any(Path.class), any())).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(criteriaBuilder.greaterThanOrEqualTo(any(Path.class), any(LocalDate.class))).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(criteriaBuilder.greaterThanOrEqualTo(any(Path.class), any(LocalTime.class))).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(criteriaBuilder.lessThanOrEqualTo(any(Path.class), any(LocalDate.class))).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(criteriaBuilder.lessThanOrEqualTo(any(Path.class), any(LocalTime.class))).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(criteriaBuilder.disjunction()).thenReturn(mock(Predicate.class));
        lenient().when(criteriaBuilder.lower(any(Expression.class))).thenAnswer(inv -> mock(Expression.class));
        lenient().when(criteriaBuilder.like(any(Expression.class), anyString())).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(criteriaBuilder.or(any(Predicate[].class))).thenAnswer(inv -> mock(Predicate.class));
        lenient().when(criteriaBuilder.or(any(Predicate.class), any(Predicate.class))).thenAnswer(inv -> mock(Predicate.class));

    Join<Object, Object> patientJoin = mock(Join.class, Answers.RETURNS_DEEP_STUBS);
    Join<Object, Object> staffJoin = mock(Join.class, Answers.RETURNS_DEEP_STUBS);
    Join<Object, Object> hospitalJoin = mock(Join.class, Answers.RETURNS_DEEP_STUBS);
    when(root.join(PATIENT, JoinType.LEFT)).thenReturn((Join) patientJoin);
    when(root.join(STAFF, JoinType.LEFT)).thenReturn((Join) staffJoin);
    when(root.join(HOSPITAL, JoinType.LEFT)).thenReturn((Join) hospitalJoin);
    when(patientJoin.join(USER, JoinType.LEFT)).thenAnswer(inv -> mock(Join.class, Answers.RETURNS_DEEP_STUBS));
    when(staffJoin.join(USER, JoinType.LEFT)).thenAnswer(inv -> mock(Join.class, Answers.RETURNS_DEEP_STUBS));

        Predicate result = AppointmentSpecification.withFilter(filter).toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(finalPredicate);
        verify(query).distinct(true);
        verify(root).join(PATIENT, JoinType.LEFT);
        verify(root).join(STAFF, JoinType.LEFT);
        verify(root).join(HOSPITAL, JoinType.LEFT);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(criteriaBuilder, atLeastOnce()).like(any(Expression.class), captor.capture());
        assertThat(captor.getAllValues()).anyMatch(value -> value.contains("checkup"));
    }

    @Test
    void inHospitalsEmptySetReturnsDisjunction() {
    Predicate disjunction = mock(Predicate.class);
    when(criteriaBuilder.disjunction()).thenReturn(disjunction);

    Predicate result = AppointmentSpecification.inHospitals(Set.of()).toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(disjunction);
        verify(criteriaBuilder).disjunction();
    }

    @Test
    void inHospitalsWithIdsBuildsInPredicate() {
        var hospitalIds = Set.of(UUID.randomUUID(), UUID.randomUUID());
        @SuppressWarnings("unchecked")
        Path<Object> hospitalPath = mock(Path.class);
        @SuppressWarnings("unchecked")
        Path<Object> idPath = mock(Path.class);
        Predicate inPredicate = mock(Predicate.class);

    when(root.get(HOSPITAL)).thenReturn(hospitalPath);
        when(hospitalPath.get("id")).thenReturn(idPath);
        when(idPath.in(hospitalIds)).thenReturn(inPredicate);

        Predicate result = AppointmentSpecification.inHospitals(hospitalIds).toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(inPredicate);
    }

    @Test
    void forPatientUserNullReturnsDisjunction() {
    Predicate disjunction = mock(Predicate.class);
        when(criteriaBuilder.disjunction()).thenReturn(disjunction);

        Predicate result = AppointmentSpecification.forPatientUser(null).toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(disjunction);
    }

    @Test
    @SuppressWarnings("unchecked")
    void forPatientUserWithIdCreatesJoinPredicate() {
        UUID userId = UUID.randomUUID();
        Join<Object, Object> patientJoin = mock(Join.class, Answers.RETURNS_DEEP_STUBS);
        Predicate expected = mock(Predicate.class);

        when(root.join(PATIENT, JoinType.INNER)).thenReturn((Join) patientJoin);
        when(criteriaBuilder.equal(patientJoin.get(USER).get("id"), userId)).thenReturn(expected);

        Predicate result = AppointmentSpecification.forPatientUser(userId).toPredicate(root, query, criteriaBuilder);

        assertThat(result).isSameAs(expected);
        verify(root).join(PATIENT, JoinType.INNER);
    }
}
