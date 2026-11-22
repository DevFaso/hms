package com.example.hms.service.clinicalrules;

import com.example.hms.enums.RuleSignoffStatus;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.clinicalrules.ClinicalRuleStatusDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRulesOverviewDTO;
import com.example.hms.payload.dto.clinicalrules.HospitalRuleSignoffDTO;
import com.example.hms.payload.dto.clinicalrules.LocalizedRuleText;
import com.example.hms.repository.HospitalRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V356: the French sign-off pack lists every rule set of the registry with its source, approver role, status at
 * this hospital and the lines to sign, whatever the reader's language.
 */
@DisplayName("V356: the French sign-off pack of a hospital")
class RuleSignoffPackPdfServiceTest {

    @Test
    @DisplayName("every rule set is printed in French with its source, approver, status and signature lines")
    void printsEveryRuleInFrench() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Clinique Les Palmiers");
        HospitalRepository hospitals = mock(HospitalRepository.class);
        when(hospitals.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));

        MessageSource messages = com.example.hms.i18n.TestMessageSources.bundles();
        List<ClinicalRuleStatusDTO> rules = new ArrayList<>();
        for (ClinicalRuleSet rule : ClinicalRuleRegistry.all()) {
            boolean signed = rule.key() == ClinicalRuleSetKey.PARACETAMOL_LIMITS;
            rules.add(new ClinicalRuleStatusDTO(rule.key().name(), rule.version(), french(messages, rule.titleKey()),
                french(messages, rule.descriptionKey()), french(messages, rule.sourceKey()), french(messages, rule.approverKey()),
                rule.enforcedInNames(), signed ? RuleSignoffStatus.SIGNED : RuleSignoffStatus.PENDING,
                signed ? HospitalRuleSignoffDTO.builder().id(UUID.randomUUID()).ruleVersion(rule.version())
                    .status(RuleSignoffStatus.SIGNED).signerName("Dr Awa Sawadogo").signerTitle("Médecin-chef")
                    .signedOn(LocalDate.of(2026, 11, 20)).recordedByName("Admin").recordedAt(LocalDateTime.now())
                    .build() : null,
                null, rule.key().area().name(), rule.sourceUrl()));
        }
        HospitalRuleSignoffService signoffs = mock(HospitalRuleSignoffService.class);
        when(signoffs.overview(hospitalId, null))
            .thenReturn(new ClinicalRulesOverviewDTO(hospitalId, true, 1, rules.size(), rules));

        byte[] pdf = new RuleSignoffPackPdfService(signoffs, hospitals, messages).render(hospitalId, null);

        String text;
        try (PDDocument document = PDDocument.load(pdf)) {
            text = new PDFTextStripper().getText(document).replaceAll("\\s+", " ");
        }
        assertThat(text)
            .contains("Règles cliniques et validation")
            .contains("Clinique Les Palmiers")
            .contains("Règles validées : 1 / " + rules.size())
            .contains("Validée le 20 nov. 2026 par Médecin-chef (Dr Awa Sawadogo)")
            .contains("En attente (provisoire)")
            .contains("Nom et fonction")
            .contains("Signature");
        for (ClinicalRuleSet rule : ClinicalRuleRegistry.all()) {
            String title = messages.getMessage(rule.titleKey(), null, java.util.Locale.FRENCH);
            assertThat(text).as(rule.key().name()).contains(title.replaceAll("\\s+", " "));
        }
    }

    private static LocalizedRuleText french(MessageSource messages, String key) {
        String fr = messages.getMessage(key, null, java.util.Locale.FRENCH);
        return new LocalizedRuleText("en", fr, "es");
    }
}
