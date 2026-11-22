package com.example.hms.service.clinicalrules;

import com.example.hms.enums.RuleSignoffStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.clinicalrules.ClinicalRuleStatusDTO;
import com.example.hms.payload.dto.clinicalrules.ClinicalRulesOverviewDTO;
import com.example.hms.payload.dto.clinicalrules.HospitalRuleSignoffDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.pharmacy.PharmacyPdfWriter;
import com.example.hms.utility.DisplayDates;
import com.example.hms.utility.HospitalCalendar;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

/**
 * V356: the French sign-off pack of one hospital — every provisional rule set with its source, its approver role,
 * its status at this hospital and the lines the médecin-chef (or the named approver) signs on paper. The admin then
 * records each signature on « Règles cliniques et validation ».
 *
 * <p>Printed in French whatever the reader's language: it is the document the approver signs. Through the same
 * PDFBox writer as the pharmacy's printed documents (standard fonts, wrapped text, page breaks).
 */
@Service
@RequiredArgsConstructor
public class RuleSignoffPackPdfService {

    private static final Locale PACK_LOCALE = Locale.FRENCH;

    private final HospitalRuleSignoffService signoffService;
    private final HospitalRepository hospitalRepository;
    private final MessageSource messageSource;

    @Transactional(readOnly = true)
    public byte[] render(UUID hospitalId, UUID readerUserId) {
        Hospital hospital = hospitalRepository.findClinicalById(hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("hospital.notFound", hospitalId));
        ClinicalRulesOverviewDTO overview = signoffService.overview(hospitalId, readerUserId);
        LocalDate today = HospitalCalendar.today(hospital);
        try (PharmacyPdfWriter pdf = new PharmacyPdfWriter(false)) {
            pdf.paragraph(PharmacyPdfWriter.BOLD, 15f, label("clinicalRules.pack.title"));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, label("clinicalRules.pack.hospital", hospital.getName()));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f, label("clinicalRules.pack.printedOn", DisplayDates.date(today, PACK_LOCALE)));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 10f,
                label("clinicalRules.pack.progress", overview.signedCount(), overview.total()));
            pdf.space(4f);
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label("clinicalRules.pack.intro"));
            pdf.rule();
            int number = 0;
            for (ClinicalRuleStatusDTO rule : overview.rules()) {
                number++;
                pdf.space(6f);
                pdf.paragraph(PharmacyPdfWriter.BOLD, 11f, number + ". " + rule.title().fr()
                    + "  (" + label("clinicalRules.pack.version", rule.version()) + ")");
                pdf.field(label("clinicalRules.pack.description"), rule.description().fr(), 9f);
                pdf.field(label("clinicalRules.pack.source"), rule.source().fr(), 9f);
                pdf.field(label("clinicalRules.pack.approver"), rule.approverRole().fr(), 9f);
                pdf.field(label("clinicalRules.pack.status"), statusText(rule), 9f);
                pdf.space(2f);
                pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label("clinicalRules.pack.decisionLine"));
                pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label("clinicalRules.pack.signerLine"));
                pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label("clinicalRules.pack.signatureLine"));
                pdf.rule();
            }
            pdf.space(10f);
            pdf.paragraph(PharmacyPdfWriter.BOLD, 11f, label("clinicalRules.pack.closingTitle"));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label("clinicalRules.pack.closing"));
            pdf.space(6f);
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label("clinicalRules.pack.signerLine"));
            pdf.paragraph(PharmacyPdfWriter.REGULAR, 9f, label("clinicalRules.pack.signatureLine"));
            return pdf.finish();
        } catch (IOException e) {
            throw new BusinessException("clinicalRules.pack.failed", e);
        }
    }

    private String statusText(ClinicalRuleStatusDTO rule) {
        HospitalRuleSignoffDTO signoff = rule.signoff();
        if (rule.status() == RuleSignoffStatus.SIGNED && signoff != null) {
            return label("clinicalRules.pack.statusSigned", DisplayDates.date(signoff.getSignedOn(), PACK_LOCALE),
                signoff.getSignerTitle(), signoff.getSignerName());
        }
        if (rule.status() == RuleSignoffStatus.REVOKED) {
            return label("clinicalRules.pack.statusRevoked");
        }
        if (rule.lastSignedVersion() != null) {
            return label("clinicalRules.pack.statusPendingNewVersion", rule.lastSignedVersion());
        }
        return label("clinicalRules.pack.statusPending");
    }

    private String label(String key, Object... args) {
        return messageSource.getMessage(key, args, key, PACK_LOCALE);
    }
}
