package com.example.hms.service.vitals;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.vitals.MaternalEarlyWarning.Score;
import com.example.hms.service.vitals.MaternalEarlyWarning.Tier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Policy 2026-11-20 (PROVISIONAL, pending a named obstetric clinician's sign-off): the maternal early
 * warning of a vital set, for whoever it applies to, and its escalation.
 *
 * <p><b>Who</b>: a woman the shared rule finds pregnant or within 42 days of a birth
 * ({@link News2Applicability}, which reads {@link SevereHypertension}'s scope: the record-access
 * policy decides which hospitals' obstetric records count). Nobody else gets a maternal score, and
 * for her NEWS2 is neither shown nor paged: this escalation replaces the NEWS2 page.
 *
 * <p><b>Paging</b> (one notification per person and per reading; the mapping of the chart's UK grades
 * to local roles is documented on {@link MaternalEarlyWarningRules.Escalation} and is provisional):
 * <ul>
 *   <li>low (0–1, no concern): nobody;</li>
 *   <li>low-medium (2–4): the recorder, the stay's responsible physician and the visit's clinician
 *       (the circle NEWS2 paged), the midwife in charge and the doctor; review within 30 minutes;</li>
 *   <li>medium (5–7): the same, and the senior doctor (the head of her department and the doctors on
 *       call; every doctor only when nobody is on the rota); review within 15 minutes;</li>
 *   <li>high (8 or more, or a RED add-on alert): the same as medium; immediate review.</li>
 * </ul>
 * A person another notice already reached for this very reading (the severe blood-pressure notice,
 * which carries the score and the level) is not paged a second time. One page per woman and per level
 * in 30 minutes (provisional): a level she was not paged at in that time always goes through at once.
 * It runs in the caller's transaction, like the NEWS2 escalation: if the vital set commits, so do its
 * notifications and the mark the cool-down reads; a notice that cannot be created never fails the set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MaternalEarlyWarningService {

    public static final String NOTIFICATION_TYPE = "MEWS_ESCALATION";

    static final String KEY_LOW_MEDIUM = "mews.escalation.lowMedium";
    static final String KEY_MEDIUM = "mews.escalation.medium";
    static final String KEY_HIGH = "mews.escalation.high";
    static final String KEY_RED = "mews.escalation.red";
    /** The label of a CRADLE add-on alert, rendered in each reader's language. */
    public static final String ALERT_LABEL_PREFIX = "mews.alert.";

    private final MaternalEarlyWarningRules rules;
    private final News2Applicability applicability;
    private final News2EscalationService news2EscalationService;
    private final StaffRepository staffRepository;
    private final OnCallScheduleRepository onCallScheduleRepository;
    private final AdmissionRepository admissionRepository;
    private final NotificationService notificationService;
    private final Clock clock;

    public MaternalEarlyWarningRules rules() {
        return rules;
    }

    public boolean enabled() {
        return rules.isEnabled();
    }

    /**
     * The maternal warning of {@code vital} for {@code patient}, read at {@code hospitalId}; null when
     * she is not under the score at that reading (or the score is switched off). Queries the obstetric
     * record once.
     */
    public Score assess(Patient patient, UUID hospitalId, PatientVitalSign vital) {
        return assess(patient, hospitalId, vital, false);
    }

    /**
     * {@link #assess} for a write. With {@code accountForeignRecord}, a warning that is raised (a review
     * is due, or a CRADLE alert is lit) on another hospital's obstetric record is accounted in the reach
     * ledger; the caller passes false when the pressure warning of the same reading already accounted it.
     */
    public Score assess(Patient patient, UUID hospitalId, PatientVitalSign vital, boolean accountForeignRecord) {
        if (!rules.isEnabled() || patient == null || vital == null) {
            return null;
        }
        LocalDateTime at = vital.getRecordedAt() != null ? vital.getRecordedAt()
            : LocalDateTime.now(ZoneId.systemDefault());
        News2Applicability.Applicability known = applicability.applicabilityOf(patient, hospitalId, at);
        Score score = scoreFor(known, vital);
        if (accountForeignRecord && score != null && (score.needsEscalation() || !score.alerts().isEmpty())) {
            applicability.accountForeignRecord(patient.getId(), hospitalId, known);
        }
        return score;
    }

    /** The warning once the applicability is known (a chart read asks it once per day, not per row). */
    public Score scoreFor(News2Applicability.Applicability known, PatientVitalSign vital) {
        if (!rules.isEnabled() || known == null || vital == null
            || known.status() != News2Applicability.Status.OBSTETRIC) {
            return null;
        }
        LocalDateTime at = vital.getRecordedAt() != null ? vital.getRecordedAt()
            : LocalDateTime.now(ZoneId.systemDefault());
        return MaternalEarlyWarning.score(rules, vital,
            MaternalEarlyWarning.postBirthPulse(rules, known.birthAt(), at));
    }

    /**
     * The score as the chart reads it. A set with nothing to score (a weight alone) is left without one,
     * and the chart then says « NEWS2 non validé » as before.
     */
    public boolean describe(Score score, com.example.hms.payload.dto.PatientVitalSignResponseDTO dto) {
        if (score == null || dto == null || score.empty()) {
            return false;
        }
        dto.setMewsScore(score.total());
        dto.setMewsTier(score.responseTier().name());
        dto.setMewsComplete(score.complete());
        dto.setMewsMissing(score.missing().stream().map(Enum::name).toList());
        dto.setMewsConcerns(score.concerns().stream().map(Enum::name).toList());
        dto.setMewsAlerts(score.alerts().stream().map(Enum::name).toList());
        dto.setMewsReviewWithinMinutes(MaternalEarlyWarning.reviewWithinMinutes(rules, score.responseTier()));
        dto.setMewsPostBirthPulse(score.postBirthPulse());
        return true;
    }

    /** Policy 2026-11-20: the vital sets, read for the page cool-down (setter-injected; absent, no cool-down). */
    private com.example.hms.repository.PatientVitalSignRepository vitalSignRepository;

    @org.springframework.beans.factory.annotation.Autowired
    void setVitalSignRepository(com.example.hms.repository.PatientVitalSignRepository vitalSignRepository) {
        this.vitalSignRepository = vitalSignRepository;
    }

    /**
     * Pages the level's circle, except anyone in {@code alreadyPaged} (reached by another notice for
     * this reading: the severe-pressure notice, which carries the score and the level). Returns who was
     * paged. Nobody at the low level, and nobody when she was already paged at this very level within the
     * cool-down (30 minutes; a level she was not paged at, a higher one above all, goes through at once).
     * The reading that pages is marked with its level and the time, which is what the cool-down reads.
     */
    public Set<String> escalate(PatientVitalSign saved, Score score, UUID patientId, UUID hospitalId,
                                String recorderUsername, String visitPhysicianUsername,
                                Collection<String> alreadyPaged) {
        Set<String> paged = new LinkedHashSet<>();
        if (score == null || !score.needsEscalation() || patientId == null || hospitalId == null
            || pagedRecently(patientId, hospitalId, score.responseTier())) {
            return paged;
        }
        Set<String> skip = lowerCased(alreadyPaged);
        boolean toldByAnotherNotice = !skip.isEmpty();
        String patientName = saved != null && saved.getPatient() != null ? saved.getPatient().getFullName() : null;
        Object patientArg = patientName == null || patientName.isBlank()
            ? NotificationLocalizer.key("patient.fallback.generic") : patientName;
        String key = keyOf(score);
        Object[] args = argsOf(score, patientArg);
        for (String recipient : recipients(score.responseTier(), patientId, hospitalId, recorderUsername,
            visitPhysicianUsername)) {
            if (!skip.add(recipient.toLowerCase(Locale.ROOT))) {
                continue;
            }
            try {
                notificationService.createNotification(key, args, recipient, NOTIFICATION_TYPE);
                paged.add(recipient);
            } catch (RuntimeException ex) {
                log.warn("Maternal early-warning notice not created: {}", ex.getClass().getSimpleName());
            }
        }
        if (saved != null && (toldByAnotherNotice || !paged.isEmpty())) {
            saved.setMewsPagedTier(score.responseTier().name());
            saved.setMewsPagedAt(LocalDateTime.now(clock));
        }
        return paged;
    }

    /** She was paged at this level, at this hospital, within the cool-down. Never without a repository. */
    private boolean pagedRecently(UUID patientId, UUID hospitalId, Tier tier) {
        int minutes = rules.getEscalation().getPageCoolDownMinutes();
        if (vitalSignRepository == null || minutes <= 0) {
            return false;
        }
        return vitalSignRepository.existsByPatient_IdAndHospital_IdAndMewsPagedTierAndMewsPagedAtGreaterThanEqual(
            patientId, hospitalId, tier.name(), LocalDateTime.now(clock).minusMinutes(minutes));
    }

    private static Set<String> lowerCased(Collection<String> usernames) {
        Set<String> lower = new LinkedHashSet<>();
        if (usernames != null) {
            usernames.stream().filter(java.util.Objects::nonNull).forEach(u -> lower.add(u.toLowerCase(Locale.ROOT)));
        }
        return lower;
    }

    private static String keyOf(Score score) {
        if (score.red()) {
            return KEY_RED;
        }
        return switch (score.responseTier()) {
            case HIGH -> KEY_HIGH;
            case MEDIUM -> KEY_MEDIUM;
            default -> KEY_LOW_MEDIUM;
        };
    }

    /** {0} the score, {1} the patient, {2} review within (min), {3} observations every (min), {4} the red alert. */
    private Object[] argsOf(Score score, Object patientArg) {
        Integer review = MaternalEarlyWarning.reviewWithinMinutes(rules, score.responseTier());
        Object red = score.alerts().stream().filter(a -> a.light() == MaternalEarlyWarning.Light.RED).findFirst()
            .<Object>map(a -> NotificationLocalizer.key(ALERT_LABEL_PREFIX + a.name())).orElse("");
        return new Object[]{score.total(), patientArg, review == null ? 0 : review,
            MaternalEarlyWarning.observationInterval(rules, score, java.time.Duration.ofHours(12)).toMinutes(), red};
    }

    /** Who the level pages, in the order they are told. */
    Set<String> recipients(Tier tier, UUID patientId, UUID hospitalId, String recorderUsername,
                           String visitPhysicianUsername) {
        Set<String> recipients = new LinkedHashSet<>();
        if (tier == null || tier == Tier.LOW) {
            return recipients;
        }
        MaternalEarlyWarningRules.Escalation mapping = rules.getEscalation();
        // The circle the NEWS2 page had: the recorder, the stay's responsible physician, the visit's clinician.
        recipients.addAll(news2EscalationService.recipients(patientId, hospitalId, recorderUsername,
            visitPhysicianUsername));
        List<String> onCall = onCallUsernames(hospitalId);
        recipients.addAll(onCallElseAll(hospitalId, mapping.getMidwifeInChargeRoles(), onCall));
        recipients.addAll(onCallElseAll(hospitalId, mapping.getDoctorRoles(), onCall));
        if (tier == Tier.MEDIUM || tier == Tier.HIGH) {
            departmentHeadUsername(patientId, hospitalId).ifPresent(recipients::add);
            // The rota when there is one: every doctor of the hospital only when nobody is on call.
            recipients.addAll(onCallElseAll(hospitalId, mapping.getSeniorDoctorRoles(), onCall));
        }
        return recipients;
    }

    /** The holders of the roles who are on call now; every holder when none of them is. */
    private Set<String> onCallElseAll(UUID hospitalId, List<String> roles, List<String> onCall) {
        Set<String> holders = holders(hospitalId, roles);
        Set<String> lower = new LinkedHashSet<>();
        holders.forEach(u -> lower.add(u.toLowerCase(Locale.ROOT)));
        Set<String> present = new LinkedHashSet<>();
        for (String username : onCall) {
            if (lower.contains(username.toLowerCase(Locale.ROOT))) {
                present.add(username);
            }
        }
        return present.isEmpty() ? holders : present;
    }

    /** The active staff of this hospital (never another's) who hold one of the roles. */
    private Set<String> holders(UUID hospitalId, List<String> roles) {
        Set<String> holders = new LinkedHashSet<>();
        if (roles != null) {
            for (String role : roles) {
                holders.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role));
            }
        }
        return holders;
    }

    private List<String> onCallUsernames(UUID hospitalId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> usernames = new java.util.ArrayList<>();
        for (OnCallSchedule shift : onCallScheduleRepository.findByHospitalAndWindow(hospitalId, now, now)) {
            Staff staff = shift.getStaff();
            User user = staff == null ? null : staff.getUser();
            if (staff != null && staff.isActive() && user != null && user.getUsername() != null
                && user.isActive() && !user.isDeleted()) {
                usernames.add(user.getUsername());
            }
        }
        return usernames;
    }

    /** The head of the department of her open stay at this hospital, when one is named. */
    private java.util.Optional<String> departmentHeadUsername(UUID patientId, UUID hospitalId) {
        return admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(patientId, hospitalId,
                AdmissionStatus.OPEN).stream()
            .max(Comparator.comparing(Admission::getAdmissionDateTime,
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .map(Admission::getDepartment)
            .map(com.example.hms.model.Department::getHeadOfDepartment)
            .filter(head -> head.isActive() && head.getUser() != null && head.getUser().getUsername() != null
                && head.getUser().isActive() && !head.getUser().isDeleted())
            .map(head -> head.getUser().getUsername());
    }
}
