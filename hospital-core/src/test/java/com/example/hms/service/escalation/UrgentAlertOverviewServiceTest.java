package com.example.hms.service.escalation;

import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.mapper.InBasketMapper;
import com.example.hms.model.InBasketItem;
import com.example.hms.model.Patient;
import com.example.hms.model.UrgentAlertIncident;
import com.example.hms.model.User;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.payload.dto.clinical.InBasketItemDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertIncidentDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertPatientMarkDTO;
import com.example.hms.payload.dto.clinical.UrgentAlertsMineDTO;
import com.example.hms.repository.CriticalResultIncidentRepository;
import com.example.hms.repository.InBasketItemRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UrgentAlertIncidentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import com.example.hms.service.maternity.ObstetricAlertEscalationWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D4101, D4102 (r81): what the ladder is waiting on, as a clinician and as an administrator read it. The
 * register never gives an administrator the patient, and gives it to no doctor but the médecin-chef.
 */
class UrgentAlertOverviewServiceTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID OTHER_HOSPITAL = UUID.randomUUID();
    private static final UUID READER = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 27, 7, 0);

    private final ObstetricAlertEscalationRepository escalations = mock(ObstetricAlertEscalationRepository.class);
    private final InBasketItemRepository items = mock(InBasketItemRepository.class);
    private final UrgentAlertIncidentRepository incidents = mock(UrgentAlertIncidentRepository.class);
    private final CriticalResultIncidentRepository labIncidents = mock(CriticalResultIncidentRepository.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UrgentEscalationSupport ladder = mock(UrgentEscalationSupport.class);
    private final InBasketMapper mapper = mock(InBasketMapper.class);
    private final MessageSource messages = mock(MessageSource.class);
    private UrgentAlertOverviewService service;

    @BeforeEach
    void setUp() {
        service = new UrgentAlertOverviewService(escalations, items, incidents, labIncidents, patients, users, ladder,
            mapper, messages, Clock.fixed(Instant.parse("2026-11-27T07:00:00Z"), ZoneOffset.UTC));
        User reader = User.builder().username("dr.chef").build();
        reader.setId(READER);
        when(users.findById(READER)).thenReturn(Optional.of(reader));
        when(messages.getMessage(any(String.class), any(), any(String.class), any())).thenAnswer(c -> c.getArgument(0));
        when(labIncidents.findRegister(any(), any())).thenReturn(List.of());
    }

    private static ObstetricAlertEscalation alert(UUID hospitalId) {
        ObstetricAlertEscalation row = ObstetricAlertEscalation.builder().hospitalId(hospitalId).patientId(PATIENT)
            .sourceType(ObstetricAlertEscalationService.SOURCE_NEWS2).escalationLevel((short) 3)
            .firstNoticeFiledAt(NOW.minusMinutes(61)).lastEscalationAt(NOW.minusMinutes(1)).build();
        row.setId(UUID.randomUUID());
        return row;
    }

    private UrgentAlertIncident incidentOf(ObstetricAlertEscalation row) {
        UrgentAlertIncident incident = UrgentAlertIncident.builder().sourceType(UrgentAlertIncident.SOURCE_URGENT_ALERT)
            .sourceId(row.getId()).hospitalId(HOSPITAL).openedAt(NOW.minusMinutes(1)).minutesUnacknowledged(60)
            .recipientsTold(4).build();
        incident.setId(UUID.randomUUID());
        when(incidents.findRegister(eq(HOSPITAL), any())).thenReturn(List.of(incident));
        when(escalations.findAllById(anyIterable())).thenReturn(List.of(row));
        return incident;
    }

    @Test
    void theReadersAlertsCountThoseToAcknowledgeAndThoseSheDocumented() {
        InBasketItem item = new InBasketItem();
        item.setCreatedAt(NOW.minusMinutes(20));
        ObstetricAlertEscalation documented = alert(HOSPITAL);
        when(items.findOpenUrgentAlertItems(READER, HOSPITAL, ObstetricAlertEscalationWorker.REFERENCE_TYPE))
            .thenReturn(List.of(item));
        when(escalations.findOpenDocumentedBy(HOSPITAL, READER)).thenReturn(List.of(documented));
        when(mapper.toDto(item)).thenReturn(InBasketItemDTO.builder().title("Alerte").build());

        UrgentAlertsMineDTO mine = service.mine(READER, HOSPITAL);

        assertThat(mine.toAcknowledge()).isEqualTo(1);
        assertThat(mine.documentedWaiting()).isEqualTo(1);
        assertThat(mine.oldestRaisedAt()).isEqualTo(NOW.minusMinutes(61));
        assertThat(mine.items()).hasSize(1);
    }

    @Test
    void aPatientsMarkIsACountAndTheOldestTimeAndNothingElse() {
        when(escalations.findOpenPatientMarks(HOSPITAL))
            .thenReturn(List.<Object[]>of(new Object[]{PATIENT, 3L, NOW.minusMinutes(90)}));

        assertThat(service.patientMarks(HOSPITAL))
            .containsExactly(new UrgentAlertPatientMarkDTO(PATIENT, 3, NOW.minusMinutes(90)));
        assertThat(service.patientMarks(null)).isEmpty();
    }

    @Test
    void anAdministratorReadsTheRegisterWithoutThePatient() {
        ObstetricAlertEscalation row = alert(HOSPITAL);
        incidentOf(row);

        List<UrgentAlertIncidentDTO> register = service.incidents(READER, HOSPITAL, true, false);

        assertThat(register).hasSize(1);
        UrgentAlertIncidentDTO line = register.get(0);
        assertThat(line.patientId()).isNull();
        assertThat(line.patientName()).isNull();
        assertThat(line.caseKey()).doesNotContain(PATIENT.toString());
        // Review of #1393 (F3): the key cannot be recomputed from the hospital, a patient id and the date.
        assertThat(line.caseKey()).isEqualTo(line.id().toString())
            .isNotEqualTo(UUID.nameUUIDFromBytes((HOSPITAL + ":" + PATIENT + ":" + line.openedAt().toLocalDate())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        assertThat(line.source()).isEqualTo(ObstetricAlertEscalationService.SOURCE_NEWS2);
        assertThat(line.stepsTaken()).isEqualTo(3);
        assertThat(line.minutesUnacknowledged()).isEqualTo(60);
        assertThat(line.closedAt()).isNull();
        verify(patients, never()).findByIdUnscoped(any());
    }

    @Test
    void theMedicalLeadReadsThePatientAndWhoAcknowledged() {
        ObstetricAlertEscalation row = alert(HOSPITAL);
        UrgentAlertIncident incident = incidentOf(row);
        UUID nurseId = UUID.randomUUID();
        incident.setResolvedAt(NOW);
        incident.setResolvedByUserId(nurseId);
        incident.setResolution(UrgentAlertIncident.RESOLVED_ACKNOWLEDGED);
        User nurse = User.builder().username("nurse.a").firstName("Test").lastName("Infirmière A").build();
        nurse.setId(nurseId);
        when(users.findAllById(anyIterable())).thenReturn(List.of(nurse));
        when(ladder.medicalLead(HOSPITAL)).thenReturn(Set.of("DR.CHEF"));
        Patient patient = mock(Patient.class);
        when(patient.getFullName()).thenReturn("Adama TestEchelle");
        when(patients.findByIdUnscoped(PATIENT)).thenReturn(Optional.of(patient));

        UrgentAlertIncidentDTO line = service.incidents(READER, HOSPITAL, false, true).get(0);

        assertThat(line.patientId()).isEqualTo(PATIENT);
        assertThat(line.patientName()).isEqualTo("Adama TestEchelle");
        assertThat(line.acknowledgedByName()).isEqualTo("Test Infirmière A");
        assertThat(line.closedAt()).isEqualTo(NOW);
        assertThat(line.resolution()).isEqualTo(UrgentAlertIncident.RESOLVED_ACKNOWLEDGED);
    }

    @Test
    void aDoctorWhoIsNotTheMedicalLeadIsRefused() {
        when(ladder.medicalLead(HOSPITAL)).thenReturn(Set.of("dr.other"));

        assertThatThrownBy(() -> service.incidents(READER, HOSPITAL, false, true))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        verify(incidents, never()).findRegister(any(), any());
    }

    @Test
    void anAlertOfAnotherHospitalIsNeverDescribed() {
        // The incident says this hospital, its alert row another: nothing of the row is read out.
        ObstetricAlertEscalation foreign = alert(OTHER_HOSPITAL);
        incidentOf(foreign);
        when(ladder.medicalLead(HOSPITAL)).thenReturn(Set.of("dr.chef"));

        UrgentAlertIncidentDTO line = service.incidents(READER, HOSPITAL, true, true).get(0);

        assertThat(line.patientId()).isNull();
        assertThat(line.source()).isEqualTo(UrgentAlertIncident.SOURCE_URGENT_ALERT);
        assertThat(line.stepsTaken()).isNull();
    }
}
