package com.example.hms.service.integration.impl;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.enums.ActorType;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabSpecimenRepository;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedObservation;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
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

    @Override
    @Transactional
    public MllpInboundOutcome processOruR01(ParsedObservation observation,
                                            Hospital receivingHospital,
                                            String sendingApplication,
                                            String sendingFacility) {
        if (observation == null
            || !StringUtils.hasText(observation.placerOrderNumber())
            || !StringUtils.hasText(observation.resultValue())) {
            log.warn("MLLP ORU^R01 rejected — missing OBR-2 or OBX value (sender={}/{} hospital={})",
                sendingApplication, sendingFacility,
                receivingHospital != null ? receivingHospital.getId() : null);
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        if (receivingHospital == null || receivingHospital.getId() == null) {
            log.warn("MLLP ORU^R01 rejected — no resolved hospital (sender={}/{})",
                sendingApplication, sendingFacility);
            return MllpInboundOutcome.REJECTED_INVALID;
        }

        String placer = observation.placerOrderNumber().trim();
        Optional<LabSpecimen> specimen = specimenRepository.findByAccessionNumber(placer);
        if (specimen.isEmpty()) {
            log.warn("MLLP ORU^R01 placer={} unknown — sender={}/{} hospital={}",
                placer, sendingApplication, sendingFacility, receivingHospital.getId());
            return MllpInboundOutcome.REJECTED_NOT_FOUND;
        }

        LabOrder order = specimen.get().getLabOrder();
        if (order == null || order.getHospital() == null || order.getHospital().getId() == null) {
            log.warn("MLLP ORU^R01 placer={} maps to a specimen without a hospital-scoped order",
                placer);
            return MllpInboundOutcome.REJECTED_INVALID;
        }
        if (!Objects.equals(order.getHospital().getId(), receivingHospital.getId())) {
            // Cross-tenant: the analyzer's allowlisted hospital does not
            // own this order. Hard reject so the analyzer surfaces the
            // misconfiguration rather than silently retrying.
            log.warn("MLLP ORU^R01 cross-tenant: order hospital={} but sender hospital={} (sender={}/{}, placer={})",
                order.getHospital().getId(), receivingHospital.getId(),
                sendingApplication, sendingFacility, placer);
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
            .build();
        LabResult saved = labResultRepository.save(result);
        log.info("MLLP ORU^R01 persisted — labResult={} order={} placer={} sender={}/{} hospital={}",
            saved.getId(), order.getId(), placer,
            sendingApplication, sendingFacility, receivingHospital.getId());
        return MllpInboundOutcome.ACCEPTED;
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
