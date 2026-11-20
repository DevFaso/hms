package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.ProxyRelationship;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.enums.SensitivityCategory;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProxy;
import com.example.hms.model.ProxySensitiveRelease;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.MinorReleaseOverviewDTO;
import com.example.hms.payload.dto.recordaccess.ProxySensitiveReleaseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.ProxySensitiveReleaseRepository;
import com.example.hms.security.tenant.TenantOwnership;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Policy 2026-11-20 (provisional, « Burkina clinic policy decisions » row 4):
 * the release of a minor's sensitive result to a parent or guardian.
 *
 * <p>Loi 030-2008 gives the parents of a tested minor a right to the HIV
 * result (art. 18), so the result is never blocked for good; WHO asks that an
 * adolescent's result is not shown automatically. Both hold when the result
 * stays hidden in the family's portal and a doctor or a midwife who has care
 * of the patient releases it, after counselling, to one named parent:
 * <ul>
 *   <li>the patient is under 18 (an adult's result is the adult's only);</li>
 *   <li>the grant is an active PARENT or LEGAL_GUARDIAN one, and staff
 *       verified the relationship (the clinician may attest it here);</li>
 *   <li>the result is one this hospital's laboratory released, and one a
 *       proxy does not read;</li>
 *   <li>a reason is recorded (encrypted) and the release is audited, ids and
 *       category only.</li>
 * </ul>
 * One release names one order and one grant: the next result of the same test
 * is hidden again until it is released in turn.
 */
@Service
@RequiredArgsConstructor
public class MinorResultReleaseServiceImpl implements MinorResultReleaseService {

    private static final Logger log = LoggerFactory.getLogger(MinorResultReleaseServiceImpl.class);
    private static final String MSG_GRANT_NOT_FOUND = "proxyRelease.grantNotFound";
    private static final String MSG_RESULT_NOT_FOUND = "proxyRelease.resultNotFound";
    /** As {@code MinorResultReleaseRequestDTO}: the shortest reason, counted without the spaces around it. */
    private static final int MIN_REASON_LENGTH = 10;

    private final PatientChartAccess patientChartAccess;
    private final TreatmentRelationshipResolver treatmentRelationshipResolver;
    private final PatientProxyRepository patientProxyRepository;
    private final ProxySensitiveReleaseRepository releaseRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final HospitalRepository hospitalRepository;
    private final ProxyRecordFilter proxyRecordFilter;
    private final AuditEventLogService auditEventLogService;

    @Override
    @Transactional(readOnly = true)
    public MinorReleaseOverviewDTO overview(UUID patientId, UUID actorUserId) {
        UUID hospitalId = requireActingHospital();
        Patient patient = requirePatientInCare(patientId, hospitalId, actorUserId);
        return overviewOf(patient, hospitalId);
    }

    @Override
    @Transactional
    public MinorReleaseOverviewDTO verifyGuardian(UUID patientId, UUID grantId, UUID actorUserId) {
        UUID hospitalId = requireActingHospital();
        Patient patient = requirePatientInCare(patientId, hospitalId, actorUserId);
        requireMinor(patient);
        PatientProxy grant = requireGuardianGrant(patientId, grantId);
        stampVerified(grant, patient, hospitalId, actorUserId);
        return overviewOf(patient, hospitalId);
    }

    @Override
    @Transactional
    public MinorReleaseOverviewDTO release(UUID patientId, UUID labOrderId, UUID grantId, String reason,
                                           boolean relationshipVerified, UUID actorUserId) {
        // The reason is what the audit trail rests on: real words, not ten spaces.
        if (reason == null || reason.trim().length() < MIN_REASON_LENGTH) {
            throw new com.example.hms.exception.FieldBusinessException("reason", "proxyRelease.reasonRequired",
                MIN_REASON_LENGTH);
        }
        UUID hospitalId = requireActingHospital();
        Patient patient = requirePatientInCare(patientId, hospitalId, actorUserId);
        requireMinor(patient);
        PatientProxy grant = requireGuardianGrant(patientId, grantId);
        if (grant.getRelationshipVerifiedAt() == null) {
            if (!relationshipVerified) {
                throw new BusinessException("proxyRelease.relationshipNotVerified");
            }
            stampVerified(grant, patient, hospitalId, actorUserId);
        }
        LabOrder order = requireOrder(patientId, labOrderId, hospitalId);
        SensitivityCategory category = proxyRecordFilter.hidingCategoryOf(order);
        if (category == null) {
            throw new BusinessException("proxyRelease.notSensitive");
        }
        if (labResultRepository.findByLabOrder_Id(labOrderId).stream().noneMatch(LabResult::isReleased)) {
            throw new BusinessException("proxyRelease.notReleasedYet");
        }
        if (releaseRepository.existsByProxyGrantIdAndLabOrderId(grantId, labOrderId)) {
            // Released already: the parent reads it. Said again, it changes nothing and audits nothing.
            return overviewOf(patient, hospitalId);
        }
        ProxySensitiveRelease release = releaseRepository.save(ProxySensitiveRelease.builder()
            .hospitalId(hospitalId)
            .patientId(patientId)
            .proxyGrantId(grantId)
            .proxyUserId(grant.getProxyUser().getId())
            .labOrderId(labOrderId)
            .sensitivityCategory(category)
            .reason(reason.trim())
            .releasedByUserId(actorUserId)
            .releasedAt(LocalDateTime.now())
            .build());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("releaseId", String.valueOf(release.getId()));
        details.put("labOrderId", labOrderId.toString());
        details.put("grantId", grantId.toString());
        details.put("proxyUserId", grant.getProxyUser().getId().toString());
        details.put("relationship", String.valueOf(grant.getRelationship()));
        details.put("category", category.name());
        audit(AuditEventType.SENSITIVE_RESULT_RELEASED_TO_PROXY, "PROXY_SENSITIVE_RELEASE", labOrderId, patientId,
            hospitalId, actorUserId, "Sensitive result (" + category + ") released to a verified parent or guardian",
            details);
        log.info("[proxy-release] order {} ({}) released to grant {}", labOrderId, category, grantId);
        return overviewOf(patient, hospitalId);
    }

