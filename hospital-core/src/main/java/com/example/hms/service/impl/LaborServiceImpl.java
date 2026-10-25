package com.example.hms.service.impl;

import com.example.hms.service.i18n.NotificationLocalizer;
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

    /** QA D714: tolerated clock skew between the charting device and the server. */
    static final long PARTOGRAPH_FUTURE_SKEW_MINUTES = 10;

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
    /**
     * QA D516: a birth reaches billing as a draft charge. The charge is
     * posted after the delivery commits, in its own transaction, so a failure
     * to post it (logged, ids only) can never mark the delivery's transaction
     * rollback-only and undo the clinical record.
     */
    private void chargeDelivery(DeliveryRecord saved, UUID recorderUserId) {
        if (deliveryChargeService == null) {
            return;
        }
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        postDeliveryCharge(saved, recorderUserId);
                    }
                });
        } else {
            postDeliveryCharge(saved, recorderUserId);
        }
    }

    private void postDeliveryCharge(DeliveryRecord saved, UUID recorderUserId) {
        try {
            deliveryChargeService.chargeDelivery(saved, recorderUserId);
        } catch (RuntimeException ex) {
            log.warn("Delivery charge not posted for delivery {}: {}", saved.getId(), ex.getClass().getSimpleName());
        }
    }

    static final int VERY_PRETERM_WEEKS = 32;
    /** QA D796: the gestational age an unbooked labour may be opened at (the delivery record's own bounds). */
    static final int MIN_LABOUR_GESTATIONAL_WEEKS = 20;
    static final int MAX_LABOUR_GESTATIONAL_WEEKS = 45;

    /**
     * QA D797: approximate median birth weight in grams for each completed
     * week of gestation from {@value #FIRST_WEIGHT_WEEK} to 42 weeks (index 0 =
     * {@value #FIRST_WEIGHT_WEEK} weeks), rounded, from the usual preterm and
     * term growth curves. It only feeds a plausibility check with deliberately
     * wide bounds ({@link #IMPLAUSIBLY_LIGHT_FACTOR} to
     * {@link #IMPLAUSIBLY_HEAVY_FACTOR} times the median): a weight outside
     * them is far more likely a charting error (the weight, or the dating) than
     * a real baby, so the record is kept and a CAUTION asks to check it. It is
     * not a growth classification (small or large for gestational age).
     */
    private static final int[] MEDIAN_BIRTH_WEIGHT_GRAMS = {
        500, 580, 650, 760, 880, 1000, 1150, 1300, 1450, 1650, 1850,
        2100, 2350, 2600, 2850, 3050, 3250, 3400, 3500, 3600, 3650};
    static final int FIRST_WEIGHT_WEEK = 22;
    static final double IMPLAUSIBLY_HEAVY_FACTOR = 1.6;
    static final double IMPLAUSIBLY_LIGHT_FACTOR = 0.45;
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
    private final com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;
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
    private final com.example.hms.service.DeliveryChargeService deliveryChargeService;
    /** QA D605: URGENT alerts reach the on-call team and escalate until acknowledged. */
    private final com.example.hms.service.maternity.ObstetricAlertEscalationService obstetricAlertEscalation;
    private final com.example.hms.mapper.MaternalHistoryMapper maternalHistoryMapper;

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
        // QA D401: the record is written at the caller's acting hospital (the
        // body's hospital id is a claim), for a patient registered there.
        com.example.hms.security.tenant.TenantOwnership.requireOwnedByActingHospital(hospital, "hospital.notFound", hospital.getId());
        patientRegistrationGuard.requireRegisteredAt(patient.getId(), hospital.getId());

        if (episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospital.getId(), LaborStatus.ACTIVE)) {
            throw new BusinessException("maternity.labor.alreadyActive");
        }

        String riskNotes = resolveRecentDeliveryOverride(patientId, request);

        Staff admittedBy = resolveStaffOrCaller(request.getAdmittedByStaffId(), hospital, recorderUserId);
        User documentedBy = resolveUser(recorderUserId);
        java.util.Optional<MaternalHistory> latest = maternalHistoryRepository.findCurrentByPatientId(patientId);
        MaternalHistory history = currentPregnancy(patientId, latest, request)
            .orElseGet(() -> openPregnancyForUnbookedLabour(patient, hospital, admittedBy, request, latest.orElse(null)));

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
     * QA D796: the pregnancy this labour belongs to. The latest pregnancy
     * record, unless a birth has been recorded since it was written (that
     * pregnancy is over, and linking a new labour to it would date it from the
     * old due date), except when the clinician states that this labour
     * continues a recent birth (the D214 override, e.g. a retained twin).
     */
    private java.util.Optional<MaternalHistory> currentPregnancy(UUID patientId,
                                                                 java.util.Optional<MaternalHistory> latest,
                                                                 LaborEpisodeRequestDTO request) {
        String override = request.getRecentDeliveryOverrideReason();
        if (latest.isEmpty() || latest.get().getRecordedDate() == null || (override != null && !override.isBlank())) {
            return latest;
        }
        boolean deliveredSince = deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            patientId, latest.get().getRecordedDate());
        return deliveredSince ? java.util.Optional.empty() : latest;
    }

    /**
     * QA D796: a labour episode opened for a woman with no pregnancy on record
     * (gestational age null), and a 14-week antenatal calendar could then be
     * planned next to it. A woman who arrives in labour with no antenatal
     * booking must still be admitted, so the episode is not refused outright:
     * it needs the clinician's estimated gestational age, and that estimate
     * opens the pregnancy record the episode is linked to (its due date
     * derived from the estimate), so the chart has one pregnancy, dated, and
     * the antenatal calendar refuses a pregnancy that is in labour.
     *
     * <p>When she has an earlier history (a delivered pregnancy), the new
     * record is its next version and becomes her current history, so it
     * carries her past history forward (previous caesarean sections, PPH,
     * allergies...) and only the pregnancy's own dating starts afresh.
     * Gravida and para are the clinician's figures for this admission: the
     * birth since the previous record and this pregnancy changed both, so
     * the previous record's counts are not carried as if they were current.
     */
    private MaternalHistory openPregnancyForUnbookedLabour(Patient patient, Hospital hospital, Staff admittedBy,
                                                           LaborEpisodeRequestDTO request, MaternalHistory previous) {
        Integer weeks = request.getGestationalAgeWeeks();
        if (weeks == null) {
            throw new BusinessException("maternity.labor.pregnancyRequired");
        }
        if (weeks < MIN_LABOUR_GESTATIONAL_WEEKS || weeks > MAX_LABOUR_GESTATIONAL_WEEKS) {
            throw new BusinessException("maternity.labor.gestationalAgeRange",
                MIN_LABOUR_GESTATIONAL_WEEKS, MAX_LABOUR_GESTATIONAL_WEEKS);
        }
        LocalDate today = LocalDate.now();
        MaternalHistory pregnancy = new MaternalHistory();
        maternalHistoryMapper.carryForwardHistory(previous, pregnancy);
        pregnancy.setPatient(patient);
        pregnancy.setHospital(hospital);
        pregnancy.setRecordedBy(admittedBy);
        // A new pregnancy after a delivered one is the next version of her record.
        Integer latestVersion = maternalHistoryRepository.findMaxVersionByPatientId(patient.getId());
        pregnancy.setVersionNumber(latestVersion == null ? 1 : latestVersion + 1);
        pregnancy.setRecordedDate(LocalDateTime.now());
        pregnancy.setEstimatedDueDate(today.plusWeeks((long) TERM_WEEKS - weeks));
        pregnancy.setGravida(request.getGravida());
        pregnancy.setPara(request.getPara());
        pregnancy.setUpdateReason(messageSource.getMessage("maternity.labor.unbookedPregnancyNote",
            new Object[]{weeks}, NotificationLocales.STAFF));
        return maternalHistoryRepository.save(pregnancy);
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

        // QA D714: an observation cannot be charted in the future. A small
        // allowance covers device clock skew; anything beyond it is rejected
        // before it can raise an alert or be drawn on the partograph.
        if (request.getObservationTime() != null
                && request.getObservationTime().isAfter(LocalDateTime.now(java.time.ZoneId.systemDefault()).plusMinutes(PARTOGRAPH_FUTURE_SKEW_MINUTES))) {
            throw new BusinessException("maternity.partograph.futureTime");
        }
        // QA D745: nor before the labour it charts began.
        if (request.getObservationTime() != null && episode.getLaborOnsetAt() != null
                && request.getObservationTime().isBefore(episode.getLaborOnsetAt())) {
            throw new BusinessException("maternity.partograph.beforeOnset");
        }

        Staff recordedBy = resolveStaff(request.getRecordedByStaffId(), episode.getHospital());
        if (recordedBy != null) {
            // QA D503 class: the recorder is the caller, not a colleague named in the body.
            recordedBy = com.example.hms.security.CallerIdentity.confirmActor(staffRepository, recordedBy,
                com.example.hms.security.CallerIdentity.hospitalIdOf(episode.getHospital()));
        }
        if (recordedBy == null) {
            // QA D629: no recorder named in the body means the caller recorded it.
            recordedBy = callerStaffAt(episode.getHospital(), recorderUserId);
        }
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
        publishUrgentAlerts(saved.getAlerts(), episode, documentedBy,
            com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_PARTOGRAPH, saved.getId());
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
        Staff deliveredBy = resolveStaffOrCaller(request.getDeliveredByStaffId(), episode.getHospital(), recorderUserId);
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
        chargeDelivery(saved, recorderUserId);
        closeEarlierPostpartumPlans(episode, birthAt);
        publishUrgentAlerts(saved.getAlerts(), episode, documentedBy,
            com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_DELIVERY, saved.getId());
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
                "fetalHeartRateBpm", new Object[]{fhr, FHR_BRADYCARDIA_BPM});
        } else if (fhr != null && fhr > FHR_TACHYCARDIA_BPM) {
            entry.addAlert(LaborAlertType.FETAL_HEART_RATE, LaborAlertSeverity.URGENT,
                "labor-fetal-tachycardia",
                alertText("labor-fetal-tachycardia", fhr, FHR_TACHYCARDIA_BPM),
                "fetalHeartRateBpm", new Object[]{fhr, FHR_TACHYCARDIA_BPM});
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
                "cervicalDilationCm", new Object[]{dilation, lagHours});
        } else if (lagHours > 0) {
            entry.addAlert(LaborAlertType.LABOR_PROGRESS, LaborAlertSeverity.CAUTION,
                "labor-alert-line",
                alertText("labor-alert-line", dilation, lagHours),
                "cervicalDilationCm", new Object[]{dilation, lagHours});
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
                "bloodPressure", new Object[]{sys, dia});
        } else if (mild) {
            entry.addAlert(LaborAlertType.MATERNAL_VITALS, LaborAlertSeverity.CAUTION,
                "labor-hypertension",
                alertText("labor-hypertension", sys, dia),
                "bloodPressure", new Object[]{sys, dia});
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
                "gestationalAgeWeeksAtBirth", new Object[]{ga});
        }
        evaluateBirthWeightPlausibility(record);
        Integer ebl = record.getEstimatedBloodLossMl();
        if (ebl != null && ebl >= PPH_BLOOD_LOSS_ML) {
            record.addAlert(LaborAlertType.HEMORRHAGE, LaborAlertSeverity.URGENT,
                "labor-pph",
                alertText("labor-pph", ebl),
                "estimatedBloodLossMl", new Object[]{ebl});
        }
        if (Boolean.FALSE.equals(record.getPlacentaComplete())) {
            record.addAlert(LaborAlertType.HEMORRHAGE, LaborAlertSeverity.URGENT,
                "labor-retained-placenta",
                alertText("labor-retained-placenta"),
                "placentaComplete", new Object[]{});
        }
        Integer apgar5 = record.getApgarFiveMinute();
        if (apgar5 != null && apgar5 < APGAR_CRITICAL) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.URGENT,
                "labor-apgar-critical",
                alertText("labor-apgar-critical", apgar5),
                "apgarFiveMinute", new Object[]{apgar5});
        } else if (apgar5 != null && apgar5 < APGAR_LOW) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.CAUTION,
                "labor-apgar-low",
                alertText("labor-apgar-low", apgar5),
                "apgarFiveMinute", new Object[]{apgar5});
        }
        if (!record.isLiveBirth()) {
            record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.URGENT,
                "labor-stillbirth",
                alertText("labor-stillbirth"),
                "liveBirth", new Object[]{});
        }
    }

    /**
     * QA D797: 3,200 g at 24 weeks was recorded with only the preterm alert.
     * A weight far outside what the gestational age allows is flagged
     * (CAUTION, never a refusal: the clinician may know better, and a birth is
     * always recorded); it names both values so either can be corrected.
     */
    private void evaluateBirthWeightPlausibility(DeliveryRecord record) {
        Integer grams = record.getBirthWeightGrams();
        Integer weeks = record.getGestationalAgeWeeksAtBirth();
        if (grams == null || weeks == null || !isImplausibleBirthWeight(grams, weeks)) {
            return;
        }
        record.addAlert(LaborAlertType.GENERAL, LaborAlertSeverity.CAUTION,
            "labor-weight-implausible",
            alertText("labor-weight-implausible", grams, weeks),
            "birthWeightGrams", new Object[]{grams, weeks});
    }

    /** True when {@code grams} is outside the wide plausible band for {@code weeks} (QA D797). */
    static boolean isImplausibleBirthWeight(int grams, int weeks) {
        int index = Math.clamp((long) weeks - FIRST_WEIGHT_WEEK, 0, MEDIAN_BIRTH_WEIGHT_GRAMS.length - 1);
        int median = MEDIAN_BIRTH_WEIGHT_GRAMS[index];
        return grams > median * IMPLAUSIBLY_HEAVY_FACTOR || grams < median * IMPLAUSIBLY_LIGHT_FACTOR;
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

    /**
     * QA D629: the maternity screen sends no admitting or delivering staff id,
     * so admittedByStaffName and deliveredByStaffName were always null. With
     * none named, the clinician recording it is the one who admitted or
     * delivered: the caller's staff record at the record's hospital.
     */
    private Staff resolveStaffOrCaller(UUID staffId, Hospital hospital, UUID recorderUserId) {
        if (staffId != null) {
            return resolveStaff(staffId, hospital);
        }
        return callerStaffAt(hospital, recorderUserId);
    }

    private Staff callerStaffAt(Hospital hospital, UUID recorderUserId) {
        if (recorderUserId == null || hospital == null || hospital.getId() == null) {
            return null;
        }
        return staffRepository.findByUserIdAndHospitalId(recorderUserId, hospital.getId()).orElse(null);
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

    /**
     * URGENT alerts notify the documenting clinician (best-effort, house
     * policy) and, QA D605, the hospital's on-call team, with escalation until
     * someone else acknowledges. The team is notified even when the
     * documenter is unknown.
     */
    private void publishUrgentAlerts(List<com.example.hms.model.labor.LaborAlert> alerts,
                                     LaborEpisode episode, User documentedBy, String sourceType, UUID sourceId) {
        if (alerts == null || alerts.isEmpty()) {
            return;
        }
        boolean notifyDocumenter = documentedBy != null && documentedBy.getUsername() != null;
        // The documenter reads the notice in her language (QA D665); the escalation
        // row stores the staff-locale text, so the arg keeps the fallback as a key.
        Object patientArg = episode.getPatient() != null
            ? episode.getPatient().getFullName()
            : NotificationLocalizer.key("patient.fallback.generic");
        alerts.stream()
            .filter(alert -> alert.getSeverity() == LaborAlertSeverity.URGENT)
            .forEach(alert -> publishUrgentAlert(alert, episode, documentedBy, sourceType, sourceId,
                patientArg, notifyDocumenter));
    }

    /** The documenting clinician's own notice, in her language (QA D665). Never throws. */
    private void notifyDocumenter(com.example.hms.model.labor.LaborAlert alert, User documentedBy, Object patientArg, String messageKey, List<Object> messageArgs) {
        // QA D172 (D61/D121): the type is a translated label, never the raw enum.
        Object typeKey = alert.getType() == null ? ""
            : NotificationLocalizer.key("labor.alert.type." + alert.getType().name());
        try {
            notificationService.createNotification("labor.alert.notification",
                new Object[]{typeKey, patientArg,
                    com.example.hms.service.maternity.ObstetricAlertEscalationWorker.alertMessageArg(
                        messageKey, messageArgs, alert.getMessage())}, documentedBy.getUsername(), null);
        } catch (RuntimeException ex) {
            log.warn("Failed to create labor alert notification: {}", ex.getMessage());
        }
    }

    private void publishUrgentAlert(com.example.hms.model.labor.LaborAlert alert, LaborEpisode episode,
                                    User documentedBy, String sourceType, UUID sourceId,
                                    Object patientArg, boolean notifyDocumenter) {
        // QA D665: the alert sentence travels as its key and arguments (no key when
        // the arguments are not in memory: the stored sentence is used instead).
        String messageKey = alert.getCode() == null || alert.getMessageArgs() == null
            ? null : "labor.alert.msg." + alert.getCode();
        List<Object> messageArgs = messageKey == null ? null : NotificationLocalizer.storable(alert.getMessageArgs());
        if (notifyDocumenter) {
            notifyDocumenter(alert, documentedBy, patientArg, messageKey, messageArgs);
        }
        if (obstetricAlertEscalation != null && episode.getHospital() != null && episode.getPatient() != null) {
            // Stored on the escalation row: staff-locale text (the worker re-keys its notifications).
            java.util.Locale locale = NotificationLocales.STAFF;
            String patientName = episode.getPatient().getFullName();
            String typeLabel = alert.getType() == null ? ""
                : messageSource.getMessage("labor.alert.type." + alert.getType().name(), null, locale);
            String message = messageSource.getMessage("labor.alert.notification",
                new Object[]{typeLabel, patientName, alert.getMessage()}, locale);
            obstetricAlertEscalation.raiseAfterCommit(
                new com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert(
                    episode.getHospital().getId(), episode.getPatient().getId(), patientName,
                    sourceType, sourceId, alert.getType() == null ? null : alert.getType().name(),
                    typeLabel, alert.getMessage(),
                    documentedBy == null ? null : documentedBy.getId(), message, messageKey, messageArgs));
        }
    }
}
