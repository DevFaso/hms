package com.example.hms.service.clinicalrules;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.RuleSignoffStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.HospitalRuleSignoff;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRuleStatusDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRulesOverviewDTO;
import com.example.hms.payload.dto.clinicalrules.HospitalRuleSignoffDTO;
import com.example.hms.payload.dto.clinicalrules.LocalizedRuleText;
import com.example.hms.payload.dto.clinicalrules.RuleSignoffRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.HospitalRuleSignoffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CallerIdentity;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.support.ClinicalActorNames;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import com.example.hms.utility.TransactionCallbacks;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

/**
 * V356 (owner direction 2026-11-21): where ONE hospital stands on each provisional rule set of
 * {@link ClinicalRuleRegistry}, and the hospital admin's record of its approver's sign-off.
 *
 * <p>A rule set is signed at a hospital when a SIGNED row exists for its CURRENT registry version; anything else is
 * pending (« provisoire »), so bumping a version reopens every hospital without touching a row. A sign-off is
 * entered by the hospital's admin (or the super-admin acting for that hospital), usually from a paper signature: the
 * signer is named as written and is rarely a user. Every write is audited with ids only.
 *
 * <p>Nothing here changes how a rule behaves. The caller's right to act at {@code hospitalId} is the controller's
 * (the path's hospital, narrowed by {@code HospitalIdNarrowingInterceptor}); every read and write here is scoped to
 * that one hospital.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HospitalRuleSignoffService {

    static final String ENTITY_TYPE = "HOSPITAL_RULE_SIGNOFF";
    private static final Locale SPANISH = Locale.forLanguageTag("es");
    // recordedAt / revokedAt on the server's clock, as BaseEntity's createdAt (the screen orders by them).
    private static final String MSG_HOSPITAL_NOT_FOUND = "hospital.notFound";
    private static final String MSG_RULE_NOT_FOUND = "clinicalRules.notFound";

    private final HospitalRuleSignoffRepository signoffRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final RoleValidator roleValidator;
    private final ClinicalActorNames actorNames;
    private final AuditEventLogService auditEventLogService;
    private final MessageSource messageSource;

    /** Every rule set and its status at {@code hospitalId}, for the screen, the labels and the checklist. */
    @Transactional(readOnly = true)
    public ClinicalRulesOverviewDTO overview(UUID hospitalId, UUID readerUserId) {
        requireHospital(hospitalId);
        List<HospitalRuleSignoff> rows = signoffRepository.findByHospitalIdOrderByRecordedAtDesc(hospitalId);
        BiFunction<UUID, String, String> names = actorNames.batch(rows.stream()
            .flatMap(row -> Stream.of(row.getRecordedByUserId(), row.getRevokedByUserId()))
            .filter(Objects::nonNull)
            .toList());
        Map<String, List<HospitalRuleSignoff>> byKey = new LinkedHashMap<>();
        for (HospitalRuleSignoff row : rows) {
            byKey.computeIfAbsent(row.getRuleKey(), key -> new ArrayList<>()).add(row);
        }
        List<ClinicalRuleStatusDTO> rules = ClinicalRuleRegistry.all().stream()
            .map(rule -> statusOf(rule, byKey.getOrDefault(rule.key().name(), List.of()), names))
            .toList();
        int signed = (int) rules.stream().filter(rule -> rule.status() == RuleSignoffStatus.SIGNED).count();
        return new ClinicalRulesOverviewDTO(hospitalId, canManage(readerUserId, hospitalId), signed, rules.size(),
            rules);
    }

    /** How many rule sets {@code hospitalId} has signed off at their current version: the onboarding step. */
    @Transactional(readOnly = true)
    public long signedCount(UUID hospitalId) {
        List<HospitalRuleSignoff> rows = signoffRepository.findByHospitalIdOrderByRecordedAtDesc(hospitalId);
        return ClinicalRuleRegistry.all().stream()
            .filter(rule -> rows.stream().anyMatch(row -> isLiveSignoff(row, rule)))
            .count();
    }

    /** « Enregistrer la validation »: the approver signed {@code ruleKey} at its current version. */
    @Transactional
    public ClinicalRuleStatusDTO sign(UUID hospitalId, String ruleKey, RuleSignoffRequestDTO request,
                                      UUID actorUserId) {
        requireHospital(hospitalId);
        if (!canManage(actorUserId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("clinicalRules.sign.adminsOnly"));
        }
        ClinicalRuleSet rule = requireRule(ruleKey);
        if (!rule.version().equals(request.ruleVersion())) {
            throw new FieldBusinessException("ruleVersion", "clinicalRules.versionChanged", rule.version());
        }
        if (request.signerUserId() != null
            && !assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(request.signerUserId(), hospitalId)) {
            throw new FieldBusinessException("signerUserId", "clinicalRules.signerNotAtHospital");
        }
        if (liveSignoff(hospitalId, rule) != null) {
            throw new BusinessException("clinicalRules.alreadySigned");
        }
        HospitalRuleSignoff saved = signoffRepository.save(HospitalRuleSignoff.builder()
            .hospitalId(hospitalId)
            .ruleKey(rule.key().name())
            .ruleVersion(rule.version())
            .status(RuleSignoffStatus.SIGNED)
            .signerName(request.signerName().strip())
            .signerTitle(request.signerTitle().strip())
            .signerUserId(request.signerUserId())
            .signedOn(request.signedOn())
            .note(blankToNull(request.note()))
            .recordedByUserId(actorUserId)
            .recordedAt(LocalDateTime.now(ZoneId.systemDefault()))
            .build());
        audit(AuditEventType.CLINICAL_RULE_SIGNED_OFF, saved, actorUserId,
            "Clinical rule set " + rule.key().name() + " signed off (version " + rule.version() + ")");
        log.info("[clinical-rules] hospital {} signed {} v{} (sign-off {})", hospitalId, rule.key(), rule.version(),
            saved.getId());
        return statusOne(hospitalId, rule);
    }

    /** « Révoquer »: the live sign-off of {@code ruleKey} at its current version is withdrawn. */
    @Transactional
    public ClinicalRuleStatusDTO revoke(UUID hospitalId, String ruleKey, String reason, UUID actorUserId) {
        requireHospital(hospitalId);
        if (!canManage(actorUserId, hospitalId)) {
            throw new ClientSafeAccessDeniedException(MessageUtil.resolve("clinicalRules.revoke.adminsOnly"));
        }
        ClinicalRuleSet rule = requireRule(ruleKey);
        HospitalRuleSignoff live = liveSignoff(hospitalId, rule);
        // Only a live sign-off is revoked: a revoked one is never revoked again (its reason is never overwritten).
        if (live == null || live.getStatus() != RuleSignoffStatus.SIGNED) {
            throw new BusinessException("clinicalRules.nothingToRevoke");
        }
        live.setStatus(RuleSignoffStatus.REVOKED);
        live.setRevokedAt(LocalDateTime.now(ZoneId.systemDefault()));
        live.setRevokedByUserId(actorUserId);
        live.setRevokeReason(reason == null ? null : reason.strip());
        HospitalRuleSignoff saved = signoffRepository.save(live);
        audit(AuditEventType.CLINICAL_RULE_SIGNOFF_REVOKED, saved, actorUserId,
            "Clinical rule set " + rule.key().name() + " sign-off revoked (version " + rule.version() + ")");
        log.info("[clinical-rules] hospital {} revoked {} v{} (sign-off {})", hospitalId, rule.key(), rule.version(),
            saved.getId());
        return statusOne(hospitalId, rule);
    }

    /** The hospital's admin there, or the verified super-admin (acting for this hospital, the controller's check). */
    public boolean canManage(UUID userId, UUID hospitalId) {
        if (userId == null || hospitalId == null) {
            return false;
        }
        // The verified super-admin in global view or with THIS hospital selected (never a token claim alone).
        return CallerIdentity.isSuperAdminFor(hospitalId) || roleValidator.isHospitalAdmin(userId, hospitalId);
    }

    // ── internals ───────────────────────────────────────────────────────

    private ClinicalRuleStatusDTO statusOne(UUID hospitalId, ClinicalRuleSet rule) {
        List<HospitalRuleSignoff> rows = signoffRepository.findByHospitalIdOrderByRecordedAtDesc(hospitalId).stream()
            .filter(row -> rule.key().name().equals(row.getRuleKey()))
            .toList();
        BiFunction<UUID, String, String> names = actorNames.batch(rows.stream()
            .flatMap(row -> Stream.of(row.getRecordedByUserId(), row.getRevokedByUserId()))
            .filter(Objects::nonNull)
            .toList());
        return statusOf(rule, rows, names);
    }

    /** {@code rows}: this hospital's sign-offs of {@code rule}, newest first. */
    private ClinicalRuleStatusDTO statusOf(ClinicalRuleSet rule, List<HospitalRuleSignoff> rows,
                                           BiFunction<UUID, String, String> names) {
        HospitalRuleSignoff current = rows.stream()
            .filter(row -> rule.version().equals(row.getRuleVersion()))
            .findFirst()
            .orElse(null);
        RuleSignoffStatus status = current == null ? RuleSignoffStatus.PENDING : current.getStatus();
        String lastSignedVersion = current != null ? null : rows.stream()
            .filter(row -> row.getStatus() == RuleSignoffStatus.SIGNED)
            .map(HospitalRuleSignoff::getRuleVersion)
            .findFirst()
            .orElse(null);
        return new ClinicalRuleStatusDTO(rule.key().name(), rule.version(), text(rule.titleKey()),
            text(rule.descriptionKey()), rule.source(), text(rule.approverKey()), rule.enforcedInNames(), status,
            current == null ? null : toDto(current, names), lastSignedVersion);
    }

    private static HospitalRuleSignoffDTO toDto(HospitalRuleSignoff row, BiFunction<UUID, String, String> names) {
        return HospitalRuleSignoffDTO.builder()
            .id(row.getId())
            .ruleVersion(row.getRuleVersion())
            .status(row.getStatus())
            .signerName(row.getSignerName())
            .signerTitle(row.getSignerTitle())
            .signerUserId(row.getSignerUserId())
            .signedOn(row.getSignedOn())
            .note(row.getNote())
            .recordedByName(names.apply(row.getRecordedByUserId(), null))
            .recordedAt(row.getRecordedAt())
            .revokedAt(row.getRevokedAt())
            .revokedByName(row.getRevokedByUserId() == null ? null : names.apply(row.getRevokedByUserId(), null))
            .revokeReason(row.getRevokeReason())
            .build();
    }

    /** A rule set's text in the portal's three languages (the bundles' clinicalRules.rule.* keys). */
    private LocalizedRuleText text(String key) {
        return new LocalizedRuleText(message(key, Locale.ENGLISH), message(key, Locale.FRENCH),
            message(key, SPANISH));
    }

    private String message(String key, Locale locale) {
        return messageSource.getMessage(key, null, key, locale);
    }

    private static boolean isLiveSignoff(HospitalRuleSignoff row, ClinicalRuleSet rule) {
        return row.getStatus() == RuleSignoffStatus.SIGNED
            && rule.key().name().equals(row.getRuleKey())
            && rule.version().equals(row.getRuleVersion());
    }

    private HospitalRuleSignoff liveSignoff(UUID hospitalId, ClinicalRuleSet rule) {
        return signoffRepository
            .findFirstByHospitalIdAndRuleKeyAndRuleVersionAndStatusOrderByRecordedAtDesc(
                hospitalId, rule.key().name(), rule.version(), RuleSignoffStatus.SIGNED)
            .orElse(null);
    }

    private void requireHospital(UUID hospitalId) {
        // A pharmacy or laboratory row (or an unknown id) has no clinical rules: 404, as any clinical read.
        if (hospitalId == null || hospitalRepository.findClinicalById(hospitalId).isEmpty()) {
            throw new ResourceNotFoundException(MSG_HOSPITAL_NOT_FOUND, hospitalId);
        }
    }

    private static ClinicalRuleSet requireRule(String ruleKey) {
        return ClinicalRuleRegistry.find(ruleKey)
            .orElseThrow(() -> new ResourceNotFoundException(MSG_RULE_NOT_FOUND, ruleKey));
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }

    /** Ids only: the hospital, the rule set, its version and the sign-off. Never the signer's name, note or reason. */
    private void audit(AuditEventType type, HospitalRuleSignoff row, UUID actorUserId, String description) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hospitalId", String.valueOf(row.getHospitalId()));
        details.put("ruleKey", row.getRuleKey());
        details.put("ruleVersion", row.getRuleVersion());
        details.put("signoffId", String.valueOf(row.getId()));
        AuditEventRequestDTO event = AuditEventRequestDTO.builder()
            .eventType(type)
            .status(AuditStatus.SUCCESS)
            .userId(actorUserId)
            .hospitalId(row.getHospitalId())
            .entityType(ENTITY_TYPE)
            .resourceId(String.valueOf(row.getId()))
            .eventDescription(description)
            .details(details)
            .build();
        TransactionCallbacks.afterCommit(() -> {
            try {
                auditEventLogService.logEvent(event);
            } catch (RuntimeException ex) {
                log.warn("[clinical-rules] {} audit failed for sign-off {}: {}", type, row.getId(),
                    ex.getClass().getSimpleName());
            }
        });
    }
}
