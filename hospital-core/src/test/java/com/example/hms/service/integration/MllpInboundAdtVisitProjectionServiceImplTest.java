package com.example.hms.service.integration;

import com.example.hms.hl7.mllp.AdtVisitSyncProperties;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.service.integration.MllpInboundAdtVisitProjectionService.VisitProjectionResult;
import com.example.hms.service.integration.impl.MllpInboundAdtVisitProjectionServiceImpl;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedAdtMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MllpInboundAdtVisitProjectionServiceImplTest {

    @Mock private AdmissionRepository admissionRepository;
    @Mock private EncounterRepository encounterRepository;

    @Spy private AdtVisitSyncProperties properties = new AdtVisitSyncProperties();

    @InjectMocks private MllpInboundAdtVisitProjectionServiceImpl service;

    private Hospital hospital;
    private Patient patient;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        patient = new Patient();
        patient.setId(UUID.randomUUID());

        // Flag ON unless a specific test flips it off.
        properties.setEnabled(true);
    }

    private ParsedAdtMessage adt(String trigger, String visit) {
        return new ParsedAdtMessage(
            trigger, "MRN-1", "AUTH",
            "Doe", "Jane", "",
            LocalDate.of(1985, 1, 1), "F",
            "1 Main St", "Ouagadougou", "", "01000", "BF",
            "I", "WARD-A", visit, null, null);
    }

    @Test
    @DisplayName("SKIPPED when projection flag is off — neither repo is queried")
    void skippedWhenFlagOff() {
        properties.setEnabled(false);

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-1"), patient, hospital, "REG", "HOSP1", "MSG-1");

        assertThat(result).isEqualTo(VisitProjectionResult.SKIPPED);
        verify(admissionRepository, never())
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                any(), any(), any(), any());
        verify(encounterRepository, never())
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("SKIPPED when PV1-19 visit number is blank")
    void skippedWhenVisitNumberBlank() {
        VisitProjectionResult result = service.projectVisit(
            adt("A04", ""), patient, hospital, "REG", "HOSP1", "MSG-1");
        assertThat(result).isEqualTo(VisitProjectionResult.SKIPPED);
    }

    @Test
    @DisplayName("ADMISSION_RECONCILED — existing admission matched and stamped")
    void admissionMatched() {
        Admission row = new Admission();
        row.setId(UUID.randomUUID());
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                eq("REG"), eq("HOSP1"), eq("V-1"), eq(hospital.getId())))
            .thenReturn(Optional.of(row));

        VisitProjectionResult result = service.projectVisit(
            adt("A08", "V-1"), patient, hospital, "REG", "HOSP1", "MSG-42");

        assertThat(result).isEqualTo(VisitProjectionResult.ADMISSION_RECONCILED);
        ArgumentCaptor<Admission> saved = ArgumentCaptor.forClass(Admission.class);
        verify(admissionRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalMessageControlId()).isEqualTo("MSG-42");
        // Encounter lookup is skipped once admission matches.
        verify(encounterRepository, never())
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                any(), any(), any(), any());
    }

    @Test
    @DisplayName("ENCOUNTER_RECONCILED — falls through to encounter when no admission match")
    void encounterMatched() {
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());
        Encounter row = new Encounter();
        row.setId(UUID.randomUUID());
        when(encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                eq("REG"), eq("HOSP1"), eq("V-2"), eq(hospital.getId())))
            .thenReturn(Optional.of(row));

        VisitProjectionResult result = service.projectVisit(
            adt("A04", "V-2"), patient, hospital, "REG", "HOSP1", "MSG-7");

        assertThat(result).isEqualTo(VisitProjectionResult.ENCOUNTER_RECONCILED);
        ArgumentCaptor<Encounter> saved = ArgumentCaptor.forClass(Encounter.class);
        verify(encounterRepository).save(saved.capture());
        assertThat(saved.getValue().getExternalMessageControlId()).isEqualTo("MSG-7");
    }

    @Test
    @DisplayName("NO_MATCH when neither repo finds a row for the visit number")
    void noMatch() {
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());
        when(encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                any(), any(), any(), any()))
            .thenReturn(Optional.empty());

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-X"), patient, hospital, "REG", "HOSP1", "MSG-1");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
        verify(admissionRepository, never()).save(any());
        verify(encounterRepository, never()).save(any());
    }

    @Test
    @DisplayName("Blank sender or hospital fields → reconciliation key uses null sender (still proceeds)")
    void blankSenderProceedsWithNullScope() {
        when(admissionRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospitalId(
                eq(null), eq(null), eq("V-3"), eq(hospital.getId())))
            .thenReturn(Optional.empty());
        when(encounterRepository
            .findFirstByExternalSendingApplicationAndExternalSendingFacilityAndExternalVisitNumberAndHospital_Id(
                eq(null), eq(null), eq("V-3"), eq(hospital.getId())))
            .thenReturn(Optional.empty());

        VisitProjectionResult result = service.projectVisit(
            adt("A01", "V-3"), patient, hospital, " ", "", "MSG-3");

        assertThat(result).isEqualTo(VisitProjectionResult.NO_MATCH);
    }
}
