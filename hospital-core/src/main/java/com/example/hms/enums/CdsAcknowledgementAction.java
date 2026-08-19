package com.example.hms.enums;

/**
 * What the clinician chose to do with a Best-Practice Advisory: simply
 * dismiss it (no clinical override) or override a critical advisory with
 * a documented reason (the override is required to proceed past a
 * blocking critical card on the prescription path).
 */
public enum CdsAcknowledgementAction {
    ACKNOWLEDGED,
    OVERRIDDEN
}
