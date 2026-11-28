package com.example.hms.enums;

/**
 * Billing-money plan PR 2 (plan section 3.3): the keys a {@link PriceListLinkType#SYSTEM} link may name.
 *
 * <p>QA D4221 (round 81): a consultation is no longer one key. A hospital links each consultation tariff it has to
 * the kind of visit it prices (emergency by day or by night, follow-up, antenatal, postnatal, a specialty), and the
 * visit takes the most specific one its price list holds; {@link #CONSULTATION_DEFAULT} stays the fallback of all.
 * Which visit takes which key is {@code service.billing.ConsultationTariffContext}.
 */
public enum PriceListSystemKey {
    CONSULTATION_DEFAULT,
    BED_DAY,
    DELIVERY_VAGINAL,
    DELIVERY_CAESAREAN,
    CONSULTATION_EMERGENCY,
    CONSULTATION_EMERGENCY_NIGHT,
    CONSULTATION_FOLLOW_UP,
    CONSULTATION_PRENATAL,
    CONSULTATION_POSTNATAL,
    CONSULTATION_PAEDIATRICS,
    CONSULTATION_GYNAECOLOGY,
    CONSULTATION_SURGERY,
    CONSULTATION_INTERNAL_MEDICINE,
    /** A stay in observation of under 24 hours, billed once, not per day. */
    BED_DAY_OBSERVATION
}
