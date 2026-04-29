package com.example.hms.service.integration;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.impl.MllpInboundAdtServiceImpl;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MllpInboundAdtServiceImplTest {

    @Mock private EmpiService empiService;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;

    @InjectMocks private MllpInboundAdtServiceImpl service;

    private Hospital hospital;
    private Patient patient;
    private UUID patientId;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        patientId = UUID.randomUUID();
        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Old");
        patient.setLastName("Name");
    }

    private ParsedAdtMessage adt(String mrn, String last, String first, LocalDate dob) {
        return new ParsedAdtMessage(
            "A08", mrn, "AUTH",
            last, first, "",
            dob, "F",
            "1 Main St", "Ouagadougou", "", "01000", "BF",
            "I", "WARD-A", "VISIT-1", null, null);
    }

    private EmpiIdentityResponseDTO empiHit(UUID patientId) {
        return EmpiIdentityResponseDTO.builder()
            .id(UUID.randomUUID())
            .empiNumber("E-1")
            .patientId(patientId)
            .build();
    }

    @Test
    @DisplayName("ACCEPTED — known MRN, registered patient, demographics applied")
    void acceptedHappyPath() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));

        MllpInboundOutcome outcome = service.processAdt(
            adt("MRN-1", "Doe", "Jane", LocalDate.of(1985, 1, 1)),
            hospital, "REG", "HOSP1");

        assertThat(outcome).isEqualTo(MllpInboundOutcome.ACCEPTED);
        assertThat(patient.getFirstName()).isEqualTo("Jane");
        assertThat(patient.getLastName()).isEqualTo("Doe");
        assertThat(patient.getDateOfBirth()).isEqualTo(LocalDate.of(1985, 1, 1));
        assertThat(patient.getGender()).isEqualTo("F");
        assertThat(patient.getCity()).isEqualTo("Ouagadougou");
        verify(patientRepository).save(patient);
    }

    @Test
    @DisplayName("ACCEPTED — no demographic changes still ACKs and skips save")
    void acceptedNoOpWhenNothingChanged() {
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setDateOfBirth(LocalDate.of(1985, 1, 1));
        patient.setGender("F");
        patient.setAddressLine1("1 Main St");
        patient.setCity("Ouagadougou");
        patient.setZipCode("01000");
        patient.setCountry("BF");

        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));

        assertThat(service.processAdt(
            adt("MRN-1", "Doe", "Jane", LocalDate.of(1985, 1, 1)),
            hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(patientRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_NOT_FOUND — MRN unknown to EMPI")
    void rejectedWhenMrnUnknown() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-X"))
            .thenReturn(Optional.empty());

        assertThat(service.processAdt(
            adt("MRN-X", "Doe", "Jane", null), hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(patientRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_NOT_FOUND — EMPI alias resolves to a missing Patient row")
    void rejectedWhenPatientRowMissing() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());

        assertThat(service.processAdt(
            adt("MRN-1", "Doe", "Jane", null), hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
    }

    @Test
    @DisplayName("REJECTED_CROSS_TENANT — patient is not registered at the allowlisted hospital")
    void rejectedWhenNotRegisteredAtHospital() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.empty());

        assertThat(service.processAdt(
            adt("MRN-1", "Doe", "Jane", null), hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_CROSS_TENANT);
        verify(patientRepository, never()).save(any());
    }

    @Test
    @DisplayName("REJECTED_INVALID — missing PID-3 MRN")
    void rejectedInvalidWhenMrnBlank() {
        ParsedAdtMessage parsed = adt("", "Doe", "Jane", null);
        assertThat(service.processAdt(parsed, hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("REJECTED_INVALID — null hospital")
    void rejectedInvalidWhenHospitalNull() {
        assertThat(service.processAdt(adt("MRN-1", "Doe", "Jane", null), null, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    @Test
    @DisplayName("Blank inbound fields do NOT overwrite existing patient data")
    void blankInboundDoesNotWipeExisting() {
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setCity("Bobo-Dioulasso");
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));

        ParsedAdtMessage parsed = new ParsedAdtMessage(
            "A08", "MRN-1", "AUTH",
            "", "", "",          // blank name parts
            null, "",            // blank dob/sex
            "", "", "", "", "",  // blank address fields
            "", "", "", null, null);

        assertThat(service.processAdt(parsed, hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.ACCEPTED);
        assertThat(patient.getFirstName()).isEqualTo("Jane");
        assertThat(patient.getLastName()).isEqualTo("Doe");
        assertThat(patient.getCity()).isEqualTo("Bobo-Dioulasso");
        verify(patientRepository, never()).save(any());
    }
}
