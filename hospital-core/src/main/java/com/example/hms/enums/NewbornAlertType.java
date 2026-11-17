package com.example.hms.enums;

public enum NewbornAlertType {
    APGAR,
    RESPIRATORY,
    CARDIOVASCULAR,
    THERMOREGULATION,
    GLUCOSE,
    OXYGENATION,
    /**
     * CR-05: an observed danger sign. A type of its own because no later measurement can say the
     * sign has gone (ObstetricAlertCurrency): only a person closes its escalation.
     */
    DANGER_SIGN,
    GENERAL
}
