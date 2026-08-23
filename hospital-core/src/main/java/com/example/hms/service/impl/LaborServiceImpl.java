package com.example.hms.service.impl;

import com.example.hms.utility.ElapsedTime;
import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborAlertType;
import com.example.hms.enums.LaborOutcome;
import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.LiquorColour;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LaborMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordResponseDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeResponseDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryRequestDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryResponseDTO;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.LaborPartographEntryRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.LaborService;
import com.example.hms.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Labor & Delivery (P1 #6, roadmap row 41). Follows the OB house pattern of
 * {@code PostpartumCareServiceImpl}: explicit resolvers, threshold constants,
 * alerts computed by small evaluators onto the entity's embedded alert list,
 * URGENT alerts dispatched best-effort so a notification failure never rolls
 * back the clinical write.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class LaborServiceImpl implements LaborService {

    /* ── Clinical thresholds ─────────────────────────────────────────── */

    static final int FHR_BRADYCARDIA_BPM = 110;
    static final int FHR_TACHYCARDIA_BPM = 160;
    static final int ACTIVE_PHASE_DILATION_CM = 4;
    static final double ACTION_LINE_LAG_HOURS = 4.0;
    static final double MATERNAL_FEVER_C = 38.0;
    static final int MATERNAL_TACHYCARDIA_BPM = 120;
    static final int SEVERE_HTN_SYSTOLIC = 160;
    static final int SEVERE_HTN_DIASTOLIC = 110;
    static final int HTN_SYSTOLIC = 140;
    static final int HTN_DIASTOLIC = 90;
    static final int PPH_BLOOD_LOSS_ML = 500;
    static final int APGAR_LOW = 7;
    static final int APGAR_CRITICAL = 4;
    static final int TERM_WEEKS = 40;

    private final LaborEpisodeRepository episodeRepository;
    private final LaborPartographEntryRepository entryRepository;
    private final DeliveryRecordRepository deliveryRecordRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final MaternalHistoryRepository maternalHistoryRepository;
    private final StaffRepository staffRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final LaborMapper laborMapper;

    /* ═══════════════════════ Episodes ═══════════════════════ */

    @Override
    public LaborEpisodeResponseDTO startEpisode(UUID patientId, UUID recorderUserId, LaborEpisodeRequestDTO request) {
        Objects.requireNonNull(patientId, "patientId is required");
        if (request == null) {
            throw new BusinessException("Labor episode payload is required.");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("Patient not found with ID: " + patientId));
        PatientHospitalRegistration registration = resolveRegistration(request.getRegistrationId());
        Hospital hospital = resolveHospital(registration, request.getHospitalId());

        if (episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospital.getId(), LaborStatus.ACTIVE)) {
            throw new BusinessException("An active labor episode already exists for this patient at this hospital.");
        }

        Staff admittedBy = resolveStaff(request.getAdmittedByStaffId(), hospital);
        User documentedBy = resolveUser(recorderUserId);
        MaternalHistory history = maternalHistoryRepository.findCurrentByPatientId(patientId).orElse(null);

        LaborEpisode episode = LaborEpisode.builder()
            .patient(patient)
            .hospital(hospital)
            .registration(registration)
            .maternalHistory(history)
            .admittedByStaff(admittedBy)
            .documentedBy(documentedBy)
            .laborOnsetAt(request.getLaborOnsetAt())
            .admittedAt(request.getAdmittedAt() != null ? request.getAdmittedAt() : LocalDateTime.now())
            .membraneStatus(request.getMembraneStatus())
            .membraneRuptureAt(request.getMembraneRuptureAt())
            .gestationalAgeWeeks(resolveGestationalAgeWeeks(request.getGestationalAgeWeeks(), history))
            .gravida(request.getGravida() != null ? request.getGravida()
                : history != null ? history.getGravida() : null)
            .para(request.getPara() != null ? request.getPara()
                : history != null ? history.getPara() : null)
            .riskNotes(request.getRiskNotes())
            .build();

        LaborEpisode saved = episodeRepository.save(episode);
        return laborMapper.toEpisodeResponse(saved, 0, false);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LaborEpisodeResponseDTO> getEpisodes(UUID patientId, UUID hospitalId, int limit) {
        Objects.requireNonNull(patientId, "patientId is required");
        if (hospitalId == null) {
            throw new BusinessException("Hospital context required to list labor episodes.");
        }
        int effectiveLimit = Math.clamp(limit <= 0 ? 10 : limit, 1, 50);
        return episodeRepository
            .findByPatient_IdAndHospital_IdOrderByAdmittedAtDesc(patientId, hospitalId, PageRequest.of(0, effectiveLimit))
            .stream()
            .map(episode -> laborMapper.toEpisodeResponse(
                episode,
                entryRepository.countByEpisode_Id(episode.getId()),
                deliveryRecordRepository.existsByEpisode_Id(episode.getId())))
            .toList();
    }

    /* ═══════════════════════ Partograph entries ═══════════════════════ */

    @Override
    public PartographEntryResponseDTO addEntry(UUID patientId, UUID episodeId, UUID recorderUserId,
                                               PartographEntryRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Partograph entry payload is required.");
        }
        LaborEpisode episode = loadEpisode(patientId, episodeId, request.getHospitalId());
        if (episode.getStatus() != LaborStatus.ACTIVE) {
            throw new BusinessException("Partograph entries can only be added to an active labor episode.");
        }

        Staff recordedBy = resolveStaff(request.getRecordedByStaffId(), episode.getHospital());
        User documentedBy = resolveUser(recorderUserId);

        LaborPartographEntry entry = LaborPartographEntry.builder()
            .episode(episode)
            .patient(episode.getPatient())
            .hospital(episode.getHospital())
            .recordedByStaff(recordedBy)
            .documentedBy(documentedBy)
            .observationTime(request.getObservationTime() != null ? request.getObservationTime() : LocalDateTime.now())
            .documentedAt(LocalDateTime.now())
            .lateEntry(Boolean.TRUE.equals(request.getLateEntry()))
            .fetalHeartRateBpm(request.getFetalHeartRateBpm())
            .liquorColour(request.getLiquorColour())
            .mouldingDegree(request.getMouldingDegree())
            .cervicalDilationCm(request.getCervicalDilationCm())
            .descentFifths(request.getDescentFifths())
            .contractionsPerTenMinutes(request.getContractionsPerTenMinutes())
            .contractionDurationSeconds(request.getContractionDurationSeconds())
            .oxytocinDropsPerMinute(request.getOxytocinDropsPerMinute())
            .drugsGiven(request.getDrugsGiven())
            .ivFluids(request.getIvFluids())
            .pulseBpm(request.getPulseBpm())
            .systolicBpMmHg(request.getSystolicBpMmHg())
            .diastolicBpMmHg(request.getDiastolicBpMmHg())
            .temperatureCelsius(request.getTemperatureCelsius())
            .urineOutputMl(request.getUrineOutputMl())
            .urineProtein(request.getUrineProtein())
            .urineAcetone(request.getUrineAcetone())
            .notes(request.getNotes())
            .build();

        anchorActivePhase(episode, entry);
        evaluateFetalCondition(entry);
        evaluateLaborProgress(episode, entry);
        evaluateMaternalCondition(entry);

        LaborPartographEntry saved = entryRepository.save(entry);
        publishUrgentAlerts(saved.getAlerts(), episode, documentedBy);
        return laborMapper.toEntryResponse(saved, episode.getActivePhaseStartAt());
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartographEntryResponseDTO> getEntries(UUID patientId, UUID episodeId, UUID hospitalId) {
        LaborEpisode episode = loadEpisode(patientId, episodeId, hospitalId);
        return entryRepository.findByEpisode_IdOrderByObservationTimeAsc(episode.getId()).stream()
            .map(entry -> laborMapper.toEntryResponse(entry, episode.getActivePhaseStartAt()))
            .toList();
    }

    /* ═══════════════════════ Delivery ═══════════════════════ */

    @Override
    public DeliveryRecordResponseDTO recordDelivery(UUID patientId, UUID episodeId, UUID recorderUserId,
                                                    DeliveryRecordRequestDTO request) {
        if (request == null) {
            throw new BusinessException("Delivery record payload is required.");
        }
        LaborEpisode episode = loadEpisode(patientId, episodeId, request.getHospitalId());
        if (episode.getStatus() != LaborStatus.ACTIVE) {
            throw new BusinessException("Delivery can only be recorded for an active labor episode.");
        }
        if (deliveryRecordRepository.existsByEpisode_Id(episode.getId())) {
            throw new BusinessException("A delivery record already exists for this labor episode.");
        }

        Staff deliveredBy = resolveStaff(request.getDeliveredByStaffId(), episode.getHospital());
        User documentedBy = resolveUser(recorderUserId);

        DeliveryRecord record = DeliveryRecord.builder()
            .episode(episode)
            .patient(episode.getPatient())
            .hospital(episode.getHospital())
            .deliveredByStaff(deliveredBy)
            .documentedBy(documentedBy)
            .birthDateTime(request.getBirthDateTime() != null ? request.getBirthDateTime() : LocalDateTime.now())
            .deliveryMode(request.getDeliveryMode())
            .liveBirth(request.getLiveBirth() == null || request.getLiveBirth())
            .numberOfInfants(request.getNumberOfInfants() != null ? request.getNumberOfInfants() : 1)
            .infantSex(request.getInfantSex())
            .birthWeightGrams(request.getBirthWeightGrams())
            .gestationalAgeWeeksAtBirth(request.getGestationalAgeWeeksAtBirth() != null
                ? request.getGestationalAgeWeeksAtBirth() : episode.getGestationalAgeWeeks())
            .apgarOneMinute(request.getApgarOneMinute())
            .apgarFiveMinute(request.getApgarFiveMinute())
            .placentaDeliveredAt(request.getPlacentaDeliveredAt())
            .placentaComplete(request.getPlacentaComplete())
            .uterotonicGiven(request.getUterotonicGiven())
            .estimatedBloodLossMl(request.getEstimatedBloodLossMl())
            .perinealTear(request.getPerinealTear())
            .notes(request.getNotes())
            .build();

        evaluateDelivery(record);

        episode.setStatus(LaborStatus.DELIVERED);
        episode.setOutcome(deriveOutcome(record));
        episodeRepository.save(episode);

        DeliveryRecord saved = deliveryRecordRepository.save(record);
        publishUrgentAlerts(saved.getAlerts(), episode, documentedBy);
        return laborMapper.toDeliveryResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryRecordResponseDTO getDelivery(UUID patientId, UUID episodeId, UUID hospitalId) {
        LaborEpisode episode = loadEpisode(patientId, episodeId, hospitalId);
        DeliveryRecord record = deliveryRecordRepository.findByEpisode_Id(episode.getId())
            .orElseThrow(() -> new ResourceNotFoundException("No delivery record for episode: " + episodeId));
        return laborMapper.toDeliveryResponse(record);
    }

    /* ═══════════════════════ Alert evaluators ═══════════════════════ */

    /** Set the WHO active-phase anchor when dilation first reaches 4 cm. */
    private void anchorActivePhase(LaborEpisode episode, LaborPartographEntry entry) {
        if (episode.getActivePhaseStartAt() == null
            && entry.getCervicalDilationCm() != null
            && entry.getCervicalDilationCm() >= ACTIVE_PHASE_DILATION_CM) {
            episode.setActivePhaseStartAt(entry.getObservationTime());
            episodeRepository.save(episode);
        }
    }

    private void evaluateFetalCondition(LaborPartographEntry entry) {
        Integer fhr = entry.getFetalHeartRateBpm();
        if (fhr != null && fhr < FHR_BRADYCARDIA_BPM) {
            entry.addAlert(LaborAlertType.FETAL_HEART_RATE, LaborAlertSeverity.URGENT,
                "labor-fetal-bradycardia",
                "Fetal heart rate " + fhr + " bpm below " + FHR_BRADYCARDIA_BPM + " — fetal distress.",
                "fetalHeartRateBpm");
        } else if (fhr != null && fhr > FHR_TACHYCARDIA_BPM) {
            entry.addAlert(LaborAlertType.FETAL_HEART_RATE, LaborAlertSeverity.URGENT,
                "labor-fetal-tachycardia",
                "Fetal heart rate " + fhr + " bpm above " + FHR_TACHYCARDIA_BPM + " — fetal distress.",
                "fetalHeartRateBpm");
        }
        if (entry.getLiquorColour() == LiquorColour.MECONIUM_STAINED
            || entry.getLiquorColour() == LiquorColour.BLOOD_STAINED) {
            entry.addAlert(LaborAlertType.LIQUOR, LaborAlertSeverity.CAUTION,
                "labor-liquor",
                "Liquor " + entry.getLiquorColour().name().toLowerCase().replace('_', ' ')
                    + " — monitor fetal condition closely.",
                "liquorColour");
        }
    }

    /**
     * WHO alert line: from the active-phase anchor, expected dilation
     * advances 1 cm/h starting at 4 cm. The action line sits 4 h to its
     * right; since progress is 1 cm/h, being N cm behind the alert line
     * equals being N hours behind it.
     */
    private void evaluateLaborProgress(LaborEpisode episode, LaborPartographEntry entry) {
        LocalDateTime anchor = episode.getActivePhaseStartAt();
        Integer dilation = entry.getCervicalDilationCm();
        if (anchor == null || dilation == null || entry.getObservationTime() == null
            || entry.getObservationTime().isBefore(anchor)) {
            return;
        }
        double hours = ElapsedTime.minutesBetween(anchor, entry.getObservationTime()) / 60.0;
        double expected = Math.min(10.0, ACTIVE_PHASE_DILATION_CM + hours);
        double lagHours = expected - dilation;
        if (lagHours >= ACTION_LINE_LAG_HOURS) {
            entry.addAlert(LaborAlertType.LABOR_PROGRESS, LaborAlertSeverity.URGENT,
                "labor-action-line",
                "Cervical dilation " + dilation + " cm has crossed the WHO action line ("
                    + String.format("%.1f", lagHours) + " h behind expected progress) — senior review required.",
                "cervicalDilationCm");
        } else if (lagHours > 0) {
            entry.addAlert(LaborAlertType.LABOR_PROGRESS, LaborAlertSeverity.CAUTION,
                "labor-alert-line",
                "Cervical dilation " + dilation + " cm is behind the WHO alert line ("
                    + String.format("%.1f", lagHours) + " h behind expected progress).",
                "cervicalDilationCm");
        }
    }

    private void evaluateMaternalCondition(LaborPartographEntry entry) {
        if (entry.getTemperatureCelsius() != null && entry.getTemperatureCelsius() >= MATERNAL_FEVER_C) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION,
                "labor-maternal-fever",
                "Maternal temperature " + entry.getTemperatureCelsius() + " °C — possible infection.",
                "temperatureCelsius");
        }
        if (entry.getPulseBpm() != null && entry.getPulseBpm() > MATERNAL_TACHYCARDIA_BPM) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION,
                "labor-maternal-tachycardia",
                "Maternal pulse " + entry.getPulseBpm() + " bpm — assess for hemorrhage, infection or dehydration.",
                "pulseBpm");
        }
        Integer sys = entry.getSystolicBpMmHg();
        Integer dia = entry.getDiastolicBpMmHg();
        boolean severe = (sys != null && sys >= SEVERE_HTN_SYSTOLIC) || (dia != null && dia >= SEVERE_HTN_DIASTOLIC);
        boolean mild = (sys != null && sys >= HTN_SYSTOLIC) || (dia != null && dia >= HTN_DIASTOLIC);
        if (severe) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.URGENT,
                "labor-severe-hypertension",
                "Severe hypertension " + sys + "/" + dia + " mmHg — pre-eclampsia protocol.",
                "bloodPressure");
        } else if (mild) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION,
                "labor-hypertension",
                "Elevated blood pressure " + sys + "/" + dia + " mmHg — recheck and monitor for pre-eclampsia.",
                "bloodPressure");
        }
    }

    private void evaluateDelivery(DeliveryRecord record) {
        Integer ebl = record.getEstimatedBloodLossMl();
        if (ebl != null && ebl >= PPH_BLOOD_LOSS_ML) {
            record.addAlert(LaborAlertType.HEMORRHAGE, LaborAlertSeverity.URGENT,
                "labor-pph",
                "Estimated blood loss " + ebl + " ml — postpartum hemorrhage threshold reached.",
                "estimatedBloodLossMl");
        }
        if (Boolean.FALSE.equals(record.getPlacentaComplete())) {
            record.addAlert(LaborAlertType.HEMORRHAGE, LaborAlertSeverity.URGENT,
                "labor-retained-placenta",
                "Placenta incomplete — retained products; hemorrhage risk.",
                "placentaComplete");
        }
        Integer apgar5 = record.getApgarFiveMinute();
        if (apgar5 != null && apgar5 < APGAR_CRITICAL) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.URGENT,
                "labor-apgar-critical",
                "5-minute APGAR " + apgar5 + " — neonatal resuscitation and escalation required.",
                "apgarFiveMinute");
        } else if (apgar5 != null && apgar5 < APGAR_LOW) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.CAUTION,
                "labor-apgar-low",
                "5-minute APGAR " + apgar5 + " — close neonatal observation required.",
                "apgarFiveMinute");
        }
        if (!record.isLiveBirth()) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.URGENT,
                "labor-stillbirth",
                "Stillbirth recorded — bereavement support and mortality review workflow.",
                "liveBirth");
        }
    }

    private LaborOutcome deriveOutcome(DeliveryRecord record) {
        if (!record.isLiveBirth()) {
            return LaborOutcome.STILLBIRTH;
        }
        DeliveryMode mode = record.getDeliveryMode();
        if (mode == null) {
            return LaborOutcome.UNDETERMINED;
        }
        return switch (mode) {
            case SPONTANEOUS_VAGINAL -> LaborOutcome.VAGINAL_DELIVERY;
            case VACUUM_EXTRACTION, FORCEPS, ASSISTED_BREECH -> LaborOutcome.ASSISTED_VAGINAL_DELIVERY;
            case CAESAREAN_ELECTIVE, CAESAREAN_EMERGENCY -> LaborOutcome.CAESAREAN_SECTION;
        };
    }

    /* ═══════════════════════ Resolvers (house pattern) ═══════════════════════ */

    private LaborEpisode loadEpisode(UUID patientId, UUID episodeId, UUID hospitalId) {
        Objects.requireNonNull(patientId, "patientId is required");
        Objects.requireNonNull(episodeId, "episodeId is required");
        // ── Tenant isolation: scoped callers go through id + hospital; a
        // cross-tenant episode reads as not-found. Null scope = super-admin. ──
        LaborEpisode episode = (hospitalId != null
            ? episodeRepository.findByIdAndHospital_Id(episodeId, hospitalId)
            : episodeRepository.findById(episodeId))
            .orElseThrow(() -> new ResourceNotFoundException("Labor episode not found: " + episodeId));
        if (episode.getPatient() == null || !patientId.equals(episode.getPatient().getId())) {
            throw new ResourceNotFoundException("Labor episode not found: " + episodeId);
        }
        return episode;
    }

    private PatientHospitalRegistration resolveRegistration(UUID registrationId) {
        if (registrationId == null) {
            return null;
        }
        return registrationRepository.findById(registrationId)
            .orElseThrow(() -> new ResourceNotFoundException("Registration not found: " + registrationId));
    }

    private Hospital resolveHospital(PatientHospitalRegistration registration, UUID requestedHospitalId) {
        if (requestedHospitalId != null) {
            return hospitalRepository.findById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital not found: " + requestedHospitalId));
        }
        if (registration != null && registration.getHospital() != null) {
            return registration.getHospital();
        }
        throw new BusinessException("Hospital context required to record labor data.");
    }

    private Staff resolveStaff(UUID staffId, Hospital hospital) {
        if (staffId == null) {
            return null;
        }
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("Staff not found: " + staffId));
        if (hospital != null && staff.getHospital() != null
            && !hospital.getId().equals(staff.getHospital().getId())) {
            throw new BusinessException("Recorder staff assignment does not match resolved hospital context.");
        }
        return staff;
    }

    private User resolveUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    /**
     * Gestational age at admission: explicit value wins; otherwise derived
     * from the current maternal history's EDD (40 weeks minus time to EDD).
     */
    private Integer resolveGestationalAgeWeeks(Integer requested, MaternalHistory history) {
        if (requested != null) {
            return requested;
        }
        if (history == null || history.getEstimatedDueDate() == null) {
            return null;
        }
        LocalDate edd = history.getEstimatedDueDate();
        long daysToEdd = ChronoUnit.DAYS.between(LocalDate.now(), edd);
        long weeks = TERM_WEEKS - Math.round(daysToEdd / 7.0);
        if (weeks < 20 || weeks > 45) {
            return null; // implausible — leave blank rather than mislead
        }
        return (int) weeks;
    }

    /** URGENT alerts notify the documenting clinician — best-effort, house policy. */
    private void publishUrgentAlerts(List<com.example.hms.model.labor.LaborAlert> alerts,
                                     LaborEpisode episode, User documentedBy) {
        if (alerts == null || alerts.isEmpty() || documentedBy == null || documentedBy.getUsername() == null) {
            return;
        }
        String patientName = episode.getPatient() != null ? episode.getPatient().getFullName() : "patient";
        alerts.stream()
            .filter(alert -> alert.getSeverity() == LaborAlertSeverity.URGENT)
            .forEach(alert -> {
                String message = String.format("%s labor alert for %s: %s",
                    alert.getType(), patientName, alert.getMessage());
                try {
                    notificationService.createNotification(message, documentedBy.getUsername());
                } catch (RuntimeException ex) {
                    log.warn("Failed to create labor alert notification: {}", ex.getMessage());
                }
            });
    }
}
