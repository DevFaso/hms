package com.example.hms.enums;

/**
 * Billing-money plan PR 2 (V302, plan section 3.3): what a price-list item is linked to, so that a charge source
 * (PR 3) finds its price by a stable key instead of by name. An item with no link is priced by hand at the till.
 */
public enum PriceListLinkType {
    /** One of {@link PriceListSystemKey}: the default consultation, a bed day, a delivery. */
    SYSTEM,
    /** A lab test definition the hospital can order (its own or the platform catalogue's); the key is its id. */
    LAB_TEST_DEFINITION,
    /** The code written on a procedure order. */
    PROCEDURE_CODE,
    /** {@code <ImagingModality>:<study type in lower case, without accents>}. */
    IMAGING_STUDY,
    /** A {@link BloodProductType} name. */
    TRANSFUSION_PRODUCT,
    /** A service ({@code clinical.treatments}) of the same hospital; the key is its id. */
    TREATMENT
}
