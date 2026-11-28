package com.example.hms.service;

import com.example.hms.i18n.TestMessageSources;
import com.example.hms.service.notification.PatientMessageGate.AttachmentMailRefusal;
import com.example.hms.service.notification.PatientNotificationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.MessageSource;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4222 (round 81), the guard of the class: every reason the message gate can refuse an invoice e-mail for has
 * its own text, and that text says what the delivery ledger records. The unverified address used to be told as
 * « Ce patient a désactivé les e-mails de facturation », which the ledger recorded as RECIPIENT_UNVERIFIED.
 */
@DisplayName("Invoice e-mail: each refusal reason reads as its ledger code")
class InvoiceMailRefusalReasonsTest {

    private static final MessageSource MESSAGES = TestMessageSources.bundles();

    /** What each ledger code must say in French and in English, and what it must not. */
    private static final Map<PatientNotificationErrorCode, String[]> WORDS = Map.of(
        PatientNotificationErrorCode.OPTED_OUT, new String[] {"désactivé", "turned off", "vérifi", "verif"},
        PatientNotificationErrorCode.RECIPIENT_UNVERIFIED, new String[] {"pas vérifiée", "not been verified",
            "désactivé", "turned off"});

    @ParameterizedTest
    @EnumSource(AttachmentMailRefusal.class)
    void eachReasonSaysWhatTheLedgerRecords(AttachmentMailRefusal refusal) {
        String[] words = WORDS.get(refusal.ledgerCode());
        assertThat(words).as("the words expected for the ledger code %s", refusal.ledgerCode()).isNotNull();
        String key = InvoiceEmailService.refusalKey(refusal);
        String french = MESSAGES.getMessage(key, null, Locale.FRENCH);
        String english = MESSAGES.getMessage(key, null, Locale.ENGLISH);

        assertThat(french).contains(words[0]).doesNotContain(words[2]);
        assertThat(english).contains(words[1]).doesNotContain(words[3]);
        assertThat(MESSAGES.getMessage(key, null, Locale.forLanguageTag("es"))).isNotBlank();
    }

    @Test
    void theLedgerCodeIsTheReasonsOwnName() {
        for (AttachmentMailRefusal refusal : AttachmentMailRefusal.values()) {
            assertThat(refusal.ledgerCode().name()).isEqualTo(refusal.name());
        }
    }

    @Test
    void noTwoReasonsShareOneText() {
        Set<String> keys = new HashSet<>();
        for (AttachmentMailRefusal refusal : AttachmentMailRefusal.values()) {
            assertThat(keys.add(InvoiceEmailService.refusalKey(refusal)))
                .as("%s has a text of its own", refusal).isTrue();
        }
    }

    @Test
    @DisplayName("QA D4230: one list over the cap is refused in plain words that state the cap")
    void qaD4230_aListOverTheCapIsRefusedInPlainWords() {
        String key = com.example.hms.payload.dto.EmailInvoiceRequest.TOO_MANY.replace("{", "").replace("}", "");
        String cap = String.valueOf(com.example.hms.payload.dto.EmailInvoiceRequest.MAX_RECIPIENTS);
        for (Locale locale : new Locale[] {Locale.FRENCH, Locale.ENGLISH, Locale.forLanguageTag("es")}) {
            assertThat(MESSAGES.getMessage(key, null, locale)).contains(cap);
        }
        assertThat(MESSAGES.getMessage(key, null, Locale.FRENCH))
            .isEqualTo(MESSAGES.getMessage("invoiceMail.recipient.tooMany", new Object[] {cap}, Locale.FRENCH));
        jakarta.validation.Validator validator =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        var request = new com.example.hms.payload.dto.EmailInvoiceRequest(
            java.util.List.of("a@x.test", "b@x.test", "c@x.test", "d@x.test"), null, null, null, null, false);
        assertThat(validator.validate(request)).extracting(v -> v.getMessageTemplate())
            .containsExactly(com.example.hms.payload.dto.EmailInvoiceRequest.TOO_MANY);
    }
}
