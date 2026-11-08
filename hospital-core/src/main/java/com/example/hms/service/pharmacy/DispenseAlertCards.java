package com.example.hms.service.pharmacy;

import com.example.hms.cdshooks.dto.CdsHookDtos;
import com.example.hms.payload.dto.pharmacy.CdsAlertResult;
import com.example.hms.utility.MessageUtil;

import java.util.List;
import java.util.UUID;

/**
 * QA D1102 class (round 19): the dispense-time interaction alerts as critical
 * cards, so the dispensing screen shows them beside its override field (it had
 * no field, and a serious interaction stopped the fill for good).
 */
final class DispenseAlertCards {

    private DispenseAlertCards() {
    }

    /** The interaction check's name in the pharmacist's language. */
    static String sourceLabel() {
        return MessageUtil.resolveOrRaw("cds.source.drugInteraction");
    }

    static List<CdsHookDtos.CdsCard> of(CdsAlertResult result) {
        CdsHookDtos.Source source = new CdsHookDtos.Source(sourceLabel(), null, null);
        return result.alerts().stream()
                .map(alert -> new CdsHookDtos.CdsCard(alert, null, CdsHookDtos.CdsCard.Indicator.CRITICAL, source,
                        null, null, null, UUID.randomUUID().toString()))
                .toList();
    }
}
