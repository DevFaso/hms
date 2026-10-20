package com.example.hms.service;

import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.ItemCategory;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Hospital;
import com.example.hms.model.InvoiceItem;
import com.example.hms.model.Patient;
import com.example.hms.model.Treatment;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** QA D516: a recorded delivery posts a draft charge for the mother. */
@ExtendWith(MockitoExtension.class)
class DeliveryChargeServiceTest {

    @Mock private BillingInvoiceRepository invoiceRepository;
    @Mock private TreatmentRepository treatmentRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private InvoiceNumberAllocator invoiceNumberAllocator;
    @InjectMocks private DeliveryChargeService service;

    private final UUID recorderId = UUID.randomUUID();
    private Hospital hospital;
    private DeliveryRecord delivery;
    private UserRoleHospitalAssignment assignment;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient mother = new Patient();
        mother.setId(UUID.randomUUID());
        delivery = DeliveryRecord.builder().hospital(hospital).patient(mother)
            .deliveryMode(DeliveryMode.SPONTANEOUS_VAGINAL).build();
        assignment = new UserRoleHospitalAssignment();
        assignment.setHospital(hospital);
    }

    private Treatment service(String name, String price) {
        Treatment t = new Treatment();
        t.setName(name);
        t.setPrice(new BigDecimal(price));
        t.setHospital(hospital);
        t.setActive(true);
        return t;
    }

    private void recorderWorksHere() {
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(recorderId, hospital.getId()))
            .thenReturn(Optional.of(assignment));
        when(invoiceNumberAllocator.next(any(), any())).thenReturn("GH-2026-000009");
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void vaginalDelivery_isChargedAtTheHospitalsDeliveryPrice() {
        recorderWorksHere();
        when(treatmentRepository.findByHospital_Id(hospital.getId())).thenReturn(List.of(
            service("Accouchement voie basse", "25000"), service("Césarienne", "150000"), service("Consultation", "2000")));

        BillingInvoice invoice = service.chargeDelivery(delivery, recorderId).orElseThrow();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.getPatient()).isSameAs(delivery.getPatient());
        assertThat(invoice.getHospital()).isSameAs(hospital);
        assertThat(invoice.getCreatedBy()).isEqualTo(recorderId);
        assertThat(invoice.getInvoiceItems()).hasSize(1);
        InvoiceItem line = invoice.getInvoiceItems().iterator().next();
        assertThat(line.getItemCategory()).isEqualTo(ItemCategory.SERVICE);
        assertThat(line.getUnitPrice()).isEqualByComparingTo("25000");
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("25000");
    }

    @Test
    void caesarean_isChargedAtTheCaesareanPrice() {
        recorderWorksHere();
        delivery.setDeliveryMode(DeliveryMode.CAESAREAN_EMERGENCY);
        when(treatmentRepository.findByHospital_Id(hospital.getId())).thenReturn(List.of(
            service("Accouchement voie basse", "25000"), service("Césarienne", "150000")));

        BillingInvoice invoice = service.chargeDelivery(delivery, recorderId).orElseThrow();

        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("150000");
    }

    @Test
    void noPricedService_stillPostsTheChargeForBillingToPrice() {
        recorderWorksHere();
        when(treatmentRepository.findByHospital_Id(hospital.getId())).thenReturn(List.of());

        BillingInvoice invoice = service.chargeDelivery(delivery, recorderId).orElseThrow();

        assertThat(invoice.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(invoice.getInvoiceItems()).hasSize(1);
        assertThat(invoice.getTotalAmount()).isEqualByComparingTo("0");
    }

    /** QA D516: a cross-hospital or super-admin recorder still bills the birth, under the billing desk. */
    @Test
    void recorderWithoutAnAssignmentThere_filesTheLineUnderTheBillingDesk() {
        UserRoleHospitalAssignment billingDesk = new UserRoleHospitalAssignment();
        billingDesk.setHospital(hospital);
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(recorderId, hospital.getId()))
            .thenReturn(Optional.empty());
        when(assignmentRepository.findFirstByHospital_IdAndRole_CodeInAndActiveTrueOrderByCreatedAtAsc(
            hospital.getId(), List.of("ROLE_BILLING_SPECIALIST", "ROLE_HOSPITAL_ADMIN")))
            .thenReturn(Optional.of(billingDesk));
        when(invoiceNumberAllocator.next(any(), any())).thenReturn("GH-2026-000010");
        when(invoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(treatmentRepository.findByHospital_Id(hospital.getId())).thenReturn(List.of());

        BillingInvoice invoice = service.chargeDelivery(delivery, recorderId).orElseThrow();

        assertThat(invoice.getCreatedBy()).isEqualTo(recorderId);
        assertThat(invoice.getInvoiceItems().iterator().next().getAssignment()).isSameAs(billingDesk);
    }

    @Test
    void nobodyAtTheHospitalCanOwnTheLine_postsNothing() {
        when(assignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue(recorderId, hospital.getId()))
            .thenReturn(Optional.empty());
        when(assignmentRepository.findFirstByHospital_IdAndRole_CodeInAndActiveTrueOrderByCreatedAtAsc(
            any(), any())).thenReturn(Optional.empty());

        assertThat(service.chargeDelivery(delivery, recorderId)).isEmpty();
        verify(invoiceRepository, never()).save(any());
    }
}
