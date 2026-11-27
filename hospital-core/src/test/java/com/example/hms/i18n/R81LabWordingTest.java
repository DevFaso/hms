package com.example.hms.i18n;

import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** QA r81, lab lane: the wording faults of the escalation reminders and lab notices stay fixed. */
class R81LabWordingTest {

    private final MessageSource bundles = TestMessageSources.bundles();

    private String fr(String key, Object... args) {
        return bundles.getMessage(key, args, Locale.FRENCH);
    }

    /** D4155 (a): « toujours acquitté par aucun clinicien » said the opposite of what it meant. */
    @Test
    void theLabEscalationLeadSaysNobodyHasAcknowledged() {
        assertThat(fr("lab.critical.escalation.lead", "K", "6,9", "P"))
            .contains("qu’aucun clinicien n’a encore acquitté")
            .doesNotContain("toujours acquitté par aucun");
    }

    /** D4155 (b): the reminder appends its own instruction, so the alert sentence does not repeat one. */
    @Test
    void cultureAndReactionRemindersCarryOneInstruction() {
        for (String key : new String[] {"urgentAlert.msg.POSITIVE_CULTURE", "urgentAlert.msg.SEVERE_TRANSFUSION_REACTION"}) {
            String reminder = fr("obstetric.alert.escalation", "1", "type", "Patient", fr(key));
            assertThat(reminder.split("ccusez réception", -1)).as(key).hasSize(2);
        }
    }

    /** D4155 (d): one spelling, « contresignature », as on the blood-bank screens. */
    @Test
    void countersignatureIsSpelledOneWay() {
        assertThat(fr("urgentAlert.type.O_POSITIVE_COUNTERSIGN_OVERDUE")).contains("contresignature");
        assertThat(fr("urgentAlert.type.INCOMPATIBLE_COUNTERSIGN_OVERDUE")).contains("contresignature");
    }

    /** D4172: the billing task says who records the number, since billing cannot. */
    @Test
    void thePreAuthorisationTaskSaysWhoRecordsTheNumber() {
        assertThat(fr("imaging.preauth.followUp.body", "Scanner", "Awa"))
            .contains("transmettez le numéro au médecin prescripteur ou au radiologue")
            .contains("l'enregistre");
    }
}
