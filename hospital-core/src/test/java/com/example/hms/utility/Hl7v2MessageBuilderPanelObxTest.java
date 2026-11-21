package com.example.hms.utility;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabResultComponent;
import com.example.hms.model.LabTestComponent;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import com.example.hms.model.Patient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r65 D2610: an NFS went out as one OBX whose value was the whole summary line
 * ("HB 5.5 g/dL; HCT 42 %; ..."), with no unit, flag, status or time per
 * component, so no analyser or LIS could read the panel. A panel now goes out
 * as one OBX per component: code and name, value, unit, the range it was
 * graded against, its own flag, the result status and the result time.
 */
class Hl7v2MessageBuilderPanelObxTest {

    private final Hl7v2MessageBuilder builder = new Hl7v2MessageBuilder();

    private static LabTestReferenceRange range(double min, double max, Double critLow, Double critHigh) {
        return LabTestReferenceRange.builder().minValue(min).maxValue(max)
            .criticalLow(critLow).criticalHigh(critHigh).build();
    }

    private static LabResult nfs(boolean released) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setName("NFS");
        definition.setTestCode("NFS");
        definition.setComponents(new ArrayList<>(List.of(
            LabTestComponent.builder().code("HB").name("Hémoglobine").unit("g/dL")
                .referenceRanges(new ArrayList<>(List.of(range(12, 16, 7.0, 20.0)))).build(),
            LabTestComponent.builder().code("WBC").name("Leucocytes").unit("10^9/L")
                .referenceRanges(new ArrayList<>(List.of(range(4, 10, 1.0, 30.0)))).build(),
            LabTestComponent.builder().code("PLT").name("Plaquettes").unit("10^9/L")
                .referenceRanges(new ArrayList<>(List.of(range(150, 400, 20.0, 1000.0)))).build(),
            LabTestComponent.builder().code("RET").name("Réticulocytes").unit("%").build())));
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setFirstName("Awa");
        patient.setLastName("TestDiallo");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setPatient(patient);
        order.setLabTestDefinition(definition);
        LabResult result = LabResult.builder()
            .labOrder(order)
            .resultValue("HB 5.5 g/dL; WBC 12 10^9/L; PLT 180 10^9/L; RET 1.2 %")
            .resultDate(LocalDateTime.of(2026, 11, 19, 10, 12))
            .components(new ArrayList<>(List.of(
                LabResultComponent.builder().code("HB").name("Hémoglobine").value("5.5").unit("g/dL").build(),
                LabResultComponent.builder().code("WBC").name("Leucocytes").value("12").unit("10^9/L").build(),
                LabResultComponent.builder().code("PLT").name("Plaquettes").value("180").unit("10^9/L").build(),
                LabResultComponent.builder().code("RET").name("Réticulocytes").value("1.2").unit("%").build())))
            .build();
        result.setReleased(released);
        return result;
    }

    private static List<String[]> obxOf(String oru) {
        return oru.lines().filter(l -> l.startsWith("OBX|")).map(l -> l.split("\\|", -1)).toList();
    }

    @Test
    void aPanelGoesOutAsOneObxPerComponentWithItsOwnUnitRangeFlagStatusAndTime() {
        List<String[]> obx = obxOf(builder.buildOruR01(nfs(true)));

        assertThat(obx).hasSize(4);
        assertThat(obx).extracting(f -> f[1]).containsExactly("1", "2", "3", "4");
        assertThat(obx).extracting(f -> f[2]).containsOnly("NM");
        assertThat(obx).extracting(f -> f[3]).containsExactly(
            "HB^Hémoglobine", "WBC^Leucocytes", "PLT^Plaquettes", "RET^Réticulocytes");
        assertThat(obx).extracting(f -> f[5]).containsExactly("5.5", "12", "180", "1.2");
        // The caret in 10^9/L is escaped, so a receiver reads one unit.
        assertThat(obx).extracting(f -> f[6]).containsExactly("g/dL", "10\\S\\9/L", "10\\S\\9/L", "%");
        assertThat(obx).extracting(f -> f[7]).containsExactly("12-16", "4-10", "150-400", "");
        // HB below its critical low is LL, WBC above its range H, PLT normal, RET ungraded.
        assertThat(obx).extracting(f -> f[8]).containsExactly("LL", "H", "N", "");
        assertThat(obx).extracting(f -> f[11]).containsOnly("F");
        assertThat(obx).extracting(f -> f[14]).allSatisfy(t -> assertThat(t).startsWith("20261119"));
    }

    @Test
    void anUnreleasedPanelIsPreliminaryOnEveryComponent() {
        assertThat(obxOf(builder.buildOruR01(nfs(false)))).extracting(f -> f[11]).containsOnly("P");
    }

    @Test
    void ourOwnParserReadsBackEveryComponent() {
        assertThat(builder.parseOruR01(builder.buildOruR01(nfs(true))))
            .extracting(o -> o.resultValue() + " " + o.resultUnit() + " " + o.abnormalFlag())
            .containsExactly("5.5 g/dL LL", "12 10^9/L H", "180 10^9/L N", "1.2 % ");
    }

    @Test
    void aSingleTestStillGoesOutAsOneObx() {
        LabResult single = nfs(true);
        single.setComponents(new ArrayList<>());
        single.setResultValue("4.5");
        single.setResultUnit("g/L");

        List<String[]> obx = obxOf(builder.buildOruR01(single));

        assertThat(obx).hasSize(1);
        assertThat(obx.get(0)[5]).isEqualTo("4.5");
        assertThat(obx.get(0)[3]).isEqualTo("NFS^NFS");
    }
}
