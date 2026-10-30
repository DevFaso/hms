package com.example.hms.service.billing;

import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.event.EncounterCheckedOutEvent;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.ProcedureOrderRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InvoiceNumberAllocator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D972 (rounds 16 and 17): a checked-out visit reached billing as nothing
 * but its copay. The checkout event now opens one DRAFT visit invoice with the
 * services, lab orders and procedures of the visit.
 */
class EncounterCheckoutChargeListenerTest {

    private final EncounterRepository encounters = mock(EncounterRepository.class);
    private final BillingInvoiceRepository invoices = mock(BillingInvoiceRepository.class);
    private final EncounterTreatmentRepository given = mock(EncounterTreatmentRepository.class);
    private final LabOrderRepository labOrders = mock(LabOrderRepository.class);
    private final ProcedureOrderRepository procedures = mock(ProcedureOrderRepository.class);
    private final TreatmentRepository treatments = mock(TreatmentRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final InvoiceNumberAllocator numbers = mock(InvoiceNumberAllocator.class);
    private final EncounterCheckoutChargeListener listener = new EncounterCheckoutChargeListener(
        encounters, invoices, given, labOrders, procedures, treatments, assignments, numbers);

    private final UUID hospitalId = UUID.randomUUID();
    private Encounter encounter;
    private UUID labOrderId;

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
        when(numbers.next(any(), any())).thenReturn("CHW-2026-000020");
        // As the cascade does on save: each saved line gets its id.
        when(invoices.save(any(BillingInvoice.class))).thenAnswer(inv -> {
            BillingInvoice saved = inv.getArgument(0);
            saved.getInvoiceItems().stream().filter(i -> i.getId() == null).forEach(i -> i.setId(UUID.randomUUID()));
            return saved;
        });

        LabTestDefinition tdr = new LabTestDefinition();
        tdr.setName("TDR paludisme");
        LabOrder order = new LabOrder();
        labOrderId = UUID.randomUUID();
        order.setId(labOrderId);
        order.setLabTestDefinition(tdr);
        when(labOrders.findAllById(List.of(labOrderId))).thenReturn(List.of(order));
        when(procedures.findAllById(List.of())).thenReturn(List.of());
        when(given.findByEncounter_Id(encounter.getId())).thenReturn(List.of());
    }

    private EncounterCheckedOutEvent event() {
        return new EncounterCheckedOutEvent(encounter.getId(), "ENC-20261029-9F86F7", encounter.getPatient().getId(),
            hospitalId, null, null, "OUTPATIENT", LocalDateTime.now(), List.of(UUID.randomUUID()),
            List.of(labOrderId), List.of());
    }

    private static Treatment priced(String name, String price) {
        Treatment t = new Treatment();
        t.setId(UUID.randomUUID());
        t.setName(name);
        t.setPrice(new BigDecimal(price));
        t.setActive(true);
        return t;
    }

    private static List<InvoiceItem> lines(BillingInvoice invoice) {
        return invoice.getInvoiceItems().stream().sorted(Comparator.comparing(InvoiceItem::getItemDescription)).toList();
    }

    @Test
    void aCheckedOutVisitOpensADraftWithItsConsultationAndLabOrders_D972() {
        when(treatments.findByHospital_Id(hospitalId)).thenReturn(List.of(
            priced("Consultation générale", "2000"), priced("TDR Paludisme", "1500")));

        BillingInvoice draft = listener.chargeVisit(event()).orElseThrow();

        assertThat(draft.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(draft.getEncounter()).isSameAs(encounter);
        assertThat(draft.isCheckInCopay()).isFalse();
        assertThat(lines(draft)).extracting(InvoiceItem::getItemDescription, InvoiceItem::getItemCategory, InvoiceItem::getUnitPrice)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("Consultation générale", ItemCategory.SERVICE, new BigDecimal("2000.00")),
                org.assertj.core.groups.Tuple.tuple("TDR paludisme", ItemCategory.LAB_TEST, new BigDecimal("1500.00")));
    }

    @Test
    void anUnpricedItemIsPostedAtZeroForTheCashierNeverGuessed_D972() {
        when(treatments.findByHospital_Id(hospitalId)).thenReturn(List.of());

        BillingInvoice draft = listener.chargeVisit(event()).orElseThrow();

        assertThat(lines(draft)).extracting(InvoiceItem::getUnitPrice)
            .containsOnly(new BigDecimal("0.00"));
        assertThat(lines(draft)).hasSize(2);
    }

    @Test
    void recordedServicesAreBilledAtTheirPriceAndReplaceTheConsultationLine_D972() {
        EncounterTreatment dressing = new EncounterTreatment();
        dressing.setTreatment(priced("Pansement", "750"));
        when(given.findByEncounter_Id(encounter.getId())).thenReturn(List.of(dressing));
        when(treatments.findByHospital_Id(hospitalId)).thenReturn(List.of(priced("Consultation", "2000")));

        BillingInvoice draft = listener.chargeVisit(event()).orElseThrow();

        assertThat(lines(draft)).extracting(InvoiceItem::getItemDescription).containsExactly("Pansement", "TDR paludisme");
    }

    @Test
    void medicinesAreNotOnTheVisitInvoiceTheyArePaidAtThePharmacy_D972() {
        when(treatments.findByHospital_Id(hospitalId)).thenReturn(List.of());

        BillingInvoice draft = listener.chargeVisit(event()).orElseThrow();

        assertThat(lines(draft)).extracting(InvoiceItem::getItemCategory).doesNotContain(ItemCategory.MEDICATION);
    }

    @Test
    void aVisitAlreadyBilledIsLeftAloneButItsCopayDoesNotCount_D972() {
        BillingInvoice copay = new BillingInvoice();
        copay.setCheckInCopay(true);
        when(invoices.findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.CANCELLED)).thenReturn(List.of(copay));
        when(treatments.findByHospital_Id(hospitalId)).thenReturn(List.of());
        assertThat(listener.chargeVisit(event())).isPresent();

        BillingInvoice visit = new BillingInvoice();
        when(invoices.findByEncounter_IdAndStatusNot(encounter.getId(), InvoiceStatus.CANCELLED))
            .thenReturn(List.of(copay, visit));
        assertThat(listener.chargeVisit(event())).isEmpty();
    }

    @Test
    void aFailedChargeNeverEscapesTheListener_D972() {
        when(treatments.findByHospital_Id(hospitalId)).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.onCheckedOut(event())).doesNotThrowAnyException();
        verify(invoices, never()).save(any());
    }
}
