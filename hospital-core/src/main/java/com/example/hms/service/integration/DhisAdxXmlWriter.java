package com.example.hms.service.integration;

import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;
import org.springframework.stereotype.Component;

/**
 * Pure ADX 1.0 XML writer. No Spring deps beyond the {@link Component}
 * marker — easy to unit-test in isolation.
 *
 * <p>The {@code <group>} element groups all values that share a
 * (orgUnit, period, dataSet) tuple, which for the v0 manual-trigger
 * flow is exactly one group per export.
 */
@Component
public class DhisAdxXmlWriter {

    private static final String ADX_NAMESPACE = "urn:ihe:qrph:adx:2015";
    private static final XMLOutputFactory FACTORY = XMLOutputFactory.newInstance();

    /**
     * Build an ADX payload from a single (orgUnit, period, dataset) group.
     *
     * @param orgUnitUid     DHIS2 facility UID
     * @param periodIso      DHIS2-canonical period token (e.g. {@code 202604} for April 2026)
     * @param datasetUid     DHIS2 dataset UID
     * @param values         data values (must all share the orgUnit)
     * @param exportedAt     timestamp written into the {@code exported} attribute
     * @return ADX 1.0 XML as a string
     */
    public String build(String orgUnitUid,
                        String periodIso,
                        String datasetUid,
                        List<AggregatedDataValue> values,
                        OffsetDateTime exportedAt) {
        Objects.requireNonNull(orgUnitUid, "orgUnitUid");
        Objects.requireNonNull(periodIso, "periodIso");
        Objects.requireNonNull(datasetUid, "datasetUid");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(exportedAt, "exportedAt");

        final var out = new StringWriter();
        try {
            final XMLStreamWriter w = FACTORY.createXMLStreamWriter(out);
            w.writeStartDocument("UTF-8", "1.0");
            w.writeStartElement("adx");
            w.writeDefaultNamespace(ADX_NAMESPACE);
            w.writeAttribute("exported", exportedAt.atZoneSameInstant(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));

            w.writeStartElement("group");
            w.writeAttribute("orgUnit", orgUnitUid);
            w.writeAttribute("period", periodIso);
            w.writeAttribute("dataSet", datasetUid);

            for (AggregatedDataValue v : values) {
                w.writeEmptyElement("dataValue");
                w.writeAttribute("dataElement", v.dataElementUid());
                w.writeAttribute("value", v.value());
                if (v.categoryOptionComboUid() != null && !v.categoryOptionComboUid().isBlank()) {
                    w.writeAttribute("categoryOptionCombo", v.categoryOptionComboUid());
                }
            }

            w.writeEndElement(); // group
            w.writeEndElement(); // adx
            w.writeEndDocument();
            w.flush();
            w.close();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to write ADX XML", e);
        }
        return out.toString();
    }
}
