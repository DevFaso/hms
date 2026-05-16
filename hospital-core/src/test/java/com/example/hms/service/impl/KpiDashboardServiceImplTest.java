package com.example.hms.service.impl;

import com.example.hms.payload.dto.analytics.KpiDashboardDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link KpiDashboardServiceImpl}.
 *
 * <p>These tests cover the on-demand KPI computation paths: tenant
 * scoping, super-admin pin logic, date-validation guards, and the
 * aggregation math for all three KPIs.  The {@link EntityManager}
 * is mocked so no database is required, and the
 * {@link HospitalContextHolder} thread-local is set/cleared around
 * each test.
 */
@ExtendWith(MockitoExtension.class)
class KpiDashboardServiceImplTest {

    @Mock
    private EntityManager entityManager;

    private KpiDashboardServiceImpl service;

    private final UUID HOSPITAL_ID = UUID.randomUUID();
    private final LocalDate FROM = LocalDate.of(2026, 4, 1);
    private final LocalDate TO   = LocalDate.of(2026, 4, 30);

    @BeforeEach
    void setUp() {
        service = new KpiDashboardServiceImpl();
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    // ── Argument validation ──────────────────────────────────────────────

    @Test
    @DisplayName("null fromInclusive throws NullPointerException")
    void nullFrom_throwsNpe() {
        assertThatThrownBy(() -> service.computeDashboard(null, TO))
            .isInstanceOf(NullPointerException.class);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("null toInclusive throws NullPointerException")
    void nullTo_throwsNpe() {
        assertThatThrownBy(() -> service.computeDashboard(FROM, null))
            .isInstanceOf(NullPointerException.class);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("toInclusive before fromInclusive throws IllegalArgumentException")
    void reversedDates_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.computeDashboard(TO, FROM))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toInclusive");
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    // ── Tenant-scoping / super-admin pin ──────────────────────────────────

    @Test
    @DisplayName("no hospital context (empty) returns empty rollup without querying DB")
    void noContext_returnsEmptyRollup() {
        // HospitalContextHolder is empty — getContextOrEmpty() returns HospitalContext.empty()
        KpiDashboardDTO result = service.computeDashboard(FROM, TO);

        assertThat(result.hospitalId()).isNull();
        assertThat(result.doorToDoctor().sampleSize()).isZero();
        assertThat(result.doorToDoctor().averageMinutes()).isNull();
        assertThat(result.dispenseLeadTime().sampleSize()).isZero();
        assertThat(result.noShowRate().totalAppointments()).isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("super-admin without header pin (JWT-only hospitalId) returns empty rollup")
    void superAdminWithoutPin_returnsEmptyRollup() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(HOSPITAL_ID) // JWT-derived — not an explicit header pin
            .superAdmin(true)
            .headerOverridden(false)
            .build());

        KpiDashboardDTO result = service.computeDashboard(FROM, TO);

        assertThat(result.hospitalId()).isNull();
        assertThat(result.doorToDoctor().sampleSize()).isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("super-admin with explicit X-Hospital-Id pin computes KPIs for that hospital")
    void superAdminWithPin_computesKpis() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(HOSPITAL_ID)
            .superAdmin(true)
            .headerOverridden(true)
            .build());

        stubThreeQueries(
            new Object[]{5L, 900.0},    // d2d: 5 samples, 900 s = 15 min
            new Object[]{3L, 480.0},    // lead: 3 samples, 480 s = 8 min
            new Object[]{10L, 1L}       // noShow: 10 total, 1 no-show
        );

        KpiDashboardDTO result = service.computeDashboard(FROM, TO);

        assertThat(result.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(result.doorToDoctor().sampleSize()).isEqualTo(5L);
        assertThat(result.doorToDoctor().averageMinutes()).isEqualTo(15.0);
    }

    // ── KPI aggregation math ─────────────────────────────────────────────

    @Test
    @DisplayName("computeDashboard returns correct KPI values for non-empty window")
    void computeDashboard_nonEmptyWindow_correctValues() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(HOSPITAL_ID)
            .build());

