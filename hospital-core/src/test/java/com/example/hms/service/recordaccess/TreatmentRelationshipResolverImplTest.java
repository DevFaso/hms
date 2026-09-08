package com.example.hms.service.recordaccess;

import com.example.hms.config.RecordAccessProperties;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.Encounter;
import com.example.hms.model.PanelAssignment;
import com.example.hms.model.Staff;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.ImagingOrderRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.PanelAssignmentRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The decay rule, carrier by carrier. Every window comes from
 * {@link RecordAccessProperties}; the clock is pinned so "now" is a fixture.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TreatmentRelationshipResolverImpl")
class TreatmentRelationshipResolverImplTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 8, 10, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock private AdmissionRepository admissions;
    @Mock private EncounterRepository encounters;
    @Mock private AppointmentRepository appointments;
    @Mock private PanelAssignmentRepository panels;
    @Mock private LabOrderRepository labOrders;
    @Mock private ImagingOrderRepository imagingOrders;
    @Mock private StaffRepository staffRepository;

    private final UUID patientId = UUID.randomUUID();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID actorUserId = UUID.randomUUID();
    private final UUID actorStaffId = UUID.randomUUID();

    private TreatmentRelationshipResolverImpl resolver;

    @BeforeEach
    void setUp() {
        @SuppressWarnings("unchecked")
        ObjectProvider<Clock> clockProvider = mock(ObjectProvider.class);
        when(clockProvider.getIfAvailable(any())).thenReturn(CLOCK);

        when(admissions.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(encounters.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(any(), any(), any(), any()))
            .thenReturn(List.of());
        when(panels.findByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, PanelAssignmentStatus.ACTIVE))
            .thenReturn(List.of());
        when(labOrders.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());
        when(imagingOrders.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of());

        Staff actor = new Staff();
        actor.setId(actorStaffId);
        when(staffRepository.findByUserIdAndHospitalId(actorUserId, hospitalId)).thenReturn(Optional.of(actor));

        resolver = new TreatmentRelationshipResolverImpl(admissions, encounters, appointments, panels,
            labOrders, imagingOrders, staffRepository, new RecordAccessProperties(), clockProvider);
    }

    // ---------------------------------------------------------------- nothing

    @Test
    @DisplayName("no carrier at this hospital means no relationship")
    void emptyWhenNoCarrier() {
        assertThat(resolver.resolve(patientId, hospitalId, actorUserId)).isEmpty();
    }

    @Test
    @DisplayName("a null patient or hospital is never a relationship")
    void emptyOnNulls() {
        assertThat(resolver.resolve(null, hospitalId, actorUserId)).isEmpty();
        assertThat(resolver.resolve(patientId, null, actorUserId)).isEmpty();
    }

    // -------------------------------------------------------------- admission

    @Test
    @DisplayName("an ACTIVE admission is live with no expiry, and the attending is directly attached")
    void activeAdmission() {
        Admission a = admission(AdmissionStatus.ACTIVE, null);
        Staff attending = new Staff();
        attending.setId(actorStaffId);
        a.setAttendingPhysician(attending);
        when(admissions.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(a));

        TreatmentRelationship r = resolver.resolve(patientId, hospitalId, actorUserId).orElseThrow();

        assertThat(r.kind()).isEqualTo(TreatmentRelationshipKind.ACTIVE_ADMISSION);
        assertThat(r.expiresAt()).isNull();
        assertThat(r.actorDirectlyAttached()).isTrue();
    }

    @Test
    @DisplayName("a discharge inside the post-visit tail is still live, and names when it lapses")
    void dischargedWithinTail() {
        Admission a = admission(AdmissionStatus.DISCHARGED, NOW.minusDays(10));
        when(admissions.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(a));

        TreatmentRelationship r = resolver.resolve(patientId, hospitalId, actorUserId).orElseThrow();

        assertThat(r.expiresAt()).isEqualTo(NOW.minusDays(10).plusDays(30));
        assertThat(r.actorDirectlyAttached()).isFalse();
    }

    @Test
    @DisplayName("a discharge older than the tail has lapsed — a relationship that never expires is not one")
    void dischargedBeyondTail() {
        Admission a = admission(AdmissionStatus.DISCHARGED, NOW.minusDays(31));
        when(admissions.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(a));

        assertThat(resolver.resolve(patientId, hospitalId, actorUserId)).isEmpty();
    }

    @Test
    @DisplayName("a CANCELLED admission never counts, however recent")
    void cancelledAdmission() {
        Admission a = admission(AdmissionStatus.CANCELLED, NOW.minusHours(1));
        when(admissions.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(a));

        assertThat(resolver.resolve(patientId, hospitalId, actorUserId)).isEmpty();
    }

    // -------------------------------------------------------------- encounter

    @Test
    @DisplayName("an IN_PROGRESS encounter is live; the encounter's clinician is directly attached")
    void openEncounter() {
        Encounter e = encounter(EncounterStatus.IN_PROGRESS, null);
        Staff s = new Staff();
        s.setId(actorStaffId);
        e.setStaff(s);
        when(encounters.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(e));

        TreatmentRelationship r = resolver.resolve(patientId, hospitalId, actorUserId).orElseThrow();

        assertThat(r.kind()).isEqualTo(TreatmentRelationshipKind.OPEN_ENCOUNTER);
        assertThat(r.expiresAt()).isNull();
        assertThat(r.actorDirectlyAttached()).isTrue();
    }

    @Test
    @DisplayName("a COMPLETED encounter keeps the tail from checkout, then lapses")
    void completedEncounterTail() {
        Encounter fresh = encounter(EncounterStatus.COMPLETED, NOW.minusDays(29));
        when(encounters.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(fresh));
        assertThat(resolver.resolve(patientId, hospitalId, actorUserId)).isPresent();

        Encounter stale = encounter(EncounterStatus.COMPLETED, NOW.minusDays(31));
        when(encounters.findByPatient_IdAndHospital_Id(patientId, hospitalId)).thenReturn(List.of(stale));
        assertThat(resolver.resolve(patientId, hospitalId, actorUserId)).isEmpty();
    }

    @Test
    @DisplayName("the admission wins over the encounter when both are live — strongest carrier first")
    void admissionOutranksEncounter() {
        when(admissions.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(admission(AdmissionStatus.ACTIVE, null)));
        when(encounters.findByPatient_IdAndHospital_Id(patientId, hospitalId))
            .thenReturn(List.of(encounter(EncounterStatus.IN_PROGRESS, null)));

        assertThat(resolver.resolve(patientId, hospitalId, actorUserId).orElseThrow().kind())
            .isEqualTo(TreatmentRelationshipKind.ACTIVE_ADMISSION);
    }

    // ------------------------------------------------------------ appointment

    @Test
    @DisplayName("an appointment booked within the lookahead opens the relationship before the visit")
    void scheduledWithinLookahead() {
        Appointment ap = appointment(AppointmentStatus.SCHEDULED, NOW.toLocalDate().plusDays(5));
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(any(), any(), any(), any()))
            .thenReturn(List.of(ap));

        TreatmentRelationship r = resolver.resolve(patientId, hospitalId, actorUserId).orElseThrow();

        assertThat(r.kind()).isEqualTo(TreatmentRelationshipKind.SCHEDULED_APPOINTMENT);
        assertThat(r.expiresAt()).isNull();
    }

    @Test
    @DisplayName("an appointment beyond the lookahead is not yet a relationship")
    void scheduledBeyondLookahead() {
        Appointment ap = appointment(AppointmentStatus.SCHEDULED, NOW.toLocalDate().plusDays(8));
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(any(), any(), any(), any()))
            .thenReturn(List.of(ap));

        assertThat(resolver.resolve(patientId, hospitalId, actorUserId)).isEmpty();
    }

    @Test
    @DisplayName("NO_SHOW and CANCELLED appointments never count")
    void noShowAndCancelled() {
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(any(), any(), any(), any()))
            .thenReturn(List.of(
                appointment(AppointmentStatus.NO_SHOW, NOW.toLocalDate()),
                appointment(AppointmentStatus.CANCELLED, NOW.toLocalDate())));

        assertThat(resolver.resolve(patientId, hospitalId, actorUserId)).isEmpty();
    }

    @Test
    @DisplayName("a COMPLETED appointment keeps the post-visit tail from its end time")
    void completedAppointmentTail() {
        Appointment ap = appointment(AppointmentStatus.COMPLETED, NOW.toLocalDate().minusDays(3));
        ap.setEndTime(LocalTime.of(11, 30));
        when(appointments.findByPatient_IdAndHospital_IdAndAppointmentDateBetween(any(), any(), any(), any()))
            .thenReturn(List.of(ap));

        TreatmentRelationship r = resolver.resolve(patientId, hospitalId, actorUserId).orElseThrow();

        assertThat(r.expiresAt())
            .isEqualTo(LocalDateTime.of(NOW.toLocalDate().minusDays(3), LocalTime.of(11, 30)).plusDays(30));
    }

    // ------------------------------------------------------------------ panel

    @Test
    @DisplayName("an ACTIVE panel assignment is a standing relationship with no expiry")
    void panelAssignment() {
        PanelAssignment p = new PanelAssignment();
        p.setId(UUID.randomUUID());
        p.setAssignedOn(LocalDate.of(2026, 1, 15));
        Staff provider = new Staff();
        provider.setId(actorStaffId);
        p.setProviderStaff(provider);
        when(panels.findByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, PanelAssignmentStatus.ACTIVE))
            .thenReturn(List.of(p));

        TreatmentRelationship r = resolver.resolve(patientId, hospitalId, actorUserId).orElseThrow();

        assertThat(r.kind()).isEqualTo(TreatmentRelationshipKind.PANEL_ASSIGNMENT);
        assertThat(r.expiresAt()).isNull();
        assertThat(r.actorDirectlyAttached()).isTrue();
    }

    // -------------------------------------------------------------- fixtures

    private Admission admission(AdmissionStatus status, LocalDateTime discharged) {
        Admission a = new Admission();
        a.setId(UUID.randomUUID());
        a.setStatus(status);
        a.setAdmissionDateTime(NOW.minusDays(12));
        a.setActualDischargeDateTime(discharged);
        return a;
    }

    private Encounter encounter(EncounterStatus status, LocalDateTime checkout) {
        Encounter e = new Encounter();
        e.setId(UUID.randomUUID());
        e.setStatus(status);
        e.setEncounterDate(NOW.minusDays(1));
        e.setCheckoutTimestamp(checkout);
        return e;
    }

    private Appointment appointment(AppointmentStatus status, LocalDate date) {
        Appointment ap = new Appointment();
        ap.setId(UUID.randomUUID());
        ap.setStatus(status);
        ap.setAppointmentDate(date);
        ap.setStartTime(LocalTime.of(9, 0));
        return ap;
    }

    @SuppressWarnings("unused")
    private static Instant instant(LocalDateTime t) {
        return t.toInstant(ZoneOffset.UTC);
    }
}
