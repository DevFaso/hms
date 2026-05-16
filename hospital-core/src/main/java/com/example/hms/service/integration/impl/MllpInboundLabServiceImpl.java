package com.example.hms.service.integration.impl;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ActorType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class MllpInboundLabServiceImpl implements MllpInboundLabService {

    private final LabSpecimenRepository specimenRepository;
    private final LabResultRepository labResultRepository;
    private final IntegrationMessageRecorder messageRecorder;
    private final AuditEventLogService auditEventLogService;

    @Override
    @Transactional
    public MllpInboundOutcome processOruR01(ParsedObservation observation,
                                            Hospital receivingHospital,
                                            String sendingApplication,
                                            String sendingFacility,
                                            String messageControlId,
                                            String rawMessageBody) {
        UUID hospitalId = receivingHospital != null ? receivingHospital.getId() : null;
        UUID organizationId = (receivingHospital != null && receivingHospital.getOrganization() != null)
            ? receivingHospital.getOrganization().getId() : null;
        String integrationId = buildIntegrationId(sendingApplication, sendingFacility);

        if (observation == null
            || !StringUtils.hasText(observation.placerOrderNumber())
            || !StringUtils.hasText(observation.resultValue())) {
            log.warn("MLLP ORU^R01 rejected — missing OBR-2 or OBX value (sender={}/{} hospital={})",
                sendingApplication, sendingFacility, hospitalId);
            recordInboundMessage(integrationId, organizationId, rawMessageBody,
                IntegrationMessageStatus.FAILED, "missing OBR-2 placer or OBX value");
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        if (receivingHospital == null || hospitalId == null) {
            log.warn("MLLP ORU^R01 rejected — no resolved hospital (sender={}/{})",
                sendingApplication, sendingFacility);
            recordInboundMessage(integrationId, organizationId, rawMessageBody,
                IntegrationMessageStatus.FAILED, "no resolved hospital");
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        // Idempotency: identical MSH-10 means the analyzer retransmitted
        // (lost ACK, TCP timeout, restart). We've already persisted this
        // observation — short-circuit so we don't get duplicate rows on
        // the lab_result trend.
        String controlId = StringUtils.hasText(messageControlId) ? messageControlId.trim() : null;
        if (controlId != null) {
            Optional<LabResult> existing = labResultRepository.findFirstBySourceMessageControlId(controlId);
            if (existing.isPresent()) {
                log.info("MLLP ORU^R01 replay — control id {} already persisted as labResult {}; ACCEPTED without re-insert",
                    controlId, existing.get().getId());
                recordInboundMessage(integrationId, organizationId, rawMessageBody,
                    IntegrationMessageStatus.RECEIVED, "duplicate MSH-10; replayed");
                return MllpInboundOutcome.ACCEPTED;
            }
        }

        String placer = observation.placerOrderNumber().trim();
        Optional<LabSpecimen> specimen = specimenRepository.findByAccessionNumber(placer);
        if (specimen.isEmpty()) {
            log.warn("MLLP ORU^R01 placer={} unknown — sender={}/{} hospital={}",
                placer, sendingApplication, sendingFacility, hospitalId);
            recordInboundMessage(integrationId, organizationId, rawMessageBody,
                IntegrationMessageStatus.FAILED, "accession " + placer + " not found");
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        LabOrder order = specimen.get().getLabOrder();
        if (order == null || order.getHospital() == null || order.getHospital().getId() == null) {
            log.warn("MLLP ORU^R01 placer={} maps to a specimen without a hospital-scoped order",
                placer);
            recordInboundMessage(integrationId, organizationId, rawMessageBody,
                IntegrationMessageStatus.FAILED, "specimen without hospital-scoped order");
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        if (!Objects.equals(order.getHospital().getId(), hospitalId)) {
            // Cross-tenant: the analyzer's allowlisted hospital does not
            // own this order. Hard reject so the analyzer surfaces the
            // misconfiguration rather than silently retrying.
            log.warn("MLLP ORU^R01 cross-tenant: order hospital={} but sender hospital={} (sender={}/{}, placer={})",
                order.getHospital().getId(), hospitalId,
                sendingApplication, sendingFacility, placer);
            recordInboundMessage(integrationId, organizationId, rawMessageBody,
                IntegrationMessageStatus.FAILED, "cross-tenant rejection");
            return MllpInboundOutcome.REJECTED_CROSS_TENANT;
        }

        LabResult result = LabResult.builder()
            .labOrder(order)
            .assignment(null)
            .actorType(ActorType.SYSTEM)
            .actorLabel(buildActorLabel(sendingApplication, sendingFacility))
            .resultValue(observation.resultValue().trim())
            .resultUnit(observation.resultUnit() == null ? null : observation.resultUnit().trim())
            .resultDate(observation.resultDate() != null ? observation.resultDate() : LocalDateTime.now())
            .abnormalFlag(toAbnormalFlag(observation.abnormalFlag()))
            .sourceMessageControlId(controlId)
            .build();
        LabResult saved = labResultRepository.save(result);
        log.info("MLLP ORU^R01 persisted — labResult={} order={} placer={} sender={}/{} hospital={} msgCtrlId={}",
            saved.getId(), order.getId(), placer,
            sendingApplication, sendingFacility, hospitalId, controlId);
        recordInboundMessage(integrationId, organizationId, rawMessageBody,
            IntegrationMessageStatus.RECEIVED, null);
        emitAudit(saved, hospitalId, integrationId, controlId);
        return MllpInboundOutcome.ACCEPTED;
    }

    /**
     * Best-effort audit emission. Failure to write the audit row must
     * NOT roll back the LabResult save — the clinical write is the
     * load-bearing operation; audit is observation.
     */
    private void emitAudit(LabResult saved, UUID hospitalId, String integrationId, String controlId) {
        try {
            AuditEventRequestDTO request = AuditEventRequestDTO.builder()
                .eventType(AuditEventType.LAB_RESULT_UPDATED)
                .status(AuditStatus.SUCCESS)
                .eventDescription("ORU^R01 ingested via " + integrationId
                    + (controlId != null ? " (msgCtrlId=" + controlId + ")" : ""))
                .entityType("LabResult")
                .resourceId(saved.getId() != null ? saved.getId().toString() : null)
                .build();
            auditEventLogService.logEvent(request);
        } catch (RuntimeException ex) {
            log.warn("MLLP ORU^R01 audit emission failed for labResult={} hospital={} integration={}",
                saved.getId(), hospitalId, integrationId, ex);
        }
    }

    /**
     * Best-effort message recorder. {@link IntegrationMessageRecorder}
     * already runs in REQUIRES_NEW and swallows its own exceptions; the
     * extra try-catch here is belt-and-braces in case the bean is
     * unavailable (e.g. in narrow unit-test contexts).
     */
    private void recordInboundMessage(String integrationId, UUID organizationId,
                                      String rawBody, IntegrationMessageStatus status,
                                      String errorMessage) {
        if (messageRecorder == null) {
            return;
        }
        try {
            messageRecorder.recordMessage(
                integrationId,
                organizationId,
                IntegrationMessageDirection.INBOUND,
                "ORU^R01",
                rawBody,
                status,
                errorMessage);
        } catch (RuntimeException ex) {
            log.warn("MLLP ORU^R01 message recorder threw for integration={} status={}",
                integrationId, status, ex);
        }
    }

    private String buildIntegrationId(String app, String fac) {
        String safeApp = StringUtils.hasText(app) ? app.trim() : "?";
        String safeFac = StringUtils.hasText(fac) ? fac.trim() : "?";
        return "MLLP:" + safeApp + "/" + safeFac;
    }

    private String buildActorLabel(String app, String fac) {
        String safeApp = StringUtils.hasText(app) ? app.trim() : "?";
        String safeFac = StringUtils.hasText(fac) ? fac.trim() : "?";
        String label = "MLLP:" + safeApp + "/" + safeFac;
        return label.length() > 255 ? label.substring(0, 255) : label;
    }

    /**
     * Maps the HL7 v2 OBX-8 abnormal-flag code to the internal enum.
     * Anything outside the recognised set degrades to {@link AbnormalFlag#NORMAL}
     * — better to mis-flag a result as normal and surface it for review
     * than to silently drop it.
     */
    private AbnormalFlag toAbnormalFlag(String hl7Flag) {
        if (!StringUtils.hasText(hl7Flag)) return AbnormalFlag.NORMAL;
        return switch (hl7Flag.trim().toUpperCase(Locale.ROOT)) {
            case "N", "" -> AbnormalFlag.NORMAL;
            case "A", "L", "H" -> AbnormalFlag.ABNORMAL;
            case "LL", "HH", "AA", ">", "<" -> AbnormalFlag.CRITICAL;
            default -> AbnormalFlag.NORMAL;
        };
    }
}
