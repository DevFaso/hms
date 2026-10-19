package com.example.hms.service;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.LabTestReferenceRange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LabResultValueRulesTest {

    private static LabTestDefinition potassium() {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setReferenceRanges(new ArrayList<>(List.of(
            LabTestReferenceRange.builder().minValue(3.5).maxValue(5.0).unit("mmol/L").build())));
        return definition;
    }

    /** QA D288: "abc" and -1 were stored on a numeric test. */
    @Test
    void aNumericTestRefusesTextAndANegativeValue() {
        assertThatThrownBy(() -> LabResultValueRules.requireValidValue("abc", potassium()))
            .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "lab.result.value.notNumeric");
        assertThatThrownBy(() -> LabResultValueRules.requireValidValue("-1", potassium()))
            .isInstanceOf(BusinessException.class).hasFieldOrPropertyWithValue("messageKey", "lab.result.value.negative");
        assertThatThrownBy(() -> LabResultValueRules.requireValidValue("NaN", potassium()))
            .isInstanceOf(BusinessException.class);
        assertThatCode(() -> LabResultValueRules.requireValidValue("4.2", potassium())).doesNotThrowAnyException();
    }

    @Test
    void aNegativeValueIsAllowedWhenTheTestHasANegativeLimit() {
        LabTestDefinition baseExcess = new LabTestDefinition();
        baseExcess.setReferenceRanges(new ArrayList<>(List.of(
            LabTestReferenceRange.builder().criticalLow(-10.0).criticalHigh(10.0).build())));
        assertThatCode(() -> LabResultValueRules.requireValidValue("-3", baseExcess)).doesNotThrowAnyException();
    }

    @Test
    void aTestWithNoNumericLimitsTakesAWordAnswer() {
        assertThatCode(() -> LabResultValueRules.requireValidValue("Positif", new LabTestDefinition()))
            .doesNotThrowAnyException();
    }

    /** QA D286: a positive malaria RDT read "not evaluated" and a green check. */
    @Test
    void aPositiveQualitativeAnswerIsAbnormal() {
        assertThat(LabResultValueRules.flagFor("Positif", null, null)).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(LabResultValueRules.flagFor("POSITIVE (P. falciparum)", null, null)).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(LabResultValueRules.flagFor("Détecté", null, null)).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(LabResultValueRules.flagFor("+++", null, null)).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(LabResultValueRules.flagFor("Négatif", null, null)).isNull();
        assertThat(LabResultValueRules.flagFor("A positif", null, null)).isNull();
        assertThat(LabResultValueRules.flagFor("Positivity", null, null)).isNull();
        assertThat(LabResultValueRules.flagFor("4.2", null, null)).isNull();
    }

    @Test
    void amendingAPositiveToANegativeWithdrawsTheFlagThisRuleSet() {
        assertThat(LabResultValueRules.flagFor("Négatif", "Positif", AbnormalFlag.ABNORMAL)).isNull();
        assertThat(LabResultValueRules.flagFor("Négatif", "12", AbnormalFlag.ABNORMAL)).isEqualTo(AbnormalFlag.ABNORMAL);
        assertThat(LabResultValueRules.flagFor("Positif", null, AbnormalFlag.CRITICAL)).isEqualTo(AbnormalFlag.CRITICAL);
    }
}
