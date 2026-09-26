package com.example.hms.controller;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import com.example.hms.service.LabResultService;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The tenant boundary on the HTTP HL7 ingest door.
 *
 * <p>The roles on the handler say the caller may ingest results somewhere.
 * They say nothing about whose order this is, and the order id arrives in a
 * request header the caller chooses — so without the allowlist lookup asserted
 * here, an authorised lab user of one hospital could attach a result to
 * another hospital's order.
 *
 * <p>Asserted at the controller rather than through a {@code @WebMvcTest}
 * slice: the slices are expensive and fragile in this codebase (one
 * WebMvcConfigurer once broke all 105), and what matters here is which
 * hospital reaches the service, which a direct call states plainly.
 */
@ExtendWith(MockitoExtension.class)
class Hl7InboundControllerTenancyTest {

    private static final String ORU =
        "MSH|^~\\&|ANALYZER|LAB-A|HMS|HOSP|20260101120000||ORU^R01|MSG-1|P|2.5\r"
      + "PID|1||11111111-1111-1111-1111-111111111111\r"
      + "OBR|1||ACC-1|K^Potassium\r"
      + "OBX|1|NM|K^Potassium||4.1|mmol/L|3.5-5.1|N|||F\r";

    @Mock private Hl7v2MessageBuilder hl7v2MessageBuilder;
    @Mock private LabResultService labResultService;
    @Mock private MllpAllowedSenderService mllpAllowedSenderService;

    @InjectMocks private Hl7InboundController controller;

    private UUID labOrderId;
    private UUID assignmentId;

    @BeforeEach
    void setUp() {
        labOrderId = UUID.randomUUID();
        assignmentId = UUID.randomUUID();
    }

    private void stubParse() {
        when(hl7v2MessageBuilder.parseOruR01(any())).thenReturn(List.of(new ParsedObservation(
            "11111111-1111-1111-1111-111111111111", "PLACER-1", "ACC-1", "1",
            "K", "4.1", "mmol/L", "3.5-5.1", "N", LocalDateTime.now(), "F")));
    }

    @Test
    @DisplayName("the hospital the sending pair is allowlisted for is what reaches the service")
    void theAllowlistedHospitalIsPassedToTheService() {
        UUID receivingHospitalId = UUID.randomUUID();
        stubParse();
        when(mllpAllowedSenderService.resolveHospitalId("ANALYZER", "LAB-A"))
            .thenReturn(Optional.of(receivingHospitalId));
        when(labResultService.createIngestedLabResult(any(), eq(receivingHospitalId), any()))
            .thenReturn(LabResultResponseDTO.builder().build());

        controller.inbound(ORU, labOrderId, assignmentId, Locale.ENGLISH);

        ArgumentCaptor<LabResultRequestDTO> sent = ArgumentCaptor.forClass(LabResultRequestDTO.class);
        verify(labResultService).createIngestedLabResult(
            sent.capture(), eq(receivingHospitalId), any());
        // the header triple the replay guard keys on still travels with it
        assertThat(sent.getValue().getSourceSendingApplication()).isEqualTo("ANALYZER");
        assertThat(sent.getValue().getSourceSendingFacility()).isEqualTo("LAB-A");
        assertThat(sent.getValue().getSourceMessageControlId()).isEqualTo("MSG-1");
        assertThat(sent.getValue().getLabOrderId()).isEqualTo(labOrderId);
    }

    @Test
    @DisplayName("a sender that is not on the active allowlist never reaches the service")
    void anUnlistedSenderIsRefusedBeforeTheService() {
        stubParse();
        when(mllpAllowedSenderService.resolveHospitalId("ANALYZER", "LAB-A"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.inbound(ORU, labOrderId, assignmentId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(labResultService, never()).createIngestedLabResult(any(), any(), any());
    }

    @Test
    @DisplayName("an unlisted sender is refused as a missing lab order, not as a forbidden sender")
    void theRefusalDoesNotTellAnUnlistedSenderWhichItWas() {
        // Told apart, a caller could walk order ids with a pair it knows is
        // unlisted and learn which ids exist — the accession oracle this
        // codebase already closed once on the ORU path.
        stubParse();
        when(mllpAllowedSenderService.resolveHospitalId(any(), any())).thenReturn(Optional.empty());

        // On the message KEY, not the rendered message. MessageUtil.resolve
        // returns the key itself until a MessageSource has been installed, so
        // an assertion on the text passes alone and fails in the full suite
        // once some other test has installed one - green in isolation proving
        // nothing. The key is what makes this refusal the same object as the
        // service's own "that order is not yours" refusal.
        Throwable refusal = org.assertj.core.api.Assertions.catchThrowable(
            () -> controller.inbound(ORU, labOrderId, assignmentId, Locale.ENGLISH));

        assertThat(refusal).isInstanceOf(ResourceNotFoundException.class);
        assertThat(((ResourceNotFoundException) refusal).getMessageKey())
            .isEqualTo("laborder.notfound");
    }

    @Test
    @DisplayName("a body with no readable MSH is refused: a sender that does not identify itself is unknown")
    void aBodyWithNoReadableMshIsRefused() {
        // parseOruR01 is tolerant enough to return observations from a body
        // Hl7MessageInspector cannot read a header from. Before this change
        // such a message ingested with null source fields; there is no sender
        // to allowlist, so there is nothing to accept.
        when(hl7v2MessageBuilder.parseOruR01(any())).thenReturn(List.of(new ParsedObservation(
            null, null, null, "1", "K", "4.1", "mmol/L", null, "N", LocalDateTime.now(), "F")));

        assertThatThrownBy(() ->
            controller.inbound("OBX|1|NM|K||4.1|mmol/L|||N|||F\r", labOrderId, assignmentId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(labResultService, never()).createIngestedLabResult(any(), any(), any());
        verify(mllpAllowedSenderService, never()).resolveHospitalId(any(), any());
    }

    @Test
    @DisplayName("an unparseable body still gets its documented 400, not the tenancy answer")
    void anUnparseableBodyStillFailsAsABadRequest() {
        // Order of the guards: the parse guard runs first, so the contract's
        // 400 is not quietly replaced by a 404 for every malformed message.
        when(hl7v2MessageBuilder.parseOruR01(any())).thenReturn(List.of());

        assertThatThrownBy(() -> controller.inbound(ORU, labOrderId, assignmentId, Locale.ENGLISH))
            .isInstanceOf(com.example.hms.exception.BusinessException.class);

        verify(mllpAllowedSenderService, never()).resolveHospitalId(any(), any());
        verify(labResultService, never()).createIngestedLabResult(any(), any(), any());
    }

    @Test
    @DisplayName("an MSH-10 wider than its column is refused like an unreadable MSH, never truncated and stored")
    void anOverWidthMsh10IsRefusedNotTruncated() {
        // Before the parse-time bound this endpoint cut MSH-10 to 255 and
        // stored it, so two control ids sharing 255 characters became one
        // replay key. Now the MSH is refused, and the body is answered as one
        // that identifies no sender.
        stubParse();
        String overWidth = ORU.replace("|MSG-1|", "|" + "C".repeat(256) + "|");

        assertThatThrownBy(() -> controller.inbound(overWidth, labOrderId, assignmentId, Locale.ENGLISH))
            .isInstanceOf(ResourceNotFoundException.class);

        verify(mllpAllowedSenderService, never()).resolveHospitalId(any(), any());
        verify(labResultService, never()).createIngestedLabResult(any(), any(), any());
    }
}