    // ── The checks ──────────────────────────────────────────────────────────

    /** A release is a clinical act at one hospital: a global view has none to act at. */
    private static UUID requireActingHospital() {
        UUID hospitalId = TenantOwnership.actingHospitalIdOrNullForGlobal();
        if (hospitalId == null) {
            throw new AccessDeniedException(MessageUtil.resolveOrRaw("proxyRelease.hospitalRequired"));
        }
        return hospitalId;
    }

    /**
     * The chart gate, then "has care of the patient": a live treatment
     * relationship between the patient and the hospital the clinician acts at
     * (the role itself is the endpoint's gate).
     */
    private Patient requirePatientInCare(UUID patientId, UUID hospitalId, UUID actorUserId) {
        Patient patient = patientChartAccess.require(patientId, hospitalId);
        if (treatmentRelationshipResolver.resolve(patientId, hospitalId, actorUserId).isEmpty()) {
            throw new AccessDeniedException(MessageUtil.resolveOrRaw("proxyRelease.noCareRelationship"));
        }
        return patient;
    }

    private static void requireMinor(Patient patient) {
        if (!ProxySensitivityPolicy.isMinor(ageOf(patient))) {
            throw new BusinessException("proxyRelease.notMinor");
        }
    }

    private static Integer ageOf(Patient patient) {
        return ProxySensitivityPolicy.ageOn(patient.getDateOfBirth(), LocalDate.now());
    }

