package com.example.hms.service.transfusion;

import com.example.hms.enums.AntibodyScreenResult;
import com.example.hms.enums.AntibodyWorkupStatus;
import com.example.hms.enums.InBasketItemType;
import com.example.hms.enums.InBasketPriority;
import com.example.hms.enums.TransfusionUrgency;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.TransfusionMapper;
import com.example.hms.model.BloodUnit;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAntibodyWorkup;
import com.example.hms.model.PatientBloodGroup;
import com.example.hms.model.TransfusionCrossmatch;
import com.example.hms.model.TransfusionRequest;
import com.example.hms.model.User;
import com.example.hms.payload.dto.clinical.CreateInBasketItemRequest;
import com.example.hms.payload.dto.transfusion.AntibodyStatusResponseDTO;
import com.example.hms.payload.dto.transfusion.AntibodyWorkupRequestDTO;
import com.example.hms.payload.dto.transfusion.CrossmatchRequestDTO;
import com.example.hms.payload.dto.transfusion.TransfusionRequestRequestDTO;
import com.example.hms.repository.PatientAntibodyWorkupRepository;
import com.example.hms.repository.PatientBloodGroupRepository;
import com.example.hms.repository.TransfusionRequestRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.InBasketService;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import com.example.hms.service.i18n.NotificationLocales;
import com.example.hms.service.support.PositiveAntibodyScreenPolicy;
import com.example.hms.service.support.PositiveAntibodyScreenPolicy.Compatibilisation;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * QA D3351 (r70): what {@link PositiveAntibodyScreenPolicy} (rule set TRANSFUSION_POSITIVE_ANTIBODY_SCREEN,
 * « provisoire ») needs from the record, and every place it is enforced. {@code TransfusionServiceImpl} calls it
 * where a request is raised, a crossmatch is written and a unit leaves the laboratory; it holds no way round.
 *
 * <p>Everything is WRITTEN at the caller's hospital: a screen, a work-up and the tasks belong to the hospital
 * that made them. The flag is READ for the patient, at every hospital (owner decision 2026-11-22): an
 * alloantibody is permanent and can become undetectable, so a positive screen or an identified antibody at one
 * hospital holds red cells at the next, as an allergy follows the patient, and whatever the patient chose about
 * record sharing. What another hospital's record shows here is the fact, the antibody, the date and that
 * hospital's name, never its text, and each time it is shown the read is accounted as a cross-hospital one.
 *
 * <p>It opens no transaction of its own: every call comes from {@code TransfusionServiceImpl}, inside the
 * transaction of the request, the result or the release it guards.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.TRANSFUSION_POSITIVE_ANTIBODY_SCREEN)
public class PositiveAntibodyScreenService {

    /** The biologist's task after a positive screen: identify the antibody, or refer the sample. */
    public static final String IDENTIFY_TITLE_KEY = "transfusion.antibody.identify.inBasketTitle";
    public static final String IDENTIFY_BODY_KEY = "transfusion.antibody.identify.inBasketBody";
    /** The biologist's task after an emergency release to an allo-immunised patient: the crossmatch afterwards. */
    public static final String RETROSPECTIVE_TITLE_KEY = "transfusion.antibody.retrospective.inBasketTitle";
    public static final String RETROSPECTIVE_BODY_KEY = "transfusion.antibody.retrospective.inBasketBody";
    static final String SCREEN_REFERENCE_TYPE = "TRANSFUSION_ANTIBODY_SCREEN";
    static final String RETROSPECTIVE_REFERENCE_TYPE = "TRANSFUSION_RETROSPECTIVE_CROSSMATCH";

    private final PatientBloodGroupRepository bloodGroupRepository;
    private final PatientAntibodyWorkupRepository workupRepository;
    private final TransfusionRequestRepository requestRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final RoleValidator roleValidator;
    private final InBasketService inBasketService;
    private final Clock clock;

