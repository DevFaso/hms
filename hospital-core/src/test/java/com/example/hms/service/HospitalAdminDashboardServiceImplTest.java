package com.example.hms.service;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.BedStatus;
import com.example.hms.enums.ConsultationStatus;
import com.example.hms.enums.ConsultationUrgency;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.PaymentMethod;
import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.enums.WardType;
import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.model.Appointment;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Consultation;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffAvailability;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.model.platform.HospitalPlatformServiceLink;
import com.example.hms.model.platform.OrganizationPlatformService;
import com.example.hms.payload.dto.dashboard.HospitalAdminSummaryDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.ConsultationRepository;
import com.example.hms.repository.PaymentTransactionRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import com.example.hms.repository.platform.HospitalPlatformServiceLinkRepository;
import com.example.hms.service.impl.HospitalAdminDashboardServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HospitalAdminDashboardServiceImplTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private ConsultationRepository consultationRepository;
    @Mock private BedRepository bedRepository;
    @Mock private BillingInvoiceRepository billingInvoiceRepository;
    @Mock private PaymentTransactionRepository paymentTransactionRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private StaffAvailabilityRepository staffAvailabilityRepository;
    @Mock private AuditEventLogRepository auditEventLogRepository;
    @Mock private StaffShiftRepository staffShiftRepository;
    @Mock private HospitalPlatformServiceLinkRepository hospitalPlatformServiceLinkRepository;

    @InjectMocks private HospitalAdminDashboardServiceImpl service;

    private static final UUID HOSPITAL_ID = UUID.randomUUID();
    private static final LocalDate TODAY = LocalDate.now();

    // ── Helper builders ─────────────────────────────────────────

    private Hospital hospital() {
        Hospital h = new Hospital();
        h.setId(HOSPITAL_ID);
        h.setName("Test Hospital");
        return h;
    }

    private Patient patient(String first, String last) {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setFirstName(first);
        p.setLastName(last);
        return p;
    }

    private Appointment appointment(AppointmentStatus status) {
        Appointment a = Appointment.builder()
            .hospital(hospital())
            .appointmentDate(TODAY)
            .status(status)
            .build();
        a.setId(UUID.randomUUID());
        return a;
    }

    private BillingInvoice invoice(InvoiceStatus status, BigDecimal total, BigDecimal paid, LocalDate dueDate) {
        return BillingInvoice.builder()
            .hospital(hospital())
            .invoiceNumber("INV-" + UUID.randomUUID().toString().substring(0, 8))
            .invoiceDate(TODAY)
            .dueDate(dueDate)
            .totalAmount(total)
            .amountPaid(paid)
            .status(status)
            .build();
    }

    private Consultation consultation(ConsultationStatus status, ConsultationUrgency urgency, LocalDateTime slaDueBy) {
        Hospital h = hospital();
        Consultation c = Consultation.builder()
            .hospital(h)
            .patient(patient("John", "Doe"))
            .specialtyRequested("Cardiology")
            .urgency(urgency)
            .status(status)
            .requestedAt(LocalDateTime.now().minusHours(2))
            .slaDueBy(slaDueBy)
            .build();
        c.setId(UUID.randomUUID());
        return c;
    }

    private AuditEventLog auditEvent() {
        AuditEventLog e = AuditEventLog.builder()
            .eventType(com.example.hms.enums.AuditEventType.USER_CREATE)
            .status(com.example.hms.enums.AuditStatus.SUCCESS)
            .entityType("USER")
            .resourceName("test-resource")
            .userName("admin")
            .eventTimestamp(LocalDateTime.now())
            .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    // ── Setup helpers for common stubs ───────────────────────────

    private void stubMinimal() {
        when(appointmentRepository.findByHospital_IdAndAppointmentDate(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of());
        when(admissionRepository.countActiveAdmissionsByHospital(HOSPITAL_ID)).thenReturn(0L);
        when(admissionRepository.countAdmissionsByHospitalAndDateRange(eq(HOSPITAL_ID), any(), any())).thenReturn(0L);
        when(admissionRepository.findByHospitalIdAndStatusOrderByAdmissionDateTimeDesc(eq(HOSPITAL_ID), any()))
            .thenReturn(List.of());
        when(admissionRepository.findAwaitingDischarge(HOSPITAL_ID)).thenReturn(List.of());
        when(consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(eq(HOSPITAL_ID), any()))
            .thenReturn(List.of());
        when(consultationRepository.findOverdueConsultations(any(), anyList())).thenReturn(List.of());
        when(staffRepository.findByHospitalIdAndActiveTrueExcludingDeletedUsers(eq(HOSPITAL_ID), any()))
            .thenReturn(new PageImpl<>(List.of()));
        when(staffAvailabilityRepository.findAll()).thenReturn(List.of());
        when(billingInvoiceRepository.findOverdue(any(LocalDate.class), anyList())).thenReturn(List.of());
        when(billingInvoiceRepository.findByHospital_Id(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(auditEventLogRepository.findByAssignment_Hospital_IdOrderByEventTimestampDesc(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));
        when(staffShiftRepository.findByHospital_IdAndShiftDate(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of());
        when(consultationRepository.findByHospitalAndStatuses(eq(HOSPITAL_ID), anyList())).thenReturn(List.of());
        when(auditEventLogRepository.countDailyByHospital(eq(HOSPITAL_ID), any(LocalDateTime.class)))
            .thenReturn(List.of());
        when(hospitalPlatformServiceLinkRepository.findByHospitalId(HOSPITAL_ID)).thenReturn(List.of());
        when(staffRepository.findByHospitalIdAndLicenseExpiringBefore(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of());
        when(staffAvailabilityRepository.findOnLeaveByHospitalAndDate(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of());
        when(staffAvailabilityRepository.findOnLeaveByHospitalAndDateRange(eq(HOSPITAL_ID), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of());
        when(paymentTransactionRepository.dailyCollectionsByHospital(eq(HOSPITAL_ID), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of());
        when(paymentTransactionRepository.methodBreakdownByHospital(eq(HOSPITAL_ID), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of());
        when(bedRepository.countByHospitalGroupByStatus(HOSPITAL_ID)).thenReturn(List.of());
        when(bedRepository.countByHospitalGroupByWardAndStatus(HOSPITAL_ID)).thenReturn(List.of());
    }

    // ── Tests ───────────────────────────────────────────────────

    @Test
    void getSummary_withEmptyData_returnsZeros() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 10);

        assertThat(result).isNotNull();
        assertThat(result.getHospitalId()).isEqualTo(HOSPITAL_ID);
        assertThat(result.getAsOfDate()).isEqualTo(TODAY);
        assertThat(result.getAppointments().getToday()).isZero();
        assertThat(result.getAdmissions().getActive()).isZero();
        assertThat(result.getConsultations().getRequested()).isZero();
        assertThat(result.getStaffing().getActiveStaff()).isZero();
        assertThat(result.getBilling().getOverdueInvoices()).isZero();
        assertThat(result.getRecentAuditEvents()).isEmpty();
        assertThat(result.getStaffingByDepartment()).isEmpty();
        assertThat(result.getConsultBacklog()).isEmpty();
        assertThat(result.getAuditTrend()).isEmpty();
        assertThat(result.getInvoiceAging()).isNotNull();
        assertThat(result.getInvoiceAging().getGrandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getIntegrations()).isEmpty();
        assertThat(result.getPaymentCollectionRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getPaymentTrend()).isEmpty();
        assertThat(result.getPaymentMethodBreakdown()).isEmpty();
        assertThat(result.getWriteOffs()).isNotNull();
        assertThat(result.getWriteOffs().getCancelledCount()).isZero();
        assertThat(result.getBedOccupancy()).isNotNull();
        assertThat(result.getBedOccupancy().getTotalBeds()).isZero();
        assertThat(result.getBedOccupancy().getOccupancyRate()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getWardOccupancy()).isEmpty();
    }

    @Test
    void getSummary_appointmentMetrics_computed() {
        stubMinimal();
        when(appointmentRepository.findByHospital_IdAndAppointmentDate(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of(
                appointment(AppointmentStatus.COMPLETED),
                appointment(AppointmentStatus.COMPLETED),
                appointment(AppointmentStatus.NO_SHOW),
                appointment(AppointmentStatus.CANCELLED),
                appointment(AppointmentStatus.PENDING),
                appointment(AppointmentStatus.SCHEDULED),
                appointment(AppointmentStatus.IN_PROGRESS)
            ));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 10);

        assertThat(result.getAppointments().getToday()).isEqualTo(7);
        assertThat(result.getAppointments().getCompleted()).isEqualTo(2);
        assertThat(result.getAppointments().getNoShows()).isEqualTo(1);
        assertThat(result.getAppointments().getCancelled()).isEqualTo(1);
        assertThat(result.getAppointments().getPending()).isEqualTo(2);
        assertThat(result.getAppointments().getInProgress()).isEqualTo(1);
    }

    @Test
    void getSummary_billingMetrics_overdueAndBalance() {
        stubMinimal();
        var overdueInv = invoice(InvoiceStatus.SENT, BigDecimal.valueOf(1000), BigDecimal.valueOf(200), TODAY.minusDays(5));
        when(billingInvoiceRepository.findOverdue(any(LocalDate.class), anyList()))
            .thenReturn(List.of(overdueInv));
        when(billingInvoiceRepository.findByHospital_Id(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(
                invoice(InvoiceStatus.SENT, BigDecimal.valueOf(1000), BigDecimal.valueOf(200), TODAY.minusDays(5)),
                invoice(InvoiceStatus.PAID, BigDecimal.valueOf(500), BigDecimal.valueOf(500), TODAY.minusDays(10))
            )));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getBilling().getOverdueInvoices()).isEqualTo(1);
        assertThat(result.getBilling().getOpenBalanceTotal()).isEqualByComparingTo(BigDecimal.valueOf(800));
    }

    @Test
    void getSummary_auditSnippets_returned() {
        stubMinimal();
        Page<AuditEventLog> page = new PageImpl<>(List.of(auditEvent(), auditEvent()));
        when(auditEventLogRepository.findByAssignment_Hospital_IdOrderByEventTimestampDesc(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(page);

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getRecentAuditEvents()).hasSize(2);
        assertThat(result.getRecentAuditEvents().get(0).getEventType()).isEqualTo("USER_CREATE");
        assertThat(result.getRecentAuditEvents().get(0).getUserName()).isEqualTo("admin");
    }

    @Test
    void getSummary_staffingByDepartment_groupedCorrectly() {
        stubMinimal();

        Department cardiology = new Department();
        cardiology.setId(UUID.randomUUID());
        cardiology.setName("Cardiology");
        Department emergency = new Department();
        emergency.setId(UUID.randomUUID());
        emergency.setName("Emergency");

        StaffShift s1 = StaffShift.builder().hospital(hospital()).department(cardiology).shiftDate(TODAY).status(StaffShiftStatus.SCHEDULED).build();
        s1.setId(UUID.randomUUID());
        StaffShift s2 = StaffShift.builder().hospital(hospital()).department(cardiology).shiftDate(TODAY).status(StaffShiftStatus.SCHEDULED).build();
        s2.setId(UUID.randomUUID());
        StaffShift s3 = StaffShift.builder().hospital(hospital()).department(cardiology).shiftDate(TODAY).status(StaffShiftStatus.CANCELLED).build();
        s3.setId(UUID.randomUUID());
        StaffShift s4 = StaffShift.builder().hospital(hospital()).department(emergency).shiftDate(TODAY).status(StaffShiftStatus.SCHEDULED).build();
        s4.setId(UUID.randomUUID());

        when(staffShiftRepository.findByHospital_IdAndShiftDate(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of(s1, s2, s3, s4));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getStaffingByDepartment()).hasSize(2);
    }

    @Test
    void getSummary_consultBacklog_withOverdue() {
        stubMinimal();
        var overdueConsult = consultation(ConsultationStatus.REQUESTED, ConsultationUrgency.URGENT,
            LocalDateTime.now().minusHours(1));
        var onTimeConsult = consultation(ConsultationStatus.ACKNOWLEDGED, ConsultationUrgency.ROUTINE,
            LocalDateTime.now().plusHours(5));
        when(consultationRepository.findByHospitalAndStatuses(eq(HOSPITAL_ID), anyList()))
            .thenReturn(List.of(overdueConsult, onTimeConsult));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getConsultBacklog()).hasSize(2);
        long overdueCount = result.getConsultBacklog().stream().filter(HospitalAdminSummaryDTO.ConsultBacklogItem::isOverdue).count();
        assertThat(overdueCount).isEqualTo(1);
    }

    @Test
    void getSummary_auditTrend_mapped() {
        stubMinimal();
        java.sql.Date sqlDate = java.sql.Date.valueOf(TODAY.minusDays(1));
        List<Object[]> rows = java.util.Collections.singletonList(new Object[]{ sqlDate, 15L });
        when(auditEventLogRepository.countDailyByHospital(eq(HOSPITAL_ID), any(LocalDateTime.class)))
            .thenReturn(rows);

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getAuditTrend()).hasSize(1);
        assertThat(result.getAuditTrend().get(0).getCount()).isEqualTo(15);
        assertThat(result.getAuditTrend().get(0).getDate()).isEqualTo(TODAY.minusDays(1));
    }

    // ── MVP 18 Tests ────────────────────────────────────────────

    @Test
    void getSummary_invoiceAging_bucketsCategorized() {
        stubMinimal();
        var current = invoice(InvoiceStatus.SENT, BigDecimal.valueOf(500), BigDecimal.ZERO, TODAY.plusDays(10));
        var days15 = invoice(InvoiceStatus.SENT, BigDecimal.valueOf(300), BigDecimal.valueOf(100), TODAY.minusDays(15));
        var days45 = invoice(InvoiceStatus.PARTIALLY_PAID, BigDecimal.valueOf(800), BigDecimal.valueOf(200), TODAY.minusDays(45));
        var days75 = invoice(InvoiceStatus.SENT, BigDecimal.valueOf(1200), BigDecimal.ZERO, TODAY.minusDays(75));
        var days100 = invoice(InvoiceStatus.SENT, BigDecimal.valueOf(2000), BigDecimal.valueOf(500), TODAY.minusDays(100));
        var paidInv = invoice(InvoiceStatus.PAID, BigDecimal.valueOf(600), BigDecimal.valueOf(600), TODAY.minusDays(20));

        when(billingInvoiceRepository.findByHospital_Id(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(current, days15, days45, days75, days100, paidInv)));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        var aging = result.getInvoiceAging();
        assertThat(aging).isNotNull();
        assertThat(aging.getCurrent().getCount()).isEqualTo(1);
        assertThat(aging.getCurrent().getTotal()).isEqualByComparingTo(BigDecimal.valueOf(500));
        assertThat(aging.getDays1to30().getCount()).isEqualTo(1);
        assertThat(aging.getDays1to30().getTotal()).isEqualByComparingTo(BigDecimal.valueOf(200));
        assertThat(aging.getDays31to60().getCount()).isEqualTo(1);
        assertThat(aging.getDays31to60().getTotal()).isEqualByComparingTo(BigDecimal.valueOf(600));
        assertThat(aging.getDays61to90().getCount()).isEqualTo(1);
        assertThat(aging.getDays61to90().getTotal()).isEqualByComparingTo(BigDecimal.valueOf(1200));
        assertThat(aging.getOver90().getCount()).isEqualTo(1);
        assertThat(aging.getOver90().getTotal()).isEqualByComparingTo(BigDecimal.valueOf(1500));

        BigDecimal expectedGrand = BigDecimal.valueOf(500 + 200 + 600 + 1200 + 1500);
        assertThat(aging.getGrandTotal()).isEqualByComparingTo(expectedGrand);
    }

    @Test
    void getSummary_paymentCollectionRate_computed() {
        stubMinimal();
        when(billingInvoiceRepository.findByHospital_Id(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(
                invoice(InvoiceStatus.PAID, BigDecimal.valueOf(1000), BigDecimal.valueOf(1000), TODAY.minusDays(30)),
                invoice(InvoiceStatus.SENT, BigDecimal.valueOf(1000), BigDecimal.valueOf(500), TODAY.minusDays(10))
            )));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        // 1500 collected out of 2000 = 75.0%
        assertThat(result.getPaymentCollectionRate()).isEqualByComparingTo(BigDecimal.valueOf(75.0));
    }

    @Test
    void getSummary_paymentCollectionRate_zeroInvoices() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getPaymentCollectionRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getSummary_integrationStatus_mapped() {
        stubMinimal();
        OrganizationPlatformService orgSvc = OrganizationPlatformService.builder()
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.ACTIVE)
            .provider("Epic Systems")
            .baseUrl("https://ehr.example.com")
            .build();

        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital())
            .organizationService(orgSvc)
            .enabled(true)
            .overrideEndpoint(null)
            .build();

        when(hospitalPlatformServiceLinkRepository.findByHospitalId(HOSPITAL_ID))
            .thenReturn(List.of(link));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getIntegrations()).hasSize(1);
        var row = result.getIntegrations().get(0);
        assertThat(row.getServiceType()).isEqualTo("EHR");
        assertThat(row.getProvider()).isEqualTo("Epic Systems");
        assertThat(row.getStatus()).isEqualTo("ACTIVE");
        assertThat(row.isEnabled()).isTrue();
        assertThat(row.getBaseUrl()).isEqualTo("https://ehr.example.com");
    }

    @Test
    void getSummary_integrationStatus_usesOverrideEndpoint() {
        stubMinimal();
        OrganizationPlatformService orgSvc = OrganizationPlatformService.builder()
            .serviceType(PlatformServiceType.BILLING)
            .status(PlatformServiceStatus.PILOT)
            .provider("StripeHealth")
            .baseUrl("https://billing.default.com")
            .build();

        HospitalPlatformServiceLink link = HospitalPlatformServiceLink.builder()
            .hospital(hospital())
            .organizationService(orgSvc)
            .enabled(false)
            .overrideEndpoint("https://billing.custom.com")
            .build();

        when(hospitalPlatformServiceLinkRepository.findByHospitalId(HOSPITAL_ID))
            .thenReturn(List.of(link));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        var row = result.getIntegrations().get(0);
        assertThat(row.getBaseUrl()).isEqualTo("https://billing.custom.com");
        assertThat(row.isEnabled()).isFalse();
        assertThat(row.getStatus()).isEqualTo("PILOT");
    }

    @Test
    void getSummary_invoiceAging_noOpenInvoices() {
        stubMinimal();
        when(billingInvoiceRepository.findByHospital_Id(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(
                invoice(InvoiceStatus.PAID, BigDecimal.valueOf(500), BigDecimal.valueOf(500), TODAY.minusDays(10)),
                invoice(InvoiceStatus.CANCELLED, BigDecimal.valueOf(300), BigDecimal.ZERO, TODAY.minusDays(5))
            )));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        var aging = result.getInvoiceAging();
        assertThat(aging.getCurrent().getCount()).isZero();
        assertThat(aging.getDays1to30().getCount()).isZero();
        assertThat(aging.getGrandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getSummary_multipleIntegrations_allReturned() {
        stubMinimal();
        OrganizationPlatformService ehrSvc = OrganizationPlatformService.builder()
            .serviceType(PlatformServiceType.EHR).status(PlatformServiceStatus.ACTIVE)
            .provider("Epic").baseUrl("https://ehr.example.com").build();
        OrganizationPlatformService limsSvc = OrganizationPlatformService.builder()
            .serviceType(PlatformServiceType.LIMS).status(PlatformServiceStatus.PENDING)
            .provider("LabCorp").baseUrl("https://lims.example.com").build();

        when(hospitalPlatformServiceLinkRepository.findByHospitalId(HOSPITAL_ID))
            .thenReturn(List.of(
                HospitalPlatformServiceLink.builder().hospital(hospital())
                    .organizationService(ehrSvc).enabled(true).build(),
                HospitalPlatformServiceLink.builder().hospital(hospital())
                    .organizationService(limsSvc).enabled(false).build()
            ));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getIntegrations()).hasSize(2);
        assertThat(result.getIntegrations()).extracting("serviceType").containsExactlyInAnyOrder("EHR", "LIMS");
    }

    @Test
    void getSummary_staffAvailability_onShiftFiltered() {
        stubMinimal();
        Hospital h = hospital();
        Hospital otherH = new Hospital();
        otherH.setId(UUID.randomUUID());
        StaffAvailability onShift = StaffAvailability.builder()
            .hospital(h).date(TODAY).dayOff(false).active(true).build();
        StaffAvailability dayOff = StaffAvailability.builder()
            .hospital(h).date(TODAY).dayOff(true).active(true).build();
        StaffAvailability inactive = StaffAvailability.builder()
            .hospital(h).date(TODAY).dayOff(false).active(false).build();
        StaffAvailability otherHospital = StaffAvailability.builder()
            .hospital(otherH).date(TODAY).dayOff(false).active(true).build();

        when(staffAvailabilityRepository.findAll())
            .thenReturn(List.of(onShift, dayOff, inactive, otherHospital));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getStaffing().getOnShiftToday()).isEqualTo(1);
    }

    @Test
    void getSummary_consultationMetrics_computed() {
        stubMinimal();
        when(consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(HOSPITAL_ID, ConsultationStatus.REQUESTED))
            .thenReturn(List.of(consultation(ConsultationStatus.REQUESTED, ConsultationUrgency.URGENT, null)));
        when(consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(HOSPITAL_ID, ConsultationStatus.ACKNOWLEDGED))
            .thenReturn(List.of(consultation(ConsultationStatus.ACKNOWLEDGED, ConsultationUrgency.ROUTINE, null)));
        when(consultationRepository.findByHospital_IdAndStatusOrderByRequestedAtDesc(HOSPITAL_ID, ConsultationStatus.IN_PROGRESS))
            .thenReturn(List.of());

        Consultation overdueC = consultation(ConsultationStatus.REQUESTED, ConsultationUrgency.EMERGENCY, LocalDateTime.now().minusHours(2));
        when(consultationRepository.findOverdueConsultations(any(), anyList()))
            .thenReturn(List.of(overdueC));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getConsultations().getRequested()).isEqualTo(1);
        assertThat(result.getConsultations().getAcknowledged()).isEqualTo(1);
        assertThat(result.getConsultations().getInProgress()).isZero();
        assertThat(result.getConsultations().getOverdue()).isEqualTo(1);
    }

    // ── MVP 19: License expiry alerts ───────────────────────────

    private Staff staffWithLicense(String firstName, String lastName, String license, LocalDate expiryDate) {
        User u = User.builder().firstName(firstName).lastName(lastName).build();
        u.setId(UUID.randomUUID());
        Department dept = new Department();
        dept.setId(UUID.randomUUID());
        dept.setName("Cardiology");
        Staff s = new Staff();
        s.setId(UUID.randomUUID());
        s.setUser(u);
        s.setHospital(hospital());
        s.setDepartment(dept);
        s.setJobTitle(JobTitle.DOCTOR);
        s.setLicenseNumber(license);
        s.setLicenseExpiryDate(expiryDate);
        s.setActive(true);
        return s;
    }

    @Test
    void getSummary_licenseAlerts_expiredCriticalWarning() {
        stubMinimal();
        Staff expired = staffWithLicense("Expired", "Doc", "LIC-001", TODAY.minusDays(10));
        Staff critical = staffWithLicense("Critical", "Doc", "LIC-002", TODAY.plusDays(15));
        Staff warning = staffWithLicense("Warning", "Doc", "LIC-003", TODAY.plusDays(60));

        when(staffRepository.findByHospitalIdAndLicenseExpiringBefore(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of(expired, critical, warning));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getLicenseAlerts()).hasSize(3);
        // sorted by daysUntilExpiry ascending (expired first)
        assertThat(result.getLicenseAlerts().get(0).getSeverity()).isEqualTo("EXPIRED");
        assertThat(result.getLicenseAlerts().get(0).getDaysUntilExpiry()).isEqualTo(-10);
        assertThat(result.getLicenseAlerts().get(1).getSeverity()).isEqualTo("CRITICAL");
        assertThat(result.getLicenseAlerts().get(1).getDaysUntilExpiry()).isEqualTo(15);
        assertThat(result.getLicenseAlerts().get(2).getSeverity()).isEqualTo("WARNING");
        assertThat(result.getLicenseAlerts().get(2).getDaysUntilExpiry()).isEqualTo(60);
    }

    @Test
    void getSummary_licenseAlerts_emptyWhenNoExpiring() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getLicenseAlerts()).isEmpty();
    }

    @Test
    void getSummary_licenseAlerts_fieldsMapped() {
        stubMinimal();
        Staff s = staffWithLicense("Jane", "Smith", "MED-999", TODAY.plusDays(20));
        when(staffRepository.findByHospitalIdAndLicenseExpiringBefore(eq(HOSPITAL_ID), any(LocalDate.class)))
            .thenReturn(List.of(s));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        var alert = result.getLicenseAlerts().get(0);
        assertThat(alert.getStaffName()).isEqualTo("Jane Smith");
        assertThat(alert.getJobTitle()).isEqualTo("DOCTOR");
        assertThat(alert.getDepartmentName()).isEqualTo("Cardiology");
        assertThat(alert.getLicenseNumber()).isEqualTo("MED-999");
        assertThat(alert.getLicenseExpiryDate()).isEqualTo(TODAY.plusDays(20));
        assertThat(alert.getSeverity()).isEqualTo("CRITICAL");
    }

    // ── MVP 19: Leave metrics ───────────────────────────────────

    @Test
    void getSummary_leaveMetrics_onLeaveToday() {
        stubMinimal();
        StaffAvailability onLeave = StaffAvailability.builder()
            .hospital(hospital()).date(TODAY).dayOff(true).active(true).build();
        when(staffAvailabilityRepository.findOnLeaveByHospitalAndDate(HOSPITAL_ID, TODAY))
            .thenReturn(List.of(onLeave));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getLeave()).isNotNull();
        assertThat(result.getLeave().getOnLeaveToday()).isEqualTo(1);
        assertThat(result.getStaffing().getStaffOnLeaveToday()).isEqualTo(1);
    }

    @Test
    void getSummary_leaveMetrics_upcomingLeave() {
        stubMinimal();
        StaffAvailability upcoming1 = StaffAvailability.builder()
            .hospital(hospital()).date(TODAY.plusDays(2)).dayOff(true).active(true).build();
        StaffAvailability upcoming2 = StaffAvailability.builder()
            .hospital(hospital()).date(TODAY.plusDays(5)).dayOff(true).active(true).build();
        when(staffAvailabilityRepository.findOnLeaveByHospitalAndDateRange(
            HOSPITAL_ID, TODAY.plusDays(1), TODAY.plusDays(7)))
            .thenReturn(List.of(upcoming1, upcoming2));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getLeave()).isNotNull();
        assertThat(result.getLeave().getUpcomingLeaveNext7Days()).isEqualTo(2);
        assertThat(result.getStaffing().getUpcomingLeave()).isEqualTo(2);
    }

    @Test
    void getSummary_leaveMetrics_emptyWhenNoLeave() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getLeave()).isNotNull();
        assertThat(result.getLeave().getOnLeaveToday()).isZero();
        assertThat(result.getLeave().getUpcomingLeaveNext7Days()).isZero();
    }

    // ── MVP 20: Payment trend ───────────────────────────────────

    @Test
    void getSummary_paymentTrend_dailyCollections() {
        stubMinimal();
        when(paymentTransactionRepository.dailyCollectionsByHospital(eq(HOSPITAL_ID), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of(
                new Object[]{java.sql.Date.valueOf(TODAY.minusDays(2)), BigDecimal.valueOf(1500)},
                new Object[]{java.sql.Date.valueOf(TODAY.minusDays(1)), BigDecimal.valueOf(2300)},
                new Object[]{java.sql.Date.valueOf(TODAY), BigDecimal.valueOf(800)}
            ));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getPaymentTrend()).hasSize(3);
        assertThat(result.getPaymentTrend().get(0).getDate()).isEqualTo(TODAY.minusDays(2));
        assertThat(result.getPaymentTrend().get(0).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(1500));
        assertThat(result.getPaymentTrend().get(2).getDate()).isEqualTo(TODAY);
        assertThat(result.getPaymentTrend().get(2).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(800));
    }

    @Test
    void getSummary_paymentTrend_emptyWhenNoTransactions() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getPaymentTrend()).isEmpty();
    }

    @Test
    void getSummary_paymentMethodBreakdown_grouped() {
        stubMinimal();
        when(paymentTransactionRepository.methodBreakdownByHospital(eq(HOSPITAL_ID), any(LocalDate.class), any(LocalDate.class)))
            .thenReturn(List.of(
                new Object[]{PaymentMethod.INSURANCE, 5L, BigDecimal.valueOf(12000)},
                new Object[]{PaymentMethod.CASH, 3L, BigDecimal.valueOf(450)},
                new Object[]{PaymentMethod.CREDIT_CARD, 2L, BigDecimal.valueOf(1200)}
            ));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getPaymentMethodBreakdown()).hasSize(3);
        assertThat(result.getPaymentMethodBreakdown().get(0).getMethod()).isEqualTo("INSURANCE");
        assertThat(result.getPaymentMethodBreakdown().get(0).getCount()).isEqualTo(5);
        assertThat(result.getPaymentMethodBreakdown().get(0).getTotal()).isEqualByComparingTo(BigDecimal.valueOf(12000));
        assertThat(result.getPaymentMethodBreakdown().get(1).getMethod()).isEqualTo("CASH");
    }

    @Test
    void getSummary_paymentMethodBreakdown_emptyWhenNoTransactions() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getPaymentMethodBreakdown()).isEmpty();
    }

    @Test
    void getSummary_writeOffs_cancelledInvoicesCounted() {
        stubMinimal();
        when(billingInvoiceRepository.findByHospital_Id(eq(HOSPITAL_ID), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(
                invoice(InvoiceStatus.CANCELLED, BigDecimal.valueOf(500), BigDecimal.valueOf(100), TODAY.minusDays(10)),
                invoice(InvoiceStatus.CANCELLED, BigDecimal.valueOf(300), BigDecimal.ZERO, TODAY.minusDays(5)),
                invoice(InvoiceStatus.SENT, BigDecimal.valueOf(1000), BigDecimal.valueOf(200), TODAY.plusDays(10))
            )));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getWriteOffs()).isNotNull();
        assertThat(result.getWriteOffs().getCancelledCount()).isEqualTo(2);
        // (500-100) + (300-0) = 400 + 300 = 700
        assertThat(result.getWriteOffs().getCancelledTotal()).isEqualByComparingTo(BigDecimal.valueOf(700));
    }

    @Test
    void getSummary_writeOffs_zeroWhenNoCancelled() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getWriteOffs()).isNotNull();
        assertThat(result.getWriteOffs().getCancelledCount()).isZero();
        assertThat(result.getWriteOffs().getCancelledTotal()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ── MVP 21: Bed occupancy ───────────────────────────────────

    @Test
    void getSummary_bedOccupancy_computedFromStatuses() {
        stubMinimal();
        when(bedRepository.countByHospitalGroupByStatus(HOSPITAL_ID))
            .thenReturn(List.of(
                new Object[]{BedStatus.AVAILABLE, 10L},
                new Object[]{BedStatus.OCCUPIED, 8L},
                new Object[]{BedStatus.RESERVED, 2L},
                new Object[]{BedStatus.MAINTENANCE, 1L},
                new Object[]{BedStatus.OUT_OF_SERVICE, 1L}
            ));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getBedOccupancy()).isNotNull();
        assertThat(result.getBedOccupancy().getTotalBeds()).isEqualTo(22);
        assertThat(result.getBedOccupancy().getOccupiedBeds()).isEqualTo(8);
        assertThat(result.getBedOccupancy().getAvailableBeds()).isEqualTo(10);
        assertThat(result.getBedOccupancy().getReservedBeds()).isEqualTo(2);
        assertThat(result.getBedOccupancy().getMaintenanceBeds()).isEqualTo(2); // MAINTENANCE + OUT_OF_SERVICE
        // occupancyRate = 8/22 * 100 = 36.4%
        assertThat(result.getBedOccupancy().getOccupancyRate()).isEqualByComparingTo(new BigDecimal("36.4"));
    }

    @Test
    void getSummary_bedOccupancy_emptyWhenNoBeds() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getBedOccupancy()).isNotNull();
        assertThat(result.getBedOccupancy().getTotalBeds()).isZero();
        assertThat(result.getBedOccupancy().getOccupancyRate()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void getSummary_wardOccupancy_groupedByWard() {
        stubMinimal();
        UUID ward1 = UUID.randomUUID();
        UUID ward2 = UUID.randomUUID();
        when(bedRepository.countByHospitalGroupByWardAndStatus(HOSPITAL_ID))
            .thenReturn(List.of(
                new Object[]{ward1, "ICU Ward", WardType.ICU, BedStatus.OCCUPIED, 5L},
                new Object[]{ward1, "ICU Ward", WardType.ICU, BedStatus.AVAILABLE, 3L},
                new Object[]{ward2, "General A", WardType.GENERAL, BedStatus.OCCUPIED, 10L},
                new Object[]{ward2, "General A", WardType.GENERAL, BedStatus.AVAILABLE, 15L},
                new Object[]{ward2, "General A", WardType.GENERAL, BedStatus.MAINTENANCE, 1L}
            ));

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getWardOccupancy()).hasSize(2);
        var icu = result.getWardOccupancy().get(0);
        assertThat(icu.getWardName()).isEqualTo("ICU Ward");
        assertThat(icu.getWardType()).isEqualTo("ICU");
        assertThat(icu.getTotalBeds()).isEqualTo(8);
        assertThat(icu.getOccupiedBeds()).isEqualTo(5);
        assertThat(icu.getAvailableBeds()).isEqualTo(3);
        // 5/8*100 = 62.5
        assertThat(icu.getOccupancyRate()).isEqualByComparingTo(new BigDecimal("62.5"));

        var general = result.getWardOccupancy().get(1);
        assertThat(general.getWardName()).isEqualTo("General A");
        assertThat(general.getTotalBeds()).isEqualTo(26);
        assertThat(general.getOccupiedBeds()).isEqualTo(10);
        assertThat(general.getAvailableBeds()).isEqualTo(15);
    }

    @Test
    void getSummary_wardOccupancy_emptyWhenNoBeds() {
        stubMinimal();

        HospitalAdminSummaryDTO result = service.getSummary(HOSPITAL_ID, TODAY, 5);

        assertThat(result.getWardOccupancy()).isEmpty();
    }
}
