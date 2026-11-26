package com.example.hms.service.pharmacy;

import com.example.hms.enums.AllergyReviewDecision;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.AllergyVerificationStatus;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.Prescription;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.utility.MessageUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * QA D3865 (r77): a prescription signed over a severe allergy (amoxicilline for a patient with a documented
 * penicillin allergy, with the prescriber's reasoned override) printed as a plain prescription: no allergy, no
 * override. An outside pharmacy dispensing from that paper, or from the partner offer, could not know that the
 * conflict was seen and decided.
 *
 * <p>The one source of what every rendering of a prescription says about allergies, so no path can show one and
 * forget the other ({@code PrescriptionRenderAllergyGuardTest}):
 * <ul>
 *   <li>the patient's known allergies: active, not refuted nor entered in error, as the reader's hospital may see
 *       them (a patient's sharing opt-out keeps other hospitals' rows out, and the document then says so);</li>
 *   <li>for a line prescribed despite an allergy alert, « Allergie signalée — prescrit malgré l'allergie : motif »,
 *       the motif being the prescriber's own reason (the one given at the signature when there is one).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class PrescriptionAllergyNotice {

    static final String KEY_ALLERGIES = "pdf.ordonnance.allergies";
    static final String KEY_NONE_RECORDED = "pdf.ordonnance.allergies.noneRecorded";
    static final String KEY_WITHHELD = "pdf.ordonnance.allergies.withheld";
    static final String KEY_OVERRIDE = "pdf.ordonnance.allergyOverride";
    static final String KEY_SEVERITY_PREFIX = "cds.severity.";
    static final String KEY_SMS_OVERRIDE = "sms.partner.allergyOverride";
    static final String KEY_SMS_ALLERGIES = "sms.partner.allergies";

    private final PatientAllergyRepository allergyRepository;

    /** What the document says about the patient's allergies, read once per document. */
    public record KnownAllergies(List<String> labels, boolean othersWithheld) {
        public KnownAllergies {
            labels = labels == null ? List.of() : List.copyOf(labels);
        }

        public boolean none() {
            return labels.isEmpty();
        }
    }

    /** The patient's known allergies as the prescription's hospital may show them, each with its severity. */
    public KnownAllergies knownAllergies(Prescription rx, Locale locale) {
        UUID patientId = rx == null || rx.getPatient() == null ? null : rx.getPatient().getId();
        UUID hospitalId = rx == null || rx.getHospital() == null ? null : rx.getHospital().getId();
        if (patientId == null || hospitalId == null) {
            return new KnownAllergies(List.of(), false);
        }
        List<String> labels = allergyRepository.findVisibleAt(patientId, hospitalId).stream()
            .filter(PatientAllergy::isActive)
            .filter(a -> a.getVerificationStatus() != AllergyVerificationStatus.REFUTED
                && a.getVerificationStatus() != AllergyVerificationStatus.ENTERED_IN_ERROR)
            .map(a -> label(a, locale))
            .filter(label -> label != null && !label.isBlank())
            .distinct()
            .toList();
        return new KnownAllergies(labels, allergyRepository.hasActiveSharingOptOut(patientId));
    }

    /** « Pénicilline (sévère) »: the allergen, then its severity in the reader's language when recorded. */
    static String label(PatientAllergy allergy, Locale locale) {
        String allergen = firstNonBlank(allergy.getAllergenDisplay(), allergy.getAllergenCode());
        if (allergen == null) {
            return null;
        }
        if (allergy.getSeverity() == null || allergy.getSeverity() == AllergySeverity.UNKNOWN) {
            // An unrecorded severity is left out: « Pénicilline (inconnue) » would read as an unknown allergen.
            return allergen.trim();
        }
        String severity = MessageUtil.resolveOrNull(locale, KEY_SEVERITY_PREFIX + allergy.getSeverity().name());
        return severity == null ? allergen.trim() : allergen.trim() + " (" + severity + ")";
    }

    /** The allergy field's value: the list, or « none recorded »; and a note when other hospitals' rows are held back. */
    public String allergiesValue(KnownAllergies known, Locale locale) {
        String value = known.none() ? MessageUtil.resolve(locale, KEY_NONE_RECORDED) : String.join(", ", known.labels());
        return known.othersWithheld() ? value + " " + MessageUtil.resolve(locale, KEY_WITHHELD) : value;
    }

    /** The label of the allergy field. */
    public static String allergiesLabel(Locale locale) {
        return MessageUtil.resolve(locale, KEY_ALLERGIES);
    }

    /**
     * True when the line was prescribed through an allergy alert: the prescriber's allergy override (creation, edit
     * or signature), or a prescriber's review that decided to continue it.
     */
    public static boolean prescribedDespiteAllergy(Prescription rx) {
        if (rx == null) {
            return false;
        }
        return (rx.isAllergiesReviewed() && !blank(rx.getOverrideReason()))
            || rx.getAllergyReviewDecision() == AllergyReviewDecision.CONTINUE;
    }

    /** The prescriber's reason for prescribing despite the allergy: the review's own when given, else the override's. */
    public static String overrideReason(Prescription rx) {
        if (!prescribedDespiteAllergy(rx)) {
            return null;
        }
        if (rx.getAllergyReviewDecision() == AllergyReviewDecision.CONTINUE && !blank(rx.getAllergyReviewRationale())) {
            return rx.getAllergyReviewRationale().trim();
        }
        return blank(rx.getOverrideReason()) ? null : rx.getOverrideReason().trim();
    }

    /** « Allergie signalée — prescrit malgré l'allergie : motif », or null for a line no allergy alert concerned. */
    public static String overrideLine(Prescription rx, Locale locale) {
        if (!prescribedDespiteAllergy(rx)) {
            return null;
        }
        String reason = overrideReason(rx);
        return MessageUtil.resolve(locale, KEY_OVERRIDE, reason == null ? "-" : reason);
    }

    /**
     * The clause a partner pharmacy's offer carries (a text message, so short): the override with the known
     * allergies when the line was prescribed despite one, else the known allergies, else nothing.
     */
    public String smsClause(Prescription rx, Locale locale) {
        KnownAllergies known = knownAllergies(rx, locale);
        String allergens = known.none() ? "-" : String.join(", ", known.labels());
        if (prescribedDespiteAllergy(rx)) {
            String reason = overrideReason(rx);
            return MessageUtil.resolve(locale, KEY_SMS_OVERRIDE, allergens, reason == null ? "-" : shortened(reason));
        }
        return known.none() ? "" : MessageUtil.resolve(locale, KEY_SMS_ALLERGIES, allergens);
    }

    /** A text message carries the reason's start; the printed ordonnance carries all of it. */
    static final int SMS_REASON_MAX = 120;

    private static String shortened(String reason) {
        return reason.length() <= SMS_REASON_MAX ? reason : reason.substring(0, SMS_REASON_MAX - 3) + "...";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return !blank(first) ? first : (!blank(second) ? second : null);
    }
}
