package com.example.hms.controller.pharmacy;

import com.example.hms.enums.RoutingDecisionStatus;
import com.example.hms.model.pharmacy.PrescriptionRoutingDecision;
import com.example.hms.service.pharmacy.partner.PartnerExchangeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PartnerSmsWebhookControllerTest {

    private static final String SECRET = "s3cret";

    @Mock private PartnerExchangeService exchangeService;

    private PartnerSmsWebhookController controller;

    @BeforeEach
    void setUp() {
        controller = new PartnerSmsWebhookController(exchangeService);
        ReflectionTestUtils.setField(controller, "configuredSecret", SECRET);
    }

    @Test
    @DisplayName("G8: the sender number travels with the body to the exchange service")
    void forwardsSenderAndBody() {
        PrescriptionRoutingDecision decision = PrescriptionRoutingDecision.builder()
                .status(RoutingDecisionStatus.ACCEPTED)
                .build();
        decision.setId(UUID.randomUUID());
        when(exchangeService.handleInboundReply("+22670000000", "1 ABCD1234"))
                .thenReturn(Optional.of(decision));

        ResponseEntity<Map<String, Object>> response = controller.inbound(SECRET,
                new PartnerSmsWebhookController.InboundSms("+22670000000", "1 ABCD1234"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody())
                .containsEntry("status", "applied")
                .containsEntry("routingDecisionId", decision.getId())
                .containsEntry("decisionStatus", "ACCEPTED");
    }

    @Test
    @DisplayName("an unmatched reply is acknowledged as ignored")
    void ignoredReply() {
        when(exchangeService.handleInboundReply(any(), anyString())).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.inbound(SECRET,
                new PartnerSmsWebhookController.InboundSms(null, "1 ZZZZ"));

        assertThat(response.getBody()).containsEntry("status", "ignored");
    }

    @Test
    @DisplayName("a wrong or missing signature is refused before the service is touched")
    void badSignature() {
        ResponseEntity<Map<String, Object>> wrong = controller.inbound("nope",
                new PartnerSmsWebhookController.InboundSms("+22670000000", "1 ABCD1234"));
        ResponseEntity<Map<String, Object>> missing = controller.inbound(null,
                new PartnerSmsWebhookController.InboundSms("+22670000000", "1 ABCD1234"));

        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        assertThat(missing.getStatusCode().value()).isEqualTo(401);
        verify(exchangeService, never()).handleInboundReply(any(), any());
    }

    @Test
    @DisplayName("no configured secret fails closed")
    void noSecretFailsClosed() {
        ReflectionTestUtils.setField(controller, "configuredSecret", "");

        ResponseEntity<Map<String, Object>> response = controller.inbound("",
                new PartnerSmsWebhookController.InboundSms("+22670000000", "1 ABCD1234"));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        verify(exchangeService, never()).handleInboundReply(any(), any());
    }

    @Test
    @DisplayName("a null payload is passed as nulls, not a crash")
    void nullPayload() {
        when(exchangeService.handleInboundReply(null, null)).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> response = controller.inbound(SECRET, null);

        assertThat(response.getBody()).containsEntry("status", "ignored");
    }
}
