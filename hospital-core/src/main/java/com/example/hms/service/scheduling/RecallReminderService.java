package com.example.hms.service.scheduling;

import com.example.hms.enums.RecallStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.scheduling.PatientRecall;
import com.example.hms.repository.scheduling.PatientRecallRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import com.example.hms.service.i18n.PatientLocaleResolver;

/**
 * Recall notice sweep (P3 #22), the V112 reminder idiom: select PENDING
 * recalls coming due within the lead window that carry no {@code notifiedAt}
 * stamp, push in-app + SMS through {@link PatientOutreachNotifier}, then
 * stamp exactly once — even when every channel was skipped — so the sweep
 * converges and never double-texts. Runs as a system actor across hospitals.
 */
@Slf4j
@Service
public class RecallReminderService {

    private final PatientRecallRepository recallRepository;
    private final PatientOutreachNotifier outreachNotifier;
    private final MessageSource messageSource;
    private final PatientLocaleResolver patientLocaleResolver;

    /** Days before the due date at which the notice goes out. */
    @Value("${hms.recalls.notice.lead-days:14}")
    private long leadDays;

    /** A sweep has no request locale — explicit config, French by default. */
    @Value("${hms.scheduling.outreach.locale:fr}")
    private String outreachLocale;

    public RecallReminderService(PatientRecallRepository recallRepository,
                                 PatientOutreachNotifier outreachNotifier,
                                 MessageSource messageSource,
                                 PatientLocaleResolver patientLocaleResolver) {
        this.recallRepository = recallRepository;
        this.outreachNotifier = outreachNotifier;
        this.messageSource = messageSource;
        this.patientLocaleResolver = patientLocaleResolver;
    }

    /** @return number of recalls for which at least one channel dispatched */
    @Transactional
    public int sendDueRecallNotices() {
        List<PatientRecall> due =
            recallRepository.findAwaitingNotification(LocalDate.now().plusDays(leadDays));
        int notified = 0;
        for (PatientRecall recall : due) {
            try {
                if (notifyRecall(recall)) {
                    notified++;
                }
                // Stamp even when both channels were skipped, so the sweep
                // converges instead of re-evaluating the same row forever.
                recall.setNotifiedAt(LocalDateTime.now());
                recall.setStatus(RecallStatus.NOTIFIED);
                recallRepository.save(recall);
            } catch (RuntimeException ex) {
                log.warn("Recall notice failed for {}: {}", recall.getId(), ex.getMessage(), ex);
            }
        }
        return notified;
    }

    private boolean notifyRecall(PatientRecall recall) {
        Hospital hospital = recall.getHospital();
        String hospitalName = hospital != null && hospital.getName() != null ? hospital.getName() : "";
        // Deliberately no clinical reason in the body: SMS is an untrusted
        // channel, so the notice says only that a visit is due and where.
        // Configured locale is the fallback; the patient's stated language
        // wins when we have a bundle for it.
        Locale locale = patientLocaleResolver.resolve(
            recall.getPatient(), Locale.forLanguageTag(outreachLocale));
        String message = messageSource.getMessage("sms.recall.due",
            new Object[]{hospitalName}, locale);
        return outreachNotifier.notifyPatient(recall.getPatient(), message);
    }
}
