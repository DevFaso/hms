package com.example.hms.service.billing;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.InvoiceLinePriceSource;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.event.EncounterCheckedOutEvent;
import com.example.hms.model.Admission;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PriceListItem;
import com.example.hms.model.PriceListPrice;
import com.example.hms.model.ProcedureOrder;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.BloodUnitRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.PriceListPriceRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InvoiceNumberAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 3 (plan section 3.3): a checked-out visit's lines are priced from the hospital's price list,
 * through the link each charge declares, at the version in force on the checkout day; the old service-name match
 * is the fallback, and an unmatched line is posted at 0 for the cashier. Every line says where its price came from.
 */
@DisplayName("Checkout: the price list prices the visit's lines")
class TariffAtCheckoutTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC);

    private final EncounterRepository encounters = mock(EncounterRepository.class);
    private final BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
    private final EncounterTreatmentRepository given = mock(EncounterTreatmentRepository.class);
    private final LabOrderRepository labOrders = mock(LabOrderRepository.class);
    private final ProcedureOrderRepository procedures = mock(ProcedureOrderRepository.class);
    private final ImagingOrderRepository imaging = mock(ImagingOrderRepository.class);
    private final TransfusionRequestRepository transfusions = mock(TransfusionRequestRepository.class);
    private final BloodUnitRepository units = mock(BloodUnitRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final TreatmentRepository treatments = mock(TreatmentRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final InvoiceNumberAllocator numbers = mock(InvoiceNumberAllocator.class);
    private final PriceListItemRepository items = mock(PriceListItemRepository.class);
    private final PriceListPriceRepository prices = mock(PriceListPriceRepository.class);
    private final com.example.hms.repository.LabTestDefinitionRepository labDefinitions =
        mock(com.example.hms.repository.LabTestDefinitionRepository.class);
    private final TariffResolver resolver = new TariffResolver(items, prices, CLOCK, "Africa/Ouagadougou");
    private final EncounterCheckoutChargeListener listener = new EncounterCheckoutChargeListener(
        encounters, invoices, treatments, assignments, numbers, resolver, List.of(
            new ConsultationChargeSource(given, admissions), new LabOrderChargeSource(labOrders, labDefinitions),
            new ImagingOrderChargeSource(imaging), new ProcedureOrderChargeSource(procedures),
            new TransfusionChargeSource(transfusions, units), new BedDaysChargeSource(admissions)));

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID tdrId = UUID.randomUUID();
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        when(encounters.findByIdAndHospital_Id(encounter.getId(), hospitalId)).thenReturn(Optional.of(encounter));
        when(invoices.findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.CANCELLED)).thenReturn(List.of());
        when(assignments.findFirstByHospital_IdAndRole_CodeInAndActiveTrueOrderByCreatedAtAsc(eq(hospitalId), anyList()))
            .thenReturn(Optional.of(new UserRoleHospitalAssignment()));
        when(numbers.next(any(), any())).thenReturn("TWP-2026-000001");
        when(invoices.save(any(BillingInvoice.class))).thenAnswer(inv -> {
            BillingInvoice saved = inv.getArgument(0);
            saved.getInvoiceItems().stream().filter(i -> i.getId() == null).forEach(i -> i.setId(UUID.randomUUID()));
            return saved;
        });
        when(given.findByEncounter_Id(encounter.getId())).thenReturn(List.of());
        when(treatments.findByHospital_Id(hospitalId)).thenReturn(List.of());

        // The visit: a TDR, a chest X-ray and a three-night stay; no recorded service, so one consultation.
        LabTestDefinition tdr = new LabTestDefinition();
        tdr.setId(tdrId);
        tdr.setName("TDR paludisme");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(tdr);
        when(labOrders.findIdsByEncounterIdAndStatusNotIn(eq(encounter.getId()), any())).thenReturn(List.of(order.getId()));
        when(labOrders.findAllById(List.of(order.getId()))).thenReturn(List.of(order));
        ImagingOrder chest = new ImagingOrder();
        chest.setModality(ImagingModality.XRAY);
        chest.setStudyType("Radio thorax");
        when(imaging.findByEncounterIdAndStatusNotIn(eq(encounter.getId()), any())).thenReturn(List.of(chest));
        UUID admissionId = UUID.randomUUID();
        encounter.setAdmissionId(admissionId);
        Admission stay = new Admission();
        stay.setHospital(hospital);
        stay.setAdmissionDateTime(LocalDateTime.of(2026, 11, 12, 10, 0));
        stay.setActualDischargeDateTime(LocalDateTime.of(2026, 11, 15, 8, 0));
        when(admissions.findById(admissionId)).thenReturn(Optional.of(stay));
    }

    private EncounterCheckedOutEvent event(LocalDateTime checkedOutAt) {
        return new EncounterCheckedOutEvent(encounter.getId(), "ENC-1", encounter.getPatient().getId(), hospitalId,
            null, null, "INPATIENT", checkedOutAt);
    }

    /** One item of {@code hospital}'s list on this link, with one version in force since {@code from}. */
    private PriceListPrice tariff(UUID hospital, PriceListLinkType type, String key, String label, String price,
                                  LocalDate from) {
        PriceListItem item = PriceListItem.builder().hospitalId(hospital).code(label.toUpperCase().replace(' ', '-'))
            .label(label).category(PriceListCategory.OTHER_SERVICE).linkType(type).linkKey(key).build();
        item.setId(UUID.randomUUID());
        PriceListPrice version = PriceListPrice.builder().hospitalId(hospital).item(item)
            .unitPrice(new BigDecimal(price)).effectiveFrom(from).build();
        version.setId(UUID.randomUUID());
        when(items.findActiveByLinkForCharge(hospital, type, key)).thenReturn(List.of(item));
        when(prices.findInForce(eq(item.getId()), eq(hospital), any())).thenAnswer(call -> {
            LocalDate day = call.getArgument(2);
            return version.coversDay(day) ? List.of(version) : List.of();
        });
        return version;
    }

    private void theTestClinicGrid(UUID hospital) {
        tariff(hospital, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT", "Consultation générale", "2000",
            TODAY.minusDays(30));
        tariff(hospital, PriceListLinkType.LAB_TEST_DEFINITION, tdrId.toString(), "TDR paludisme (grille)", "1500",
            TODAY.minusDays(30));
        tariff(hospital, PriceListLinkType.IMAGING_STUDY, "XRAY:radio thorax", "Radiographie du thorax", "7500",
            TODAY.minusDays(30));
        tariff(hospital, PriceListLinkType.SYSTEM, "BED_DAY", "Journée d'hospitalisation", "5000",
            TODAY.minusDays(30));
    }

    @Test
    @DisplayName("acceptance 1: consultation, TDR, chest X-ray and a 3-day stay are all priced from the list")
    void theVisitIsPricedFromTheList() {
        theTestClinicGrid(hospitalId);

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems())
            .extracting(InvoiceItem::getItemDescription, InvoiceItem::getQuantity, InvoiceItem::getUnitPrice,
                InvoiceItem::getPriceSource)
            .containsExactlyInAnyOrder(
                tuple("Consultation générale", 1, new BigDecimal("2000.00"), InvoiceLinePriceSource.TARIFF),
                tuple("TDR paludisme (grille)", 1, new BigDecimal("1500.00"), InvoiceLinePriceSource.TARIFF),
                tuple("Radiographie du thorax", 1, new BigDecimal("7500.00"), InvoiceLinePriceSource.TARIFF),
                tuple("Journée d'hospitalisation", 3, new BigDecimal("5000.00"), InvoiceLinePriceSource.TARIFF));
        assertThat(draft.getInvoiceItems()).allSatisfy(line -> {
            assertThat(line.getPriceListItemId()).isNotNull();
            assertThat(line.getPriceListPriceId()).isNotNull();
            // Plan 3.7: the hospital's label as typed, never a translated code.
            assertThat(line.getTextCode()).isNull();
            assertThat(line.getRelatedService()).isNull();
        });
        BigDecimal total = draft.getInvoiceItems().stream().map(InvoiceItem::getTotalPrice)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("26000"); // 2000 + 1500 + 7500 + 3 x 5000
    }

    @Test
    @DisplayName("no tariff and no service: UNPRICED at 0; a service of the same name: SERVICE_NAME")
    void theFallbacks() {
        Treatment xray = new Treatment();
        xray.setId(UUID.randomUUID());
        xray.setName("Radio thorax");
        xray.setPrice(new BigDecimal("6000"));
        xray.setActive(true);
        when(treatments.findByHospital_Id(hospitalId)).thenReturn(List.of(xray));

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems())
            .extracting(InvoiceItem::getUnitPrice, InvoiceItem::getPriceSource)
            .containsExactlyInAnyOrder(
                tuple(new BigDecimal("0.00"), InvoiceLinePriceSource.UNPRICED),
                tuple(new BigDecimal("0.00"), InvoiceLinePriceSource.UNPRICED),
                tuple(new BigDecimal("6000.00"), InvoiceLinePriceSource.SERVICE_NAME),
                tuple(new BigDecimal("0.00"), InvoiceLinePriceSource.UNPRICED));
        assertThat(draft.getInvoiceItems()).allSatisfy(line -> assertThat(line.getPriceListPriceId()).isNull());
    }

    @Test
    @DisplayName("acceptance: hospital B's tariff never prices hospital A's visit, even with the same link and key")
    void anotherHospitalsListNeverPricesTheVisit() {
        theTestClinicGrid(UUID.randomUUID());

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems()).extracting(InvoiceItem::getPriceSource)
            .containsOnly(InvoiceLinePriceSource.UNPRICED);
    }

    @Test
    @DisplayName("D-4: the price in force on the checkout day applies, never one that starts later")
    void theCheckoutDaysPriceApplies() {
        tariff(hospitalId, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT", "Consultation générale", "2500",
            TODAY.plusDays(1));

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems()).extracting(InvoiceItem::getPriceSource)
            .isNotEmpty()
            .doesNotContain(InvoiceLinePriceSource.TARIFF);
        // A visit checked out tomorrow takes it.
        when(invoices.findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.CANCELLED)).thenReturn(List.of());
        BillingInvoice tomorrow = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 16, 8, 30))).orElseThrow();
        assertThat(tomorrow.getInvoiceItems()).extracting(InvoiceItem::getUnitPrice, InvoiceItem::getPriceSource)
            .contains(tuple(new BigDecimal("2500.00"), InvoiceLinePriceSource.TARIFF));
        verify(items, org.mockito.Mockito.atLeastOnce())
            .findActiveByLinkForCharge(hospitalId, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT");
    }

    @Test
    @DisplayName("a procedure is priced by its code, looked up the way the list stored it (upper case)")
    void aProcedureByItsCode() {
        ProcedureOrder suture = new ProcedureOrder();
        suture.setId(UUID.randomUUID());
        suture.setProcedureName("Suture de plaie");
        suture.setProcedureCode(" sut-01 ");
        when(procedures.findIdsByEncounterIdAndStatusNotIn(eq(encounter.getId()), any())).thenReturn(List.of(suture.getId()));
        when(procedures.findAllById(List.of(suture.getId()))).thenReturn(List.of(suture));
        tariff(hospitalId, PriceListLinkType.PROCEDURE_CODE, "SUT-01", "Suture simple", "3000", TODAY);

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems()).extracting(InvoiceItem::getItemDescription, InvoiceItem::getPriceSource)
            .contains(tuple("Suture simple", InvoiceLinePriceSource.TARIFF));
    }

    @Test
    @DisplayName("QA D2700: the lab revises the test (a new version); the visit is still priced from the test's link")
    void aRevisedLabTestKeepsItsPrice() {
        theTestClinicGrid(hospitalId);
        // The lab's version 2 supersedes the version the price list links to; the doctor orders version 2.
        LabTestDefinition v1 = new LabTestDefinition();
        v1.setId(tdrId);
        v1.setName("TDR paludisme");
        LabTestDefinition v2 = new LabTestDefinition();
        v2.setId(UUID.randomUUID());
        v2.setName("TDR paludisme");
        v2.setSupersedesDefinitionId(tdrId);
        when(labDefinitions.findById(tdrId)).thenReturn(Optional.of(v1));
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(v2);
        when(labOrders.findIdsByEncounterIdAndStatusNotIn(eq(encounter.getId()), any())).thenReturn(List.of(order.getId()));
        when(labOrders.findAllById(List.of(order.getId()))).thenReturn(List.of(order));

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems())
            .extracting(InvoiceItem::getItemDescription, InvoiceItem::getUnitPrice, InvoiceItem::getPriceSource)
            .contains(tuple("TDR paludisme (grille)", new BigDecimal("1500.00"), InvoiceLinePriceSource.TARIFF));
    }

    @Test
    @DisplayName("QA D2701: a labour admission with no recorded service gets no automatic consultation line")
    void aLabourAdmissionHasNoDefaultConsultation() {
        theTestClinicGrid(hospitalId);
        admissions.findById(encounter.getAdmissionId()).orElseThrow()
            .setAdmissionType(com.example.hms.enums.AdmissionType.LABOR_DELIVERY);

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems()).extracting(InvoiceItem::getItemDescription)
            .doesNotContain("Consultation générale")
            .contains("Journée d'hospitalisation");
    }

    @Test
    @DisplayName("QA D2701: an emergency admission (seen by a doctor first) keeps the default consultation (E3)")
    void anEmergencyAdmissionKeepsTheDefaultConsultation() {
        theTestClinicGrid(hospitalId);
        admissions.findById(encounter.getAdmissionId()).orElseThrow()
            .setAdmissionType(com.example.hms.enums.AdmissionType.EMERGENCY);

        BillingInvoice draft = listener.chargeVisit(event(LocalDateTime.of(2026, 11, 15, 8, 30))).orElseThrow();

        assertThat(draft.getInvoiceItems()).extracting(InvoiceItem::getItemDescription)
            .contains("Consultation générale");
    }
}
