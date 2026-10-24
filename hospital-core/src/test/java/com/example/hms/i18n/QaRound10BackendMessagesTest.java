package com.example.hms.i18n;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA round 10: D727 (appointment and consultation workflow refusals answered
 * in English) and D728 (shifts called "service" in French, the word the
 * portal uses for a department).
 */
class QaRound10BackendMessagesTest {

    private static Properties bundle(String name) throws IOException {
        Properties props = new Properties();
        try (InputStream in = QaRound10BackendMessagesTest.class.getResourceAsStream("/i18n/" + name + ".properties")) {
            assertThat(in).as(name).isNotNull();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    @Test
    void qaD728NoFrenchShiftMessageCallsTheShiftAService() throws IOException {
        Properties fr = bundle("messages-scheduling_fr");
        assertThat(fr.stringPropertyNames()).anyMatch(k -> k.startsWith("schedule.shift."));
        fr.stringPropertyNames().stream()
            .filter(k -> k.startsWith("schedule.shift."))
            .forEach(k -> assertThat(fr.getProperty(k).toLowerCase(Locale.ROOT))
                .as(k)
                .doesNotContain("service"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "scheduling:appointment.timeRange.invalid",
        "scheduling:appointment.alreadyCancelled",
        "scheduling:appointment.cancel.completed",
        "scheduling:appointment.reschedule.completed",
        "scheduling:appointment.reschedule.cancelled",
        "scheduling:appointment.patient.notChangeable",
        "maternity:maternity.partograph.futureTime",
        "patient:patient.registration.existingRecord",
        "clinical:consultation.workflow.acknowledge.invalidStatus",
        "clinical:consultation.workflow.alreadyCompleted",
        "clinical:consultation.workflow.complete.cancelled",
        "clinical:consultation.workflow.cancel.completed",
        "clinical:consultation.workflow.alreadyCancelled",
        "clinical:consultation.workflow.schedule.invalidStatus",
        "clinical:consultation.workflow.start.invalidStatus",
        "clinical:consultation.workflow.decline.completed",
        "clinical:consultation.workflow.decline.cancelled",
        "clinical:consultation.workflow.alreadyDeclined",
        "clinical:consultation.workflow.assign.invalidStatus",
        "clinical:consultation.workflow.reassign.completed",
        "clinical:consultation.workflow.reassign.cancelled",
    })
    void qaD727EveryRefusalHasARealFrenchAndSpanishText(String spec) throws IOException {
        String[] parts = spec.split(":");
        String base = "messages-" + parts[0];
        String key = parts[1];
        String en = bundle(base).getProperty(key);
        String fr = bundle(base + "_fr").getProperty(key);
        String es = bundle(base + "_es").getProperty(key);
        assertThat(en).as(key + " (base)").isNotBlank();
        assertThat(bundle(base + "_en").getProperty(key)).as(key + " (en)").isEqualTo(en);
        assertThat(fr).as(key + " (fr)").isNotBlank().isNotEqualTo(en);
        assertThat(es).as(key + " (es)").isNotBlank().isNotEqualTo(en).isNotEqualTo(fr);
    }
}
