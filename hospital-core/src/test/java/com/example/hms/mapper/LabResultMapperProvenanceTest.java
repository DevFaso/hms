package com.example.hms.mapper;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.payload.dto.LabResultReferenceRangeDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LabResultMapperProvenanceTest {

    private final LabResultMapper mapper = new LabResultMapper();

    private static LabResult result(String value, String unit, LabTestReferenceRange... ranges) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setReferenceRanges(new ArrayList<>(List.of(ranges)));
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(definition);
        LabResult result = new LabResult();
        result.setId(UUID.randomUUID());
        result.setLabOrder(order);
        result.setResultValue(value);
        result.setResultUnit(unit);
        return result;
    }

    private static LabTestReferenceRange range(double min, double max, String unit) {
        return LabTestReferenceRange.builder().minValue(min).maxValue(max).unit(unit).build();
    }

    @Test
    void anInstrumentResultCarriesItsControlIdAndItsObservationStatus() {
        LabResult row = result("5.4", "mmol/L");
        row.setSourceMessageControlId("MSG00042");
        row.setObservationResultStatus("P");

        LabResultResponseDTO dto = mapper.toResponseDTO(row);

        assertThat(dto.getSourceMessageControlId()).isEqualTo("MSG00042");
        assertThat(dto.getObservationResultStatus()).isEqualTo("P");
    }

    @Test
    void aHandEnteredResultCarriesNeither() {
        LabResultResponseDTO dto = mapper.toResponseDTO(result("5.4", "mmol/L"));

        assertThat(dto.getSourceMessageControlId()).isNull();
        assertThat(dto.getObservationResultStatus()).isNull();
    }

    @Test
    void theGradedRangeIsTheOneTheSeverityWasComputedAgainst() {
        LabResult row = result("5.4", "mmol/L", range(70, 110, "mg/dL"), range(3.9, 6.1, "mmol/L"));

        LabResultReferenceRangeDTO graded = mapper.gradedReferenceRange(row);

        assertThat(graded.getUnit()).isEqualTo("mmol/L");
        assertThat(graded.getMinValue()).isEqualTo(3.9);
        assertThat(mapper.toResponseDTO(row).getSeverityFlag()).isEqualTo("NORMAL");
    }

    @Test
    void noConfiguredRangeGradesNothing() {
        assertThat(mapper.gradedReferenceRange(result("5.4", "mmol/L"))).isNull();
        assertThat(mapper.gradedReferenceRange(null)).isNull();
    }
}
