package com.example.hms.service.clinicalrules;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.RuleSignoffStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.HospitalRuleSignoff;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRuleStatusDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRulesOverviewDTO;
import com.example.hms.payload.dto.clinicalrules.RuleSignoffRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.HospitalRuleSignoffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.support.ClinicalActorNames;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V356: a hospital's sign-off of the provisional rule sets — pending until signed at the CURRENT version, recorded
 * only by that hospital's admin (or the super-admin), audited with ids only, revocable, and never read across
 * hospitals.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("V356: per-hospital sign-off of the provisional rule sets")
class HospitalRuleSignoffServiceTest {

    @Mock private HospitalRuleSignoffRepository signoffRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private ClinicalActorNames actorNames;
    @Mock private AuditEventLogService auditEventLogService;

    private HospitalRuleSignoffService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final List<HospitalRuleSignoff> stored = new ArrayList<>();
    private final ClinicalRuleSet paracetamol =
        ClinicalRuleRegistry.find(ClinicalRuleSetKey.PARACETAMOL_LIMITS).orElseThrow();
    private static final String PARACETAMOL = ClinicalRuleSetKey.PARACETAMOL_LIMITS.name();

    @BeforeEach
    void setUp() {
        service = new HospitalRuleSignoffService(signoffRepository, hospitalRepository, assignmentRepository,
            roleValidator, actorNames, auditEventLogService, com.example.hms.i18n.TestMessageSources.bundles());
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(actorNames.batch(anyCollection())).thenReturn((id, snapshot) -> "Admin " + id);
        lenient().when(signoffRepository.findByHospitalIdOrderByRecordedAtDesc(hospitalId))
            .thenAnswer(inv -> stored.stream()
                .sorted((a, b) -> b.getRecordedAt().compareTo(a.getRecordedAt())).toList());
        lenient().when(signoffRepository.findFirstByHospitalIdAndRuleKeyAndRuleVersionAndStatusOrderByRecordedAtDesc(
                eq(hospitalId), anyString(), anyString(), eq(RuleSignoffStatus.SIGNED)))
            .thenAnswer(inv -> stored.stream()
                .filter(row -> row.getRuleKey().equals(inv.getArgument(1))
                    && row.getRuleVersion().equals(inv.getArgument(2))
                    && row.getStatus() == RuleSignoffStatus.SIGNED)
                .findFirst());
        lenient().when(signoffRepository.save(any())).thenAnswer(inv -> {
            HospitalRuleSignoff row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
                stored.add(row);
            }
            return row;
        });
        lenient().when(roleValidator.isHospitalAdmin(adminId, hospitalId)).thenReturn(true);
    }

    private RuleSignoffRequestDTO request(String version) {
        return new RuleSignoffRequestDTO(version, "Dr Awa Ouédraogo", "Médecin-chef", null,
            LocalDate.of(2026, 11, 20), "Signé en réunion de direction.");
    }

    private static ClinicalRuleStatusDTO statusOf(ClinicalRulesOverviewDTO overview, ClinicalRuleSetKey key) {
        return overview.rules().stream().filter(rule -> rule.key().equals(key.name())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("with no sign-off, every rule set of the registry is pending, with its source and approver")
    void everyRuleIsPendingAtFirst() {
        ClinicalRulesOverviewDTO overview = service.overview(hospitalId, adminId);

        assertThat(overview.total()).isEqualTo(ClinicalRuleRegistry.all().size());
        assertThat(overview.signedCount()).isZero();
        assertThat(overview.canManage()).isTrue();
        assertThat(overview.rules()).allSatisfy(rule -> {
            assertThat(rule.status()).isEqualTo(RuleSignoffStatus.PENDING);
            assertThat(rule.source()).isNotBlank();
            assertThat(rule.approverRole().fr()).isNotBlank().doesNotStartWith("clinicalRules.");
            assertThat(rule.title().es()).isNotBlank().doesNotStartWith("clinicalRules.");
            assertThat(rule.signoff()).isNull();
        });
    }

    @Test
    @DisplayName("a sign-off makes the rule set signed at its version, names the signer, and is audited with ids only")
    void signRecordsAndAudits() {
        ClinicalRuleStatusDTO status = service.sign(hospitalId, paracetamol.key().name(),
            request(paracetamol.version()), adminId);

        assertThat(status.status()).isEqualTo(RuleSignoffStatus.SIGNED);
        assertThat(status.signoff().getSignerTitle()).isEqualTo("Médecin-chef");
        assertThat(status.signoff().getSignedOn()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(stored).singleElement().satisfies(row -> {
            assertThat(row.getHospitalId()).isEqualTo(hospitalId);
            assertThat(row.getRuleVersion()).isEqualTo(paracetamol.version());
            assertThat(row.getRecordedByUserId()).isEqualTo(adminId);
        });
        assertThat(service.signedCount(hospitalId)).isEqualTo(1);

        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.CLINICAL_RULE_SIGNED_OFF);
        assertThat(audit.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(audit.getValue().getEventDescription())
            .isEqualTo("Clinical rule set PARACETAMOL_LIMITS signed off (version " + paracetamol.version() + ")");
        // Ids only: neither the signer's name nor the note reaches the audit trail.
        assertThat(String.valueOf(audit.getValue().getDetails()) + audit.getValue().getEventDescription())
            .doesNotContain("Ouédraogo").doesNotContain("réunion");
    }

    @Test
    @DisplayName("a sign-off of an older version counts for nothing: a new rule version reopens it as pending")
    void aNewVersionReopensTheRule() {
        stored.add(HospitalRuleSignoff.builder().hospitalId(hospitalId).ruleKey(paracetamol.key().name())
            .ruleVersion("2026-01-01.0").status(RuleSignoffStatus.SIGNED).signerName("Dr A").signerTitle("Médecin-chef")
            .signedOn(LocalDate.of(2026, 1, 2)).recordedByUserId(adminId).recordedAt(LocalDateTime.now().minusDays(9))
            .build());

        ClinicalRuleStatusDTO status = statusOf(service.overview(hospitalId, adminId), paracetamol.key());

        assertThat(status.status()).isEqualTo(RuleSignoffStatus.PENDING);
        assertThat(status.lastSignedVersion()).isEqualTo("2026-01-01.0");
        assertThat(service.signedCount(hospitalId)).isZero();
    }

    @Test
    @DisplayName("a sign-off against a version the rule no longer has is refused, so nobody signs what they did not read")
    void staleVersionIsRefused() {
        RuleSignoffRequestDTO stale = request("2020-01-01.0");
        assertThatThrownBy(() -> service.sign(hospitalId, PARACETAMOL, stale, adminId))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("only the hospital's admin or the super-admin records or revokes; a doctor there is refused")
    void onlyAdminsWrite() {
        UUID doctorId = UUID.randomUUID();
        RuleSignoffRequestDTO current = request(paracetamol.version());
        assertThatThrownBy(() -> service.sign(hospitalId, PARACETAMOL, current, doctorId))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.revoke(hospitalId, PARACETAMOL, "Erreur de saisie", doctorId))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThat(stored).isEmpty();
        verify(auditEventLogService, never()).logEvent(any());

        // The verified super-admin (the Global acting scope) records for the hospital named in the path.
        com.example.hms.security.context.HospitalContextHolder.setContext(com.example.hms.security.context.HospitalContext
            .builder().principalUserId(doctorId).superAdmin(true).build());
        try {
            assertThat(service.sign(hospitalId, paracetamol.key().name(), request(paracetamol.version()), doctorId)
                .status()).isEqualTo(RuleSignoffStatus.SIGNED);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    @Test
    @DisplayName("a second sign-off of the same version is refused; a revoked one reads REVOKED and is never revoked twice")
    void revokeThenSignAgain() {
        RuleSignoffRequestDTO current = request(paracetamol.version());
        service.sign(hospitalId, PARACETAMOL, current, adminId);
        assertThatThrownBy(() -> service.sign(hospitalId, PARACETAMOL, current, adminId))
            .isInstanceOf(BusinessException.class);

        ClinicalRuleStatusDTO revoked = service.revoke(hospitalId, paracetamol.key().name(), "Erreur de saisie",
            adminId);
        assertThat(revoked.status()).isEqualTo(RuleSignoffStatus.REVOKED);
        assertThat(revoked.signoff().getRevokeReason()).isEqualTo("Erreur de saisie");
        assertThat(service.signedCount(hospitalId)).isZero();
        assertThatThrownBy(() -> service.revoke(hospitalId, PARACETAMOL, "Encore", adminId))
            .isInstanceOf(BusinessException.class);
        assertThat(stored.get(0).getRevokeReason()).isEqualTo("Erreur de saisie");

        stored.get(0).setRecordedAt(LocalDateTime.now().minusMinutes(5));
        assertThat(service.sign(hospitalId, paracetamol.key().name(), request(paracetamol.version()), adminId)
            .status()).isEqualTo(RuleSignoffStatus.SIGNED);
        assertThat(stored).hasSize(2);
    }

    @Test
    @DisplayName("nothing to revoke, an unknown rule set, an unknown hospital, or a signer account from elsewhere: refused")
    void refusals() {
        assertThatThrownBy(() -> service.revoke(hospitalId, PARACETAMOL, "Erreur", adminId))
            .isInstanceOf(BusinessException.class);
        RuleSignoffRequestDTO unknown = request("1");
        assertThatThrownBy(() -> service.sign(hospitalId, "NO_SUCH_RULE", unknown, adminId))
            .isInstanceOf(ResourceNotFoundException.class);
        UUID elsewhere = UUID.randomUUID();
        assertThatThrownBy(() -> service.overview(elsewhere, adminId)).isInstanceOf(ResourceNotFoundException.class);

        UUID outsider = UUID.randomUUID();
        when(assignmentRepository.existsByUserIdAndHospitalIdAndActiveTrue(outsider, hospitalId)).thenReturn(false);
        RuleSignoffRequestDTO byOutsider = new RuleSignoffRequestDTO(paracetamol.version(), "Dr X", "Médecin-chef",
            outsider, LocalDate.of(2026, 11, 20), null);
        assertThatThrownBy(() -> service.sign(hospitalId, PARACETAMOL, byOutsider, adminId))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    @DisplayName("every read names the hospital: another hospital's sign-offs are never asked for")
    void readsAreScopedToTheHospital() {
        service.overview(hospitalId, adminId);
        service.signedCount(hospitalId);

        verify(signoffRepository, never()).findAll();
        verify(signoffRepository, org.mockito.Mockito.atLeastOnce()).findByHospitalIdOrderByRecordedAtDesc(hospitalId);
    }
}
