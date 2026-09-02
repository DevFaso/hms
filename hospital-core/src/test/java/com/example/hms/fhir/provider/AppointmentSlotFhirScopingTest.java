package com.example.hms.fhir.provider;

import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.fhir.mapper.AppointmentFhirMapper;
import com.example.hms.fhir.mapper.SlotFhirMapper;
import com.example.hms.model.Appointment;
import com.example.hms.model.Hospital;
import com.example.hms.model.scheduling.AppointmentSlot;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.scheduling.AppointmentSlotRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.hl7.fhir.r4.model.IdType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Appointment + Slot tenant contract (Tier 2 item 43): scope required
 * before any repository call, foreign rows collapse to not-found. Every
 * foreign-row test stubs its mapper non-null — the false-guarantee lesson
 * from the DiagnosticReport mutation run applies verbatim here.
 */
@ExtendWith(MockitoExtension.class)
class AppointmentSlotFhirScopingTest {

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private AppointmentSlotRepository slotRepository;
    @Mock private AppointmentFhirMapper appointmentMapper;
    @Mock private SlotFhirMapper slotMapper;

    private AppointmentFhirResourceProvider appointmentProvider;
    private SlotFhirResourceProvider slotProvider;
    private UUID activeHospitalId;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-02T10:00:00Z"), ZoneOffset.UTC);
        appointmentProvider = new AppointmentFhirResourceProvider(
            appointmentRepository, appointmentMapper);
        slotProvider = new SlotFhirResourceProvider(slotRepository, slotMapper, clock);
        activeHospitalId = UUID.randomUUID();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .activeHospitalId(activeHospitalId).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    private static Hospital hospitalWithId(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    @Test
    @DisplayName("no hospital scope is a hard 403 before any repository is touched")
    void readWithoutScopeIsForbidden() {
        HospitalContextHolder.clear();
        IdType appointmentId = new IdType(UUID.randomUUID().toString());
        IdType slotId = new IdType(UUID.randomUUID().toString());

        assertThrows(ForbiddenOperationException.class,
            () -> appointmentProvider.read(appointmentId));
        assertThrows(ForbiddenOperationException.class, () -> slotProvider.read(slotId));
        verifyNoInteractions(appointmentRepository, slotRepository);
    }

    @Test
    @DisplayName("another hospital's appointment collapses to not-found")
    void foreignAppointmentIsNotFound() {
        UUID appointmentId = UUID.randomUUID();
        Appointment foreign = new Appointment();
        foreign.setId(appointmentId);
        foreign.setHospital(hospitalWithId(UUID.randomUUID()));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(foreign));
        Mockito.lenient().when(appointmentMapper.toFhir(foreign))
            .thenReturn(new org.hl7.fhir.r4.model.Appointment());
        IdType id = new IdType(appointmentId.toString());

        assertThrows(ResourceNotFoundException.class, () -> appointmentProvider.read(id));
        verifyNoInteractions(appointmentMapper);
    }

    @Test
    @DisplayName("another hospital's slot collapses to not-found")
    void foreignSlotIsNotFound() {
        UUID slotId = UUID.randomUUID();
        AppointmentSlot foreign = new AppointmentSlot();
        foreign.setId(slotId);
        foreign.setHospital(hospitalWithId(UUID.randomUUID()));
        when(slotRepository.findById(slotId)).thenReturn(Optional.of(foreign));
        Mockito.lenient().when(slotMapper.toFhir(foreign))
            .thenReturn(new org.hl7.fhir.r4.model.Slot());
        IdType id = new IdType(slotId.toString());

        assertThrows(ResourceNotFoundException.class, () -> slotProvider.read(id));
        verifyNoInteractions(slotMapper);
    }

    @Test
    @DisplayName("appointment search without a patient returns empty rather than the whole hospital")
    void appointmentSearchWithoutPatientIsEmpty() {
        org.assertj.core.api.Assertions.assertThat(appointmentProvider.search(null, null))
            .isEmpty();
        verifyNoInteractions(appointmentRepository);
    }
}