    /** This patient's active, unexpired grant, held by a declared parent or legal guardian. */
    private PatientProxy requireGuardianGrant(UUID patientId, UUID grantId) {
        PatientProxy grant = patientProxyRepository.findById(grantId)
            .filter(found -> found.getGrantorPatient() != null
                && patientId.equals(found.getGrantorPatient().getId()))
            .filter(MinorResultReleaseServiceImpl::isLive)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_GRANT_NOT_FOUND, grantId));
        if (!isParentOrGuardian(grant)) {
            throw new BusinessException("proxyRelease.notParentOrGuardian");
        }
        return grant;
    }

    private static boolean isLive(PatientProxy grant) {
        return grant.getStatus() == ProxyStatus.ACTIVE
            && (grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(LocalDateTime.now()));
    }

    private static boolean isParentOrGuardian(PatientProxy grant) {
        return grant.getRelationship() == ProxyRelationship.PARENT
            || grant.getRelationship() == ProxyRelationship.LEGAL_GUARDIAN;
    }

    /** The order, when it is this patient's and the acting hospital's; unknown otherwise. */
    private LabOrder requireOrder(UUID patientId, UUID labOrderId, UUID hospitalId) {
        return labOrderRepository.findById(labOrderId)
            .filter(order -> order.getPatient() != null && patientId.equals(order.getPatient().getId()))
            .filter(order -> order.getHospital() != null && hospitalId.equals(order.getHospital().getId()))
            .orElseThrow(() -> new ResourceNotFoundException(MSG_RESULT_NOT_FOUND, labOrderId));
    }

    private void stampVerified(PatientProxy grant, Patient patient, UUID hospitalId, UUID actorUserId) {
        if (grant.getRelationshipVerifiedAt() != null) {
            return;
        }
        grant.setRelationshipVerifiedAt(LocalDateTime.now());
        grant.setRelationshipVerifiedByUserId(actorUserId);
        patientProxyRepository.save(grant);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("grantId", String.valueOf(grant.getId()));
        details.put("proxyUserId", String.valueOf(grant.getProxyUser().getId()));
        details.put("relationship", String.valueOf(grant.getRelationship()));
        audit(AuditEventType.PROXY_RELATIONSHIP_VERIFIED, "PATIENT_PROXY", grant.getId(), patient.getId(), hospitalId,
            actorUserId, "Family-access relationship verified (" + grant.getRelationship() + ")", details);
    }

    // ── The overview ────────────────────────────────────────────────────────

    private MinorReleaseOverviewDTO overviewOf(Patient patient, UUID hospitalId) {
        Integer configured = hospitalRepository.findClinicalById(hospitalId)
            .map(Hospital::getAdolescentConfidentialityAge).orElse(null);
        int adolescentAge = ProxySensitivityPolicy.adolescentAgeOr(configured);
        if (!ProxySensitivityPolicy.isMinor(ageOf(patient))) {
            return new MinorReleaseOverviewDTO(patient.getId(), false, adolescentAge, configured == null,
                List.of(), List.of());
        }
        Map<UUID, String> holders = new LinkedHashMap<>();
        List<MinorReleaseOverviewDTO.Guardian> guardians = new ArrayList<>();
        for (PatientProxy grant : patientProxyRepository
                .findByGrantorPatient_IdAndStatus(patient.getId(), ProxyStatus.ACTIVE)) {
            if (!isLive(grant) || !isParentOrGuardian(grant)) {
                continue;
            }
            String name = holderName(grant.getProxyUser());
            holders.put(grant.getId(), name);
            guardians.add(new MinorReleaseOverviewDTO.Guardian(grant.getId(), name, grant.getRelationship(),
                grant.getRelationshipVerifiedAt() != null, grant.getRelationshipVerifiedAt()));
        }
        Map<UUID, List<ProxySensitiveReleaseDTO>> releasesByOrder = new LinkedHashMap<>();
        for (ProxySensitiveRelease release : releaseRepository
                .findByPatientIdAndHospitalIdOrderByReleasedAtDesc(patient.getId(), hospitalId)) {
            releasesByOrder.computeIfAbsent(release.getLabOrderId(), id -> new ArrayList<>())
                .add(new ProxySensitiveReleaseDTO(release.getProxyGrantId(),
                    holders.get(release.getProxyGrantId()), release.getReleasedAt(), release.getReason()));
        }
        return new MinorReleaseOverviewDTO(patient.getId(), true, adolescentAge, configured == null, guardians,
            hiddenResultsOf(patient.getId(), hospitalId, releasesByOrder));
    }

    /** The released results of this hospital that a proxy does not read, newest first, one per order. */
    private List<MinorReleaseOverviewDTO.HiddenResult> hiddenResultsOf(
            UUID patientId, UUID hospitalId, Map<UUID, List<ProxySensitiveReleaseDTO>> releasesByOrder) {
        Map<UUID, MinorReleaseOverviewDTO.HiddenResult> byOrder = new LinkedHashMap<>();
        List<LabResult> rows = labResultRepository
            .findByLabOrder_Patient_IdAndLabOrder_Hospital_Id(patientId, hospitalId, Pageable.unpaged());
        for (LabResult result : rows) {
            LabOrder order = result.getLabOrder();
            if (!result.isReleased() || order == null || byOrder.containsKey(order.getId())) {
                continue;
            }
            SensitivityCategory category = proxyRecordFilter.hidingCategoryOf(order);
            if (category == null) {
                continue;
            }
            LabTestDefinition test = order.getLabTestDefinition();
            byOrder.put(order.getId(), new MinorReleaseOverviewDTO.HiddenResult(order.getId(),
                test == null ? null : test.getName(), category, result.getReleasedAt(),
                releasesByOrder.getOrDefault(order.getId(), List.of())));
        }
        return byOrder.values().stream()
            .sorted(Comparator.comparing(MinorReleaseOverviewDTO.HiddenResult::resultedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    private static String holderName(User user) {
        if (user == null) {
            return null;
        }
        String first = user.getFirstName() == null ? "" : user.getFirstName();
        String last = user.getLastName() == null ? "" : user.getLastName();
        String name = (first + " " + last).trim();
        return name.isEmpty() ? user.getUsername() : name;
    }

    /** After commit, ids and category names only - never the reason, which can say what the result is. */
    private void audit(AuditEventType type, String entityType, UUID resourceId, UUID patientId, UUID hospitalId,
                       UUID actorUserId, String description, Map<String, Object> details) {
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(type)
            .status(AuditStatus.SUCCESS)
            .userId(actorUserId)
            .patientId(patientId)
            .hospitalId(hospitalId)
            .entityType(entityType)
            .resourceId(String.valueOf(resourceId))
            .eventDescription(description)
            .details(details)
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[proxy-release] audit not written for {} {}: {}", entityType, resourceId,
                    ex.getClass().getSimpleName());
            }
        });
    }
}
