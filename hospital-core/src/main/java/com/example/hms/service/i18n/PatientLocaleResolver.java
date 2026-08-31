package com.example.hms.service.i18n;

import com.example.hms.enums.PatientLanguage;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientSocialHistory;
import com.example.hms.repository.SocialHistoryRepository;

import java.util.Locale;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Which language to write to a patient in.
 *
 * <p><b>The gap this closes.</b> Every outbound message — appointment
 * reminders, recall notices, waitlist offers — rendered in one system-wide
 * locale set by configuration, French by default. The patient's own stated
 * language had been captured on the medical-history tab since V1 and no
 * dispatch path had ever read it. A patient who told the desk they read
 * English was sent French anyway, and nothing anywhere reported that.
 *
 * <p><b>Falling back is not failing.</b> Only French, English and Spanish have
 * message bundles. A patient who answers Moore gets the hospital's configured
 * default, because the alternative is sending nothing — and a message in the
 * wrong language still carries the date, the time and the hospital name, which
 * is most of what a reminder is for. {@link PatientLanguage#hasMessageBundle()}
 * is what marks the difference, so the day a Moore bundle exists this starts
 * using it with no change here.
 *
 * <p>The caller supplies its own fallback rather than this class owning one.
 * Each sweep already had a configured locale and callers differ in which
 * property they read; taking it as an argument means this changes what happens
 * for patients who stated a language and nothing at all for those who did not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PatientLocaleResolver {

    private final SocialHistoryRepository socialHistoryRepository;

    /**
     * @param patient  may be null — callers are best-effort notification paths
     * @param fallback the locale to use when the patient has stated no usable
     *                 preference; never null
     */
    @Transactional(readOnly = true)
    public Locale resolve(Patient patient, Locale fallback) {
        return preferredLanguage(patient)
            .filter(PatientLanguage::hasMessageBundle)
            .map(PatientLanguage::toLocale)
            .orElse(fallback);
    }

    /**
     * The patient's stated language, whether or not it can be delivered.
     *
     * <p>Exposed separately from {@link #resolve} so a report can count the
     * patients asking for a language nothing can yet send — the only evidence
     * that would justify commissioning a bundle.
     */
    @Transactional(readOnly = true)
    public Optional<PatientLanguage> preferredLanguage(Patient patient) {
        if (patient == null || patient.getId() == null) {
            return Optional.empty();
        }
        Optional<PatientLanguage> stated = socialHistoryRepository
            .findFirstByPatient_IdAndActiveTrueOrderByRecordedDateDesc(patient.getId())
            .map(PatientSocialHistory::getPreferredLanguage)
            .flatMap(PatientLanguage::fromFreeText);

        stated.filter(language -> !language.hasMessageBundle()).ifPresent(language ->
            log.debug("[PATIENT-LANG] Patient {} asked for {} — no message bundle exists, "
                + "falling back", patient.getId(), language));

        return stated;
    }
}
