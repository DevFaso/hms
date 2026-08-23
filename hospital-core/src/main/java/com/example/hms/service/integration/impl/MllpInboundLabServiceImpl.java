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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    // Last so existing positional constructor calls in tests only append.
    private final com.example.hms.service.CriticalValueNotificationService criticalValueNotificationService;

    @Override
    @Transactional
    public MllpInboundOutcome processOruR01(List<ParsedObservation> observations,
                                            Hospital receivingHospital,
                                            String sendingApplication,
                                            String sendingFacility,
                                            String messageControlId,
                                            String rawMessageBody) {
        UUID hospitalId = receivingHospital != null ? receivingHospital.getId() : null;
        UUID organizationId = (receivingHospital != null && receivingHospital.getOrganization() != null)
            ? receivingHospital.getOrganization().getId() : null;
        String integrationId = buildIntegrationId(sendingApplication, sendingFacility);

        if (observations == null || observations.isEmpty()) {
            log.warn("MLLP ORU^R01 rejected — no OBX segments (sender={}/{} hospital={})",
                sendingApplication, sendingFacility, hospitalId);
            recordInboundMessage(integrationId, organizationId, rawMessageBody,
                IntegrationMessageStatus.FAILED, "no OBX segments");
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        // All-or-nothing: one malformed OBX rejects the whole message so
        // the analyzer resends a corrected one — a partial persist would
        // poison the replay check (any surviving row for the triple
        // reads as "message already landed").
        for (ParsedObservation observation : observations) {
            if (observation == null
                || !StringUtils.hasText(observation.placerOrderNumber())
                || !StringUtils.hasText(observation.resultValue())) {
                log.warn("MLLP ORU^R01 rejected — an OBX is missing OBR-2 or its value (sender={}/{} hospital={})",
                    sendingApplication, sendingFacility, hospitalId);
                recordInboundMessage(integrationId, organizationId, rawMessageBody,
                    IntegrationMessageStatus.FAILED, "missing OBR-2 placer or OBX value");
                return MllpInboundOutcome.REJECTED_INVALID;
            }
        }
        if (receivingHospital == null || hospitalId == null) {
            log.warn("MLLP ORU^R01 rejected — no resolved hospital (sender={}/{})",
                sendingApplication, sendingFacility);
            recordInboundMessage(integrationId, organizationId, rawMessageBody,
                IntegrationMessageStatus.FAILED, "no resolved hospital");
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        // Idempotency: identical (sendingApp, sendingFacility, MSH-10)
        // means the analyzer retransmitted (lost ACK, TCP timeout,
        // restart). Scope by sender — HL7 v2 only guarantees MSH-10
        // uniqueness within a sending system, so two different
        // analyzers can legitimately emit the same control id and we
        // must NOT collapse those. Because the message persists
        // atomically, ANY row for the triple implies the whole message
        // landed — replaying is safe. The composite partial unique
        // index (V98, widened by V131 with the OBX set id) enforces
        // the same invariant at the DB layer.
        String controlId = StringUtils.hasText(messageControlId) ? messageControlId.trim() : null;
        String senderApp = StringUtils.hasText(sendingApplication) ? sendingApplication.trim() : null;
        String senderFac = StringUtils.hasText(sendingFacility) ? sendingFacility.trim() : null;
        if (controlId != null && senderApp != null && senderFac != null) {
            Optional<LabResult> existing = labResultRepository
                .findFirstBySourceSendingApplicationAndSourceSendingFacilityAndSourceMessageControlId(
                    senderApp, senderFac, controlId);
            if (existing.isPresent()) {
                log.info("MLLP ORU^R01 replay — sender={}/{} controlId={} already persisted (labResult {}); ACCEPTED without re-insert",
                    senderApp, senderFac, controlId, existing.get().getId());
                recordInboundMessage(integrationId, organizationId, rawMessageBody,
                    IntegrationMessageStatus.RECEIVED, "duplicate (sender, MSH-10); replayed");
                return MllpInboundOutcome.ACCEPTED;
            }
        }

        // Resolve each distinct placer once; every one must resolve to a
        // hospital-scoped order in the receiving hospital or the whole
        // message is rejected.
        Map<String, LabOrder> ordersByPlacer = new LinkedHashMap<>();
        for (ParsedObservation observation : observations) {
            String placer = observation.placerOrderNumber().trim();
            if (ordersByPlacer.containsKey(placer)) {
                continue;
            }
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
            ordersByPlacer.put(placer, order);
        }

        List<String> setIds = normalizedSetIds(observations);
        List<LabResult> saved = new ArrayList<>(observations.size());
        for (int i = 0; i < observations.size(); i++) {
            ParsedObservation observation = observations.get(i);
            LabOrder order = ordersByPlacer.get(observation.placerOrderNumber().trim());
            LabResult result = LabResult.builder()
                .labOrder(order)
                .assignment(null)
                .actorType(ActorType.SYSTEM)
                .actorLabel(buildActorLabel(sendingApplication, sendingFacility))
                .resultValue(observation.resultValue().trim())
                .resultUnit(trimToNull(observation.resultUnit(), 50))
                .resultDate(observation.resultDate() != null ? observation.resultDate() : LocalDateTime.now())
                .abnormalFlag(toAbnormalFlag(observation.abnormalFlag()))
                .sourceSendingApplication(senderApp)
                .sourceSendingFacility(senderFac)
                .sourceMessageControlId(controlId)
                .sourceObservationSetId(setIds.get(i))
                .testCode(trimToNull(observation.testCode(), 255))
                .referenceRange(trimToNull(observation.referenceRange(), 255))
                .build();
            saved.add(labResultRepository.save(result));
        }
        log.info("MLLP ORU^R01 persisted {} observation(s) — orders={} sender={}/{} hospital={} msgCtrlId={}",
            saved.size(), ordersByPlacer.keySet(),
            sendingApplication, sendingFacility, hospitalId, controlId);
        recordInboundMessage(integrationId, organizationId, rawMessageBody,
            IntegrationMessageStatus.RECEIVED, null);
        for (LabResult savedResult : saved) {
            emitAudit(savedResult, hospitalId, integrationId, controlId);
            // P0 #5 — analyzer-reported criticals (HL7 abnormal flag) notify
            // the ordering provider; never rolls back the ingest. Per row:
            // a critical hemoglobin on OBX-2 of a CBC must fire even though
            // OBX-1 was normal.
            criticalValueNotificationService.notifyIfCritical(savedResult);
        }
        return MllpInboundOutcome.ACCEPTED;
    }

    /**
     * Per-message-unique, retransmit-stable discriminators for the V131
     * unique index. Uses OBX-1 as transmitted when every segment carries
     * a distinct, non-blank value (after truncation to the 16-char
     * column); otherwise falls back to the 1-based segment position for
     * ALL rows — deterministic across retransmissions, which is what
     * keeps the replay short-circuit and the DB index in agreement.
     */
    private static List<String> normalizedSetIds(List<ParsedObservation> observations) {
        List<String> raw = new ArrayList<>(observations.size());
        Set<String> seen = new HashSet<>();
        boolean usable = true;
        for (ParsedObservation observation : observations) {
            String setId = observation.setId() == null ? "" : observation.setId().trim();
            if (setId.length() > 16) {
                setId = setId.substring(0, 16);
            }
            if (setId.isEmpty() || !seen.add(setId)) {
                usable = false;
                break;
            }
            raw.add(setId);
        }
        if (usable) {
            return raw;
        }
        List<String> positional = new ArrayList<>(observations.size());
        for (int i = 1; i <= observations.size(); i++) {
            positional.add(String.valueOf(i));
        }
        return positional;
    }

    private static String trimToNull(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
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
