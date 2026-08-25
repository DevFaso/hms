package com.example.hms.enums;

/**
 * The legal manner, distinct from the medical cause.
 *
 * <p>A death can be medically caused by haemorrhage and legally manner
 * HOMICIDE; the two fields answer different questions and a certificate needs
 * both. PENDING_INVESTIGATION is a real state, not a placeholder — it is what a
 * certifier writes when the manner is genuinely not yet established.
 */
public enum MannerOfDeath {
    NATURAL,
    ACCIDENT,
    SUICIDE,
    HOMICIDE,
    UNDETERMINED,
    PENDING_INVESTIGATION
}
