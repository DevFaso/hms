package com.example.hms.service.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DhisAdxXmlWriterTest {

    private final DhisAdxXmlWriter writer = new DhisAdxXmlWriter();
    private final OffsetDateTime exportedAt =
        OffsetDateTime.of(2026, 5, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("emits ADX root with namespace and exported attribute in UTC ISO-8601")
    void rootIncludesNamespaceAndTimestamp() {
        String xml = writer.build("OU000000001", "202604", "DS00000DEFK",
            List.of(value("DE000000001", null, "42")), exportedAt);

        assertThat(xml).contains("xmlns=\"urn:ihe:qrph:adx:2015\"");
        assertThat(xml).contains("exported=\"2026-05-01T12:00:00Z\"");
        assertThat(xml).contains("<group ");
        assertThat(xml).contains("orgUnit=\"OU000000001\"");
        assertThat(xml).contains("dataSet=\"DS00000DEFK\"");
        assertThat(xml).contains("period=\"202604\"");
    }

    @Test
    @DisplayName("dataValue without categoryOptionCombo omits the attribute")
    void dataValueNoCategoryOptionCombo() {
        String xml = writer.build("OU000000001", "202604", "DS00000DEFK",
            List.of(value("DE000000001", null, "42")), exportedAt);
        assertThat(xml).contains("<dataValue dataElement=\"DE000000001\" value=\"42\"/>");
        assertThat(xml).doesNotContain("categoryOptionCombo");
    }

    @Test
    @DisplayName("dataValue with categoryOptionCombo includes the attribute")
    void dataValueWithCategoryOptionCombo() {
        String xml = writer.build("OU000000001", "202604", "DS00000DEFK",
            List.of(value("DE000000001", "CC000000001", "7")), exportedAt);
        assertThat(xml).contains(
            "<dataValue dataElement=\"DE000000001\" value=\"7\" categoryOptionCombo=\"CC000000001\"/>");
    }

    @Test
    @DisplayName("multiple dataValues all appear in the same group")
    void multipleDataValues() {
        String xml = writer.build("OU000000001", "202604", "DS00000DEFK", List.of(
            value("DE000000001", null, "1"),
            value("DE000000002", null, "2"),
            value("DE000000003", null, "3")
        ), exportedAt);
        assertThat(xml.split("<dataValue ")).hasSize(4); // 3 occurrences + 1 prefix
        assertThat(xml).contains("DE000000001");
        assertThat(xml).contains("DE000000002");
        assertThat(xml).contains("DE000000003");
    }

    @Test
    @DisplayName("empty values list still produces a valid <group/>")
    void emptyGroup() {
        String xml = writer.build("OU000000001", "202604", "DS00000DEFK",
            List.of(), exportedAt);
        assertThat(xml).contains("<group ");
        assertThat(xml).doesNotContain("<dataValue");
    }

    @Test
    @DisplayName("PHI-leakage regression: payload contains no UUID-shaped string")
    void noPatientIdentifierLeaksThroughXmlWriter() {
        String xml = writer.build("OU000000001", "202604", "DS00000DEFK", List.of(
            value("DE000000001", null, "42"),
            value("DE000000002", "CC000000001", "3")
        ), exportedAt);
        // UUID v4 shape: 8-4-4-4-12 hex
        assertThat(xml).doesNotMatch(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*");
    }

    @Test
    @DisplayName("null inputs throw NullPointerException")
    void nullInputs() {
        assertThatThrownBy(() ->
            writer.build(null, "202604", "DS00000DEFK", List.of(), exportedAt))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
            writer.build("OU000000001", null, "DS00000DEFK", List.of(), exportedAt))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
            writer.build("OU000000001", "202604", null, List.of(), exportedAt))
            .isInstanceOf(NullPointerException.class);
    }

    private static AggregatedDataValue value(String dataElement, String coc, String v) {
        return new AggregatedDataValue("OU000000001", dataElement, coc, v);
    }
}
