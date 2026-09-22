package com.example.hms.controller;

import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.service.PrescriptionService;
import com.example.hms.service.PrescriptionSmsDispatchService;
import com.example.hms.service.pharmacy.PharmacistVerificationService;
import com.example.hms.service.pharmacy.PrescriptionClarificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * G7 — GET /prescriptions/{id} is open to ROLE_PATIENT and used to return
 * the clinician's projection unfiltered; the patient copy rule applies
 * there as it does on /me/patient/prescriptions.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PrescriptionController: the patient's copy on GET /prescriptions/{id}")
class PrescriptionControllerPatientCopyTest {

    @Mock private PrescriptionService prescriptionService;
    @Mock private PharmacistVerificationService pharmacistVerificationService;
    @Mock private PrescriptionClarificationService clarificationService;
    @Mock private PrescriptionSmsDispatchService smsDispatchService;
    @Mock private MessageSource messageSource;

    private PrescriptionController controller() {
        return new PrescriptionController(prescriptionService, pharmacistVerificationService,
                clarificationService, smsDispatchService, messageSource);
    }

    private static Authentication with(String... roles) {
        return new UsernamePasswordAuthenticationToken("u", "n",
                List.of(roles).stream().map(SimpleGrantedAuthority::new).toList());
    }

    private PrescriptionResponseDTO fresh() {
        return PrescriptionResponseDTO.builder()
                .id(UUID.randomUUID())
                .pharmacyName("Pharmacie du Marché")
                .clarificationReason("Dose au-dessus du plafond rénal")
                .clarificationResponse("Dose confirmée")
                .build();
    }

    @Test
    @DisplayName("a patient gets the copy without the clarification exchange")
    void patientCopyIsStripped() {
        UUID id = UUID.randomUUID();
        when(prescriptionService.getPrescriptionById(eq(id), any())).thenReturn(fresh());

        PrescriptionResponseDTO body = controller().getById(id, with("ROLE_PATIENT"), Locale.FRENCH).getBody();

        assertThat(body.getPharmacyName()).isEqualTo("Pharmacie du Marché");
        assertThat(body.getClarificationReason()).isNull();
        assertThat(body.getClarificationResponse()).isNull();
    }

    @Test
    @DisplayName("a clinician — even one who is also a patient — reads the exchange")
    void clinicianReadsTheExchange() {
        UUID id = UUID.randomUUID();
        when(prescriptionService.getPrescriptionById(eq(id), any())).thenReturn(fresh());

        PrescriptionResponseDTO body = controller()
                .getById(id, with("ROLE_PATIENT", "ROLE_DOCTOR"), Locale.FRENCH).getBody();

        assertThat(body.getClarificationReason()).isEqualTo("Dose au-dessus du plafond rénal");
    }

    @Test
    void isPatientOnlyContract() {
        assertThat(PrescriptionController.isPatientOnly(with("ROLE_PATIENT"))).isTrue();
        assertThat(PrescriptionController.isPatientOnly(with("ROLE_PHARMACIST"))).isFalse();
        assertThat(PrescriptionController.isPatientOnly(with("ROLE_PATIENT", "ROLE_NURSE"))).isFalse();
        assertThat(PrescriptionController.isPatientOnly(null)).isFalse();
    }
}
