package com.example.hms.service.apikey;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.platform.ApiKeyStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ApiKeyMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.ApiKey;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.platform.ApiKeyCreateDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.ApiKeyRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contracts worth defending (Tier 2 item 45): only the HASH of a key
 * is ever stored — issuance is the one moment the raw key exists in a
 * response; verification is silent about WHY a key fails; rotation is
 * revoke-plus-reissue under the same label; foreign and nonexistent ids
 * collapse to the identical not-found; concurrent lifecycle changes get
 * a clean retryable refusal.
 */
@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 9, 0);

    @Mock private ApiKeyRepository apiKeyRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditService;

    private ApiKeyService service;

    private UUID hospitalId;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new ApiKeyService(apiKeyRepository, hospitalRepository, roleValidator,
            auditService, new ApiKeyMapper(), clock);
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General");
    }

    private void asAdminAtHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private ApiKey activeKey() {
        ApiKey key = ApiKey.builder()
            .hospital(hospital)
            .label("Mutuelle X claims")
            .keyPrefix("hms_pk_abcd")
            .keyHash("0".repeat(64))
            .status(ApiKeyStatus.ACTIVE)
            .build();
        key.setId(UUID.randomUUID());
        return key;
    }

    // ── issuance ────────────────────────────────────────────────────────

    @Test
    @DisplayName("issue stores ONLY the hash - the raw key appears once, in the response")
    void issueStoresOnlyTheHash() {
        asAdminAtHospital();
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var issued = service.issue(ApiKeyCreateDTO.builder().label("Mutuelle X claims").build());

        assertThat(issued.getRawKey()).startsWith("hms_pk_");
        ArgumentCaptor<ApiKey> saved = ArgumentCaptor.forClass(ApiKey.class);
        verify(apiKeyRepository).save(saved.capture());
        // The stored row can identify the key in hand, never reproduce it.
        assertThat(saved.getValue().getKeyHash())
            .isEqualTo(ApiKeyService.sha256Hex(issued.getRawKey()))
            .isNotEqualTo(issued.getRawKey());
        assertThat(saved.getValue().getKeyPrefix())
            .isEqualTo(issued.getRawKey().substring(0, 12));
        // And the read DTO carries the prefix only.
        assertThat(issued.getKey().getKeyPrefix()).isEqualTo(saved.getValue().getKeyPrefix());

        ArgumentCaptor<AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.API_KEY_CREATED);
    }

    @Test
    @DisplayName("issue refuses an expiry that is not in the future")
    void issueRefusesPastExpiry() {
        asAdminAtHospital();
        ApiKeyCreateDTO dto = ApiKeyCreateDTO.builder()
            .label("x").expiresOn(NOW.toLocalDate()).build();

        assertThatThrownBy(() -> service.issue(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("future");
        verify(apiKeyRepository, never()).save(any());
    }

    // ── verification ────────────────────────────────────────────────────

    @Test
    @DisplayName("authenticate accepts an active key and pins its hospital")
    void authenticateAcceptsActiveKey() {
        ApiKey key = activeKey();
        String raw = "hms_pk_someRawKeyValue";
        when(apiKeyRepository.findByKeyHashAndStatus(
                ApiKeyService.sha256Hex(raw), ApiKeyStatus.ACTIVE))
            .thenReturn(Optional.of(key));

        Optional<ApiKeyService.ApiKeyAuth> auth = service.authenticate(raw);

        assertThat(auth).isPresent();
        assertThat(auth.get().hospitalId()).isEqualTo(hospitalId);
        assertThat(auth.get().label()).isEqualTo("Mutuelle X claims");
        // Liveness stamp on first sight - as a direct UPDATE that cannot
        // optimistic-lock a valid request into a 500.
        verify(apiKeyRepository).stampLastUsed(key.getId(), NOW);
    }

    @Test
    @DisplayName("authenticate is silent about WHY - unknown, blank and expired all return empty")
    void authenticateRefusesSilently() {
        assertThat(service.authenticate(null)).isEmpty();
        assertThat(service.authenticate("  ")).isEmpty();

        when(apiKeyRepository.findByKeyHashAndStatus(any(), any()))
            .thenReturn(Optional.empty());
        assertThat(service.authenticate("hms_pk_unknown")).isEmpty();

        ApiKey expired = activeKey();
        expired.setExpiresOn(NOW.toLocalDate().minusDays(1));
        when(apiKeyRepository.findByKeyHashAndStatus(any(), any()))
            .thenReturn(Optional.of(expired));
        assertThat(service.authenticate("hms_pk_expired")).isEmpty();
    }

    @Test
    @DisplayName("the lastUsedAt stamp is throttled - a fresh stamp is not rewritten")
    void lastUsedStampIsThrottled() {
        ApiKey key = activeKey();
        key.setLastUsedAt(NOW.minusSeconds(10));
        when(apiKeyRepository.findByKeyHashAndStatus(any(), any()))
            .thenReturn(Optional.of(key));

        assertThat(service.authenticate("hms_pk_whatever")).isPresent();

        verify(apiKeyRepository, never()).stampLastUsed(any(), any());
    }

    // ── rotation / revocation ───────────────────────────────────────────

    @Test
    @DisplayName("rotate revokes the old key and issues a fresh one under the same label")
    void rotateRevokesAndReissues() {
        asAdminAtHospital();
        ApiKey old = activeKey();
        when(apiKeyRepository.findById(old.getId())).thenReturn(Optional.of(old));
        when(apiKeyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var issued = service.rotate(old.getId());

        assertThat(old.getStatus()).isEqualTo(ApiKeyStatus.REVOKED);
        assertThat(old.getRevokedAt()).isEqualTo(NOW);
        assertThat(issued.getKey().getLabel()).isEqualTo(old.getLabel());
        assertThat(issued.getRawKey()).startsWith("hms_pk_");
        ArgumentCaptor<AuditEventRequestDTO> audit =
            ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditService, times(1)).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.API_KEY_ROTATED);
    }

    @Test
    @DisplayName("foreign and NONEXISTENT keys collapse to the identical not-found")
    void foreignAndUnknownCollapseAlike() {
        asAdminAtHospital();
        ApiKey foreign = activeKey();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(apiKeyRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        UUID unknownId = UUID.randomUUID();
        when(apiKeyRepository.findById(unknownId)).thenReturn(Optional.empty());
        UUID foreignId = foreign.getId();

        Throwable foreignT = catchThrowable(() -> service.revoke(foreignId));
        Throwable unknownT = catchThrowable(() -> service.revoke(unknownId));

        assertThat(foreignT).isInstanceOf(ResourceNotFoundException.class);
        assertThat(unknownT).isInstanceOf(ResourceNotFoundException.class);
        assertThat(foreignT.getMessage()).isEqualTo(unknownT.getMessage());
    }

    @Test
    @DisplayName("a revoked key refuses a second lifecycle change")
    void revokedKeyRefusesAgain() {
        asAdminAtHospital();
        ApiKey key = activeKey();
        key.setStatus(ApiKeyStatus.REVOKED);
        when(apiKeyRepository.findById(key.getId())).thenReturn(Optional.of(key));
        UUID keyId = key.getId();

        assertThatThrownBy(() -> service.rotate(keyId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already revoked");
    }

    @Test
    @DisplayName("a concurrent lifecycle change surfaces as a retryable refusal")
    void concurrentChangeIsRefused() {
        asAdminAtHospital();
        ApiKey key = activeKey();
        when(apiKeyRepository.findById(key.getId())).thenReturn(Optional.of(key));
        when(apiKeyRepository.saveAndFlush(any())).thenThrow(
            new ObjectOptimisticLockingFailureException(ApiKey.class, key.getId()));
        UUID keyId = key.getId();

        assertThatThrownBy(() -> service.revoke(keyId))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reload and retry");
    }

    // ── misc ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("list is hospital-scoped")
    void listIsScoped() {
        asAdminAtHospital();
        when(apiKeyRepository.findByHospital_IdOrderByCreatedAtDesc(hospitalId))
            .thenReturn(List.of(activeKey()));

        assertThat(service.list()).hasSize(1);
    }

    @Test
    @DisplayName("no active hospital context refuses the admin entry points")
    void noHospitalContextRefuses() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        ApiKeyCreateDTO dto = ApiKeyCreateDTO.builder().label("x").build();

        assertThatThrownBy(() -> service.issue(dto))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital context");
    }

    @Test
    @DisplayName("an audit-sink failure never undoes a credential change")
    void auditFailureIsSwallowed() {
        asAdminAtHospital();
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("audit sink down")).when(auditService).logEvent(any());
        ApiKeyCreateDTO dto = ApiKeyCreateDTO.builder().label("x").build();

        assertThatCode(() -> service.issue(dto)).doesNotThrowAnyException();
    }
}
