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
import com.example.hms.service.recordaccess.CrossHospitalReachRecorder;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import java.util.Set;
import com.example.hms.security.context.HospitalContextHolder;
import org.springframework.context.MessageSource;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.utility.GestationalDating;
import com.example.hms.utility.ObstetricEligibility;
import java.util.Locale;

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

    /** Null-check message for the required patient identifier. */
    private static final String PATIENT_ID_REQUIRED = "patientId is required";

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
    static final int PRETERM_WEEKS = 37;
    static final int VERY_PRETERM_WEEKS = 32;
    /**
     * QA D214: a new labour this soon after a recorded birth cannot be a new
     * pregnancy reaching labour (20 weeks is the floor of a birth). It is a
     * charting error unless the clinician states why (e.g. a retained twin).
     */
    static final int MIN_DAYS_BETWEEN_DELIVERIES = 140;

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
    private final RecordAccessPolicy recordAccessPolicy;
    private final CrossHospitalReachRecorder reachRecorder;
    private final MessageSource messageSource;
    private final com.example.hms.repository.PostpartumCarePlanRepository postpartumCarePlanRepository;

    /* ═══════════════════════ Episodes ═══════════════════════ */

    @Override
    public LaborEpisodeResponseDTO startEpisode(UUID patientId, UUID recorderUserId, LaborEpisodeRequestDTO request) {
        Objects.requireNonNull(patientId, PATIENT_ID_REQUIRED);
        if (request == null) {
            throw new BusinessException("maternity.labor.payloadRequired");
        }
        // QA D295: an episode with no onset time has no partograph clock.
        if (request.getLaborOnsetAt() == null) {
            throw new BusinessException("maternity.labor.onset.required");
        }
        if (request.getLaborOnsetAt().isAfter(LocalDateTime.now(java.time.ZoneId.systemDefault()).plusMinutes(5))) {
            throw new BusinessException("maternity.labor.onset.future");
        }
        Patient patient = patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException("patient.notFound", patientId));
        ObstetricEligibility.requireObstetricEligible(patient);
        PatientHospitalRegistration registration = resolveRegistration(request.getRegistrationId());
        Hospital hospital = resolveHospital(registration, request.getHospitalId());

        if (episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospital.getId(), LaborStatus.ACTIVE)) {
            throw new BusinessException("maternity.labor.alreadyActive");
        }

        String riskNotes = resolveRecentDeliveryOverride(patientId, request);

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
            .riskNotes(riskNotes)
            .build();

        LaborEpisode saved = episodeRepository.save(episode);
        return laborMapper.toEpisodeResponse(saved, 0, false);
    }

    /**
     * QA D214: a birth was recorded for this mother less than
     * {@value #MIN_DAYS_BETWEEN_DELIVERIES} days ago. Refuse, unless the
     * clinician gives an explicit override reason, which is kept on the
     * episode's risk notes so the chart shows why it was accepted.
     */
    private String resolveRecentDeliveryOverride(UUID patientId, LaborEpisodeRequestDTO request) {
        LocalDateTime since = LocalDateTime.now().minusDays(MIN_DAYS_BETWEEN_DELIVERIES);
        if (!deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(patientId, since)) {
            return request.getRiskNotes();
        }
        String reason = request.getRecentDeliveryOverrideReason();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("labor.episode.recentDelivery");
        }
        String note = messageSource.getMessage("labor.episode.recentDeliveryOverrideNote",
            new Object[]{reason.trim()}, NotificationLocales.STAFF);
        String existing = request.getRiskNotes();
        return existing == null || existing.isBlank() ? note : note + "\n" + existing;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LaborEpisodeResponseDTO> getEpisodes(UUID patientId, UUID hospitalId, int limit) {
        Objects.requireNonNull(patientId, PATIENT_ID_REQUIRED);
        if (hospitalId == null) {
            throw new BusinessException("maternity.labor.hospitalRequiredToList");
        }
        int effectiveLimit = Math.clamp(limit <= 0 ? 10 : limit, 1, 50);
        // E9 #59d — labour episodes follow the patient across the readable
        // hospitals (a transfer in labour is the case this exists for); every
        // foreign episode surfaced is accounted.
        UUID requesterUserId = HospitalContextHolder.getContextOrEmpty().getPrincipalUserId();
        Set<UUID> readable = recordAccessPolicy.readableHospitalIds(requesterUserId, patientId, hospitalId);
        List<LaborEpisode> episodes = episodeRepository
            .findByPatient_IdAndHospital_IdInOrderByAdmittedAtDesc(patientId, readable, PageRequest.of(0, effectiveLimit));
        reachRecorder.recordReach(patientId, hospitalId, requesterUserId, null,
            CrossHospitalReachRecorder.reachOf(episodes.stream().map(e -> CrossHospitalReachRecorder.hospitalIdOf(e.getHospital())).toList(), hospitalId),
            "Cross-hospital labour episode read on the treatment relationship");
        return episodes
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
            throw new BusinessException("maternity.partograph.payloadRequired");
        }
        LaborEpisode episode = loadEpisode(patientId, episodeId, request.getHospitalId());
        if (episode.getStatus() != LaborStatus.ACTIVE) {
            throw new BusinessException("maternity.partograph.laborNotActive");
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
            throw new BusinessException("maternity.delivery.payloadRequired");
        }
        LaborEpisode episode = loadEpisode(patientId, episodeId, request.getHospitalId());
        if (episode.getStatus() != LaborStatus.ACTIVE) {
            throw new BusinessException("maternity.delivery.laborNotActive");
        }
        if (deliveryRecordRepository.existsByEpisode_Id(episode.getId())) {
            throw new BusinessException("maternity.delivery.alreadyRecorded");
        }

        LocalDateTime birthAt = request.getBirthDateTime() != null ? request.getBirthDateTime() : LocalDateTime.now();
        // QA D104: a birth cannot precede the recorded labour onset.
        if (episode.getLaborOnsetAt() != null && birthAt.isBefore(episode.getLaborOnsetAt())) {
            throw new BusinessException("labor.delivery.beforeOnset");
        }
        String notes = resolveRecentDeliveryOverride(episode, birthAt, request);
        Staff deliveredBy = resolveStaff(request.getDeliveredByStaffId(), episode.getHospital());
        User documentedBy = resolveUser(recorderUserId);

        DeliveryRecord record = DeliveryRecord.builder()
            .episode(episode)
            .patient(episode.getPatient())
            .hospital(episode.getHospital())
            .deliveredByStaff(deliveredBy)
            .documentedBy(documentedBy)
            .birthDateTime(birthAt)
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
            .notes(notes)
            .build();

        evaluateDelivery(record);

        episode.setStatus(LaborStatus.DELIVERED);
        episode.setOutcome(deriveOutcome(record));
        episodeRepository.save(episode);

        DeliveryRecord saved = deliveryRecordRepository.save(record);
        closeEarlierPostpartumPlans(episode, birthAt);
        publishUrgentAlerts(saved.getAlerts(), episode, documentedBy);
        return laborMapper.toDeliveryResponse(saved);
    }

    /**
     * QA D214 (#896 review): the episode guard alone is not enough — an
     * episode opened at another hospital before the first birth can still
     * file a second delivery hours later. Any other birth for this mother
     * within {@value #MIN_DAYS_BETWEEN_DELIVERIES} days either side of this
     * one is refused unless an explicit override reason is given, which is
     * kept on the record notes.
     */
    private String resolveRecentDeliveryOverride(LaborEpisode episode, LocalDateTime birthAt,
                                                 DeliveryRecordRequestDTO request) {
        boolean conflict = deliveryRecordRepository.existsByPatient_IdAndEpisode_IdNotAndBirthDateTimeBetween(
            episode.getPatient().getId(), episode.getId(),
            birthAt.minusDays(MIN_DAYS_BETWEEN_DELIVERIES), birthAt.plusDays(MIN_DAYS_BETWEEN_DELIVERIES));
        if (!conflict) {
            return request.getNotes();
        }
        String reason = request.getRecentDeliveryOverrideReason();
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("labor.delivery.recentDelivery");
        }
        String note = messageSource.getMessage("labor.episode.recentDeliveryOverrideNote",
            new Object[]{reason.trim()}, NotificationLocales.STAFF);
        String existing = request.getNotes();
        return existing == null || existing.isBlank() ? note : note + "\n" + existing;
    }

    @Override
    @Transactional(readOnly = true)
    public DeliveryRecordResponseDTO getDelivery(UUID patientId, UUID episodeId, UUID hospitalId) {
        LaborEpisode episode = loadEpisode(patientId, episodeId, hospitalId);
        DeliveryRecord record = deliveryRecordRepository.findByEpisode_Id(episode.getId())
            .orElseThrow(() -> new ResourceNotFoundException("labor.deliveryRecord.notFoundForEpisode", episodeId));
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
                alertText("labor-fetal-bradycardia", fhr, FHR_BRADYCARDIA_BPM),
                "fetalHeartRateBpm");
        } else if (fhr != null && fhr > FHR_TACHYCARDIA_BPM) {
            entry.addAlert(LaborAlertType.FETAL_HEART_RATE, LaborAlertSeverity.URGENT,
                "labor-fetal-tachycardia",
                alertText("labor-fetal-tachycardia", fhr, FHR_TACHYCARDIA_BPM),
                "fetalHeartRateBpm");
        }
        if (entry.getLiquorColour() == LiquorColour.MECONIUM_STAINED
            || entry.getLiquorColour() == LiquorColour.BLOOD_STAINED) {
            entry.addAlert(LaborAlertType.LIQUOR, LaborAlertSeverity.CAUTION,
                "labor-liquor",
                alertText("labor-liquor", messageSource.getMessage(
                    "labor.liquor." + entry.getLiquorColour().name(), null, NotificationLocales.STAFF)),
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
                alertText("labor-action-line", dilation, lagHours),
                "cervicalDilationCm");
        } else if (lagHours > 0) {
            entry.addAlert(LaborAlertType.LABOR_PROGRESS, LaborAlertSeverity.CAUTION,
                "labor-alert-line",
                alertText("labor-alert-line", dilation, lagHours),
                "cervicalDilationCm");
        }
    }

    private void evaluateMaternalCondition(LaborPartographEntry entry) {
        if (entry.getTemperatureCelsius() != null && entry.getTemperatureCelsius() >= MATERNAL_FEVER_C) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION,
                "labor-maternal-fever",
                alertText("labor-maternal-fever", entry.getTemperatureCelsius()),
                "temperatureCelsius");
        }
        if (entry.getPulseBpm() != null && entry.getPulseBpm() > MATERNAL_TACHYCARDIA_BPM) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION,
                "labor-maternal-tachycardia",
                alertText("labor-maternal-tachycardia", entry.getPulseBpm()),
                "pulseBpm");
        }
        Integer sys = entry.getSystolicBpMmHg();
        Integer dia = entry.getDiastolicBpMmHg();
        boolean severe = (sys != null && sys >= SEVERE_HTN_SYSTOLIC) || (dia != null && dia >= SEVERE_HTN_DIASTOLIC);
        boolean mild = (sys != null && sys >= HTN_SYSTOLIC) || (dia != null && dia >= HTN_DIASTOLIC);
        if (severe) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.URGENT,
                "labor-severe-hypertension",
                alertText("labor-severe-hypertension", sys, dia),
                "bloodPressure");
        } else if (mild) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION,
                "labor-hypertension",
                alertText("labor-hypertension", sys, dia),
                "bloodPressure");
        }
    }

    /**
     * QA D215 (D162): a new birth starts a new postpartum course. Any active
     * plan from an earlier delivery at this hospital is closed, so the next
     * observation opens a fresh plan in immediate recovery instead of
     * inheriting the old one's phase, cadence and overdue state.
     */
    private void closeEarlierPostpartumPlans(LaborEpisode episode, LocalDateTime birthAt) {
        if (episode.getPatient() == null || episode.getHospital() == null) {
            return;
        }
        postpartumCarePlanRepository
            .findByPatient_IdAndHospital_IdAndActiveTrue(episode.getPatient().getId(), episode.getHospital().getId())
            .stream()
            .filter(plan -> plan.getDeliveryOccurredAt() == null || plan.getDeliveryOccurredAt().isBefore(birthAt))
            .forEach(plan -> {
                plan.markClosed(birthAt);
                postpartumCarePlanRepository.save(plan);
            });
    }

    private void evaluateDelivery(DeliveryRecord record) {
        // QA D214: a preterm birth is flagged, not silently recorded.
        Integer ga = record.getGestationalAgeWeeksAtBirth();
        if (ga != null && ga < PRETERM_WEEKS) {
            record.addAlert(LaborAlertType.GENERAL,
                ga < VERY_PRETERM_WEEKS ? LaborAlertSeverity.URGENT : LaborAlertSeverity.CAUTION,
                "labor-preterm",
                alertText("labor-preterm", ga),
                "gestationalAgeWeeksAtBirth");
        }
        Integer ebl = record.getEstimatedBloodLossMl();
        if (ebl != null && ebl >= PPH_BLOOD_LOSS_ML) {
            record.addAlert(LaborAlertType.HEMORRHAGE, LaborAlertSeverity.URGENT,
                "labor-pph",
                alertText("labor-pph", ebl),
                "estimatedBloodLossMl");
        }
        if (Boolean.FALSE.equals(record.getPlacentaComplete())) {
            record.addAlert(LaborAlertType.HEMORRHAGE, LaborAlertSeverity.URGENT,
                "labor-retained-placenta",
                alertText("labor-retained-placenta"),
                "placentaComplete");
        }
        Integer apgar5 = record.getApgarFiveMinute();
        if (apgar5 != null && apgar5 < APGAR_CRITICAL) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.URGENT,
                "labor-apgar-critical",
                alertText("labor-apgar-critical", apgar5),
                "apgarFiveMinute");
        } else if (apgar5 != null && apgar5 < APGAR_LOW) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.CAUTION,
                "labor-apgar-low",
                alertText("labor-apgar-low", apgar5),
                "apgarFiveMinute");
        }
        if (!record.isLiveBirth()) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.URGENT,
                "labor-stillbirth",
                alertText("labor-stillbirth"),
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
        Objects.requireNonNull(patientId, PATIENT_ID_REQUIRED);
        Objects.requireNonNull(episodeId, "episodeId is required");
        // ── Tenant isolation: scoped callers go through id + hospital; a
        // cross-tenant episode reads as not-found. Null scope = super-admin. ──
        LaborEpisode episode = (hospitalId != null
            ? episodeRepository.findByIdAndHospital_Id(episodeId, hospitalId)
            : episodeRepository.findById(episodeId))
            .orElseThrow(() -> new ResourceNotFoundException("labor.episode.notFound", episodeId));
        if (episode.getPatient() == null || !patientId.equals(episode.getPatient().getId())) {
            throw new ResourceNotFoundException("labor.episode.notFound", episodeId);
        }
        return episode;
    }

    private PatientHospitalRegistration resolveRegistration(UUID registrationId) {
        if (registrationId == null) {
            return null;
        }
        PatientHospitalRegistration registration = registrationRepository.findById(registrationId)
            .orElseThrow(() -> new ResourceNotFoundException("registration.notFound", registrationId));
        // QA r5 tenancy sweep: another hospital's row answers as unknown.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(registration.getHospital(), "registration.notFound", registrationId);
        return registration;
    }

    private Hospital resolveHospital(PatientHospitalRegistration registration, UUID requestedHospitalId) {
        if (requestedHospitalId != null) {
            return hospitalRepository.findClinicalById(requestedHospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", requestedHospitalId));
        }
        if (registration != null && registration.getHospital() != null) {
            return registration.getHospital();
        }
        throw new BusinessException("maternity.labor.hospitalRequiredToRecord");
    }

    private Staff resolveStaff(UUID staffId, Hospital hospital) {
        if (staffId == null) {
            return null;
        }
        Staff staff = staffRepository.findById(staffId)
            .orElseThrow(() -> new ResourceNotFoundException("staff.notFound", staffId));
        if (hospital != null && staff.getHospital() != null
            && !hospital.getId().equals(staff.getHospital().getId())) {
            throw new BusinessException("maternity.recorder.hospitalMismatch");
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
     * from the current maternal history's EDD (40 weeks minus time to EDD),
     * or, when only the LMP was recorded (QA D162), from the LMP's due date.
     */
    private Integer resolveGestationalAgeWeeks(Integer requested, MaternalHistory history) {
        if (requested != null) {
            return requested;
        }
        if (history == null) {
            return null;
        }
        LocalDate edd = history.getEstimatedDueDate();
        if (edd == null && GestationalDating.isPlausibleCurrentLmp(history.getLastMenstrualPeriod(), LocalDate.now())) {
            edd = GestationalDating.dueDateFromLmp(history.getLastMenstrualPeriod(),
                history.getMenstrualCycleLengthDays());
        }
        if (edd == null) {
            return null;
        }
        long daysToEdd = ChronoUnit.DAYS.between(LocalDate.now(), edd);
        long weeks = TERM_WEEKS - Math.round(daysToEdd / 7.0);
        if (weeks < 20 || weeks > 45) {
            return null; // implausible — leave blank rather than mislead
        }
        return (int) weeks;
    }

    /**
     * QA D172 (D61/D121): alert text is rendered from the message bundle in the
     * staff locale (it is stored, shown on the chart and sent as a notification),
     * not composed in English in code.
     */
    private String alertText(String code, Object... args) {
        return messageSource.getMessage("labor.alert.msg." + code, args, NotificationLocales.STAFF);
    }

    /** URGENT alerts notify the documenting clinician — best-effort, house policy. */
    private void publishUrgentAlerts(List<com.example.hms.model.labor.LaborAlert> alerts,
                                     LaborEpisode episode, User documentedBy) {
        if (alerts == null || alerts.isEmpty() || documentedBy == null || documentedBy.getUsername() == null) {
            return;
        }
        // Read by the documenting clinician: staff locale, not the request locale.
        Locale locale = NotificationLocales.STAFF;
        String patientName = episode.getPatient() != null
            ? episode.getPatient().getFullName()
            : messageSource.getMessage("patient.fallback.generic", null, locale);
        alerts.stream()
            .filter(alert -> alert.getSeverity() == LaborAlertSeverity.URGENT)
            .forEach(alert -> {
                // QA D172 (D61/D121): the type is a translated label, never the raw enum.
                String typeLabel = alert.getType() == null ? ""
                    : messageSource.getMessage("labor.alert.type." + alert.getType().name(), null, locale);
                String message = messageSource.getMessage("labor.alert.notification",
                    new Object[]{typeLabel, patientName, alert.getMessage()}, locale);
                try {
                    notificationService.createNotification(message, documentedBy.getUsername());
                } catch (RuntimeException ex) {
                    log.warn("Failed to create labor alert notification: {}", ex.getMessage());
                }
            });
    }
}
