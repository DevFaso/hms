package com.example.hms.service.pro;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.pro.ProResponseSource;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ProResponseMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Staff;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.pro.ProInstrument;
import com.example.hms.model.pro.ProResponse;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pro.ProResponseCreateDTO;
import com.example.hms.payload.dto.pro.ProResponseDTO;
import com.example.hms.payload.dto.pro.ProScreeningSummaryDTO;
import com.example.hms.payload.dto.pro.ProSelfReportDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.PostpartumCarePlanRepository;
import com.example.hms.repository.pro.ProResponseRepository;
import com.example.hms.security.SecurityUtils;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Administered instruments (Tier 2 item 47): recording, reading the trend,
 * acknowledging a self-harm-positive answer, and the cadence summary the
 * postpartum schedule shows.
 *
 * <p>House contract: hospital scope pinned (explicit param, else the
 * caller's active hospital, else the patient's primary); a foreign or
 * nonexistent patient or response collapses to the IDENTICAL not-found;
 * a concurrent acknowledgement surfaces as a clean retryable refusal via
 * the {@code @Version} column.
 *
 * <p>Recording links the response to the patient's open postpartum plan
 * and, when the screen is positive, sets the plan's
 * {@code mentalHealthReferralOutstanding} flag — the same flag the
 * observation alert engine raises, so the referral shows up where the
 * team already looks. Audit descriptions carry no answers and no score:
 * {@code event_description} is plaintext; the encrypted row is a click
 * away through {@code resourceId}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProResponseService {

    /** The postpartum module's screening instrument. */
    public static final String POSTPARTUM_INSTRUMENT_CODE = "EPDS";

    static final int MAX_HISTORY = 100;
    static final int DEFAULT_HISTORY = 20;

    private static final String PATIENT_NOT_FOUND = "patient.notfound";
    private static final String RESPONSE_NOT_FOUND = "Screening response not found.";

    private final ProResponseRepository responseRepository;
    private final ProInstrumentService instrumentService;
    private final ProScreeningEscalationService escalationService;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final StaffRepository staffRepository;
    private final PostpartumCarePlanRepository carePlanRepository;
    private final ProResponseMapper mapper;
    private final AuditEventLogService auditService;
    private final RoleValidator roleValidator;
    private final Clock clock;

    // ── staff surface ─────────────────────────────────────────────────

    @Transactional
    public ProResponseDTO record(UUID patientId, ProResponseCreateDTO request) {
        Patient patient = requirePatient(patientId);
        UUID hospitalId = resolveHospitalId(patient, request.getHospitalId());
        requireInTenant(patient, hospitalId);
        ProResponse response = persist(patient, hospitalId, request,
            ProResponseSource.STAFF_ADMINISTERED, roleValidator.getCurrentUserId());
        return mapper.toDto(response);
    }

    @Transactional(readOnly = true)
    public List<ProResponseDTO> history(UUID patientId, UUID requestedHospitalId,
                                        String instrumentCode, Integer limit) {
        Patient patient = requirePatient(patientId);
        UUID hospitalId = resolveHospitalId(patient, requestedHospitalId);
        requireInTenant(patient, hospitalId);
        String code = ProInstrumentService.normalizeCode(
            instrumentCode == null || instrumentCode.isBlank() ? POSTPARTUM_INSTRUMENT_CODE : instrumentCode);
        int size = limit == null || limit < 1 ? DEFAULT_HISTORY : Math.min(limit, MAX_HISTORY);
        return responseRepository
            .findByPatient_IdAndHospital_IdAndInstrument_CodeOrderByAdministeredAtDesc(
                patient.getId(), hospitalId, code, PageRequest.of(0, size))
            .stream()
            .map(mapper::toDto)
            .toList();
    }

    /**
     * Close the loop on a self-harm-positive response. Only a critical
     * response has anything to acknowledge; a second acknowledgement is
     * refused rather than silently overwriting who acted first.
     */
    @Transactional
    public ProResponseDTO acknowledge(UUID patientId, UUID responseId, UUID requestedHospitalId,
                                      String actionTaken) {
        Patient patient = requirePatient(patientId);
        UUID hospitalId = resolveHospitalId(patient, requestedHospitalId);
        requireInTenant(patient, hospitalId);
        ProResponse response = responseRepository
            .findByIdAndPatient_IdAndHospital_Id(responseId, patient.getId(), hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException(RESPONSE_NOT_FOUND));
        if (!response.isCriticalItemPositive()) {
            throw new BusinessException("Only a safety-item-positive response needs acknowledging.");
        }
        if (response.isAcknowledged()) {
            throw new BusinessException("This response was already acknowledged by "
                + response.getAcknowledgedByDisplay() + ".");
        }
        UUID userId = roleValidator.getCurrentUserId();
        response.setAcknowledgedAt(LocalDateTime.now(clock));
        response.setAcknowledgedByUserId(userId);
        response.setAcknowledgedByDisplay(currentDisplayName(userId, hospitalId));
        response.setAcknowledgementNote(trimToNull(actionTaken));
        try {
            response = responseRepository.saveAndFlush(response);
        } catch (OptimisticLockingFailureException ex) {
            throw new BusinessException("Somebody else just acknowledged this response - reload and retry.");
        }
        emitAudit(AuditEventType.PRO_ALERT_ACKNOWLEDGED, response, "PRO safety alert acknowledged");
        return mapper.toDto(response);
    }

    // ── cadence hook ──────────────────────────────────────────────────

    /**
     * Where the postpartum instrument stands on one plan. Read-only and
     * cheap: one indexed lookup, no decryption.
     */
    @Transactional(readOnly = true)
    public ProScreeningSummaryDTO summaryForCarePlan(PostpartumCarePlan plan) {
        boolean available = instrumentService.isAvailable(POSTPARTUM_INSTRUMENT_CODE);
        ProResponse last = plan == null ? null : responseRepository
            .findFirstByCarePlan_IdAndInstrument_CodeOrderByAdministeredAtDesc(
                plan.getId(), POSTPARTUM_INSTRUMENT_CODE)
            .orElse(null);
        ProScreeningSummaryDTO.ProScreeningSummaryDTOBuilder builder = ProScreeningSummaryDTO.builder()
            .instrumentCode(POSTPARTUM_INSTRUMENT_CODE)
            .instrumentAvailable(available)
            .due(available && plan != null && plan.isActive() && last == null);
        if (last != null) {
            builder.lastResponseId(last.getId())
                .lastAdministeredAt(last.getAdministeredAt())
                .lastTotalScore(last.getTotalScore())
                .maxScore(last.getMaxScore())
                .lastScreenPositive(last.isScreenPositive())
                .lastCriticalItemPositive(last.isCriticalItemPositive())
                .escalationOpen(last.isEscalationOpen());
        }
        return builder.build();
    }

    // ── patient self-service ──────────────────────────────────────────

    /**
     * What the patient may answer and what they answered before. Eligible
     * while a postpartum plan is open for the patient at any hospital they
     * are registered with — that is the only context in which an
     * unsolicited self-report has somebody on the other end. The plan
     * picks the hospital, not the patient's primary one: a mother whose
     * plan lives at the district hospital must not be told nothing is open
     * because she first registered at the health post.
     */
    @Transactional(readOnly = true)
    public ProSelfReportDTO overviewForSelf(Patient patient) {
        List<ProSelfReportDTO.Available> available = new ArrayList<>();
        if (openPlanForSelf(patient) != null) {
            for (ProInstrument instrument : instrumentService.listActive()) {
                List<String> languages = instrumentService.languagesOf(instrument);
                if (!languages.isEmpty()) {
                    available.add(ProSelfReportDTO.Available.builder()
                        .code(instrument.getCode())
                        .name(instrument.getName())
                        .languages(languages)
                        .build());
                }
            }
        }
        List<ProSelfReportDTO.Entry> history = responseRepository
            .findByPatient_IdOrderByAdministeredAtDesc(patient.getId(), PageRequest.of(0, DEFAULT_HISTORY))
            .stream()
            .map(mapper::toSelfEntry)
            .toList();
        return ProSelfReportDTO.builder().available(available).history(history).build();
    }

    /**
     * The self-report contract: the hospital comes from the open plan and
     * the administration time is the server's. A patient may not choose
     * either — a backdated critical answer would sort beneath the "last
     * result" the care team looks at, and the escalation clock would start
     * in the past.
     */
    @Transactional
    public ProSelfReportDTO.Entry recordForSelf(Patient patient, ProResponseCreateDTO request) {
        PostpartumCarePlan plan = openPlanForSelf(patient);
        if (plan == null) {
            throw new BusinessException(
                "No screening is open for you right now. Your care team will invite you when one is due.");
        }
        request.setHospitalId(null);
        request.setAdministeredAt(null);
        ProResponse response = persist(patient, plan.getHospital().getId(), request,
            ProResponseSource.PATIENT_REPORTED, null);
        return mapper.toSelfEntry(response);
    }

    /**
     * The patient's open postpartum plan, wherever it is: newest active
     * plan at a hospital the patient is (still) registered with.
     */
    private PostpartumCarePlan openPlanForSelf(Patient patient) {
        return carePlanRepository.findByPatient_IdAndActiveTrue(patient.getId()).stream()
            .filter(p -> p.getHospital() != null && isRegistered(patient, p.getHospital().getId()))
            .max(Comparator.comparing(PostpartumCarePlan::getCreatedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
    }

    // ── shared write path ─────────────────────────────────────────────

    private ProResponse persist(Patient patient, UUID hospitalId, ProResponseCreateDTO request,
                                ProResponseSource source, UUID recordedByUserId) {
        ProInstrument instrument = instrumentService.requireActive(request.getInstrumentCode());
        ProScoring.ProScoreResult score = ProScoring.score(instrument, request.getAnswers());
        Hospital hospital = hospitalRepository.findById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Hospital not found with ID: " + hospitalId));
        PostpartumCarePlan plan = activePlan(patient, hospitalId);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime administeredAt = request.getAdministeredAt() != null ? request.getAdministeredAt() : now;
        if (administeredAt.isAfter(now.plusMinutes(5))) {
            throw new BusinessException("The administration time cannot be in the future.");
        }

        ProResponse response = ProResponse.builder()
            .instrument(instrument)
            .patient(patient)
            .hospital(hospital)
            .carePlan(plan)
            .source(source)
            .language(request.getLanguage() != null
                ? ProInstrumentService.normalizeLanguage(request.getLanguage()) : null)
            .administeredAt(administeredAt)
            .recordedByUserId(recordedByUserId)
            .answers(mapper.answersToJson(request.getAnswers()))
            .notes(trimToNull(request.getNotes()))
            .totalScore(score.totalScore())
            .maxScore(instrument.getMaxScore())
            .instrumentVersion(instrument.getVersion())
            .answeredItems(score.answeredItems())
            .totalItems(score.totalItems())
            .complete(score.complete())
            .screenPositive(score.screenPositive())
            .criticalItemScore(score.criticalItemScore())
            .criticalItemPositive(score.criticalPositive())
            .build();
        response = responseRepository.saveAndFlush(response);

        if (plan != null && (score.screenPositive() || score.criticalPositive())
            && !plan.isMentalHealthReferralOutstanding()) {
            plan.setMentalHealthReferralOutstanding(true);
            carePlanRepository.save(plan);
        }

        emitAudit(AuditEventType.PRO_RESPONSE_RECORDED, response,
            "PRO response recorded (" + instrument.getCode() + ", " + source + ")");
        escalationService.notifyOnRecord(response);
        return response;
    }

    // ── helpers ───────────────────────────────────────────────────────

    private PostpartumCarePlan activePlan(Patient patient, UUID hospitalId) {
        return carePlanRepository
            .findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patient.getId(), hospitalId)
            .orElse(null);
    }

    private Patient requirePatient(UUID patientId) {
        return patientRepository.findById(patientId)
            .orElseThrow(() -> new ResourceNotFoundException(PATIENT_NOT_FOUND));
    }

    /**
     * Explicit parameter, else the caller's active hospital, else (for a
     * super-admin in global view) the patient's own primary hospital.
     */
    private UUID resolveHospitalId(Patient patient, UUID requested) {
        if (requested != null) {
            return requested;
        }
        UUID active = roleValidator.requireActiveHospitalId();
        if (active != null) {
            return active;
        }
        if (patient.getHospitalId() != null) {
            return patient.getHospitalId();
        }
        Hospital primary = patient.getPrimaryHospital();
        if (primary != null) {
            return primary.getId();
        }
        throw new BusinessException("Unable to resolve a hospital for this patient.");
    }

    /** Same not-found as a nonexistent patient: a foreign chart is not a chart. */
    private static void requireInTenant(Patient patient, UUID hospitalId) {
        if (!isRegistered(patient, hospitalId)) {
            throw new ResourceNotFoundException(PATIENT_NOT_FOUND);
        }
    }

    private static boolean isRegistered(Patient patient, UUID hospitalId) {
        return patient.isRegisteredInHospital(hospitalId) || hospitalId.equals(patient.getHospitalId());
    }

    private String currentDisplayName(UUID userId, UUID hospitalId) {
        if (userId != null) {
            Staff staff = staffRepository.findByUserIdAndHospitalId(userId, hospitalId).orElse(null);
            if (staff != null && staff.getFullName() != null && !staff.getFullName().isBlank()) {
                return staff.getFullName();
            }
        }
        String username = SecurityUtils.getCurrentUsername();
        return username != null ? username : "unknown";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Best-effort: the response row is the record; the trail entry must not undo it. */
    private void emitAudit(AuditEventType type, ProResponse response, String description) {
        try {
            auditService.logEvent(AuditEventRequestDTO.builder()
                .eventType(type)
                .status(AuditStatus.SUCCESS)
                .entityType("PRO_RESPONSE")
                .resourceId(response.getId() != null ? response.getId().toString() : null)
                .patientId(response.getPatient() != null ? response.getPatient().getId() : null)
                .userId(roleValidator.getCurrentUserId())
                .userName(SecurityUtils.getCurrentUsername())
                .hospitalName(response.getHospital() != null ? response.getHospital().getName() : null)
                .eventDescription(description)
                .build());
        } catch (RuntimeException ex) {
            log.warn("Failed to emit {} audit for PRO response {}: {}", type, response.getId(), ex.getMessage());
        }
    }
}
