package com.example.hms.service.integration;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.service.integration.impl.MllpInboundAdtServiceImpl;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MllpInboundAdtServiceImplTest {

    @Mock private EmpiService empiService;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private MllpInboundAdtVisitProjectionService visitProjection;
    @Mock private IntegrationMessageRecorder messageRecorder;

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
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
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
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
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
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        assertThat(service.processAdt(
            adt("MRN-1", "Doe", "Jane", null), hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
    }

    @Test
    @DisplayName("A cross-tenant patient answers REJECTED_NOT_FOUND, like an MRN nobody has")
    void rejectedWhenNotRegisteredAtHospital() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.empty());

        // NOT a cross-tenant outcome of its own: that one mapped to AR while
        // an unknown MRN mapped to AE, and the difference was a read
        // primitive over every MRN in every other hospital. The
        // indistinguishability is asserted on the ACK itself in
        // AdtCrossTenantAckTest; this pins the outcome the ACK is built from.
        assertThat(service.processAdt(
            adt("MRN-1", "Doe", "Jane", null), hospital, "REG", "HOSP1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(patientRepository, never()).save(any());
    }

    @Test
    @DisplayName("The cross-tenant reason IS recorded — on the integration message row, without the body")
    void crossTenantReasonIsRecordedOnTheIntegrationRow() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.empty());

        service.processAdt(adt("MRN-1", "Doe", "Jane", null), hospital, "REG", "HOSP1", "MSG-1");

        // isNull() on the payload is load-bearing: one probe must not park a
        // full PID in the DLQ.
        verify(messageRecorder).recordMessage(
            eq("MLLP:REG/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A08"), isNull(),
            eq(IntegrationMessageStatus.FAILED),
            eq("cross-tenant rejection (MSH-10 MSG-1)"));
    }

    @Test
    @DisplayName("An unknown MRN records its own, different reason on the same surface")
    void unknownMrnRecordsADifferentReason() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-X"))
            .thenReturn(Optional.empty());

        service.processAdt(adt("MRN-X", "Doe", "Jane", null), hospital, "REG", "HOSP1", "MSG-1");

        // The operator can still tell the two apart. The sender cannot.
        verify(messageRecorder).recordMessage(
            eq("MLLP:REG/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A08"), isNull(),
            // RECEIVED, not FAILED. A partner feed naming patients we have
            // not been told about is normal, and one dead letter per message
            // would bury the refusals that need somebody. The reason is still
            // on the row, and still not in the ACK.
            eq(IntegrationMessageStatus.RECEIVED),
            eq("PID-3 not found (MSH-10 MSG-1)"));
    }

    @Test
    @DisplayName("Only the cross-tenant refusal is a dead letter; the benign unknown MRN is not")
    void onlyTheCrossTenantRefusalCountsAsADeadLetter() {
        // The two rows differ in exactly one field, and it is the one that
        // drives countUnresolvedDeadLetters and the super-admin badge. The
        // ACK is identical for both, so the difference is invisible to the
        // sender and visible only to an operator.
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-X"))
            .thenReturn(Optional.empty());
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.empty());

        service.processAdt(adt("MRN-X", "Doe", "Jane", null), hospital, "REG", "HOSP1", "M1");
        service.processAdt(adt("MRN-1", "Doe", "Jane", null), hospital, "REG", "HOSP1", "M2");

        ArgumentCaptor<IntegrationMessageStatus> statuses =
            ArgumentCaptor.forClass(IntegrationMessageStatus.class);
        verify(messageRecorder, org.mockito.Mockito.times(2)).recordMessage(
            any(), any(), any(), any(), any(), statuses.capture(), any());
        assertThat(statuses.getAllValues())
            .containsExactly(IntegrationMessageStatus.RECEIVED, IntegrationMessageStatus.FAILED);
    }

    @Test
    @DisplayName("An accepted in-tenant update records no rejection row")
    void anAcceptedUpdateRecordsNoRejection() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));

        assertThat(service.processAdt(adt("MRN-1", "Doe", "Jane", LocalDate.of(1985, 1, 1)),
            hospital, "REG", "HOSP1", "MSG-1"))
            .isEqualTo(MllpInboundOutcome.ACCEPTED);

        verify(messageRecorder, never()).recordMessage(
            any(), any(), any(), any(), any(), any(), any());
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
    @DisplayName("Visit projection invoked with MSH-10 control id when supplied")
    void visitProjectionReceivesMessageControlId() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));

        ParsedAdtMessage parsed = adt("MRN-1", "Doe", "Jane", LocalDate.of(1985, 1, 1));
        assertThat(service.processAdt(parsed, hospital, "REG", "HOSP1", "MSG-99"))
            .isEqualTo(MllpInboundOutcome.ACCEPTED);

        ArgumentCaptor<String> ctrl = ArgumentCaptor.forClass(String.class);
        verify(visitProjection).projectVisit(
            org.mockito.ArgumentMatchers.eq(parsed),
            org.mockito.ArgumentMatchers.eq(patient),
            org.mockito.ArgumentMatchers.eq(hospital),
            org.mockito.ArgumentMatchers.eq("REG"),
            org.mockito.ArgumentMatchers.eq("HOSP1"),
            ctrl.capture());
        assertThat(ctrl.getValue()).isEqualTo("MSG-99");
    }

    @Test
    @DisplayName("Projection failure does NOT roll back the demographic ACCEPTED outcome")
    void projectionFailureDoesNotBreakAck() {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, hospital.getId()))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        org.mockito.Mockito.doThrow(new RuntimeException("projection bean failure"))
            .when(visitProjection).projectVisit(any(), any(), any(), any(), any(), any());

        assertThat(service.processAdt(
            adt("MRN-1", "Doe", "Jane", LocalDate.of(1985, 1, 1)),
            hospital, "REG", "HOSP1", null))
            .isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(patientRepository).save(patient);
    }

    @Test
    @DisplayName("Blank inbound fields do NOT overwrite existing patient data")
    void blankInboundDoesNotWipeExisting() {
        patient.setFirstName("Jane");
        patient.setLastName("Doe");
        patient.setCity("Bobo-Dioulasso");
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, "MRN-1"))
            .thenReturn(Optional.of(empiHit(patientId)));
        when(patientRepository.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
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
