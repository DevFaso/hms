package com.example.hms.service.support;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Appointment;
import com.example.hms.payload.dto.AppointmentRequestDTO;
import com.example.hms.payload.dto.portal.PortalBookAppointmentRequestDTO;
import com.example.hms.repository.AppointmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppointmentBookingIdentityTest {
    private final UUID actor = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID hospital = UUID.randomUUID();
    private final AppointmentRepository repository = mock(AppointmentRepository.class);

    @Test
    void explicitIdentitySurvivesCancellationButRejectsChangedDetails() {
        var r = staffRequest();
        r.setBookingRequestId(UUID.randomUUID());
        var identity = staff(r);
        Appointment appointment = recorded(identity, AppointmentStatus.CANCELLED);
        assertThat(identity.replay(repository)).contains(appointment);

        r.setNotes("A different intent");
        var changed = staff(r);
        assertThat(changed.key()).isEqualTo(identity.key());
        assertThatThrownBy(() -> changed.replay(repository)).isInstanceOf(FieldBusinessException.class);
    }

    @Test
    void legacyRetryReplaysButCancellationAndNoShowAllowRebooking() {
        var identity = staff(staffRequest());
        Appointment appointment = recorded(identity, AppointmentStatus.SCHEDULED);
        assertThat(staff(staffRequest()).replay(repository)).contains(appointment);
        appointment.setStatus(AppointmentStatus.CANCELLED);
        assertThat(identity.replay(repository)).isEmpty();
        appointment.setStatus(AppointmentStatus.NO_SHOW);
        assertThat(identity.replay(repository)).isEmpty();
    }

    @Test
    void legacyIdentityIgnoresIgnoredClientFieldsButPreservesTypedTextExactly() {
        var r = staffRequest();
        var original = staff(r);
        r.setId(UUID.randomUUID());
        r.setEndTime(LocalTime.MAX);
        assertThat(staff(r)).isEqualTo(original);
        r.setNotes(" ");
        assertThat(staff(r).key()).isNotEqualTo(original.key());
        r.setNotes("");
        assertThat(staff(r).key()).isNotEqualTo(original.key());
    }

    @Test
    void explicitIdentityIsScopedToActorHospitalAndRouteAndChecksPatient() {
        var r = staffRequest();
        r.setBookingRequestId(UUID.randomUUID());
        var identity = staff(r);
        recorded(identity, AppointmentStatus.SCHEDULED);
        assertThat(AppointmentBookingIdentity.staff(UUID.randomUUID(), patient, hospital, r).key())
            .isNotEqualTo(identity.key());
        assertThat(AppointmentBookingIdentity.staff(actor, patient, UUID.randomUUID(), r).key())
            .isNotEqualTo(identity.key());
        var otherPatient = AppointmentBookingIdentity.staff(actor, UUID.randomUUID(), hospital, r);
        assertThatThrownBy(() -> otherPatient.replay(repository)).isInstanceOf(FieldBusinessException.class);
        var portal = PortalBookAppointmentRequestDTO.builder().hospitalId(hospital)
            .bookingRequestId(r.getBookingRequestId()).build();
        assertThat(AppointmentBookingIdentity.portal(actor, patient, portal).key()).isNotEqualTo(identity.key());
    }

    @Test
    void anyClinicianPortalRetryKeepsIdentityWithoutChoosingAnotherProvider() {
        var r = PortalBookAppointmentRequestDTO.builder().hospitalId(hospital).departmentId(UUID.randomUUID())
            .date(LocalDate.now().plusDays(1)).startTime(LocalTime.NOON).build();
        var identity = AppointmentBookingIdentity.portal(actor, patient, r);
        Appointment appointment = recorded(identity, AppointmentStatus.SCHEDULED);
        r.setEndTime(LocalTime.MAX);
        assertThat(AppointmentBookingIdentity.portal(actor, patient, r).replay(repository)).contains(appointment);
        assertThat(identity.key()).hasSize(64).doesNotContain("null");
    }

    @Test
    void aTransferredAppointmentCannotBeDisclosedThroughItsOldBookingRequest() {
        var r = staffRequest();
        r.setBookingRequestId(UUID.randomUUID());
        var identity = staff(r);
        Appointment a = recorded(identity, AppointmentStatus.SCHEDULED);
        a.getHospital().setId(UUID.randomUUID());
        assertThatThrownBy(() -> identity.replay(repository)).isInstanceOf(FieldBusinessException.class);
        a.getHospital().setId(hospital);
        a.getPatient().setId(UUID.randomUUID());
        assertThatThrownBy(() -> identity.replay(repository)).isInstanceOf(FieldBusinessException.class);
    }

    @Test
    void aLegacyRequestInitiallyCreatingAClosedVisitStillDeduplicates() {
        var r = staffRequest();
        r.setStatus(AppointmentStatus.CANCELLED);
        var identity = staff(r);
        Appointment a = recorded(identity, AppointmentStatus.CANCELLED);
        identity.stamp(a);
        assertThat(identity.replay(repository)).contains(a);
        assertThat(a.isBookingRequestDurable()).isTrue();
    }

    @ParameterizedTest
    @MethodSource("slotChanges")
    void movingALegacyVisitReleasesTheOriginalIntentForBothBookingRoutes(Consumer<Appointment> move) {
        var request = staffRequest();
        var staffIdentity = staff(request);
        var portalRequest = PortalBookAppointmentRequestDTO.builder().hospitalId(hospital)
            .date(request.getAppointmentDate()).startTime(request.getStartTime()).build();
        var portalIdentity = AppointmentBookingIdentity.portal(actor, patient, portalRequest);
        for (var identity : List.of(staffIdentity, portalIdentity)) {
            Appointment appointment = recorded(identity, AppointmentStatus.SCHEDULED);
            move.accept(appointment);
            assertThat(appointment.getBookingRequestKey()).isNull();
            assertThat(appointment.getBookingRequestFingerprint()).isNull();
            assertThat(identity.replay(repository)).isEmpty();
        }
    }

    @ParameterizedTest
    @MethodSource("slotChanges")
    void explicitIntentSurvivesScheduleEdits(Consumer<Appointment> move) {
        var request = staffRequest();
        request.setBookingRequestId(UUID.randomUUID());
        var identity = staff(request);
        Appointment appointment = recorded(identity, AppointmentStatus.SCHEDULED);
        move.accept(appointment);
        assertThat(identity.replay(repository)).contains(appointment);
    }

    @Test
    void noteAndUnchangedScheduleEditsKeepLegacyRetryProtection() {
        var identity = staff(staffRequest());
        Appointment appointment = recorded(identity, AppointmentStatus.SCHEDULED);
        appointment.setAppointmentDate(appointment.getAppointmentDate());
        appointment.setStartTime(appointment.getStartTime());
        appointment.setStaff(appointment.getStaff());
        appointment.setDepartment(appointment.getDepartment());
        appointment.setNotes("A clinical note edited after booking");
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        assertThat(identity.replay(repository)).contains(appointment);
    }

    @Test
    void reinstatingAClosedLegacyVisitCannotReclaimItsReleasedKey() {
        var identity = staff(staffRequest());
        Appointment appointment = recorded(identity, AppointmentStatus.SCHEDULED);
        appointment.setStatus(AppointmentStatus.CANCELLED);
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        assertThat(appointment.getBookingRequestKey()).isNull();
        assertThat(identity.replay(repository)).isEmpty();
    }

    @Test
    void reinstatementAlsoReleasesLegacyKeysLeftByAnOlderWriter() {
        Appointment appointment = Appointment.builder().status(AppointmentStatus.CANCELLED)
            .bookingRequestKey("legacy-key-from-before-release-fix")
            .bookingRequestFingerprint("legacy-payload").bookingRequestDurable(false).build();
        appointment.setStatus(AppointmentStatus.SCHEDULED);
        assertThat(appointment.getBookingRequestKey()).isNull();
        assertThat(appointment.getBookingRequestFingerprint()).isNull();
    }

    @Test
    void everyBookingDtoFieldHasAnExplicitIdentityClassification() {
        assertThat(instanceFields(AppointmentRequestDTO.class)).containsExactlyInAnyOrderElementsOf(Set.of(
            // Hashed payload fields:
            "appointmentDate", "startTime", "staffId", "staffEmail", "staffUsername", "departmentId",
            "departmentName", "departmentCode", "recallId", "status", "reason", "notes", "statusReason", "overbookReason",
            // Resolved to a canonical patient before hashing:
            "patientId", "patientUsername", "patientEmail",
            // Resolved to the key's hospital scope before hashing:
            "hospitalId", "hospitalCode", "hospitalName",
            // Operation identity, and deliberately ignored client fields:
            "bookingRequestId", "id", "endTime"));
        assertThat(instanceFields(PortalBookAppointmentRequestDTO.class)).containsExactlyInAnyOrderElementsOf(Set.of(
            "bookingRequestId", "hospitalId", "departmentId", "staffId", "date", "startTime", "reason", "notes", "endTime"));
    }

    private static List<String> instanceFields(Class<?> type) {
        return Arrays.stream(type.getDeclaredFields())
            .filter(field -> !java.lang.reflect.Modifier.isStatic(field.getModifiers()))
            .map(java.lang.reflect.Field::getName).toList();
    }

    private static Stream<Consumer<Appointment>> slotChanges() {
        return Stream.of(a -> a.setAppointmentDate(a.getAppointmentDate().plusDays(3)),
            a -> a.setStartTime(a.getStartTime().plusHours(1)),
            a -> { var staff = new com.example.hms.model.Staff(); staff.setId(UUID.randomUUID()); a.setStaff(staff); },
            a -> { var department = new com.example.hms.model.Department(); department.setId(UUID.randomUUID());
                a.setDepartment(department); });
    }

    private AppointmentBookingIdentity staff(AppointmentRequestDTO r) {
        return AppointmentBookingIdentity.staff(actor, patient, hospital, r);
    }

    private Appointment recorded(AppointmentBookingIdentity identity, AppointmentStatus status) {
        Appointment a = new Appointment();
        a.setStatus(AppointmentStatus.SCHEDULED);
        var owner = new com.example.hms.model.Patient();
        owner.setId(patient);
        a.setPatient(owner);
        var site = new com.example.hms.model.Hospital();
        site.setId(hospital);
        a.setHospital(site);
        a.setAppointmentDate(identity.date());
        a.setStartTime(identity.startTime());
        identity.stamp(a);
        a.setStatus(status);
        when(repository.findAllByBookingRequestKey(identity.key())).thenReturn(List.of(a));
        return a;
    }

    private AppointmentRequestDTO staffRequest() {
        return AppointmentRequestDTO.builder().appointmentDate(LocalDate.now().plusDays(1))
            .startTime(LocalTime.NOON).build();
    }
}
