package com.example.hms.service.integration;

/**
 * One aggregated value ready to be sent to DHIS2.
 *
 * <p>This is a hard contract: <strong>no patient identifier of any kind
 * may be carried on this record</strong>. Aggregator implementations
 * MUST emit only counts/sums and the DHIS2-side identifiers
 * (orgUnit, dataElement, categoryOptionCombo). A regression test
 * asserts the shape of the field set.
 *
 * @param orgUnitUid              DHIS2 organisation-unit UID (the hospital)
 * @param dataElementUid          DHIS2 dataElement UID (e.g. BCG given)
 * @param categoryOptionComboUid  Optional disaggregation (age band, sex). Null = default combo.
 * @param value                   Stringified count or sum (DHIS2 accepts numeric values as strings).
 */
public record AggregatedDataValue(
        String orgUnitUid,
        String dataElementUid,
        String categoryOptionComboUid,
        String value
) {
}