    /** What a reader acting at one hospital is told when the flag rests on another hospital's record. */
    public static final String REACH_DESCRIPTION =
        "Cross-hospital allo-immunisation flag read (transfusion safety, shown whatever the sharing choice)";

    /**
     * The account of cross-hospital reads. Optional for the sliced test contexts only; in the application the
     * bean exists and every read of another hospital's antibody record is accounted.
     */
    private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setReachRecorder(com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder) {
        this.reachRecorder = reachRecorder;
    }

    // ── The flag ────────────────────────────────────────────────────────

    /**
     * The patient is allo-immunised: a positive screen is on record at some hospital (superseded ones included: a
     * later negative screen clears nothing, here or elsewhere), or a biologist recorded an antibody at some
     * hospital. The flag follows the patient; the record-sharing choice of the patient does not narrow it.
     */
    public boolean alloImmunised(UUID patientId) {
        return patientId != null && (!positiveScreens(patientId).isEmpty() || workupRepository.existsByPatient_Id(patientId));
    }

    /**
     * {@link #alloImmunised(UUID)} as it is SHOWN to somebody acting at a hospital: when the flag rests on
     * another hospital's record, the read is accounted as a cross-hospital one.
     */
    public boolean alloImmunisedShownAt(UUID patientId, UUID actingHospitalId) {
        if (patientId == null) {
            return false;
        }
        List<PatientBloodGroup> screens = positiveScreens(patientId);
        List<PatientAntibodyWorkup> workups = workupRepository.findByPatient_IdOrderByRecordedAtDesc(patientId);
        accountReach(patientId, actingHospitalId, screens, workups);
        return !screens.isEmpty() || !workups.isEmpty();
    }

    /** Every positive screen of the patient, at every hospital, the newest first. */
    private List<PatientBloodGroup> positiveScreens(UUID patientId) {
        return bloodGroupRepository.findByPatient_IdAndAntibodyScreenOrderByPerformedAtDesc(patientId,
            AntibodyScreenResult.POSITIVE);
    }

    /** The flag and the biologists' records, for the screens that must show them. */
    public AntibodyStatusResponseDTO status(UUID patientId, UUID actingHospitalId) {
        return status(patientId, actingHospitalId, null);
    }

    /**
     * {@link #status(UUID, UUID)} with the patient's name, for a screen that names them.
     *
     * <p>What crosses from another hospital is the minimum the next transfusion needs: that a positive screen is on
     * record, its date and the hospital that holds it; and, when an antibody was identified there, its
     * specificity, the date and that hospital. Another hospital's referral decision, its centre, its reasons, its
     * notes and its author are not shown (until an antibody is identified the patient is, here, "positive screen,
     * not identified"); nor is anything else of its blood group record.
     */
    public AntibodyStatusResponseDTO status(UUID patientId, UUID actingHospitalId, String patientName) {
        List<PatientAntibodyWorkup> workups = patientId == null ? List.of()
            : workupRepository.findByPatient_IdOrderByRecordedAtDesc(patientId);
        List<PatientBloodGroup> screens = patientId == null ? List.of() : positiveScreens(patientId);
        Optional<PatientBloodGroup> positive = screens.stream().findFirst();
        Optional<PatientAntibodyWorkup> identified = workups.stream()
            .filter(w -> w.getStatus() == AntibodyWorkupStatus.IDENTIFIED).findFirst();
        accountReach(patientId, actingHospitalId, screens, workups);
        return AntibodyStatusResponseDTO.builder()
            .patientId(patientId)
            .patientName(patientName)
            .alloImmunised(positive.isPresent() || !workups.isEmpty())
            .positiveScreenAt(positive.map(PatientBloodGroup::getPerformedAt).orElse(null))
            .positiveScreenHospitalName(positive.map(PatientBloodGroup::getHospital).map(Hospital::getName).orElse(null))
            .identified(identified.isPresent())
            .antibodySpecificities(identified.map(PatientAntibodyWorkup::getAntibodySpecificities).orElse(null))
            .identifiedHospitalName(identified.map(PatientAntibodyWorkup::getHospital).map(Hospital::getName).orElse(null))
            .identifiedAt(identified.map(PatientAntibodyWorkup::getRecordedAt).orElse(null))
            .otherHospitalNames(otherHospitalNames(actingHospitalId, screens, workups))
            .workups(workups.stream()
                .filter(workup -> !elsewhere(workup.getHospital(), actingHospitalId)
                    || workup.getStatus() == AntibodyWorkupStatus.IDENTIFIED)
                .map(workup -> toDto(workup, actingHospitalId)).toList())
            .build();
    }

