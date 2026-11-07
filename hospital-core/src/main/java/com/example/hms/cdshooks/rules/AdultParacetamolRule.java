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
 * <p>Every card is an alert, {@code warning} or {@code info}: the order is saved and the prescriber
 * reads the card on the form ({@link #ALERT} is the one place to change that). The one exception is
 * the contraindication, {@code critical}: the existing override, so the order saves only with a
 * reason, recorded and audited. Nothing here adds a hard stop. A dose that cannot be read in mg is
 * silent, never read as mg, and that includes a bare number ("2" is more likely two tablets); the
 * checks of the interval and of the contraindication do not need a dose.
 *
 * <p>What the check does not know is said, never read as an absence (user, 2026-11-07). Every card
 * carries the calculated total, the limit and the rule that set it, what is missing, and a request
 * to reconcile. A missing code is not an absent risk, so a total above the lowest ceiling names the
 * lower-limit groups even with nothing coded. The total counts prescribed orders, not actual use.
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
    private static final CdsCard.Indicator ALERT = CdsCard.Indicator.WARNING;
    private static final double MINUTES_PER_DAY = 1440d;
    private static final double MINUTES_PER_HOUR = 60d;
    private static final String NAME_SEPARATOR = ", ";
    private static final String COMBINATIONS_KEY = "cds.adultParacetamol.detail.combinations";
    private static final String COMBINATIONS_TEXT =
        "Combination products are not counted: their paracetamol content cannot be read{0}.";

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
        if (context.patient() == null || !context.paracetamolFacts().applies()) {
            return List.of();
        }
        if (context.patient().getDateOfBirth() == null) {
            return List.of(new Check(context).ageUnknown());
        }
        if (!PediatricDoseRule.isAdult(context.patient())) {
            return List.of();
        }
        Check check = new Check(context);
        List<CdsCard> cards = new ArrayList<>();
        check.contraindication(cards);
        check.singleDose(cards);
        check.dailyTotal(cards);
        check.interval(cards);
        check.leftOutOfTheTotal(cards);
        return cards;
    }

    /** One evaluation's facts, so each check reads them the same way. */
    private final class Check {
        private final CdsRuleContext context;
        private final ParacetamolFacts facts;
        private final Set<ParacetamolRisk> risks;
        private final String medication;
        private final Double doseMg;
        private final Double dosesPerDay;
        private final Limit limit;
        private final Totals others;
        private final Double total;

        Check(CdsRuleContext context) {
            this.context = context;
            this.facts = context.paracetamolFacts();
            this.risks = facts.risks();
            this.medication = context.proposedMedicationName() == null ? "" : context.proposedMedicationName().trim();
            // parseDoseUnits reads a count or a bare number; when it equals the mg reading no
            // unit made it mg, whatever the number (CdsRuleEngine.parseLabelledDoseMg, for other orders).
            this.doseMg = context.proposedDoseMg() != null && context.proposedDoseMg().equals(context.proposedDoseUnits())
                ? null : context.proposedDoseMg();
            this.dosesPerDay = PediatricDoseRule.dosesOnADosingDay(
                context.proposedFrequency(), context.proposedPrnDosesPerDay());
            this.limit = dailyLimit();
            this.others = otherOrders();
            this.total = doseMg == null ? null : doseMg * (dosesPerDay == null ? 1d : dosesPerDay) + others.mg;
        }

        /** No date of birth: neither the adult nor the paediatric limits can be judged, and it says so. */
        CdsCard ageUnknown() {
            String detail = String.join(" ", totalSentence(),
                text.get("cds.adultParacetamol.age.detail",
                    "No limit was judged: without a date of birth the patient cannot be told from a child. Adult maximum: {0} mg per 24 h.",
                    number(MAX_MG_PER_DAY)),
                sentences(), footer());
            return build(text.get("cds.adultParacetamol.age.summary",
                "{0}: no date of birth on file, so neither the adult nor the paediatric paracetamol limits could be judged",
                medication), detail, CdsCard.Indicator.INFO);
        }

        void contraindication(List<CdsCard> cards) {
            if (risks.contains(ParacetamolRisk.SEVERE_HEPATIC)) {
                String detail = String.join(" ", totalSentence(),
                    text.get("cds.adultParacetamol.contraindication.judged",
                        "Judged against the contraindication in severe hepatocellular insufficiency (RCP 4.3), read from a coded problem and its recorded severity."),
                    sentences(),
                    text.get("cds.adultParacetamol.contraindication.detail",
                        "The order saves only with a reason, which is recorded on the order and audited. Sources: FDA acetaminophen guidance; EFFERALGANSTIX 1000 mg RCP 4.2, 4.3; proposed logic, pending clinician sign-off."));
                cards.add(build(text.get("cds.adultParacetamol.contraindication.summary",
                    "{0}: paracetamol is contraindicated in severe hepatocellular insufficiency, recorded on the problem list",
                    medication), detail, CdsCard.Indicator.CRITICAL));
            }
        }

        void singleDose(List<CdsCard> cards) {
            if (doseMg != null && doseMg > MAX_MG_PER_ADMINISTRATION) {
                cards.add(alert(text.get("cds.adultParacetamol.single.summary",
                    "{0}: {1} mg per dose is above the adult maximum of {2} mg per administration",
                    medication, number(doseMg), number(MAX_MG_PER_ADMINISTRATION)),
                    text.get("cds.adultParacetamol.single.judged",
                        "Judged against {0} mg per administration, set by RCP 4.2.", number(MAX_MG_PER_ADMINISTRATION))));
            }
        }

        void dailyTotal(List<CdsCard> cards) {
            if (total == null) {
                return;
            }
            double ceiling = limit.mg;
            if (total > ceiling) {
                cards.add(alert(text.get("cds.adultParacetamol.daily.summary",
                    "{0}: {1} mg per day of paracetamol from all active orders is above the adult maximum of {2} mg per day",
                    medication, number(total), number(ceiling)), null));
            } else if (dosesPerDay == null && MarDoseSchedule.writtenWithNoCount(context.proposedFrequency())) {
                cards.add(alert(text.get("cds.adultParacetamol.unread.summary",
                    "{0}: the daily total could not be read from the frequency \"{1}\" - check paracetamol from all active orders against the maximum of {2} mg per day",
                    medication, context.proposedFrequency(), number(ceiling)), null));
            } else if (others.unreadFrequency > 0) {
                cards.add(alert(text.get("cds.adultParacetamol.unreadOther.summary",
                    "{0}: {1} other active order(s) have a frequency that could not be read, counted as one dose - check paracetamol from all active orders against the maximum of {2} mg per day",
                    medication, String.valueOf(others.unreadFrequency), number(ceiling)), null));
            } else if (!risks.contains(ParacetamolRisk.SEVERE_HEPATIC)) {
                information(cards, ceiling);
            }
        }

        /** Under the ceiling: what the prescriber should still weigh. Never blocks. */
        private void information(List<CdsCard> cards, double ceiling) {
            if (ceiling >= MAX_MG_PER_DAY && total > USUAL_MG_PER_DAY) {
                cards.add(build(text.get("cds.adultParacetamol.info.summary",
                    "{0}: {1} mg per day of paracetamol from all active orders. The usual adult dose is {2} mg per day; up to {3} mg per day only on medical advice, without additional hepatotoxicity risk factors",
                    medication, number(total), number(USUAL_MG_PER_DAY), number(MAX_MG_PER_DAY)),
                    detail(null), CdsCard.Indicator.INFO));
            } else if (ceiling > TWO_GRAM_GROUP_MG_PER_DAY && total > TWO_GRAM_GROUP_MG_PER_DAY) {
                cards.add(build(text.get("cds.adultParacetamol.lowerGroups.summary",
                    "{0}: {1} mg per 24 h of paracetamol from all active orders. A lower limit, down to {2} mg per 24 h, may apply: a condition that is not coded may still be present",
                    medication, number(total), number(TWO_GRAM_GROUP_MG_PER_DAY)),
                    detail(null), CdsCard.Indicator.INFO));
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
                    "{0}: {1} h between doses is shorter than the minimum of {2} h required in chronic alcoholism, recorded on the problem list",
                    medication, hours(minutes), hours(least))
                : text.get("cds.adultParacetamol.interval.summary",
                    "{0}: {1} h between doses is shorter than the adult minimum of {2} h",
                    medication, hours(minutes), hours(least));
            cards.add(alert(summary, text.get("cds.adultParacetamol.interval.judged",
                "Judged against at least {0} h between doses, set by RCP 4.2.", hours(least))));
        }

        /** With no other card, an order left out of the total is still never dropped in silence. */
        void leftOutOfTheTotal(List<CdsCard> cards) {
            if (!cards.isEmpty() || (facts.mayStillBeInUse().isEmpty() && facts.combinations().isEmpty())) {
                return;
            }
            List<String> names = new ArrayList<>(facts.mayStillBeInUse());
            names.addAll(facts.combinations());
            cards.add(build(text.get("cds.adultParacetamol.leftOut.summary",
                "{0}: other paracetamol orders are not counted in the total: {1}",
                medication, String.join(NAME_SEPARATOR, names)), detail(null), CdsCard.Indicator.INFO));
        }

        /** The lowest ceiling of every group that applies, and the rule that set it. */
        private Limit dailyLimit() {
            Limit lowest = new Limit(MAX_MG_PER_DAY, text.get("cds.adultParacetamol.rule.general",
                "the general adult ceiling, from all medicines combined (FDA)"));
            Double weight = weightKg();
            if (weight != null && weight < LOW_WEIGHT_UNDER_KG) {
                lowest = lowest.orLower(lowReserveCeiling(weight), text.get("cds.adultParacetamol.rule.lowWeight",
                    "the weight on file, under {0} kg (RCP 4.2)", number(LOW_WEIGHT_UNDER_KG)));
            }
            if (risks.contains(ParacetamolRisk.MALNUTRITION_OR_DEHYDRATION)) {
                lowest = lowest.orLower(lowReserveCeiling(weight), text.get("cds.adultParacetamol.rule.lowReserve",
                    "coded malnutrition or dehydration (RCP 4.2)"));
            }
            if (risks.contains(ParacetamolRisk.TWO_GRAM_GROUP)) {
                lowest = lowest.orLower(TWO_GRAM_GROUP_MG_PER_DAY, text.get("cds.adultParacetamol.rule.twoGram",
                    "coded hepatocellular insufficiency, Gilbert syndrome or chronic alcoholism (RCP 4.2)"));
            }
            return lowest;
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
            for (ParacetamolFacts.OtherOrder other : facts.otherOrders()) {
                Prescription rx = other.order();
                Double mg = CdsRuleEngine.parseLabelledDoseMg(rx.getDosage(), weightKg(), other.item());
                if (!other.durationRead()) {
                    totals.noDuration++;
                }
                if (mg == null) {
                    totals.uncounted++;
                    continue;
                }
                Double perDay = PediatricDoseRule.dosesOnADosingDay(rx.getFrequency(),
                    PrnMinInterval.highestDosesPerDay(PrnMinInterval.isAsNeeded(rx), rx.getPrnMinIntervalHours()));
                totals.mg += mg * (perDay == null ? 1d : perDay);
                totals.counted++;
                if (perDay == null && MarDoseSchedule.writtenWithNoCount(rx.getFrequency())) {
                    totals.unreadFrequency++;
                }
            }
            return totals;
        }

        /** The shortest time between two doses the order allows, or {@code null} when none is written. */
        private Integer shortestMinutesBetweenDoses() {
            Integer shortest = null;
            Double prnPerDay = context.proposedPrnDosesPerDay();
            if (prnPerDay != null && prnPerDay > 0d) {
                shortest = (int) Math.round(MINUTES_PER_DAY / prnPerDay);
            }
            Integer read = MarDoseSchedule.shortestMinutesBetweenDoses(context.proposedFrequency());
            if (read != null && (shortest == null || read < shortest)) {
                shortest = read;
            }
            return shortest;
        }

        /** Every card: the total, this card's limit, the daily limit and its rule, what is missing, the request. */
        private String detail(String judged) {
            List<String> parts = new ArrayList<>();
            parts.add(totalSentence());
            if (judged != null) {
                parts.add(judged);
            }
            parts.add(text.get("cds.adultParacetamol.limit",
                "Daily limit: {0} mg per 24 h, set by {1}.", number(limit.mg), limit.rule));
            parts.add(sentences());
            parts.add(footer());
            return String.join(" ", parts);
        }

        private String totalSentence() {
            return total == null
                ? text.get("cds.adultParacetamol.total.unknown",
                    "The 24-hour total could not be calculated: the dose of this order could not be read in mg.")
                : text.get("cds.adultParacetamol.total",
                    "Calculated 24-hour total: {0} mg. It counts prescribed orders, not actual use.", number(total));
        }

        /** What applied, what is missing, what was left out, and the request to reconcile. */
        private String sentences() {
            List<String> parts = new ArrayList<>(basis());
            parts.addAll(orderSentences());
            String named = facts.combinations().isEmpty() ? ""
                : " (" + String.join(NAME_SEPARATOR, facts.combinations()) + ")";
            parts.add(text.get(COMBINATIONS_KEY, COMBINATIONS_TEXT, named));
            parts.add(text.get("cds.adultParacetamol.reconcile",
                "Please reconcile: check combination products, nonprescription medicines, actual use and risk factors."));
            return String.join(" ", parts);
        }

        private List<String> orderSentences() {
            List<String> parts = new ArrayList<>();
            if (others.counted > 0) {
                parts.add(text.get("cds.adultParacetamol.detail.counted",
                    "The total counts {0} other active order(s).", String.valueOf(others.counted)));
            }
            if (others.unreadFrequency > 0) {
                parts.add(text.get("cds.adultParacetamol.detail.oneDose",
                    "{0} of them counted as one dose: the frequency could not be read.",
                    String.valueOf(others.unreadFrequency)));
            }
            if (others.noDuration > 0) {
                parts.add(text.get("cds.adultParacetamol.detail.noDuration",
                    "{0} other active order(s) state no readable duration.", String.valueOf(others.noDuration)));
            }
            if (others.uncounted > 0) {
                parts.add(text.get("cds.adultParacetamol.detail.uncounted",
                    "{0} other active order(s) could not be counted: the dose could not be read in mg.",
                    String.valueOf(others.uncounted)));
            }
            if (!facts.mayStillBeInUse().isEmpty()) {
                parts.add(text.get("cds.adultParacetamol.detail.mayStillBeInUse",
                    "May still be in use, not counted: {0}.", String.join(NAME_SEPARATOR, facts.mayStillBeInUse())));
            }
            return parts;
        }

        /** Every lower limit that applies, what is missing about them, and the groups not coded. */
        private List<String> basis() {
            List<String> basis = new ArrayList<>();
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
            basis.add(lowerLimits());
            return basis;
        }

        /** The groups: a missing code is not an absent risk. */
        private String lowerLimits() {
            return text.get("cds.adultParacetamol.detail.groups",
                "A condition not coded may still be present. Lower limits to check: under {0} kg, chronic malnutrition or dehydration: {1} mg/kg per day or {2} mg per day, whichever is lower; mild to moderate hepatocellular insufficiency, Gilbert syndrome or chronic alcoholism: {3} mg per day, with at least {4} h between doses in chronic alcoholism; contraindicated in severe hepatocellular insufficiency.",
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
                "This alert does not block the order. Sources: FDA acetaminophen guidance; EFFERALGANSTIX 1000 mg RCP 4.2, 4.3; proposed logic, pending clinician sign-off.");
        }

        private String number(double value) {
            return text.measure(value, 2);
        }

        private String hours(int minutes) {
            return text.measure(minutes / MINUTES_PER_HOUR, 2);
        }

        private CdsCard alert(String summary, String judged) {
            return build(summary, detail(judged), ALERT);
        }

        private CdsCard build(String summary, String detail, CdsCard.Indicator indicator) {
            return new CdsCard(summary, detail, indicator,
                new Source(text.get("cds.source.adultParacetamol", SOURCE_LABEL), null, null),
                null, null, null, UUID.randomUUID().toString());
        }
    }

    /** A daily ceiling and the rule that set it. */
    private record Limit(double mg, String rule) {
        Limit orLower(double otherMg, String otherRule) {
            return otherMg < mg ? new Limit(otherMg, otherRule) : this;
        }
    }

    /** The other orders' share of the daily total. */
    private static final class Totals {
        private double mg;
        private int counted;
        private int uncounted;
        private int unreadFrequency;
        private int noDuration;
    }
}
