package com.example.hms.controller;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D933: the patient tracker offers "Évaluation de triage" to the midwife,
 * and the midwife records ANC vitals there. Both triage writes must admit
 * ROLE_MIDWIFE, or her submission is a 403 and the typed form is lost.
 * Reflection reads the compiled annotation, the exact string Spring evaluates.
 */
class EncounterTriageMidwifeRolesTest {

    @ParameterizedTest
    @ValueSource(strings = {"submitTriage", "completeTriage"})
    void theMidwifeMayTriage(String handler) {
        List<Method> matches = Arrays.stream(EncounterController.class.getDeclaredMethods())
            .filter(m -> m.getName().equals(handler))
            .toList();
        assertThat(matches).as("exactly one %s on EncounterController", handler).hasSize(1);
        PreAuthorize preAuthorize = matches.get(0).getAnnotation(PreAuthorize.class);
        assertThat(preAuthorize).isNotNull();
        assertThat(preAuthorize.value()).contains("'ROLE_MIDWIFE'");
    }
}