    private static boolean elsewhere(Hospital hospital, UUID actingHospitalId) {
        return hospital == null || !Objects.equals(hospital.getId(), actingHospitalId);
    }

    private static List<String> otherHospitalNames(UUID actingHospitalId, List<PatientBloodGroup> screens,
                                                   List<PatientAntibodyWorkup> workups) {
        List<Hospital> sources = new java.util.ArrayList<>();
        screens.forEach(screen -> sources.add(screen.getHospital()));
        workups.forEach(workup -> sources.add(workup.getHospital()));
        return sources.stream().filter(hospital -> hospital != null && elsewhere(hospital, actingHospitalId))
            .map(Hospital::getName).filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static AntibodyStatusResponseDTO.Workup toDto(PatientAntibodyWorkup workup, UUID actingHospitalId) {
        String hospitalName = workup.getHospital() == null ? null : workup.getHospital().getName();
        if (elsewhere(workup.getHospital(), actingHospitalId)) {
            // Another hospital's identification: the antibody, the date, the hospital. Nothing of its text.
            return AntibodyStatusResponseDTO.Workup.builder()
                .status(workup.getStatus())
                .antibodySpecificities(workup.getAntibodySpecificities())
                .recordedAt(workup.getRecordedAt())
                .hospitalName(hospitalName)
                .otherHospital(true)
                .build();
        }
        return AntibodyStatusResponseDTO.Workup.builder()
            .id(workup.getId())
            .status(workup.getStatus())
            .antibodySpecificities(workup.getAntibodySpecificities())
            .referralCentre(workup.getReferralCentre())
            .decisionReason(workup.getDecisionReason())
            .transfusionRequestId(workup.getTransfusionRequestId())
            .recordedByDisplay(workup.getRecordedByDisplay())
            .recordedAt(workup.getRecordedAt())
            .notes(workup.getNotes())
            .hospitalName(hospitalName)
            .build();
    }

    /**
     * Accounts what was shown of other hospitals' records, one entry a source hospital, as every cross-hospital
     * read is. It never fails the read it accounts for: the flag exists to stop an unsafe transfusion, and an
     * audit that cannot be written must not hide it or hold blood back.
     */
    private void accountReach(UUID patientId, UUID actingHospitalId, List<PatientBloodGroup> screens,
                              List<PatientAntibodyWorkup> workups) {
        if (reachRecorder == null || patientId == null || actingHospitalId == null) {
            return;
        }
        try {
            List<UUID> sources = new java.util.ArrayList<>();
            screens.forEach(screen -> sources.add(
                com.example.hms.service.recordaccess.CrossHospitalReachRecorder.hospitalIdOf(screen.getHospital())));
            workups.forEach(workup -> sources.add(
                com.example.hms.service.recordaccess.CrossHospitalReachRecorder.hospitalIdOf(workup.getHospital())));
            reachRecorder.recordReach(patientId, actingHospitalId, roleValidator.getCurrentUserId(), null,
                com.example.hms.service.recordaccess.CrossHospitalReachRecorder.reachOf(sources, actingHospitalId),
                REACH_DESCRIPTION);
        } catch (RuntimeException ex) {
            log.warn("Cross-hospital allo-immunisation read not accounted for patient {} at hospital {}: {}",
                patientId, actingHospitalId, ex.getClass().getSimpleName());
        }
    }

    // ── The biologist's record ──────────────────────────────────────────

    /**
     * Records the antibody identified, or the referral and the decision to serve one request before the answer.
     * Only a biologist of this hospital ({@link PositiveAntibodyScreenPolicy#BIOLOGIST_ROLE_CODES}, held by
     * assignment): a super-admin alone, a laboratory technician and a prescriber are refused.
     */
    public AntibodyStatusResponseDTO recordWorkup(AntibodyWorkupRequestDTO request, Patient patient, Hospital hospital) {
        UUID hospitalId = hospital.getId();
        UUID userId = roleValidator.getCurrentUserId();
        if (userId == null || !assignmentRepository.existsActiveWitness(userId, hospitalId,
                PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolveOrRaw("transfusion.antibody.biologistOnly"));
        }
        PatientAntibodyWorkup.PatientAntibodyWorkupBuilder workup = PatientAntibodyWorkup.builder()
            .patient(patient)
            .hospital(hospital)
            .status(request.getStatus())
            .recordedByUserId(userId)
            .recordedByDisplay(userRepository.findById(userId).map(TransfusionMapper::userName).orElse(null))
            .recordedAt(LocalDateTime.now(clock))
            .notes(StringUtils.hasText(request.getNotes()) ? request.getNotes().trim() : null);
        if (request.getStatus() == AntibodyWorkupStatus.IDENTIFIED) {
            if (!StringUtils.hasText(request.getAntibodySpecificities())) {
                throw new FieldBusinessException("antibodySpecificities", "transfusion.antibody.specificitiesRequired");
            }
            workup.antibodySpecificities(request.getAntibodySpecificities().trim());
        } else {
            if (!StringUtils.hasText(request.getReferralCentre())) {
                throw new FieldBusinessException("referralCentre", "transfusion.antibody.referralCentreRequired");
            }
            if (!StringUtils.hasText(request.getDecisionReason())) {
                throw new FieldBusinessException("decisionReason", "transfusion.antibody.decisionReasonRequired");
            }
            workup.referralCentre(request.getReferralCentre().trim())
                .decisionReason(request.getDecisionReason().trim())
                .transfusionRequestId(servedRequest(request.getTransfusionRequestId(), patient, hospitalId).getId());
        }
        workupRepository.save(workup.build());
        return status(patient.getId(), hospitalId, patient.getFullName());
    }

    /** The open red-cell request of this patient at this hospital that a referral decision serves. */
    private TransfusionRequest servedRequest(UUID requestId, Patient patient, UUID hospitalId) {
        if (requestId == null) {
            throw new FieldBusinessException("transfusionRequestId", "transfusion.antibody.requestRequired");
        }
        TransfusionRequest served = requestRepository.findById(requestId)
            .filter(r -> r.getHospital() != null && Objects.equals(r.getHospital().getId(), hospitalId))
            .filter(r -> r.getPatient() != null && Objects.equals(r.getPatient().getId(), patient.getId()))
            .orElseThrow(() -> new ResourceNotFoundException("transfusion.request.notFound", requestId));
        if (served.isTerminal() || !PositiveAntibodyScreenPolicy.appliesTo(served.getProductType())) {
            throw new FieldBusinessException("transfusionRequestId", "transfusion.antibody.requestNotServable");
        }
        return served;
    }

    // ── Where the rule is enforced ──────────────────────────────────────

    /** Whether the rule holds this request: red cells for an allo-immunised patient, wherever that is on record. */
    public boolean holds(TransfusionRequest request) {
        return request != null && request.getPatient() != null && request.getHospital() != null
            && PositiveAntibodyScreenPolicy.appliesTo(request.getProductType())
            && alloImmunised(request.getPatient().getId());
    }

    /** {@link #holds} for a request that is being SHOWN: a flag resting on another hospital's record is accounted. */
    public boolean holdsShown(TransfusionRequest request) {
        return request != null && request.getPatient() != null && request.getHospital() != null
            && PositiveAntibodyScreenPolicy.appliesTo(request.getProductType())
            && alloImmunisedShownAt(request.getPatient().getId(), request.getHospital().getId());
    }

    /**
     * A new EMERGENCY request of red cells for an allo-immunised patient states the level of vital urgency (UVI
     * or UV), why, and that the pre-transfusion sample is drawn before the first unit. Nothing is asked of a
     * routine or an urgent request (they follow the routine rule), nor of another product or patient.
     */
    public void requireEmergencyDeclaration(TransfusionRequestRequestDTO request, UUID patientId,
                                            TransfusionUrgency urgency) {
        if (urgency != TransfusionUrgency.EMERGENCY || !PositiveAntibodyScreenPolicy.appliesTo(request.getProductType())
                || !alloImmunised(patientId)) {
            return;
        }
        if (request.getVitalUrgencyLevel() == null) {
            throw new FieldBusinessException("vitalUrgencyLevel", "transfusion.antibody.vitalUrgencyLevelRequired");
        }
        if (!StringUtils.hasText(request.getVitalUrgencyReason())) {
            throw new FieldBusinessException("vitalUrgencyReason", "transfusion.antibody.vitalUrgencyReasonRequired");
        }
        if (!Boolean.TRUE.equals(request.getPreTransfusionSampleDrawn())) {
            throw new FieldBusinessException("preTransfusionSampleDrawn", "transfusion.antibody.sampleDrawnRequired");
        }
    }

    /**
     * A COMPATIBLE crossmatch for a held request is « compatibilisé » or it is not written: the work-up, the
     * antiglobulin technique, the two attestations about the unit and a sample of 72 hours or less. An
     * incompatible result is always written. So is a result on an EMERGENCY request: around an emergency
     * release the result is the laboratory's finding and is recorded as found (the crossmatch afterwards this
     * rule asks the biologist for would otherwise be impossible to record before the antibody is identified).
     */
    public void requireCompatibilised(TransfusionRequest request, CrossmatchRequestDTO result) {
        if (!Boolean.TRUE.equals(result.getCompatible()) || !holds(request) || emergency(request)) {
            return;
        }
        refuse(PositiveAntibodyScreenPolicy.firstMissing(workupServes(request),
            new Compatibilisation(result.getAntiglobulinMethod(), result.getAntigenNegative(),
                result.getRhKellMatched(), result.getSampleCollectedAt()), LocalDateTime.now(clock)));
    }

    /**
     * A unit leaves the laboratory on its crossmatch (the routine path) for a held request only when that
     * crossmatch is « compatibilisé » NOW: the sample ages, and a crossmatch written before the positive screen
     * carries no attestation. An EMERGENCY request is not held by it (a vital emergency is never delayed), but
     * what its crossmatch lacks is still owed: a plain « compatible » result is not the antiglobulin crossmatch
     * on an antigen-negative unit, so the biologist is asked for the crossmatch afterwards.
     *
     * @return true when an EMERGENCY request leaves on a crossmatch that is not « compatibilisé »: the caller
     *         then asks the biologist for the crossmatch afterwards
     */
    public boolean requireRoutineIssueAllowed(TransfusionRequest request, TransfusionCrossmatch crossmatch) {
        if (!holds(request)) {
            return false;
        }
        Optional<PositiveAntibodyScreenPolicy.Missing> missing = PositiveAntibodyScreenPolicy.firstMissing(
            workupServes(request), crossmatch == null ? null
                : new Compatibilisation(crossmatch.getAntiglobulinMethod(), crossmatch.getAntigenNegative(),
                    crossmatch.getRhKellMatched(), crossmatch.getSampleCollectedAt()), LocalDateTime.now(clock));
        if (emergency(request)) {
            return missing.isPresent();
        }
        refuse(missing);
        return false;
    }

    /**
     * An emergency release (uncrossmatched, O positive by decision, serologically incompatible) to an
     * allo-immunised patient is open to an EMERGENCY request only. « Urgence relative » (an URGENT request) and a
     * routine request have no emergency path: they are told what the compatible unit still lacks.
     *
     * <p>The prescriber's declaration (level, reason, sample drawn) is asked when the request is RAISED for a
     * patient already known allo-immunised ({@link #requireEmergencyDeclaration}). It is never asked here: a
     * request raised before the screen turned positive could not carry it, and a vital emergency in progress is
     * not delayed (« ne pas retarder la transfusion même si RAI positives »). The existing emergency release
     * rules (reason, authoriser) apply to it unchanged.
     *
     * @return true when the rule holds the request and the emergency path is taken: the caller then asks the
     *         biologist for the crossmatch afterwards
     */
    public boolean requireEmergencyReleaseAllowed(TransfusionRequest request) {
        if (!holds(request)) {
            return false;
        }
        if (!emergency(request)) {
            refuse(PositiveAntibodyScreenPolicy.firstMissing(workupServes(request), null, LocalDateTime.now(clock)));
        }
        return true;
    }

    private static boolean emergency(TransfusionRequest request) {
        return request.getUrgency() == TransfusionUrgency.EMERGENCY;
    }

    /**
     * The antibody is identified since the newest positive screen, or a referral decision serves this very
     * request. An identification made before a later positive screen does not cover it: that screen may be a
     * second antibody, and the biologist is asked again ({@link #askBiologistToIdentify}).
     */
    public boolean workupServes(TransfusionRequest request) {
        UUID patientId = request.getPatient().getId();
        UUID hospitalId = request.getHospital().getId();
        return identifiedSinceTheNewestPositiveScreen(patientId)
            || (request.getId() != null && workupRepository.existsByPatient_IdAndHospital_IdAndStatusAndTransfusionRequestId(
                patientId, hospitalId, AntibodyWorkupStatus.REFERRED, request.getId()));
    }

    /** Read for the patient, at every hospital: an identification made at one serves at another. */
    private boolean identifiedSinceTheNewestPositiveScreen(UUID patientId) {
        Optional<PatientAntibodyWorkup> identified = workupRepository
            .findFirstByPatient_IdAndStatusOrderByRecordedAtDesc(patientId, AntibodyWorkupStatus.IDENTIFIED);
        if (identified.isEmpty()) {
            return false;
        }
        LocalDateTime screenedAt = positiveScreens(patientId).stream().findFirst()
            .map(PatientBloodGroup::getPerformedAt).orElse(null);
        if (screenedAt == null) {
            // An antibody recorded with no positive screen on the platform (known from outside): nothing newer.
            return true;
        }
        LocalDateTime recordedAt = identified.get().getRecordedAt();
        return recordedAt != null && !recordedAt.isBefore(screenedAt);
    }

    private static void refuse(Optional<PositiveAntibodyScreenPolicy.Missing> missing) {
        missing.ifPresent(lack -> {
            throw new FieldBusinessException(lack.field(), lack.messageKey(),
                PositiveAntibodyScreenPolicy.SAMPLE_MAX_AGE_HOURS);
        });
    }

    // ── The biologist's tasks ───────────────────────────────────────────

    /**
     * A positive screen was just recorded: every biologist of the hospital is asked to identify or refer. Every
     * positive screen asks, one recorded after an antibody was identified too: it may be a second antibody, and
     * the earlier identification does not cover it ({@link #workupServes}).
     */
    public void askBiologistToIdentify(PatientBloodGroup screen) {
        if (screen == null || screen.getAntibodyScreen() != AntibodyScreenResult.POSITIVE
                || screen.getPatient() == null || screen.getHospital() == null) {
            return;
        }
        Patient patient = screen.getPatient();
        Object[] args = {patient.getFullName()};
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setTitleKey(IDENTIFY_TITLE_KEY);
        item.setBodyKey(IDENTIFY_BODY_KEY);
        item.setReferenceId(screen.getId());
        item.setReferenceType(SCREEN_REFERENCE_TYPE);
        fileForBiologists(item, screen.getHospital().getId(), patient, args, args);
    }

    /** A unit went out on the emergency path to an allo-immunised patient: the crossmatch is done afterwards. */
    public void askRetrospectiveCrossmatch(TransfusionRequest request, BloodUnit unit) {
        Patient patient = request.getPatient();
        Object[] titleArgs = {unit.getUnitNumber()};
        Object[] bodyArgs = {unit.getUnitNumber(), patient == null ? null : patient.getFullName(),
            request.getVitalUrgencyLevel() == null ? "" : request.getVitalUrgencyLevel().name()};
        CreateInBasketItemRequest item = new CreateInBasketItemRequest();
        item.setTitleKey(RETROSPECTIVE_TITLE_KEY);
        item.setBodyKey(RETROSPECTIVE_BODY_KEY);
        item.setReferenceId(unit.getId());
        item.setReferenceType(RETROSPECTIVE_REFERENCE_TYPE);
        item.setEncounterId(request.getEncounter() == null ? null : request.getEncounter().getId());
        fileForBiologists(item, request.getHospital().getId(), patient, titleArgs, bodyArgs);
    }

    /**
     * One URGENT task per biologist, filed after the commit: a filing that fails never undoes what raised it.
     * {@code template} carries the title and body keys; each reader's copy is rendered from them.
     */
    private void fileForBiologists(CreateInBasketItemRequest template, UUID hospitalId, Patient patient,
                                   Object[] titleArgs, Object[] bodyArgs) {
        String titleKey = template.getTitleKey();
        String bodyKey = template.getBodyKey();
        UUID referenceId = template.getReferenceId();
        String referenceType = template.getReferenceType();
        UUID encounterId = template.getEncounterId();
        List<UUID> biologists = assignmentRepository
            .findLiveUsersAtHospitalWithRoleCodes(hospitalId, PositiveAntibodyScreenPolicy.BIOLOGIST_ROLE_CODES)
            .stream().map(User::getId).distinct().toList();
        if (biologists.isEmpty()) {
            // The block does not depend on the task: with no biologist, routine red cells simply stay held.
            log.warn("Antibody task {} {} has no biologist to go to at hospital {}", referenceType, referenceId, hospitalId);
            return;
        }
        String title = MessageUtil.resolve(NotificationLocales.STAFF, titleKey, titleArgs);
        String body = MessageUtil.resolve(NotificationLocales.STAFF, bodyKey, bodyArgs);
        UUID patientId = patient == null ? null : patient.getId();
        String patientName = patient == null ? null : patient.getFullName();
        TransactionCallbacks.afterCommit(() -> {
            for (UUID biologist : biologists) {
                try {
                    CreateInBasketItemRequest item = new CreateInBasketItemRequest();
                    item.setRecipientUserId(biologist);
                    item.setHospitalId(hospitalId);
                    item.setItemType(InBasketItemType.TASK);
                    item.setPriority(InBasketPriority.URGENT);
                    item.setReferenceId(referenceId);
                    item.setReferenceType(referenceType);
                    item.setEncounterId(encounterId);
                    item.setPatientId(patientId);
                    item.setPatientName(patientName);
                    item.setTitleKey(titleKey);
                    item.setTitleArgs(titleArgs);
                    item.setTitle(title);
                    item.setBodyKey(bodyKey);
                    item.setBodyArgs(bodyArgs);
                    item.setBody(body);
                    inBasketService.createItemInNewTransaction(item);
                } catch (RuntimeException ex) {
                    log.warn("In-basket item not filed for {} {}: {}", referenceType, referenceId,
                        ex.getClass().getSimpleName());
                }
            }
        });
    }
}
