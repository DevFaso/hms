package com.example.hms.enums;

/** Policy rule 1 (provisional, V345): on what authority a renewal request was decided. */
public enum RefillDecisionBasis {
    /** An authorised prescriber approved or refused it. */
    PRESCRIBER_APPROVAL,
    /** A refill the prescriber already wrote on the prescription: ordinary dispensing, released by the pharmacy. */
    ORIGINAL_PRESCRIPTION,
    /** The pharmacist refused to dispense, with a reason; the prescriber was told. */
    PHARMACIST_REFUSAL
}
