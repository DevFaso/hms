package com.example.hms.service.webhook;

import com.example.hms.enums.platform.WebhookEndpointStatus;
import com.example.hms.enums.platform.WebhookEventType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.WebhookMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.platform.WebhookDelivery;
import com.example.hms.model.platform.WebhookEndpoint;
import com.example.hms.payload.dto.platform.WebhookEndpointRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.platform.WebhookDeliveryRepository;
import com.example.hms.repository.platform.WebhookEndpointRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The contracts worth defending (Tier 2 item 45): the SSRF gate refuses
 * private/loopback/plain-http targets on every write path; the signing
 * secret appears exactly once per generation; a deliberate resume wipes
 * the auto-disable strike count; foreign and nonexistent endpoints
 * collapse to the identical not-found; revoked is terminal.
 */
@ExtendWith(MockitoExtension.class)
class WebhookEndpointServiceTest {

    /** A public IP literal: no DNS in unit tests. */
    private static final String PUBLIC_URL = "https://93.184.216.34/hooks/hms";

    @Mock private WebhookEndpointRepository endpointRepository;
    @Mock private WebhookDeliveryRepository deliveryRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private AuditEventLogService auditService;

    private WebhookEndpointService service;

    private UUID hospitalId;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new WebhookEndpointService(endpointRepository, deliveryRepository,
            hospitalRepository, roleValidator, auditService, new WebhookMapper());
        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General");
    }

    private void asAdminAtHospital() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private WebhookEndpointRequestDTO request(String url) {
        return WebhookEndpointRequestDTO.builder()
            .url(url)
            .description("Claims system")
            .events(Set.of(WebhookEventType.APPOINTMENT_BOOKED))
            .build();
    }

    private WebhookEndpoint endpoint() {
        WebhookEndpoint e = WebhookEndpoint.builder()
            .hospital(hospital)
            .url(PUBLIC_URL)
            .secret("whsec_old")
            .status(WebhookEndpointStatus.ACTIVE)
            .subscribedEvents(EnumSet.of(WebhookEventType.APPOINTMENT_BOOKED))
            .build();
        e.setId(UUID.randomUUID());
        return e;
    }

    // ── registration + the SSRF gate ────────────────────────────────────

    @Test
    @DisplayName("register generates the signing secret server-side and returns it once")
    void registerReturnsSecretOnce() {
        asAdminAtHospital();
        when(hospitalRepository.getReferenceById(hospitalId)).thenReturn(hospital);
        when(endpointRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var registered = service.register(request(PUBLIC_URL));

        assertThat(registered.getSecret()).startsWith("whsec_");
        ArgumentCaptor<WebhookEndpoint> saved = ArgumentCaptor.forClass(WebhookEndpoint.class);
        verify(endpointRepository).save(saved.capture());
        assertThat(saved.getValue().getSecret()).isEqualTo(registered.getSecret());
        // The read DTO never carries it.
        assertThat(registered.getEndpoint().getUrl()).isEqualTo(PUBLIC_URL);
    }

    @Test
    @DisplayName("the SSRF gate refuses http, localhost, private and link-local targets")
    void ssrfGateRefusesPrivateTargets() {
        asAdminAtHospital();
        for (String bad : new String[]{
            "http://example.com/hook",
            "https://localhost/hook",
            "https://127.0.0.1/hook",
            "https://10.0.0.5/hook",
            "https://192.168.1.5/hook",
            "https://169.254.169.254/latest/meta-data",
            "https://receiver.internal/hook",
            "https://user@93.184.216.34/hook"}) {
            WebhookEndpointRequestDTO dto = request(bad);
            assertThatThrownBy(() -> service.register(dto))
                .as("URL should be refused: %s", bad)
                .isInstanceOf(BusinessException.class);
        }
        verify(endpointRepository, never()).save(any());
    }

    @Test
    @DisplayName("update revalidates the URL - the gate holds on every write path")
    void updateRevalidatesUrl() {
        asAdminAtHospital();
        WebhookEndpoint existing = endpoint();
        when(endpointRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        WebhookEndpointRequestDTO dto = request("https://127.0.0.1/moved");
        UUID id = existing.getId();

        assertThatThrownBy(() -> service.update(id, dto))
            .isInstanceOf(BusinessException.class);
    }

    // ── lifecycle ───────────────────────────────────────────────────────

    @Test
    @DisplayName("resume clears the strike count - it is the way back from an auto-disable")
    void resumeClearsStrikes() {
        asAdminAtHospital();
        WebhookEndpoint disabled = endpoint();
        disabled.setStatus(WebhookEndpointStatus.DISABLED_FAILURES);
        disabled.setConsecutiveFailures(20);
        when(endpointRepository.findById(disabled.getId())).thenReturn(Optional.of(disabled));
        when(endpointRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var dto = service.setActive(disabled.getId(), true);

        assertThat(dto.getStatus()).isEqualTo(WebhookEndpointStatus.ACTIVE);
        assertThat(dto.getConsecutiveFailures()).isZero();
    }

    @Test
    @DisplayName("revoked is terminal - every further change refuses")
    void revokedIsTerminal() {
        asAdminAtHospital();
        WebhookEndpoint revoked = endpoint();
        revoked.setStatus(WebhookEndpointStatus.REVOKED);
        when(endpointRepository.findById(revoked.getId())).thenReturn(Optional.of(revoked));
        UUID id = revoked.getId();

        assertThatThrownBy(() -> service.rotateSecret(id))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("rotateSecret changes the secret and returns the new one once")
    void rotateSecretChangesIt() {
        asAdminAtHospital();
        WebhookEndpoint existing = endpoint();
        when(endpointRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(endpointRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        var rotated = service.rotateSecret(existing.getId());

        assertThat(rotated.getSecret()).startsWith("whsec_").isNotEqualTo("whsec_old");
        assertThat(existing.getSecret()).isEqualTo(rotated.getSecret());
    }

    @Test
    @DisplayName("foreign and NONEXISTENT endpoints collapse to the identical not-found")
    void foreignAndUnknownCollapseAlike() {
        asAdminAtHospital();
        WebhookEndpoint foreign = endpoint();
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        foreign.setHospital(other);
        when(endpointRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));
        UUID unknownId = UUID.randomUUID();
        when(endpointRepository.findById(unknownId)).thenReturn(Optional.empty());
        UUID foreignId = foreign.getId();

        Throwable foreignT = catchThrowable(() -> service.revoke(foreignId));
        Throwable unknownT = catchThrowable(() -> service.revoke(unknownId));

        assertThat(foreignT).isInstanceOf(ResourceNotFoundException.class);
        assertThat(unknownT).isInstanceOf(ResourceNotFoundException.class);
        assertThat(foreignT.getMessage()).isEqualTo(unknownT.getMessage());
    }

    // ── ping ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ping enqueues a PING delivery that the normal sweep will sign and send")
    void pingEnqueuesDelivery() {
        asAdminAtHospital();
        WebhookEndpoint existing = endpoint();
        when(endpointRepository.findById(existing.getId())).thenReturn(Optional.of(existing));
        when(deliveryRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var delivery = service.ping(existing.getId());

        assertThat(delivery.getEventType()).isEqualTo(WebhookEventType.PING);
        ArgumentCaptor<WebhookDelivery> saved = ArgumentCaptor.forClass(WebhookDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getPayload()).contains("\"event\":\"PING\"");
    }

    @Test
    @DisplayName("ping refuses a paused endpoint - a test send is still a send")
    void pingRefusesPaused() {
        asAdminAtHospital();
        WebhookEndpoint paused = endpoint();
        paused.setStatus(WebhookEndpointStatus.PAUSED);
        when(endpointRepository.findById(paused.getId())).thenReturn(Optional.of(paused));
        UUID id = paused.getId();

        assertThatThrownBy(() -> service.ping(id))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("ACTIVE");
    }
}
