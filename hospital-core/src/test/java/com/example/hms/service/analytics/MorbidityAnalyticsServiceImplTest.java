package com.example.hms.service.analytics;

import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO;
import com.example.hms.payload.dto.analytics.MorbidityDashboardDTO.Scope;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientProblemRepository.DiagnosisCount;
import com.example.hms.repository.PatientProblemRepository.HospitalDiagnosisCount;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scope rule is the whole point of this service, and it is delegated
 * to {@link RoleValidator#requireActiveHospitalId()} — whose contract is
 * "null means global". These tests pin both branches of that contract and
 * the guarantee that a scoped caller never reaches a cross-tenant query.
 */
@ExtendWith(MockitoExtension.class)
class MorbidityAnalyticsServiceImplTest {

    private static final YearMonth AUGUST = YearMonth.of(2026, 8);
    private static final LocalDateTime FROM = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 9, 1, 0, 0);

    @Mock private PatientProblemRepository problemRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RoleValidator roleValidator;

    private MorbidityAnalyticsServiceImpl service;
    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new MorbidityAnalyticsServiceImpl(
            problemRepository, hospitalRepository, roleValidator);
    }

    /* ── Global scope (unscoped super-admin) ───────────────────────── */

    @Test
    @DisplayName("a global caller sees every hospital plus the per-hospital split")
    void globalCallerGetsNetworkViewAndBreakdown() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(problemRepository.countDiagnosesAcrossHospitals(eq(FROM), eq(TO)))
            .thenReturn(List.of(count("B54", "Malaria, unspecified", 412),
                                count("I10", "Essential hypertension", 198)));
        when(problemRepository.countDiagnosesByHospital(eq(FROM), eq(TO)))
            .thenReturn(List.of(
                hospitalCount(hospitalA, "Hospital A", "B54", "Malaria, unspecified", 210),
                hospitalCount(hospitalA, "Hospital A", "I10", "Essential hypertension", 88),
                hospitalCount(hospitalB, "Hospital B", "A00", "Cholera", 121)));

        MorbidityDashboardDTO result = service.topDiagnoses(AUGUST, 10);

        assertThat(result.month()).isEqualTo("2026-08");
        assertThat(result.scope()).isEqualTo(Scope.NETWORK);
        assertThat(result.hospitalName()).isNull();
        assertThat(result.overall()).extracting(MorbidityDashboardDTO.DiagnosisSlice::display)
            .containsExactly("Malaria, unspecified", "Essential hypertension");
        assertThat(result.byHospital()).hasSize(2);
        assertThat(result.byHospital().get(0).hospitalName()).isEqualTo("Hospital A");
        assertThat(result.byHospital().get(0).top()).hasSize(2);
        assertThat(result.byHospital().get(0).totalRecorded()).isEqualTo(298);
        assertThat(result.byHospital().get(1).hospitalName()).isEqualTo("Hospital B");
        assertThat(result.byHospital().get(1).totalRecorded()).isEqualTo(121);
    }

    @Test
    @DisplayName("the per-hospital total counts the tail the chart cuts off")
    void perHospitalTotalIncludesRowsBeyondTheLimit() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(problemRepository.countDiagnosesAcrossHospitals(any(), any())).thenReturn(List.of());
        when(problemRepository.countDiagnosesByHospital(any(), any()))
            .thenReturn(List.of(
                hospitalCount(hospitalA, "Hospital A", "B54", "Malaria", 200),
                hospitalCount(hospitalA, "Hospital A", "I10", "Hypertension", 50),
                hospitalCount(hospitalA, "Hospital A", "A00", "Cholera", 10)));

        MorbidityDashboardDTO result = service.topDiagnoses(AUGUST, 1);

        assertThat(result.byHospital().get(0).top()).hasSize(1);
        assertThat(result.byHospital().get(0).totalRecorded()).isEqualTo(260);
    }

    /* ── Scoped callers ────────────────────────────────────────────── */

    @Test
    @DisplayName("a scoped caller sees only their own hospital and an EMPTY breakdown")
    void scopedCallerIsLimitedToTheirOwnHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalA);
        hospital.setName("Hospital A");
        when(hospitalRepository.findById(hospitalA)).thenReturn(Optional.of(hospital));
        when(problemRepository.countDiagnosesRecordedInWindow(eq(hospitalA), eq(FROM), eq(TO)))
            .thenReturn(List.of(count("B54", "Malaria, unspecified", 210)));

        MorbidityDashboardDTO result = service.topDiagnoses(AUGUST, 10);

        assertThat(result.scope()).isEqualTo(Scope.HOSPITAL);
        assertThat(result.hospitalName()).isEqualTo("Hospital A");
        assertThat(result.overall()).hasSize(1);
        // Empty, never partial — a partial list would confirm other tenants exist.
        assertThat(result.byHospital()).isEmpty();
    }

    @Test
    @DisplayName("a scoped caller never reaches a cross-tenant query")
    void scopedCallerNeverTouchesTheCrossTenantQueries() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalA);
        when(problemRepository.countDiagnosesRecordedInWindow(any(), any(), any()))
            .thenReturn(List.of());

        service.topDiagnoses(AUGUST, 10);

        verify(problemRepository, never()).countDiagnosesAcrossHospitals(any(), any());
        verify(problemRepository, never()).countDiagnosesByHospital(any(), any());
    }

    @Test
    @DisplayName("a super-admin who scoped with X-Hospital-Id gets that hospital, not the network")
    void chipScopedSuperAdminNarrowsToTheChosenHospital() {
        // The resolver already encodes this: an explicit header override wins
        // over the global default. The dashboard must follow it rather than
        // showing the network view and silently ignoring the chip.
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalB);
        when(problemRepository.countDiagnosesRecordedInWindow(eq(hospitalB), any(), any()))
            .thenReturn(List.of(count("A00", "Cholera", 121)));

        MorbidityDashboardDTO result = service.topDiagnoses(AUGUST, 10);

        assertThat(result.scope()).isEqualTo(Scope.HOSPITAL);
        assertThat(result.byHospital()).isEmpty();
        verify(problemRepository, never()).countDiagnosesAcrossHospitals(any(), any());
    }

    @Test
    @DisplayName("the resolver's refusal propagates rather than degrading to an unscoped read")
    void resolverRefusalPropagates() {
        when(roleValidator.requireActiveHospitalId())
            .thenThrow(new BusinessException("Hospital context required."));

        assertThatThrownBy(() -> service.topDiagnoses(AUGUST, 10))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context required");

        verify(problemRepository, never()).countDiagnosesAcrossHospitals(any(), any());
    }

    /* ── window arithmetic ─────────────────────────────────────────── */

    @Test
    @DisplayName("the month becomes a half-open window so the last day is not lost")
    void monthMapsToAHalfOpenWindow() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        when(problemRepository.countDiagnosesAcrossHospitals(any(), any())).thenReturn(List.of());
        when(problemRepository.countDiagnosesByHospital(any(), any())).thenReturn(List.of());

        service.topDiagnoses(YearMonth.of(2026, 12), 10);

        // December ends at 2027-01-01T00:00 EXCLUSIVE — a diagnosis recorded
        // at 23:59 on the 31st still counts.
        verify(problemRepository).countDiagnosesAcrossHospitals(
            LocalDateTime.of(2026, 12, 1, 0, 0), LocalDateTime.of(2027, 1, 1, 0, 0));
    }

    /* ── fixtures ──────────────────────────────────────────────────── */

    private static DiagnosisCount count(String code, String display, long total) {
        return new DiagnosisCount() {
            @Override public String getCode() { return code; }
            @Override public String getDisplay() { return display; }
            @Override public long getTotal() { return total; }
        };
    }

    private static HospitalDiagnosisCount hospitalCount(UUID hospitalId, String hospitalName,
                                                        String code, String display, long total) {
        return new HospitalDiagnosisCount() {
            @Override public UUID getHospitalId() { return hospitalId; }
            @Override public String getHospitalName() { return hospitalName; }
            @Override public String getCode() { return code; }
            @Override public String getDisplay() { return display; }
            @Override public long getTotal() { return total; }
        };
    }
}
