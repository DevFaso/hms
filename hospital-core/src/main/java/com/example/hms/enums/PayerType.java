package com.example.hms.enums;

/**
 * Billing-money plan PR 6 (V305): the kind of payer a hospital bills a share of a visit to. The payer's own name is
 * hospital data, typed and shown as is (D-8); only this type is translated.
 */
public enum PayerType {
    /** A universal health insurance scheme (assurance maladie universelle). */
    AMU,
    /** A mutual health insurer (mutuelle). */
    MUTUELLE,
    /** A private insurance company. */
    ASSUREUR_PRIVE,
    /** An employer that pays for its staff's care. */
    EMPLOYEUR,
    /** An NGO or a health programme that pays for its beneficiaries' care. */
    ONG_PROGRAMME
}
