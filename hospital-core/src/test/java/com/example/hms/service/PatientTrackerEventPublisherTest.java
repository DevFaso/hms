package com.example.hms.service;

import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.clinical.PatientTrackerEventDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatientTrackerEventPublisher")
class PatientTrackerEventPublisherTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    @InjectMocks private PatientTrackerEventPublisher publisher;

    private Hospital hospital;
    private Encounter encounter;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());

        Department department = new Department();
        department.setId(UUID.randomUUID());

        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());

        encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        encounter.setHospital(hospital);
        encounter.setDepartment(department);
        encounter.setPatient(patient);
    }

    @Test
    @DisplayName("publishes to /topic/patient-tracker/{hospitalId}")
    void publishesToHospitalTopic() {
        publisher.publishStatusTransition(encounter, "TRIAGE", "WAITING_FOR_PHYSICIAN");

        ArgumentCaptor<String> destinationCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<PatientTrackerEventDTO> payloadCaptor =
                ArgumentCaptor.forClass(PatientTrackerEventDTO.class);
        verify(messagingTemplate).convertAndSend(destinationCaptor.capture(), payloadCaptor.capture());

        assertThat(destinationCaptor.getValue())
                .isEqualTo(PatientTrackerEventPublisher.TOPIC_PREFIX + hospital.getId());
        assertThat(payloadCaptor.getValue().getNewStatus()).isEqualTo("WAITING_FOR_PHYSICIAN");
        assertThat(payloadCaptor.getValue().getPreviousStatus()).isEqualTo("TRIAGE");
        assertThat(payloadCaptor.getValue().getHospitalId()).isEqualTo(hospital.getId());
    }

    @Test
    @DisplayName("does NOT publish when encounter has no hospital")
    void skipsWhenHospitalMissing() {
        encounter.setHospital(null);

        publisher.publishStatusTransition(encounter, "ARRIVED", "TRIAGE");

        verify(messagingTemplate, never()).convertAndSend(
                org.mockito.ArgumentMatchers.anyString(),
                (Object) org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("swallows broker exceptions so the clinical transaction is not rolled back")
    void swallowsBrokerErrors() {
        doThrow(new RuntimeException("broker offline"))
                .when(messagingTemplate).convertAndSend(
                        org.mockito.ArgumentMatchers.anyString(),
                        (Object) org.mockito.ArgumentMatchers.any());

        assertThatCode(() ->
                publisher.publishStatusTransition(encounter, "IN_PROGRESS", "AWAITING_RESULTS"))
                .doesNotThrowAnyException();
    }
}
