package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.Prescription;
import com.example.hms.service.impl.MarDoseSchedule;
import com.example.hms.service.impl.PrnMinInterval;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Adult paracetamol limits (user-supplied and user-verified 2026-11-07, proposed alert logic
 * for clinician sign-off; PediatricDoseRule keeps every patient under its age threshold).
 *
 * <p>Sources:
 * <ul>
 *   <li>US FDA guidance on acetaminophen, "Acetaminophen Is in Many Products": 4,000 mg in 24
 *       hours is the adult maximum, from all medicines combined;</li>
 *   <li>EFFERALGANSTIX 1000 mg, RCP 4.2 and 4.3 (Base de données publique des médicaments,
 *       France): 3 g/day usual dose from 50 kg; up to 4 g/day only on medical advice, without
 *       additional hepatotoxicity risk factors; 1 g per administration; at least 4 hours apart;
 *       under 50 kg, chronic malnutrition or dehydration 60 mg/kg/day or 3,000 mg/day, whichever
 *       is lower; mild to moderate hepatocellular insufficiency, Gilbert syndrome or chronic
 *       alcoholism 2,000 mg/day, with at least 8 hours between doses in chronic alcoholism;
 *       contraindicated in severe hepatocellular insufficiency.</li>
 * </ul>
 *
 * <p>Every card is an alert: {@code warning} or {@code info}, never {@code critical}, so the
 * order is saved and the prescriber reads the card on the form ({@link #ALERT} is the one place
 * to change that). Nothing here adds a hard stop. A dose that cannot be read in mg is silent,
 * never read as mg; the checks of the interval and of the contraindication do not need a dose.
 */
@Component
public class AdultParacetamolRule implements CdsRule {

    static final double MAX_MG_PER_ADMINISTRATION = 1000d;
    static final double MAX_MG_PER_DAY = 4000d;
    static final double USUAL_MG_PER_DAY = 3000d;
    static final int MIN_MINUTES_BETWEEN_DOSES = 240;
    static final double LOW_WEIGHT_UNDER_KG = 50d;
    static final double LOW_RESERVE_MG_PER_KG_PER_DAY = 60d;
    static final double LOW_RESERVE_MG_PER_DAY = 3000d;
    static final double TWO_GRAM_GROUP_MG_PER_DAY = 2000d;
    static final int ALCOHOLISM_MIN_MINUTES_BETWEEN_DOSES = 480;

    private static final String ID = "adult-paracetamol";
    private static final String SOURCE_LABEL = "e-Keneya Adult Paracetamol Check";
    private static final String SOURCES = "FDA acetaminophen guidance; EFFERALGANSTIX 1000 mg RCP 4.2, 4.3";
    private static final CdsCard.Indicator ALERT = CdsCard.Indicator.WARNING;
    private static final double MINUTES_PER_DAY = 1440d;
    private static final double MINUTES_PER_HOUR = 60d;

    /** Card wording in the caller's language (D61); English when built outside Spring. */
    private com.example.hms.cdshooks.CdsCardText text = com.example.hms.cdshooks.CdsCardText.ENGLISH;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCardText(com.example.hms.cdshooks.CdsCardText text) {
        this.text = text;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<CdsCard> evaluate(CdsRuleContext context) {
        if (context.patient() == null || !PediatricDoseRule.isAdult(context.patient())
            || !context.paracetamolFacts().applies()) {
            return List.of();
        }
        Check check = new Check(context);
        List<CdsCard> cards = new ArrayList<>();
        check.contraindication(cards);
        check.singleDose(cards);
        check.dailyTotal(cards);
        check.interval(cards);
        return cards;
    }

    /** One evaluation's facts, so each check reads them the same way. */
    private final class Check {
        private final CdsRuleContext context;
        private final Set<ParacetamolRisk> risks;
        private final String medication;
        private final Double doseMg;
        private final Double dosesPerDay;

        Check(CdsRuleContext context) {
            this.context = context;
            this.risks = context.paracetamolFacts().risks();
            this.medication = context.proposedMedicationName() == null ? "" : context.proposedMedicationName().trim();
            this.doseMg = context.proposedDoseMg();
            this.dosesPerDay = PediatricDoseRule.dosesOnADosingDay(
                context.proposedFrequency(), context.proposedPrnDosesPerDay());
        }

        void contraindication(List<CdsCard> cards) {
            if (risks.contains(ParacetamolRisk.SEVERE_HEPATIC)) {
                cards.add(alert(text.get("cds.adultParacetamol.contraindication.summary",
                    "{0}: paracetamol is contraindicated in severe hepatocellular insufficiency, recorded on the problem list",
                    medication), footer()));
            }
        }

        void singleDose(List<CdsCard> cards) {
            if (doseMg != null && doseMg > MAX_MG_PER_ADMINISTRATION) {
                cards.add(alert(text.get("cds.adultParacetamol.single.summary",
                    "{0}: {1} mg per dose is above the adult maximum of {2} mg per administration",
                    medication, number(doseMg), number(MAX_MG_PER_ADMINISTRATION)), footer()));
            }
        }

        void dailyTotal(List<CdsCard> cards) {
            if (doseMg == null) {
                return;
            }
            double ceiling = ceilingMg();
            Totals others = otherOrders();
            double total = doseMg * (dosesPerDay == null ? 1d : dosesPerDay) + others.mg;
            if (total > ceiling) {
                cards.add(alert(text.get("cds.adultParacetamol.daily.summary",
                    "{0}: {1} mg per day of paracetamol from all active orders is above the adult maximum of {2} mg per day",
                    medication, number(total), number(ceiling)), dailyDetail(ceiling, others)));
            } else if (dosesPerDay == null && frequencyWrittenButUnread()) {
                cards.add(alert(text.get("cds.adultParacetamol.unread.summary",
                    "{0}: the daily total could not be read from the frequency \"{1}\" - check paracetamol from all active orders against the maximum of {2} mg per day",
                    medication, context.proposedFrequency(), number(ceiling)), dailyDetail(ceiling, others)));
            } else if (ceiling == MAX_MG_PER_DAY && total > USUAL_MG_PER_DAY) {
                cards.add(build(text.get("cds.adultParacetamol.info.summary",
                    "{0}: {1} mg per day of paracetamol from all active orders. The usual adult dose is {2} mg per day; up to {3} mg per day only on medical advice, without additional hepatotoxicity risk factors",
                    medication, number(total), number(USUAL_MG_PER_DAY), number(MAX_MG_PER_DAY)),
                    dailyDetail(ceiling, others), CdsCard.Indicator.INFO));
            }
        }

        void interval(List<CdsCard> cards) {
            Integer minutes = shortestMinutesBetweenDoses();
            if (minutes == null) {
                return;
            }
            boolean alcoholism = risks.contains(ParacetamolRisk.CHRONIC_ALCOHOLISM);
            int least = alcoholism ? ALCOHOLISM_MIN_MINUTES_BETWEEN_DOSES : MIN_MINUTES_BETWEEN_DOSES;
            if (minutes >= least) {
                return;
            }
            String summary = alcoholism
                ? text.get("cds.adultParacetamol.interval.alcoholism.summary",
                    "{0}: {1} hours between doses is shorter than the {2} hours required in chronic alcoholism, recorded on the problem list",
                    medication, hours(minutes), hours(least))
                : text.get("cds.adultParacetamol.interval.summary",
                    "{0}: {1} hours between doses is shorter than the adult minimum of {2} hours",
                    medication, hours(minutes), hours(least));
            cards.add(alert(summary, alcoholism ? footer() : lowerLimits() + " " + footer()));
        }

        /** The lowest ceiling of every group that applies. */
        private double ceilingMg() {
            double ceiling = MAX_MG_PER_DAY;
            Double weight = weightKg();
            if (weight != null && weight < LOW_WEIGHT_UNDER_KG) {
                ceiling = Math.min(ceiling, lowReserveCeiling(weight));
            }
            if (risks.contains(ParacetamolRisk.MALNUTRITION_OR_DEHYDRATION)) {
                ceiling = Math.min(ceiling, lowReserveCeiling(weight));
            }
            if (risks.contains(ParacetamolRisk.TWO_GRAM_GROUP)) {
                ceiling = Math.min(ceiling, TWO_GRAM_GROUP_MG_PER_DAY);
            }
            return ceiling;
        }

        private double lowReserveCeiling(Double weight) {
            return weight == null ? LOW_RESERVE_MG_PER_DAY
                : Math.min(LOW_RESERVE_MG_PER_KG_PER_DAY * weight, LOW_RESERVE_MG_PER_DAY);
        }

        private Double weightKg() {
            Double weight = context.patientWeightKg();
            return weight == null || weight <= 0d ? null : weight;
        }

        /** Paracetamol from the patient's other running orders; one whose dose is unknown is left out and told. */
        private Totals otherOrders() {
            Totals totals = new Totals();
            for (ParacetamolFacts.OtherOrder other : context.paracetamolFacts().otherOrders()) {
                Prescription rx = other.order();
                Double mg = CdsRuleEngine.parseDoseMg(rx.getDosage(), weightKg(), other.item());
                if (mg == null) {
                    totals.uncounted++;
                    continue;
                }
                Double perDay = PediatricDoseRule.dosesOnADosingDay(rx.getFrequency(),
                    PrnMinInterval.highestDosesPerDay(PrnMinInterval.isAsNeeded(rx), rx.getPrnMinIntervalHours()));
                totals.mg += mg * (perDay == null ? 1d : perDay);
                totals.counted++;
            }
            return totals;
        }

        private boolean frequencyWrittenButUnread() {
            MarDoseSchedule.Kind kind = MarDoseSchedule.read(context.proposedFrequency()).kind();
            return kind == MarDoseSchedule.Kind.UNREAD || kind == MarDoseSchedule.Kind.CONTINUOUS;
        }

        /** The shortest time between two doses the order allows, or {@code null} when none is written. */
        private Integer shortestMinutesBetweenDoses() {
            Integer shortest = null;
            Double prnPerDay = context.proposedPrnDosesPerDay();
            if (prnPerDay != null && prnPerDay > 0d) {
                shortest = (int) Math.round(MINUTES_PER_DAY / prnPerDay);
            }
            Integer written = writtenMinutes(MarDoseSchedule.read(context.proposedFrequency()));
            if (written != null && (shortest == null || written < shortest)) {
                shortest = written;
            }
            return shortest;
        }

        private Integer writtenMinutes(MarDoseSchedule.Reading reading) {
            if (reading.repeating()) {
                return reading.intervalMinutes();
            }
            if (reading.kind() != MarDoseSchedule.Kind.COURSE) {
                return null;
            }
            Integer shortest = null;
            List<Integer> offsets = reading.courseOffsets();
            for (int i = 1; i < offsets.size(); i++) {
                int gap = offsets.get(i) - offsets.get(i - 1);
                if (shortest == null || gap < shortest) {
                    shortest = gap;
                }
            }
            return shortest;
        }

        private String dailyDetail(double ceiling, Totals others) {
            List<String> sentences = new ArrayList<>(basis(ceiling));
            if (others.counted > 0) {
                sentences.add(text.get("cds.adultParacetamol.detail.counted",
                    "The total counts {0} other active order(s).", String.valueOf(others.counted)));
            }
            if (others.uncounted > 0) {
                sentences.add(text.get("cds.adultParacetamol.detail.uncounted",
                    "{0} other active order(s) could not be counted: the dose could not be read in mg.",
                    String.valueOf(others.uncounted)));
            }
            sentences.add(footer());
            return String.join(" ", sentences);
        }

        /** Why this ceiling: every lower limit that applies, else the general one and the groups. */
        private List<String> basis(double ceiling) {
            List<String> basis = new ArrayList<>();
            if (ceiling == MAX_MG_PER_DAY) {
                basis.add(text.get("cds.adultParacetamol.basis.general",
                    "General adult ceiling: {0} mg in 24 hours, from all medicines combined.", number(MAX_MG_PER_DAY)));
            }
            Double weight = weightKg();
            if (weight == null) {
                basis.add(text.get("cds.adultParacetamol.basis.noWeight",
                    "No weight on file: a lower limit applies under {0} kg.", number(LOW_WEIGHT_UNDER_KG)));
            } else if (weight < LOW_WEIGHT_UNDER_KG) {
                basis.add(text.get("cds.adultParacetamol.basis.lowWeight",
                    "Lower limit: the weight on file is {0} kg, under {1} kg: {2} mg/kg per day or {3} mg per day, whichever is lower.",
                    number(weight), number(LOW_WEIGHT_UNDER_KG), number(LOW_RESERVE_MG_PER_KG_PER_DAY),
                    number(LOW_RESERVE_MG_PER_DAY)));
                staleWeight().ifPresent(basis::add);
            }
            if (risks.contains(ParacetamolRisk.MALNUTRITION_OR_DEHYDRATION)) {
                basis.add(text.get("cds.adultParacetamol.basis.lowReserve",
                    "Lower limit: malnutrition or dehydration on the problem list: {0} mg/kg per day or {1} mg per day, whichever is lower.",
                    number(LOW_RESERVE_MG_PER_KG_PER_DAY), number(LOW_RESERVE_MG_PER_DAY)));
            }
            if (risks.contains(ParacetamolRisk.TWO_GRAM_GROUP)) {
                basis.add(text.get("cds.adultParacetamol.basis.twoGram",
                    "Lower limit: hepatocellular insufficiency, Gilbert syndrome or chronic alcoholism on the problem list: {0} mg per day.",
                    number(TWO_GRAM_GROUP_MG_PER_DAY)));
            }
            if (risks.contains(ParacetamolRisk.HEPATIC_DEGREE_UNRECORDED)) {
                basis.add(text.get("cds.adultParacetamol.basis.degreeUnrecorded",
                    "The degree of the hepatocellular insufficiency is not recorded: {0} mg per day assumes mild to moderate, and paracetamol is contraindicated if it is severe.",
                    number(TWO_GRAM_GROUP_MG_PER_DAY)));
            }
            if (ceiling == MAX_MG_PER_DAY) {
                basis.add(lowerLimits());
            }
            return basis;
        }

        /** The groups, for a prescriber who knows what the problem list does not code. */
        private String lowerLimits() {
            return text.get("cds.adultParacetamol.detail.groups",
                "Lower limits to check: under {0} kg, chronic malnutrition or dehydration: {1} mg/kg per day or {2} mg per day, whichever is lower; mild to moderate hepatocellular insufficiency, Gilbert syndrome or chronic alcoholism: {3} mg per day, with at least {4} hours between doses in chronic alcoholism; contraindicated in severe hepatocellular insufficiency.",
                number(LOW_WEIGHT_UNDER_KG), number(LOW_RESERVE_MG_PER_KG_PER_DAY), number(LOW_RESERVE_MG_PER_DAY),
                number(TWO_GRAM_GROUP_MG_PER_DAY), hours(ALCOHOLISM_MIN_MINUTES_BETWEEN_DOSES));
        }

        private Optional<String> staleWeight() {
            LocalDateTime weighedAt = context.patientWeightRecordedAt();
            if (weighedAt == null) {
                return Optional.empty();
            }
            long days = ChronoUnit.DAYS.between(weighedAt.toLocalDate(), LocalDate.now(ZoneId.systemDefault()));
            return days <= PediatricDoseRule.WEIGHT_MAX_AGE_DAYS ? Optional.empty()
                : Optional.of(text.get("cds.adultParacetamol.basis.staleWeight",
                    "That weight was recorded {0} days ago.", String.valueOf(days)));
        }

        private String footer() {
            return text.get("cds.adultParacetamol.footer",
                "This alert does not block the order. Sources: {0}; proposed logic, pending clinician sign-off.",
                SOURCES);
        }

        private String number(double value) {
            return text.measure(value, 2);
        }

        private String hours(int minutes) {
            return text.measure(minutes / MINUTES_PER_HOUR, 2);
        }

        private CdsCard alert(String summary, String detail) {
            return build(summary, detail, ALERT);
        }

        private CdsCard build(String summary, String detail, CdsCard.Indicator indicator) {
            return new CdsCard(summary, detail, indicator,
                new Source(text.get("cds.source.adultParacetamol", SOURCE_LABEL), null, null),
                null, null, null, UUID.randomUUID().toString());
        }
    }

    /** The other orders' share of the daily total. */
    private static final class Totals {
        private double mg;
        private int counted;
        private int uncounted;
    }
}
