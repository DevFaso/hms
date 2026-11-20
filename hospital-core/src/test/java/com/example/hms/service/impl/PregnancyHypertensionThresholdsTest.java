package com.example.hms.service.impl;

import com.example.hms.cdshooks.bpa.PregnancyHypertensionProtocolRule;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D971: the chart advisory and the partograph alerts use one set of blood-pressure cutoffs. */
class PregnancyHypertensionThresholdsTest {

    @Test
    void theAdvisoryAndTheLabourAlertsAgree() {
        // Policy 2026-11-20: the partograph reads its cutoffs from the one maternal rule set (WHO Labour Care Guide).
        com.example.hms.service.vitals.MaternalEarlyWarningRules.Labour labour =
            new com.example.hms.service.vitals.MaternalEarlyWarningRules().getLabour();
        assertThat(PregnancyHypertensionProtocolRule.HTN_SYSTOLIC).isEqualTo(labour.getSystolicHighFrom());
        assertThat(PregnancyHypertensionProtocolRule.HTN_DIASTOLIC).isEqualTo(labour.getDiastolicHighFrom());
        assertThat(PregnancyHypertensionProtocolRule.SEVERE_HTN_SYSTOLIC).isEqualTo(LaborServiceImpl.SEVERE_HTN_SYSTOLIC);
        assertThat(PregnancyHypertensionProtocolRule.SEVERE_HTN_DIASTOLIC).isEqualTo(LaborServiceImpl.SEVERE_HTN_DIASTOLIC);
    }

    /** QA D1890: severe after birth is the same severe range as in labour and pregnancy. */
    @Test
    void thePostpartumRuleUsesTheSameSevereRange() {
        assertThat(com.example.hms.service.vitals.SevereHypertension.OBSTETRIC_SYSTOLIC)
            .isEqualTo(LaborServiceImpl.SEVERE_HTN_SYSTOLIC);
        assertThat(com.example.hms.service.vitals.SevereHypertension.OBSTETRIC_DIASTOLIC)
            .isEqualTo(LaborServiceImpl.SEVERE_HTN_DIASTOLIC);
    }
}
