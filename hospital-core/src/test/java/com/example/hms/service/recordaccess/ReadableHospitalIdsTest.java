package com.example.hms.service.recordaccess;

import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.enums.TreatmentRelationshipKind;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRecordSharingOptOutRepository;
import com.example.hms.repository.StaffRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * E8 #49 — which hospitals a caller may read for a patient.
 *
 * <p>The flag-off case is the one that matters most: it must be exactly
 * pre-E8 behaviour, because that is what ships to production until the
 * sensitive categories are classified.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("RecordAccessPolicyImpl.readableHospitalIds")
class ReadableHospitalIdsTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientRecordSharingOptOutRepository optOutRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private TreatmentRelationshipResolver resolver;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private BreakGlassGate breakGlassGate;
    @Mock private com.example.hms.repository.PatientRepository patientRepository;

    private RecordAccessPolicyImpl policy;

    private final UUID actor = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private final UUID actingId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private Hospital other;

    @BeforeEach
    void setUp() {
        policy = new RecordAccessPolicyImpl(hospitalRepository, optOutRepository, staffRepository,
            resolver, registrationRepository, breakGlassGate, patientRepository);

        Hospital acting = hospital(actingId, TenantIsolationMode.ROW_LEVEL, RecordAccessPosture.TREATMENT_PRESUMED);
        other = hospital(otherId, TenantIsolationMode.ROW_LEVEL, RecordAccessPosture.TREATMENT_PRESUMED);

        when(hospitalRepository.findById(actingId)).thenReturn(Optional.of(acting));
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(false);
        Staff staff = new Staff();
        staff.setActive(true);
        when(staffRepository.findByUserIdAndHospitalId(actor, actingId)).thenReturn(Optional.of(staff));
        when(resolver.resolve(patientId, actingId, actor)).thenReturn(Optional.of(new TreatmentRelationship(
            TreatmentRelationshipKind.OPEN_ENCOUNTER, UUID.randomUUID(), actingId, patientId, null, null, true)));
        when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(registration(other)));

        when(registrationRepository.findByPatientIdAndHospitalId(patientId, actingId)).thenReturn(Optional.empty());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static Hospital hospital(UUID id, TenantIsolationMode mode, RecordAccessPosture posture) {
        Hospital h = new Hospital();
        h.setId(id);
        h.setIsolationMode(mode);
        h.setRecordAccessPosture(posture);
        return h;
    }

    private PatientHospitalRegistration registration(Hospital h) {
        PatientHospitalRegistration r = new PatientHospitalRegistration();
        Patient p = new Patient();
        p.setId(patientId);
        r.setPatient(p);
        r.setHospital(h);
        return r;
    }

    @Test
    @DisplayName("E9 #58 — registered at the acting hospital: the patient's other hospitals are readable with no other carrier")
    void registrationWidens() {
        when(resolver.resolve(patientId, actingId, actor)).thenReturn(Optional.empty());
        com.example.hms.model.PatientHospitalRegistration here = registration(hospital(actingId, TenantIsolationMode.ROW_LEVEL, RecordAccessPosture.TREATMENT_PRESUMED));
        when(registrationRepository.findByPatientIdAndHospitalId(patientId, actingId)).thenReturn(Optional.of(here));

        assertThat(policy.readableHospitalIds(actor, patientId, actingId))
            .containsExactlyInAnyOrder(actingId, otherId);
    }

    @Test
    @DisplayName("a treatment relationship adds the patient's other hospitals")
    void widensWhenPermitted() {
        assertThat(policy.readableHospitalIds(actor, patientId, actingId))
            .containsExactlyInAnyOrder(actingId, otherId);
    }

    @Test
    @DisplayName("no treatment relationship keeps the caller in their own hospital")
    void noRelationshipDoesNotWiden() {
        when(resolver.resolve(patientId, actingId, actor)).thenReturn(Optional.empty());

        assertThat(policy.readableHospitalIds(actor, patientId, actingId))
            .containsExactly(actingId);
    }

    @Test
    @DisplayName("an opted-out patient is never read across hospitals")
    void optOutBlocksWidening() {
        when(optOutRepository.existsByPatient_IdAndRevokedAtIsNull(patientId)).thenReturn(true);

        assertThat(policy.readableHospitalIds(actor, patientId, actingId))
            .containsExactly(actingId);
    }

    @Test
    @DisplayName("a source hospital on EXPLICIT_CONSENT keeps its own records back")
    void sourcePostureGoverns() {
        // The reader's hospital presumes treatment; the SOURCE does not. Its
        // records stay behind consent — the posture is checked at both ends.
        other.setRecordAccessPosture(RecordAccessPosture.EXPLICIT_CONSENT);

        assertThat(policy.readableHospitalIds(actor, patientId, actingId))
            .containsExactly(actingId);
    }

    @Test
    @DisplayName("a SCHEMA-isolated source is never readable, whatever its posture says")
    void schemaIsolatedSourceExcluded() {
        other.setIsolationMode(TenantIsolationMode.SCHEMA);

        assertThat(policy.readableHospitalIds(actor, patientId, actingId))
            .containsExactly(actingId);
    }

    @Test
    @DisplayName("the acting hospital is present even with no patient and no context")
    void actingHospitalAlwaysPresent() {
        assertThat(policy.readableHospitalIds(actor, null, actingId)).containsExactly(actingId);
        assertThat(policy.readableHospitalIds(null, patientId, null)).isEmpty();
    }

    @Test
    @DisplayName("a registration with no hospital is skipped rather than throwing")
    void nullHospitalRegistrationSkipped() {
        when(registrationRepository.findByPatientId(patientId))
            .thenReturn(List.of(registration(null), registration(other)));

        assertThat(policy.readableHospitalIds(actor, patientId, actingId))
            .containsExactlyInAnyOrder(actingId, otherId);
    }

    @Test
    @DisplayName("the acting hospital is not duplicated when it also appears as a registration")
    void noDuplicateActingHospital() {
        Hospital actingAgain = hospital(actingId, TenantIsolationMode.ROW_LEVEL,
            RecordAccessPosture.TREATMENT_PRESUMED);
        when(registrationRepository.findByPatientId(patientId))
            .thenReturn(List.of(registration(actingAgain), registration(other)));

        Set<UUID> readable = policy.readableHospitalIds(actor, patientId, actingId);

        assertThat(readable).containsExactlyInAnyOrder(actingId, otherId).hasSize(2);
    }
}
