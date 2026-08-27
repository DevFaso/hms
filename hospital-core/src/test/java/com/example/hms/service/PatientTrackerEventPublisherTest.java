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

    @Test
    @DisplayName("swallows a dangling lazy reference instead of failing the transition")
    void swallowsDanglingProxy() {
        // THE REGRESSION. The guard used to wrap only convertAndSend, leaving
        // the getHospital()/getDepartment()/getPatient() reads above it
        // exposed. Those are LAZY proxies and this codebase maps by FIELD
        // access, so getId() fully initialises them rather than taking
        // Hibernate's identifier shortcut — and a proxy whose row was deleted
        // throws EntityNotFoundException. That escaped completeTriage as a
        // 500 and rolled the status change back: a websocket refresh nobody
        // was waiting on took the clinical write down with it.
        //
        // The existing broker test passed throughout, because it exercised
        // the one path that was already guarded.
        Encounter dangling = org.mockito.Mockito.mock(Encounter.class);
        org.mockito.Mockito.when(dangling.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(dangling.getHospital())
                .thenThrow(new jakarta.persistence.EntityNotFoundException(
                        "Unable to find com.example.hms.model.Hospital with id ..."));

        assertThatCode(() ->
                publisher.publishStatusTransition(dangling, "ARRIVED", "WAITING_FOR_PHYSICIAN"))
                .doesNotThrowAnyException();

        verify(messagingTemplate, never()).convertAndSend(
                org.mockito.ArgumentMatchers.anyString(),
                (Object) org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("swallows a dangling reference discovered part-way through building the event")
    void swallowsDanglingProxyAfterHospitalResolves() {
        // The hospital resolves and the department does not — the event is
        // half-built when it fails. Still must not surface.
        Encounter dangling = org.mockito.Mockito.mock(Encounter.class);
        org.mockito.Mockito.when(dangling.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(dangling.getHospital()).thenReturn(hospital);
        org.mockito.Mockito.when(dangling.getDepartment())
                .thenThrow(new jakarta.persistence.EntityNotFoundException("deleted department"));

        assertThatCode(() ->
                publisher.publishStatusTransition(dangling, "TRIAGE", "WAITING_FOR_PHYSICIAN"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a null encounter is not an error")
    void nullEncounterIsIgnored() {
        assertThatCode(() -> publisher.publishStatusTransition(null, "A", "B"))
                .doesNotThrowAnyException();
    }
}