        stubThreeQueries(
            new Object[]{20L, 1620.0},  // d2d: 20 samples, 1620 s = 27 min
            new Object[]{8L,  840.0},   // lead: 8 samples, 840 s = 14 min
            new Object[]{100L, 7L}      // noShow: 100 total, 7 no-show → 0.07
        );

        KpiDashboardDTO result = service.computeDashboard(FROM, TO);

        assertThat(result.hospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(result.from()).isEqualTo(FROM);
        assertThat(result.to()).isEqualTo(TO);

        assertThat(result.doorToDoctor().sampleSize()).isEqualTo(20L);
        assertThat(result.doorToDoctor().averageMinutes()).isEqualTo(27.0);

        assertThat(result.dispenseLeadTime().sampleSize()).isEqualTo(8L);
        assertThat(result.dispenseLeadTime().averageMinutes()).isEqualTo(14.0);

        assertThat(result.noShowRate().totalAppointments()).isEqualTo(100L);
        assertThat(result.noShowRate().noShowCount()).isEqualTo(7L);
        assertThat(result.noShowRate().rate()).isEqualTo(0.07);
    }

    @Test
    @DisplayName("zero encounters returns null averageMinutes for door-to-doctor")
    void zeroDoorToDoctorSamples_returnsNullAverage() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(HOSPITAL_ID)
            .build());

        stubThreeQueries(
            new Object[]{0L, null},     // d2d: no encounters
            new Object[]{0L, null},     // lead: no dispenses
            new Object[]{0L, 0L}        // noShow: no appointments
        );

        KpiDashboardDTO result = service.computeDashboard(FROM, TO);

        assertThat(result.doorToDoctor().sampleSize()).isZero();
        assertThat(result.doorToDoctor().averageMinutes()).isNull();
        assertThat(result.dispenseLeadTime().sampleSize()).isZero();
        assertThat(result.dispenseLeadTime().averageMinutes()).isNull();
        assertThat(result.noShowRate().totalAppointments()).isZero();
        assertThat(result.noShowRate().rate()).isNull();
    }

    @Test
    @DisplayName("no-show rate is calculated as noShow / total appointments")
    void noShowRate_computesCorrectRatio() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(HOSPITAL_ID)
            .build());

        stubThreeQueries(
            new Object[]{1L, 60.0},
            new Object[]{1L, 60.0},
            new Object[]{40L, 10L}      // 10/40 = 0.25
        );

        KpiDashboardDTO result = service.computeDashboard(FROM, TO);

        assertThat(result.noShowRate().rate())
            .as("no-show rate = noShow / total")
            .isEqualTo(0.25);
    }

    @Test
    @DisplayName("same from and to date (single-day window) is accepted")
    void singleDayWindow_accepted() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(HOSPITAL_ID)
            .build());

        stubThreeQueries(
            new Object[]{2L, 120.0},
            new Object[]{1L, 60.0},
            new Object[]{5L, 0L}
        );

        KpiDashboardDTO result = service.computeDashboard(FROM, FROM);

        assertThat(result.doorToDoctor().sampleSize()).isEqualTo(2L);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Stubs three sequential {@code createNativeQuery} calls (d2d, lead,
     * noShow) with the provided single-row result arrays.
     */
    private void stubThreeQueries(Object[] d2dRow, Object[] leadRow, Object[] noShowRow) {
        Query d2dQ    = buildQueryMock(d2dRow);
        Query leadQ   = buildQueryMock(leadRow);
        Query noShowQ = buildQueryMock(noShowRow);
        when(entityManager.createNativeQuery(anyString()))
            .thenReturn(d2dQ, leadQ, noShowQ);
    }

    private Query buildQueryMock(Object[] row) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getSingleResult()).thenReturn(row);
        return q;
    }
}
