package com.example.hms.service.lab;

import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabSpecimen;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.service.InBasketService;
import com.example.hms.service.NotificationService;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** QA D962: the ordering clinician learns a specimen was rejected, and why. */
@ExtendWith(MockitoExtension.class)
class LabSpecimenRejectionNotifierTest {

    @Mock private NotificationService notificationService;
    @Mock private InBasketService inBasketService;
    @Mock private PlatformTransactionManager transactionManager;

    @InjectMocks
    private LabSpecimenRejectionNotifier notifier;

    @AfterEach
    void resetMessages() {
        MessageUtil.setMessageSource(null);
    }

    private LabSpecimen rejectedSpecimen(Staff orderingStaff) {
        Hospital hospital = Hospital.builder().build();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Fatimata");
        patient.setLastName("Kaboré");
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("Glycémie");
        LabOrder order = LabOrder.builder().hospital(hospital).build();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setLabTestDefinition(definition);
        order.setOrderingStaff(orderingStaff);
        LabSpecimen specimen = LabSpecimen.builder().labOrder(order).rejectionReason("Hémolysé").build();
        specimen.setId(UUID.randomUUID());
        return specimen;
    }

    @Test
    @DisplayName("QA D962 — the doctor gets an URGENT in-basket item with the reason and a notification naming it")
    void theOrderingClinicianIsTold() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        User doctor = new User();
        doctor.setId(UUID.randomUUID());
        doctor.setUsername("dr.ouattara");
        Staff staff = new Staff();
        staff.setUser(doctor);
        LabSpecimen specimen = rejectedSpecimen(staff);
        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);

        notifier.specimenRejected(specimen);

        verify(inBasketService).createItemInNewTransaction(item.capture());
        assertThat(item.getValue().getRecipientUserId()).isEqualTo(doctor.getId());
        assertThat(item.getValue().getItemType()).isEqualTo(InBasketItemType.ORDER);
        assertThat(item.getValue().getPriority()).isEqualTo(InBasketPriority.URGENT);
        assertThat(item.getValue().getTitle()).isEqualTo("Échantillon rejeté : Glycémie");
        assertThat(item.getValue().getBody()).isEqualTo("Hémolysé");
        assertThat(item.getValue().getReferenceId()).isEqualTo(specimen.getId());
        verify(notificationService).createNotification(eq("lab.specimen.rejected.notification"), args.capture(),
            eq("dr.ouattara"), eq("LAB_SPECIMEN_REJECTED"), eq(specimen.getId()));
        assertThat(args.getValue()).containsExactly("Glycémie", "Fatimata Kaboré", "Hémolysé");
    }

    @Test
    @DisplayName("QA D1053 — the in-basket title keeps its key, and a deleted test is named as a key too")
    void theTitleIsStoredAsAKeyAndADeletedTestIsAKey() {
        MessageUtil.setMessageSource(com.example.hms.i18n.TestMessageSources.bundles());
        User doctor = new User();
        doctor.setId(UUID.randomUUID());
        doctor.setUsername("dr.ouattara");
        Staff staff = new Staff();
        staff.setUser(doctor);
        LabSpecimen specimen = rejectedSpecimen(staff);
        specimen.getLabOrder().setLabTestDefinition(null);
        ArgumentCaptor<CreateInBasketItemRequest> item = ArgumentCaptor.forClass(CreateInBasketItemRequest.class);

        notifier.specimenRejected(specimen);

        verify(inBasketService).createItemInNewTransaction(item.capture());
        assertThat(item.getValue().getTitleKey()).isEqualTo("lab.specimen.rejected.inBasketTitle");
        assertThat(item.getValue().getTitleArgs()[0]).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> titleArg = (java.util.Map<String, Object>) item.getValue().getTitleArgs()[0];
        assertThat(titleArg)
            .containsEntry("key", com.example.hms.mapper.LabOrderMapper.DELETED_TEST_LABEL);
    }

    @Test
    @DisplayName("QA D962 — an order with no ordering clinician account is a no-op, never a failed rejection")
    void noClinicianNoNotice() {
        notifier.specimenRejected(rejectedSpecimen(null));

        verifyNoInteractions(notificationService, inBasketService);
    }
}
