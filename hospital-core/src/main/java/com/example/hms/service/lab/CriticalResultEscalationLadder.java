package com.example.hms.service.lab;

import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * QA r70, scorecard C5. PROVISIONAL rule set CRITICAL_RESULT_ESCALATION, from the research report « Block
 * routine blood, escalate critical results » (reports/Transfusion and critical result cover.md, section 2). It is
 * « provisoire » until the hospital's médecin-chef and biologist sign it off: no standard fixes the minutes.
 *
 * <p>What it replaces: an unacknowledged critical result was re-sent to the SAME prescriber every 30 minutes,
 * hospital administrators were copied with the value, and the chain stopped after 12 rounds with no final
 * alert. ISO 15189:2022 §7.4.1.3 asks for « an escalation procedure … when a responsible person cannot be
 * contacted »; WHO LQSI: the laboratory « keeps trying … until contact has been made »; RCPath G158 names the
 * person who received the result; the Joint Commission (NPSG.02.03.01) asks for the acceptable length of time;
 * no source puts a non-clinical administrator in the chain.
 *
 * <p>The ladder, in minutes after the first notice (T0), each configurable per hospital:
 * <ol>
 *   <li><b>T0</b>: the prescriber and the nurse in charge. A prescriber marked absent is skipped: the cover is
 *       told at once.</li>
 *   <li><b>+{@value #DEFAULT_COVER_MINUTES}</b>: the covering doctor (the on-call roster, else the doctors on
 *       duty, else every doctor of the hospital); the prescriber stays copied.</li>
 *   <li><b>+{@value #DEFAULT_LEAD_MINUTES}</b>: the médecin-chef and the responsible biologist are added.</li>
 *   <li><b>+{@value #DEFAULT_UNRESOLVED_MINUTES}</b>: « résultat critique non résolu »: a final alert to the whole
 *       chain and an incident record; the administrators get a flag that carries no value.</li>
 *   <li><b>after that</b>: a reminder to the chain at the last interval, for as long as it is unanswered. Nothing
 *       but a clinician's acknowledgement (name, role, time, channel) ends it: there is no round cap.</li>
 * </ol>
 * The hospital examples the report cites hand over within 10 to 45 minutes (Guarner et al., AJCP 2025; Asante;
 * ChristianaCare), and SFBC 2016 targets 60 minutes for urgent results.
 */
@ProvisionalRule(ClinicalRuleSetKey.CRITICAL_RESULT_ESCALATION)
public final class CriticalResultEscalationLadder {

    public static final int DEFAULT_COVER_MINUTES = 15;
    public static final int DEFAULT_LEAD_MINUTES = 30;
    public static final int DEFAULT_UNRESOLVED_MINUTES = 60;
    /** A step is never set under this (a sweep runs every five minutes) nor over a day. */
    public static final int MIN_MINUTES = 5;
    public static final int MAX_MINUTES = 24 * 60;

    private CriticalResultEscalationLadder() {
    }

    /** The steps after the first notice, in the order they are taken. The ordinal + 1 is the level reached. */
    public enum Step { COVER, MEDICAL_LEAD, UNRESOLVED, REMINDER }

    /** A hospital's steps, in minutes after the first notice; strictly increasing. */
    public record Minutes(int cover, int lead, int unresolved) {

        public static final Minutes DEFAULT =
            new Minutes(DEFAULT_COVER_MINUTES, DEFAULT_LEAD_MINUTES, DEFAULT_UNRESOLVED_MINUTES);

        /** The hospital's own values where it set them, the defaults elsewhere; an inconsistent set falls back whole. */
        public static Minutes of(Integer cover, Integer lead, Integer unresolved) {
            Minutes minutes = new Minutes(cover == null ? DEFAULT_COVER_MINUTES : cover,
                lead == null ? DEFAULT_LEAD_MINUTES : lead,
                unresolved == null ? DEFAULT_UNRESOLVED_MINUTES : unresolved);
            return minutes.valid() ? minutes : DEFAULT;
        }

        public boolean valid() {
            return cover >= MIN_MINUTES && cover < lead && lead < unresolved && unresolved <= MAX_MINUTES;
        }

        /** How long after the final alert each reminder comes: the last interval of the ladder. */
        public int reminderEvery() {
            return Math.max(MIN_MINUTES, unresolved - lead);
        }
    }

    /**
     * The next step that is due now, or empty. Asked again after each step taken: a sweep that was down for an
     * hour takes the steps it missed one after the other.
     *
     * @param level           the steps already taken (0 after the first notice)
     * @param notifiedAt      T0
     * @param lastEscalatedAt when the last step was taken (null before any)
     */
    public static Optional<Step> due(int level, LocalDateTime notifiedAt, LocalDateTime lastEscalatedAt,
                                     Minutes minutes, LocalDateTime now) {
        if (notifiedAt == null || now == null) {
            return Optional.empty();
        }
        long elapsed = Duration.between(notifiedAt, now).toMinutes();
        if (level <= 0) {
            return elapsed >= minutes.cover() ? Optional.of(Step.COVER) : Optional.empty();
        }
        if (level == 1) {
            return elapsed >= minutes.lead() ? Optional.of(Step.MEDICAL_LEAD) : Optional.empty();
        }
        if (level == 2) {
            return elapsed >= minutes.unresolved() ? Optional.of(Step.UNRESOLVED) : Optional.empty();
        }
        // After the final alert: never a stop, a reminder at the last interval.
        LocalDateTime last = lastEscalatedAt == null ? notifiedAt.plusMinutes(minutes.unresolved()) : lastEscalatedAt;
        return Duration.between(last, now).toMinutes() >= minutes.reminderEvery()
            ? Optional.of(Step.REMINDER) : Optional.empty();
    }
}
