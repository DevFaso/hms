package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.MannerOfDeath;
import com.example.hms.enums.MaternalDeathTiming;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PlaceOfDeath;
import com.example.hms.enums.RecallStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.MortalityMapper;
import com.example.hms.model.Admission;
import com.example.hms.model.Appointment;
import com.example.hms.model.DeathRecord;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.payload.dto.mortality.DeathRecordAmendmentDTO;
import com.example.hms.payload.dto.mortality.DeathRecordRequestDTO;
import com.example.hms.payload.dto.mortality.MortalityRegisterDTO;
import com.example.hms.payload.dto.mortality.RecordDeathResponseDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DeathRecordRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import com.example.hms.service.impl.MortalityServiceImpl;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Death and mortality (Tier 2 item 29).
 *
 * <p>Two things are pinned hardest here. The CASCADE, because every record it
 * closes is one that would otherwise keep running against a person who has died
 * — and the reminder sweep in particular would text the family. And the WHO
 * MATERNAL DEATH BOUNDARY, because a late maternal death counted as a maternal
 * death overstates the ratio the facility is judged on.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MortalityServiceImplTest {

    @Mock private DeathRecordRepository deathRecordRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRecallRepository recallRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientChartAccess patientChartAccess;
    @Mock private RoleValidator roleValidator;
    @Spy private MortalityMapper mapper = new MortalityMapper();

    @InjectMocks private MortalityServiceImpl service;

    private UUID hospitalId;
    private UUID patientId;
    private UUID callerUserId;
    private Hospital hospital;
    private Patient patient;
    private Staff caller;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        callerUserId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);

        caller = new Staff();
        caller.setId(UUID.randomUUID());
        caller.setHospital(hospital);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(roleValidator.getCurrentUserId()).thenReturn(callerUserId);
        when(staffRepository.findByUserIdAndHospitalId(callerUserId, hospitalId)).thenReturn(Optional.of(caller));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(patientChartAccess.require(any(), any())).thenReturn(patient);
        when(deathRecordRepository.existsByPatient_Id(patientId)).thenReturn(false);
        when(deathRecordRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(patientRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId)).thenReturn(List.of());
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of());
        when(appointmentRepository.findByPatient_IdAndAppointmentDateAfter(any(), any())).thenReturn(List.of());
        when(recallRepository.findByPatient_IdAndStatusIn(any(), any())).thenReturn(List.of());
    }

    private DeathRecordRequestDTO.DeathRecordRequestDTOBuilder base() {
        return DeathRecordRequestDTO.builder()
            .patientId(patientId)
            .diedAt(LocalDateTime.now().minusHours(2))
            .placeOfDeath(PlaceOfDeath.FACILITY)
            .mannerOfDeath(MannerOfDeath.NATURAL)
            .immediateCause("Hypovolaemic shock");
    }

    // ── Recording ───────────────────────────────────────────────────────

    @Test
    void recordingADeathMarksThePatientDeceased() {
        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(patient.getDeceasedAt()).isNotNull();
        assertThat(patient.isDeceased()).isTrue();
        assertThat(result.getRecord().getImmediateCause()).isEqualTo("Hypovolaemic shock");
        verify(patientRepository).save(patient);
    }

    @Test
    void aSecondDeathRecordIsRefused() {
        when(deathRecordRepository.existsByPatient_Id(patientId)).thenReturn(true);

        assertThatThrownBy(() -> service.recordDeath(base().build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already recorded");
        verify(deathRecordRepository, never()).save(any());
    }

    @Test
    void aDeathCannotBeRecordedInTheFuture() {
        assertThatThrownBy(() -> service.recordDeath(base()
            .diedAt(LocalDateTime.now().plusDays(1)).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot be in the future");
    }

    // ── The cascade ─────────────────────────────────────────────────────

    @Test
    void recordingADeathClosesTheOpenAdmission() {
        Admission open = new Admission();
        open.setId(UUID.randomUUID());
        open.setHospital(hospital);
        open.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(open));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        // AdmissionStatus.DECEASED has existed since V1 with no writer at all.
        assertThat(open.getStatus()).isEqualTo(AdmissionStatus.DECEASED);
        assertThat(result.getClosure().getAdmissionsClosed()).isEqualTo(1);
    }

    @Test
    void anAlreadyDischargedAdmissionIsLeftAlone() {
        Admission done = new Admission();
        done.setId(UUID.randomUUID());
        done.setHospital(hospital);
        done.setStatus(AdmissionStatus.DISCHARGED);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(done));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(done.getStatus()).isEqualTo(AdmissionStatus.DISCHARGED);
        assertThat(result.getClosure().getAdmissionsClosed()).isZero();
    }

    @Test
    void recordingADeathClosesOpenEncounters() {
        Encounter open = new Encounter();
        open.setId(UUID.randomUUID());
        open.setHospital(hospital);
        open.setStatus(EncounterStatus.IN_PROGRESS);
        when(encounterRepository.findByPatient_Id(patientId)).thenReturn(List.of(open));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(open.getStatus()).isEqualTo(EncounterStatus.COMPLETED);
        assertThat(result.getClosure().getEncountersClosed()).isEqualTo(1);
    }

    @Test
    void recordingADeathCancelsFutureAppointments() {
        // This is the one that matters most: the reminder sweep selects purely
        // on appointment state, so a live appointment here means a text to the
        // family of someone who died.
        Appointment upcoming = new Appointment();
        upcoming.setId(UUID.randomUUID());
        upcoming.setHospital(hospital);
        upcoming.setStatus(AppointmentStatus.SCHEDULED);
        when(appointmentRepository.findByPatient_IdAndAppointmentDateAfter(any(), any()))
            .thenReturn(List.of(upcoming));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(upcoming.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(upcoming.getNotes()).contains("Patient deceased");
        assertThat(result.getClosure().getAppointmentsCancelled()).isEqualTo(1);
    }

    @Test
    void anAlreadyCompletedAppointmentIsNotRewritten() {
        // A past appointment the patient actually attended is history.
        Appointment attended = new Appointment();
        attended.setId(UUID.randomUUID());
        attended.setHospital(hospital);
        attended.setStatus(AppointmentStatus.COMPLETED);
        when(appointmentRepository.findByPatient_IdAndAppointmentDateAfter(any(), any()))
            .thenReturn(List.of(attended));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(attended.getStatus()).isEqualTo(AppointmentStatus.COMPLETED);
        assertThat(result.getClosure().getAppointmentsCancelled()).isZero();
    }

    @Test
    void recordingADeathClosesPendingRecalls() {
        PatientRecall recall = new PatientRecall();
        recall.setId(UUID.randomUUID());
        recall.setHospital(hospital);
        recall.setStatus(RecallStatus.PENDING);
        when(recallRepository.findByPatient_IdAndStatusIn(any(), any())).thenReturn(List.of(recall));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(recall.getStatus()).isEqualTo(RecallStatus.CANCELLED);
        assertThat(result.getClosure().getRecallsClosed()).isEqualTo(1);
    }

    @Test
    void theCascadeStaysInsideTheRecordingHospital() {
        // A patient linked to two facilities has open records at each. Closing
        // another facility's ward entry would be acting outside scope.
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Admission foreign = new Admission();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        foreign.setStatus(AdmissionStatus.ACTIVE);
        when(admissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc(patientId))
            .thenReturn(List.of(foreign));

        RecordDeathResponseDTO result = service.recordDeath(base().build());

        assertThat(foreign.getStatus()).isEqualTo(AdmissionStatus.ACTIVE);
        assertThat(result.getClosure().getAdmissionsClosed()).isZero();
    }

    // ── The WHO maternal boundary ───────────────────────────────────────

    @Test
    void aMaternalDeathMustSayWhenItOccurred() {
        assertThatThrownBy(() -> service.recordDeath(base()
            .maternalDeath(Boolean.TRUE).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("when it occurred relative to the pregnancy");
    }

    @Test
    void aDeathWithin42DaysPostpartumIsAWhoMaternalDeath() {
        RecordDeathResponseDTO result = service.recordDeath(base()
            .maternalDeath(Boolean.TRUE)
            .maternalDeathTiming(MaternalDeathTiming.WITHIN_42_DAYS_POSTPARTUM)
            .build());

        assertThat(result.getRecord().getWhoMaternalDeath()).isTrue();
    }

    @Test
    void aLateMaternalDeathIsNotAWhoMaternalDeath() {
        // 42 days to a year falls OUTSIDE the WHO definition and is reported
        // separately. Counting it in would overstate the facility's ratio.
        RecordDeathResponseDTO result = service.recordDeath(base()
            .maternalDeath(Boolean.TRUE)
            .maternalDeathTiming(MaternalDeathTiming.LATE_MATERNAL)
            .build());

        assertThat(result.getRecord().getMaternalDeath()).isTrue();
        assertThat(result.getRecord().getWhoMaternalDeath()).isFalse();
    }

    @Test
    void aPerinatalDeathMustSayWhetherItWasAStillbirth() {
        // A stillborn infant was never born alive, so it has no neonatal
        // period and the two are never summed.
        assertThatThrownBy(() -> service.recordDeath(base()
            .perinatalDeath(Boolean.TRUE).build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("stillbirth or a neonatal death");
    }

    // ── Amendment ───────────────────────────────────────────────────────

    private DeathRecord persisted() {
        DeathRecord record = DeathRecord.builder()
            .patient(patient)
            .hospital(hospital)
            .diedAt(LocalDateTime.now().minusDays(3))
            .placeOfDeath(PlaceOfDeath.FACILITY)
            .mannerOfDeath(MannerOfDeath.NATURAL)
            .immediateCause("Cardiac arrest")
            .maternalDeath(Boolean.FALSE)
            .perinatalDeath(Boolean.FALSE)
            .autopsyRequested(Boolean.TRUE)
            .build();
        record.setId(UUID.randomUUID());
        when(deathRecordRepository.findById(record.getId())).thenReturn(Optional.of(record));
        return record;
    }

    @Test
    void anAutopsyCanReviseTheCause() {
        DeathRecord record = persisted();

        service.amendDeathRecord(record.getId(), DeathRecordAmendmentDTO.builder()
            .amendmentReason("Post-mortem findings")
            .underlyingCause("Pulmonary embolism")
            .underlyingCauseCode("I26.9")
            .build());

        assertThat(record.getUnderlyingCause()).isEqualTo("Pulmonary embolism");
        assertThat(record.isAmended()).isTrue();
        assertThat(record.getAmendmentReason()).isEqualTo("Post-mortem findings");
        // The FACT of death is untouched — only the account of it.
        assertThat(record.getDiedAt()).isNotNull();
    }

    @Test
    void anAmendmentRequiresAReason() {
        DeathRecord record = persisted();

        assertThatThrownBy(() -> service.amendDeathRecord(record.getId(),
            DeathRecordAmendmentDTO.builder().amendmentReason("  ").build()))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void anAmendmentCannotMakeADeathMaternalWithoutSayingWhen() {
        // Re-checked AFTER the edits: otherwise the reporting query breaks on
        // a maternal death with no timing.
        DeathRecord record = persisted();

        assertThatThrownBy(() -> service.amendDeathRecord(record.getId(),
            DeathRecordAmendmentDTO.builder()
                .amendmentReason("Reclassified after review")
                .maternalDeath(Boolean.TRUE)
                .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("when it occurred");
    }

    @Test
    void anAmendmentCannotMakeADeathPerinatalWithoutAType() {
        DeathRecord record = persisted();

        assertThatThrownBy(() -> service.amendDeathRecord(record.getId(),
            DeathRecordAmendmentDTO.builder()
                .amendmentReason("Reclassified after review")
                .perinatalDeath(Boolean.TRUE)
                .build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("stillbirth or a neonatal death");
    }

    // ── Register ────────────────────────────────────────────────────────

    private DeathRecord death(MaternalDeathTiming maternal, PerinatalDeathType perinatal) {
        return DeathRecord.builder()
            .patient(patient)
            .hospital(hospital)
            .diedAt(LocalDateTime.now().minusDays(1))
            .immediateCause("cause")
            .maternalDeath(maternal != null)
            .maternalDeathTiming(maternal)
            .perinatalDeath(perinatal != null)
            .perinatalType(perinatal)
            .build();
    }

    @Test
    void theRegisterBreaksOutTheCountsTheFacilityIsMeasuredOn() {
        when(deathRecordRepository.findRegister(any(), any(), any())).thenReturn(List.of(
            death(MaternalDeathTiming.WITHIN_42_DAYS_POSTPARTUM, null),
            death(MaternalDeathTiming.DURING_LABOUR_OR_DELIVERY, null),
            death(MaternalDeathTiming.LATE_MATERNAL, null),
            death(null, PerinatalDeathType.STILLBIRTH),
            death(null, PerinatalDeathType.EARLY_NEONATAL),
            death(null, null)));

        MortalityRegisterDTO register = service.getRegister(
            LocalDate.now().minusDays(30), LocalDate.now());

        assertThat(register.getTotalDeaths()).isEqualTo(6);
        // The late maternal death is NOT in the maternal count.
        assertThat(register.getMaternalDeaths()).isEqualTo(2);
        assertThat(register.getLateMaternalDeaths()).isEqualTo(1);
        assertThat(register.getPerinatalDeaths()).isEqualTo(2);
        assertThat(register.getStillbirths()).isEqualTo(1);
    }

    @Test
    void theRegisterRefusesAnInvertedPeriod() {
        assertThatThrownBy(() -> service.getRegister(LocalDate.now(), LocalDate.now().minusDays(7)))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("cannot precede");
    }

    @Test
    void theRegisterNeedsAPeriod() {
        assertThatThrownBy(() -> service.getRegister(null, LocalDate.now()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("period is required");
    }

    // ── Reads and tenancy ───────────────────────────────────────────────

    @Test
    void aPatientDeathRecordIsReadable() {
        DeathRecord record = persisted();
        when(deathRecordRepository.findByPatient_Id(patientId)).thenReturn(Optional.of(record));

        assertThat(service.getForPatient(patientId).getImmediateCause()).isEqualTo("Cardiac arrest");
    }

    @Test
    void aLivingPatientHasNoDeathRecord() {
        when(deathRecordRepository.findByPatient_Id(patientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getForPatient(patientId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aRecordAtAnotherHospitalIsNotFoundRatherThanForbidden() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        DeathRecord foreign = DeathRecord.builder().hospital(other).build();
        UUID id = UUID.randomUUID();
        foreign.setId(id);
        when(deathRecordRepository.findById(id)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.amendDeathRecord(id,
            DeathRecordAmendmentDTO.builder().amendmentReason("Not mine").build()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void aSuperAdminWithNoActiveHospitalCannotCertifyADeath() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);

        assertThatThrownBy(() -> service.recordDeath(base().build()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital is required");
    }

    @Test
    void aCertifierAtAnotherHospitalIsRejected() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        Staff foreign = new Staff();
        foreign.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(staffRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.recordDeath(base()
            .certifiedByStaffId(foreign.getId()).build()))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void namingACertifierStampsTheCertificationTime() {
        Staff certifier = new Staff();
        certifier.setId(UUID.randomUUID());
        certifier.setHospital(hospital);
        when(staffRepository.findById(certifier.getId())).thenReturn(Optional.of(certifier));

        RecordDeathResponseDTO result = service.recordDeath(base()
            .certifiedByStaffId(certifier.getId()).build());

        assertThat(result.getRecord().getCertifiedAt()).isNotNull();
    }
}
