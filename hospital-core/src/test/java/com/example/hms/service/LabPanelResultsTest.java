package com.example.hms.service;

import com.example.hms.exception.FieldBusinessException;
import com.example.hms.mapper.LabResultMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabResultComponent;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.LabResultComponentValueDTO;
import com.example.hms.payload.dto.LabResultRequestDTO;
import com.example.hms.payload.dto.LabResultResponseDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * r63 (NFS panels): a full blood count is one entry and one release with a
 * value per component, each graded against its own component's ranges, and
 * the result's severity is the worst of them.
 */
@ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
class LabPanelResultsTest {

    private LabTestDefinition nfs;

    private static LabTestReferenceRange range(double min, double max, Double critLow, Double critHigh) {
        return LabTestReferenceRange.builder().minValue(min).maxValue(max)
            .criticalLow(critLow).criticalHigh(critHigh).build();
    }

    @BeforeEach
    void setUp() {
        nfs = new LabTestDefinition();
        nfs.setId(UUID.randomUUID());
        nfs.setName("NFS");
        nfs.setTestCode("NFS");
        nfs.setComponents(new ArrayList<>(List.of(
            LabTestComponent.builder().code("HB").name("Hemoglobine").unit("g/dL")
                .referenceRanges(new ArrayList<>(List.of(range(12, 16, 7.0, 20.0)))).build(),
            LabTestComponent.builder().code("WBC").name("Leucocytes").unit("10^9/L")
                .referenceRanges(new ArrayList<>(List.of(range(4, 10, 1.0, 30.0)))).build(),
            LabTestComponent.builder().code("PLT").name("Plaquettes").unit("10^9/L")
                .referenceRanges(new ArrayList<>(List.of(range(150, 400, 20.0, 1000.0)))).build())));
    }

    private static LabResultComponentValueDTO value(String code, String value) {
        return LabResultComponentValueDTO.builder().code(code).value(value).build();
    }

    private static LabResultRequestDTO request(LabResultComponentValueDTO... values) {
        return LabResultRequestDTO.builder().components(new ArrayList<>(List.of(values))).build();
    }

    @Test
    void componentsAreStoredInThePanelsOrderWithTheirUnitsAndSummarised() {
        LabResultRequestDTO request = request(value("wbc", "7,2"), value("HB", "13.5"), value("PLT", ""));

        List<LabResultComponent> stored = LabPanelEntry.componentsFor(request, nfs);

        assertThat(stored).extracting(LabResultComponent::getCode).containsExactly("HB", "WBC");
        assertThat(stored).extracting(LabResultComponent::getValue).containsExactly("13.5", "7.2");
        assertThat(stored).extracting(LabResultComponent::getUnit).containsExactly("g/dL", "10^9/L");
        assertThat(request.getResultValue()).isEqualTo("HB 13.5 g/dL; WBC 7.2 10^9/L");
        assertThat(request.getResultUnit()).isNull();
    }

    @Test
    void aPanelWithNoValueAnUnknownCodeOrATwiceEnteredOneIsRefusedOnItsField() {
        LabResultRequestDTO sent1 = request(value("HB", " "));
        assertThatThrownBy(() -> LabPanelEntry.componentsFor(sent1, nfs))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> assertThat(ex.getField()).isEqualTo("components"));
        LabResultRequestDTO sent2 = request(value("CRP", "3"));
        assertThatThrownBy(() -> LabPanelEntry.componentsFor(sent2, nfs))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("components[0].code"));
        LabResultRequestDTO sent3 = request(value("HB", "13"), value("hb", "14"));
        assertThatThrownBy(() -> LabPanelEntry.componentsFor(sent3, nfs))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("components[1].code"));
    }

    @Test
    void eachComponentValueIsCheckedAgainstItsOwnRanges() {
        LabResultRequestDTO sent4 = request(value("HB", "13"), value("WBC", "abc"));
        assertThatThrownBy(() -> LabPanelEntry.componentsFor(sent4, nfs))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                ex -> assertThat(ex.getField()).isEqualTo("components[1].value"));
    }

    @Test
    void componentsOnASingleValueTestAreRefusedAndASingleValueTestIsUntouched() {
        LabTestDefinition potassium = new LabTestDefinition();
        LabResultRequestDTO sent5 = request(value("HB", "13"));
        assertThatThrownBy(() -> LabPanelEntry.componentsFor(sent5, potassium))
            .isInstanceOfSatisfying(FieldBusinessException.class, ex -> assertThat(ex.getField()).isEqualTo("components"));
        LabResultRequestDTO single = LabResultRequestDTO.builder().resultValue("4.1").build();
        assertThat(LabPanelEntry.componentsFor(single, potassium)).isEmpty();
        assertThat(single.getResultValue()).isEqualTo("4.1");
    }

    private LabResult panelResult(String hb, String wbc) {
        Hospital hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setHospital(hospital);
        order.setPatient(patient);
        order.setLabTestDefinition(nfs);
        LabResultRequestDTO request = request(value("HB", hb), value("WBC", wbc));
        List<LabResultComponent> components = LabPanelEntry.componentsFor(request, nfs);
        LabResult result = new LabResult();
        result.setLabOrder(order);
        result.setResultValue(request.getResultValue());
        result.setResultDate(LocalDateTime.now());
        result.setComponents(components);
        return result;
    }

    @Test
    void aPanelIsGradedByItsWorstComponentAndEachComponentShowsItsOwnGrade() {
        LabResultMapper mapper = new LabResultMapper();

        LabResultResponseDTO normal = mapper.toResponseDTO(panelResult("13.5", "7.2"));
        assertThat(normal.isPanel()).isTrue();
        assertThat(normal.getSeverityFlag()).isEqualTo("NORMAL");

        LabResultResponseDTO high = mapper.toResponseDTO(panelResult("13.5", "12"));
        assertThat(high.getSeverityFlag()).isEqualTo("HIGH");
        assertThat(high.getComponents()).extracting("code", "severityFlag")
            .containsExactly(org.assertj.core.groups.Tuple.tuple("HB", "NORMAL"),
                org.assertj.core.groups.Tuple.tuple("WBC", "HIGH"));

        LabResult critical = panelResult("6.1", "12");
        assertThat(mapper.toResponseDTO(critical).getSeverityFlag()).isEqualTo("CRITICAL");
        assertThat(mapper.raisesCriticalAlert(mapper.severityFlagOf(critical))).isTrue();
    }

    @Test
    void aComponentNothingCanGradeKeepsThePanelUnspecifiedAndForReview() {
        LabResultMapper mapper = new LabResultMapper();
        LabResult result = panelResult("13.5", "7.2");
        result.getComponents().get(1).setValue(">5");

        LabResultResponseDTO dto = mapper.toResponseDTO(result);

        assertThat(dto.getSeverityFlag()).isEqualTo("UNSPECIFIED");
        assertThat(dto.isGradeUnknown()).isTrue();
        assertThat(mapper.isGradeUnknown(result)).isTrue();
    }
}
