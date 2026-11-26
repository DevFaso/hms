package com.example.hms.service;

import com.example.hms.controller.OrganisationAdminController;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.OrganisationSummaryDTO;
import com.example.hms.payload.dto.OrganisationSummaryDTO.HospitalTotals;
import com.example.hms.payload.dto.OrganisationSummaryDTO.SensitiveCount;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.OrganisationTotalsRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.utility.SmallCounts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Platform admin rules 2026-11-20, rule 1b: an organisation admin reads totals
 * per hospital of its own organisation, for one calendar month, and nothing
 * about a person.
 */
@ExtendWith(MockitoExtension.class)
class OrganisationAdminServiceTest {

    /** "Today" in every test: 20 November 2026. */
    private static final YearMonth THIS_MONTH = YearMonth.of(2026, 11);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 20);

    @Mock private UserAccountAccess accountAccess;
    @Mock private OrganisationTotalsRepository totals;
    @Mock private StaffRepository staffRepository;
    @Mock private AdmissionRepository admissionRepository;

    private OrganisationAdminService service;

    private final Hospital hospitalA = hospital("Centre Test A", "XOF");
    private final Hospital hospitalB = hospital("Centre Test B", "XOF");

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        service = new OrganisationAdminService(accountAccess, totals, staffRepository, admissionRepository, clock);
    }

    private static Hospital hospital(String name, String currency) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setName(name);
        h.setCurrencyCode(currency);
        return h;
    }

    private void organisationOf(Hospital... hospitals) {
        Set<UUID> ids = Arrays.stream(hospitals).map(Hospital::getId).collect(Collectors.toSet());
        when(accountAccess.organisationHospitalIds()).thenReturn(ids);
        when(totals.findClinicalByIdIn(ids)).thenReturn(List.of(hospitals));
        for (Hospital h : hospitals) {
            lenient().when(totals.sumInvoiced(eq(h.getId()), any(), any())).thenReturn(BigDecimal.ZERO);
            lenient().when(totals.sumCollected(eq(h.getId()), any(), any())).thenReturn(BigDecimal.ZERO);
        }
    }

    @Test
    @DisplayName("a caller who administers no organisation is refused before any figure is read")
    void nobodyElseReadsTotals() {
        when(accountAccess.organisationHospitalIds()).thenReturn(Set.of());

        assertThatThrownBy(() -> service.summary(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.hospitals()).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(totals, staffRepository, admissionRepository);
    }

    @Test
    @DisplayName("totals are read for the hospitals of the caller's scope, each one named, and for no other")
    void totalsAreScopedToTheOrganisation() {
        organisationOf(hospitalA, hospitalB);
        when(staffRepository.countActiveByHospitalId(hospitalA.getId())).thenReturn(46L);
        when(totals.countAppointments(eq(hospitalA.getId()), any(), any())).thenReturn(120L);
        when(admissionRepository.countAdmissionsByHospitalAndDateRange(eq(hospitalA.getId()), any(), any()))
            .thenReturn(9L);
        when(totals.sumInvoiced(eq(hospitalA.getId()), any(), any())).thenReturn(new BigDecimal("250000"));
        when(totals.sumCollected(eq(hospitalA.getId()), any(), any())).thenReturn(new BigDecimal("183000"));
        // QA D3981 (r77) class: the 3 000 F handed back on a refund that month is not money collected.
        when(totals.sumRefundedOut(hospitalA.getId(), LocalDate.of(2026, 11, 1).atStartOfDay(),
            TODAY.plusDays(1).atStartOfDay())).thenReturn(new BigDecimal("3000"));

        OrganisationSummaryDTO summary = service.summary(THIS_MONTH);

        assertThat(summary.hospitals()).extracting(HospitalTotals::hospitalId)
            .containsExactly(hospitalA.getId(), hospitalB.getId());
        HospitalTotals a = summary.hospitals().get(0);
        assertThat(a.hospitalName()).isEqualTo("Centre Test A");
        assertThat(a.currencyCode()).isEqualTo("XOF");
        assertThat(a.activeStaff()).isEqualTo(46);
        assertThat(a.appointments()).isEqualTo(120);
        assertThat(a.admissions()).isEqualTo(9);
        assertThat(a.invoicedTotal()).isEqualByComparingTo("250000");
        assertThat(a.collectedTotal()).isEqualByComparingTo("180000");
        // Every figure asked for a hospital names that hospital.
        verify(totals).countAppointments(hospitalB.getId(), LocalDate.of(2026, 11, 1), TODAY);
        verify(totals).countDeaths(hospitalB.getId(), LocalDate.of(2026, 11, 1).atStartOfDay(),
            LocalDate.of(2026, 11, 21).atStartOfDay());
    }

    // ── the period: one calendar month, never a free range ──────────────────

    @Test
    @DisplayName("with no month the period is the current month to date")
    void defaultPeriodIsTheCurrentMonthToDate() {
        organisationOf(hospitalA);

        OrganisationSummaryDTO summary = service.summary(null);

        assertThat(summary.from()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(summary.to()).isEqualTo(TODAY);
        // Naming the current month is the same period: never a month that runs past today.
        assertThat(service.summary(THIS_MONTH).to()).isEqualTo(TODAY);
    }

    @ParameterizedTest
    @CsvSource({"2026-10, 2026-10-01, 2026-10-31", "2026-02, 2026-02-01, 2026-02-28", "2024-11, 2024-11-01, 2024-11-30"})
    @DisplayName("a past month is that month, whole: its first day to its last")
    void aPastMonthIsWhole(String month, LocalDate first, LocalDate last) {
        organisationOf(hospitalA);

        OrganisationSummaryDTO summary = service.summary(YearMonth.parse(month));

        assertThat(summary.from()).isEqualTo(first);
        assertThat(summary.to()).isEqualTo(last);
    }

    @Test
    @DisplayName("a month still to come, or more than 24 months back, is refused on its field before anything is read")
    void theMonthIsBounded() {
        lenient().when(accountAccess.organisationHospitalIds()).thenReturn(Set.of(hospitalA.getId()));
        YearMonth next = THIS_MONTH.plusMonths(1);
        YearMonth tooOld = THIS_MONTH.minusMonths(25);

        assertThatThrownBy(() -> service.summary(next))
            .isInstanceOfSatisfying(FieldBusinessException.class, r -> assertThat(r.getField()).isEqualTo("month"));
        assertThatThrownBy(() -> service.summary(tooOld)).isInstanceOf(FieldBusinessException.class);
        verifyNoInteractions(totals);
    }

    @Test
    @DisplayName("no two answers overlap: every period the service answers for is one calendar month")
    void everyPeriodIsOneCalendarMonth() {
        // ONE way in, and it takes a calendar month: there is no from/to to subtract one answer from another.
        List<Method> ways = Arrays.stream(OrganisationAdminService.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()) && method.getName().equals("summary"))
            .toList();
        assertThat(ways).hasSize(1);
        assertThat(ways.get(0).getParameterTypes()).containsExactly(YearMonth.class);

        // So two different periods are two different months (disjoint), never a range inside another.
        organisationOf(hospitalA);
        for (int back = 0; back <= OrganisationAdminService.MAX_MONTHS_BACK; back++) {
            YearMonth month = THIS_MONTH.minusMonths(back);
            OrganisationSummaryDTO summary = service.summary(month);
            assertThat(summary.from()).isEqualTo(month.atDay(1));
            assertThat(summary.to()).isEqualTo(back == 0 ? TODAY : month.atEndOfMonth());
        }
    }

    @Test
    @DisplayName("the endpoint refuses from/to, alone or next to a month, before anything is read")
    void theEndpointRefusesAFreeRange() {
        OrganisationAdminController controller = new OrganisationAdminController(service);
        Map<String, String> range = Map.of("from", "2026-11-03", "to", "2026-11-04");
        Map<String, String> monthAndDay = Map.of("month", "2026-10", "to", "2026-10-02");
        YearMonth october = YearMonth.of(2026, 10);

        assertThatThrownBy(() -> controller.summary(null, range))
            .isInstanceOfSatisfying(FieldBusinessException.class, r -> assertThat(r.getField()).isEqualTo("month"));
        assertThatThrownBy(() -> controller.summary(october, monthAndDay)).isInstanceOf(FieldBusinessException.class);
        verifyNoInteractions(totals, accountAccess);

        // A month alone, or nothing at all, is answered.
        organisationOf(hospitalA);
        assertThat(controller.summary(october, Map.of("month", "2026-10")).getBody()).isNotNull();
        clearInvocations(totals);
        assertThat(controller.summary(null, Map.of()).getBody().to()).isEqualTo(TODAY);
    }

    // ── small counts ────────────────────────────────────────────────────────

    @ParameterizedTest
    @CsvSource({"1", "2", "3", "4"})
    @DisplayName("a sensitive count from 1 to 4 is withheld: no value leaves the server")
    void smallSensitiveCountsAreWithheld(long deaths) {
        organisationOf(hospitalA);
        when(totals.countDeaths(eq(hospitalA.getId()), any(), any())).thenReturn(deaths);

        SensitiveCount shown = service.summary(null).hospitals().get(0).deaths();

        assertThat(shown.suppressed()).isTrue();
        assertThat(shown.value()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"0", "5", "6", "40"})
    @DisplayName("zero, and a count at or above the threshold, is given")
    void otherSensitiveCountsAreGiven(long deaths) {
        organisationOf(hospitalA);
        when(totals.countDeaths(eq(hospitalA.getId()), any(), any())).thenReturn(deaths);

        SensitiveCount shown = service.summary(null).hospitals().get(0).deaths();

        assertThat(shown.suppressed()).isFalse();
        assertThat(shown.value()).isEqualTo(deaths);
        assertThat(SmallCounts.THRESHOLD).isEqualTo(5);
    }

    // ── what the answer may hold, and who may ask ───────────────────────────

    @Test
    @DisplayName("the answer holds totals only: no list, no person, no free text besides the hospital's own name")
    void theAnswerHoldsTotalsOnly() {
        // A field added to the totals row is a decision: this fails until it is listed here.
        assertThat(Arrays.stream(HospitalTotals.class.getRecordComponents()).map(RecordComponent::getName))
            .containsExactly("hospitalId", "hospitalName", "currencyCode", "activeStaff", "appointments",
                "admissions", "invoicedTotal", "collectedTotal", "deaths");
        for (RecordComponent component : HospitalTotals.class.getRecordComponents()) {
            assertThat(component.getType())
                .as(component.getName() + " is a number, an id, the hospital's name or currency, or a guarded count")
                .isIn(UUID.class, String.class, long.class, BigDecimal.class, SensitiveCount.class);
        }
    }

    @Test
    @DisplayName("both endpoints admit the organisation admin alone, and name no hospital or organisation")
    void endpointsAdmitTheOrganisationAdminOnly() {
        for (Method method : OrganisationAdminController.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            assertThat(method.getAnnotation(PreAuthorize.class))
                .as(method.getName()).isNotNull()
                .extracting(PreAuthorize::value).isEqualTo("hasAuthority('ROLE_ADMIN')");
            assertThat(Arrays.stream(method.getParameters()).map(java.lang.reflect.Parameter::getType))
                .as(method.getName() + " takes a month and the raw parameter names: the scope is the caller's")
                .allMatch(type -> type == YearMonth.class || type == Map.class);
        }
    }
}
