package com.example.hms.service.impl;

import com.example.hms.payload.dto.analytics.KpiDashboardDTO;
import com.example.hms.payload.dto.analytics.KpiDashboardDTO.KpiTrendPoint;
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

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
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

    private final UUID hospitalId = UUID.randomUUID();
    private final LocalDate from = LocalDate.of(2026, 4, 1);
    private final LocalDate to   = LocalDate.of(2026, 4, 30);

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
        assertThatThrownBy(() -> service.computeDashboard(null, to))
            .isInstanceOf(NullPointerException.class);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("null toInclusive throws NullPointerException")
    void nullTo_throwsNpe() {
        assertThatThrownBy(() -> service.computeDashboard(from, null))
            .isInstanceOf(NullPointerException.class);
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("toInclusive before fromInclusive throws IllegalArgumentException")
    void reversedDates_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.computeDashboard(to, from))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("toInclusive");
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    // ── Tenant-scoping / super-admin pin ──────────────────────────────────

    @Test
    @DisplayName("no hospital context (empty) returns empty rollup without querying DB")
    void noContext_returnsEmptyRollup() {
        // HospitalContextHolder is empty — getContextOrEmpty() returns HospitalContext.empty()
        KpiDashboardDTO result = service.computeDashboard(from, to);

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
            .activeHospitalId(hospitalId) // JWT-derived — not an explicit header pin
            .superAdmin(true)
            .headerOverridden(false)
            .build());

        KpiDashboardDTO result = service.computeDashboard(from, to);

        assertThat(result.hospitalId()).isNull();
        assertThat(result.doorToDoctor().sampleSize()).isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("super-admin with explicit X-Hospital-Id pin computes KPIs for that hospital")
    void superAdminWithPin_computesKpis() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .superAdmin(true)
            .headerOverridden(true)
            .build());

        stubThreeQueries(
            new Object[]{5L, 900.0, 720.0},  // d2d: 5 samples, avg 900s = 15min, p50 720s = 12min
            new Object[]{3L, 480.0},          // lead: 3 samples, 480 s = 8 min
            new Object[]{10L, 1L}             // noShow: 10 total, 1 no-show
        );

        KpiDashboardDTO result = service.computeDashboard(from, to);

        assertThat(result.hospitalId()).isEqualTo(hospitalId);
        assertThat(result.doorToDoctor().sampleSize()).isEqualTo(5L);
        assertThat(result.doorToDoctor().averageMinutes()).isEqualTo(15.0);
    }

    // ── KPI aggregation math ─────────────────────────────────────────────

    @Test
    @DisplayName("computeDashboard returns correct KPI values for non-empty window")
    void computeDashboard_nonEmptyWindow_correctValues() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .build());

        stubThreeQueries(
            new Object[]{20L, 1620.0, 1500.0},  // d2d: 20 samples, avg 1620s=27min, p50 1500s=25min
            new Object[]{8L,  840.0},            // lead: 8 samples, 840 s = 14 min
            new Object[]{100L, 7L}               // noShow: 100 total, 7 no-show → 0.07
        );

        KpiDashboardDTO result = service.computeDashboard(from, to);

        assertThat(result.hospitalId()).isEqualTo(hospitalId);
        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);

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
            .activeHospitalId(hospitalId)
            .build());

        stubThreeQueries(
            new Object[]{0L, null, null},  // d2d: no encounters (count + avg + median all null)
            new Object[]{0L, null},        // lead: no dispenses
            new Object[]{0L, 0L}           // noShow: no appointments
        );

        KpiDashboardDTO result = service.computeDashboard(from, to);

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
            .activeHospitalId(hospitalId)
            .build());

        stubThreeQueries(
            new Object[]{1L, 60.0, 60.0},
            new Object[]{1L, 60.0},
            new Object[]{40L, 10L}      // 10/40 = 0.25
        );

        KpiDashboardDTO result = service.computeDashboard(from, to);

        assertThat(result.noShowRate().rate())
            .as("no-show rate = noShow / total")
            .isEqualTo(0.25);
    }

    @Test
    @DisplayName("same from and to date (single-day window) is accepted")
    void singleDayWindow_accepted() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .build());

        stubThreeQueries(
            new Object[]{2L, 120.0, 120.0},
            new Object[]{1L, 60.0},
            new Object[]{5L, 0L}
        );

        KpiDashboardDTO result = service.computeDashboard(from, from);

        assertThat(result.doorToDoctor().sampleSize()).isEqualTo(2L);
    }

    // ── Follow-on: P50 median door-to-doctor + trend timeseries ──────────

    @Test
    @DisplayName("door-to-doctor populates medianMinutesEstimate from PERCENTILE_CONT")
    void doorToDoctor_populatesMedian() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .build());

        // 12 samples, avg = 30 min, p50 = 22 min. The median is
        // typically lower than the average because of the long tail of
        // overnight-stay outliers — exactly why we want it surfaced.
        stubThreeQueries(
            new Object[]{12L, 1800.0, 1320.0},
            new Object[]{0L, null},
            new Object[]{0L, 0L}
        );

        KpiDashboardDTO result = service.computeDashboard(from, to);

        assertThat(result.doorToDoctor().averageMinutes()).isEqualTo(30.0);
        assertThat(result.doorToDoctor().medianMinutesEstimate()).isEqualTo(22.0);
        assertThat(result.trend()).isNull();
    }

    @Test
    @DisplayName("computeDashboard(withTrends=false) does not run trend group-by queries")
    void noTrends_skipsTrendQueries() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .build());

        stubThreeQueries(
            new Object[]{1L, 60.0, 60.0},
            new Object[]{1L, 60.0},
            new Object[]{1L, 0L}
        );

        KpiDashboardDTO result = service.computeDashboard(from, to, false);

        assertThat(result.trend()).isNull();
        // Three queries only — d2d, lead, noShow. Trend queries
        // would have been a 4th / 5th / 6th createNativeQuery call.
        verify(entityManager, org.mockito.Mockito.times(3)).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("computeDashboard(withTrends=true) merges three daily series into KpiTrendPoints")
    void withTrends_returnsMergedDailyPoints() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .build());

        // 6 sequential queries: d2d-aggregate, lead-aggregate, noShow-aggregate,
        // then d2d-trend, lead-trend, noShow-trend.
        Query d2dAgg = buildQueryMock(new Object[]{3L, 1800.0, 1200.0});
        Query leadAgg = buildQueryMock(new Object[]{2L, 900.0});
        Query noShowAgg = buildQueryMock(new Object[]{10L, 1L});
        Query d2dTrend = buildListQueryMock(List.of(
            new Object[]{Date.valueOf(LocalDate.of(2026, 4, 1)), 1200.0},   // 20 min
            new Object[]{Date.valueOf(LocalDate.of(2026, 4, 2)), 1800.0}    // 30 min
        ));
        Query leadTrend = buildListQueryMock(List.<Object[]>of(
            new Object[]{Date.valueOf(LocalDate.of(2026, 4, 1)), 600.0}     // 10 min
        ));
        Query noShowTrend = buildListQueryMock(List.<Object[]>of(
            new Object[]{Date.valueOf(LocalDate.of(2026, 4, 2)), 8L, 2L}    // 0.25
        ));
        when(entityManager.createNativeQuery(anyString()))
            .thenReturn(d2dAgg, leadAgg, noShowAgg, d2dTrend, leadTrend, noShowTrend);

        KpiDashboardDTO result = service.computeDashboard(from, to, true);

        assertThat(result.trend()).isNotNull();
        assertThat(result.trend()).hasSize(2);
        KpiTrendPoint day1 = result.trend().get(0);
        KpiTrendPoint day2 = result.trend().get(1);
        assertThat(day1.date()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(day1.doorToDoctorAverageMinutes()).isEqualTo(20.0);
        assertThat(day1.dispenseLeadTimeAverageMinutes()).isEqualTo(10.0);
        assertThat(day1.noShowRate()).isNull();  // no appointments that day
        assertThat(day2.date()).isEqualTo(LocalDate.of(2026, 4, 2));
        assertThat(day2.doorToDoctorAverageMinutes()).isEqualTo(30.0);
        assertThat(day2.dispenseLeadTimeAverageMinutes()).isNull();  // no dispenses
        assertThat(day2.noShowRate()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("trend drops days that CAST-to-DATE puts outside the requested window")
    void withTrends_dropsOutOfWindowDays() {
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(hospitalId)
            .build());

        Query d2dAgg = buildQueryMock(new Object[]{1L, 60.0, 60.0});
        Query leadAgg = buildQueryMock(new Object[]{0L, null});
        Query noShowAgg = buildQueryMock(new Object[]{0L, 0L});
        // Drift row: April 1 window starts but DB returns March 31 (UTC vs local).
        Query d2dTrend = buildListQueryMock(List.of(
            new Object[]{Date.valueOf(LocalDate.of(2026, 3, 31)), 600.0},
            new Object[]{Date.valueOf(LocalDate.of(2026, 4, 15)), 1200.0}
        ));
        Query leadTrend = buildListQueryMock(List.<Object[]>of());
        Query noShowTrend = buildListQueryMock(List.<Object[]>of());
        when(entityManager.createNativeQuery(anyString()))
            .thenReturn(d2dAgg, leadAgg, noShowAgg, d2dTrend, leadTrend, noShowTrend);

        KpiDashboardDTO result = service.computeDashboard(from, to, true);

        assertThat(result.trend()).hasSize(1);
        assertThat(result.trend().get(0).date()).isEqualTo(LocalDate.of(2026, 4, 15));
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

    private Query buildListQueryMock(List<Object[]> rows) {
        Query q = mock(Query.class);
        when(q.setParameter(anyString(), any())).thenReturn(q);
        when(q.getResultList()).thenReturn(rows);
        return q;
    }
}
