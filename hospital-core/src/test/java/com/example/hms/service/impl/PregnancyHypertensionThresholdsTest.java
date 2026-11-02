package com.example.hms.service.impl;

import com.example.hms.cdshooks.bpa.PregnancyHypertensionProtocolRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D971: the chart advisory and the partograph alerts use one set of blood-pressure cutoffs. */
class PregnancyHypertensionThresholdsTest {

    @Test
    void theAdvisoryAndTheLabourAlertsAgree() {
        assertThat(PregnancyHypertensionProtocolRule.HTN_SYSTOLIC).isEqualTo(LaborServiceImpl.HTN_SYSTOLIC);
        assertThat(PregnancyHypertensionProtocolRule.HTN_DIASTOLIC).isEqualTo(LaborServiceImpl.HTN_DIASTOLIC);
        assertThat(PregnancyHypertensionProtocolRule.SEVERE_HTN_SYSTOLIC).isEqualTo(LaborServiceImpl.SEVERE_HTN_SYSTOLIC);
        assertThat(PregnancyHypertensionProtocolRule.SEVERE_HTN_DIASTOLIC).isEqualTo(LaborServiceImpl.SEVERE_HTN_DIASTOLIC);
    }
}
