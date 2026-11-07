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
 * reason, recorded and audited, at creation, edit and signature. Nothing here adds a hard stop. A dose that cannot be read in mg is
 * silent, never read as mg, and that includes a bare number ("2" is more likely two tablets); the
 * checks of the interval and of the contraindication do not need a dose.
 *
 * <p>What the check does not know is said, never read as an absence (user, 2026-11-07). Every card
 * carries the calculated total, the limit and the rule that set it, what is missing, and a request
 * to reconcile. A missing code is not an absent risk, so a total above the lowest ceiling names the
 * lower-limit groups even with nothing coded. The total counts prescribed orders at this hospital,
 * not actual use. The contraindication is not limited by age; a child gets only that card, and the
 * paediatric rule, a separate rule of the engine, keeps every limit it has.
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
        if (context.patient() != null && context.paracetamolFacts().nameNotRecognised()) {
            return List.of(notRecognised(context.proposedMedicationName()));
        }
        if (context.patient() == null || !context.paracetamolFacts().applies()) {
            return List.of();
        }
        Check check = new Check(context);
        List<CdsCard> cards = new ArrayList<>();
        boolean noBirthDate = context.patient().getDateOfBirth() == null;
        if (!noBirthDate && !PediatricDoseRule.isAdult(context.patient())) {
            check.childContraindication(cards);
            return cards;
        }
        check.contraindication(cards);
        if (noBirthDate) {
            cards.add(check.ageUnknown());
            return cards;
        }
        check.singleDose(cards);
        check.dailyTotal(cards);
        check.interval(cards);
        check.leftOutOfTheTotal(cards);
        return cards;
    }

    /** QA D1462: what the check does not know is said. A name it cannot read may be a brand of this drug. */
    private CdsCard notRecognised(String medicationName) {
        String name = medicationName == null ? "" : medicationName.trim();
        return new CdsCard(
            text.get("cds.adultParacetamol.notRecognised.summary",
                "{0} is not in the catalogue and is not a brand on file. If it holds paracetamol, no paracetamol limit was judged and it is left out of the daily total",
                name),
            text.get("cds.adultParacetamol.notRecognised.detail",
                "Choose the drug from the catalogue, or have the pharmacy add this brand to its catalogue item, so the limits and the total apply. This notice does not block the order.",
                name),
            CdsCard.Indicator.INFO, new Source(text.get("cds.source.adultParacetamol", SOURCE_LABEL), null, null),
            null, null, null, UUID.randomUUID().toString());
    }

    /** One evaluation's facts, so each check reads them the same way. */
    private final class Check {
        private final CdsRuleContext context;
        private final ParacetamolFacts facts;
        private final Set<ParacetamolRisk> risks;
        private final String medication;
        private final Double doseMg;
        private final Double dosesPerDay;
        /**
         * QA D1455: a frequency the reader does not read has no known daily total, whatever
         * {@link #dosesPerDay} holds: the count the sentence states, a minimum, or nothing, then
         * counted as one dose. A ticked interval is known: it is the most the order allows.
         */
        private final boolean totalUnknown;
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
            boolean noUnit = context.proposedDoseMg() != null && context.proposedDoseMg().equals(context.proposedDoseUnits());
            // A combination's own paracetamol content cannot be read: never read as mg.
            this.doseMg = noUnit || facts.proposedCombination() ? null : context.proposedDoseMg();
            this.dosesPerDay = PediatricDoseRule.dosesOnADosingDay(
                context.proposedFrequency(), context.proposedPrnDosesPerDay());
            this.totalUnknown = context.proposedPrnDosesPerDay() == null
                && MarDoseSchedule.writtenWithNoCount(context.proposedFrequency());
            this.limit = dailyLimit();
            this.others = otherOrders();
            double doses = dosesPerDay == null ? 1d : dosesPerDay;
            this.total = doseMg == null ? null : doseMg * doses + others.mg;
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
                cards.add(contraindicationCard(String.join(" ", totalSentence(), contraindicationJudged(), sentences(),
                    contraindicationFooter())));
            }
        }

        /** A child: this card alone, with no adult limit in its text. */
        void childContraindication(List<CdsCard> cards) {
            if (risks.contains(ParacetamolRisk.SEVERE_HEPATIC)) {
                cards.add(contraindicationCard(String.join(" ", contraindicationJudged(), scopeSentence(),
                    reconcileSentence(), contraindicationFooter())));
            }
        }

        private String contraindicationJudged() {
            return text.get("cds.adultParacetamol.contraindication.judged",
                "Judged against the contraindication in severe hepatocellular insufficiency (RCP 4.3), read from a coded problem and its recorded severity.");
        }

        private String contraindicationFooter() {
            return text.get("cds.adultParacetamol.contraindication.detail",
                "The order saves only with a reason, which is recorded on the order and audited. Sources: FDA acetaminophen guidance; EFFERALGANSTIX 1000 mg RCP 4.2, 4.3; proposed logic, pending clinician sign-off.");
        }

        private CdsCard contraindicationCard(String detail) {
            return new CdsCard(text.get("cds.adultParacetamol.contraindication.summary",
                "{0}: paracetamol is contraindicated in severe hepatocellular insufficiency, recorded on the problem list",
                medication), detail, CdsCard.Indicator.CRITICAL,
                new Source(text.get("cds.source.adultParacetamol", SOURCE_LABEL), null, null),
                null, null, null, UUID.randomUUID().toString(), null, null, Boolean.TRUE);
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
            double ceiling = limit.mg;
            if (total == null) {
                fromOtherOrders(cards, ceiling);
                return;
            }
            if (total > ceiling) {
                cards.add(alert(totalUnknown
                    ? text.get("cds.adultParacetamol.dailyAtLeast.summary",
                        "{0}: at least {1} mg per day of paracetamol from active orders at this hospital is above the adult maximum of {2} mg per day; the daily total could not be read from the frequency \"{3}\"",
                        medication, number(total), number(ceiling), context.proposedFrequency())
                    : text.get("cds.adultParacetamol.daily.summary",
                        "{0}: {1} mg per day of paracetamol from active orders at this hospital is above the adult maximum of {2} mg per day",
                        medication, number(total), number(ceiling)), null));
            } else if (totalUnknown) {
                cards.add(alert(text.get("cds.adultParacetamol.unread.summary",
                    "{0}: the daily total could not be read from the frequency \"{1}\" - check paracetamol from active orders at this hospital against the maximum of {2} mg per day",
                    medication, context.proposedFrequency(), number(ceiling)), null));
            } else if (others.unreadFrequency > 0) {
                cards.add(alert(text.get("cds.adultParacetamol.unreadOther.summary",
                    "{0}: {1} other active order(s) have a frequency that could not be read, counted as one dose - check paracetamol from active orders at this hospital against the maximum of {2} mg per day",
                    medication, String.valueOf(others.unreadFrequency), number(ceiling)), null));
            } else if (others.statedCountOnly > 0) {
                cards.add(alert(text.get("cds.adultParacetamol.statedCountOther.summary",
                    "{0}: {1} other active order(s) are counted at what their frequency states, which could not be read in full - check paracetamol from active orders at this hospital against the maximum of {2} mg per day",
                    medication, String.valueOf(others.statedCountOnly), number(ceiling)), null));
            } else if (!risks.contains(ParacetamolRisk.SEVERE_HEPATIC)) {
                information(cards, ceiling);
            }
        }

        /** This order's dose is unread: the other orders are still judged, and their total is a floor. */
        private void fromOtherOrders(List<CdsCard> cards, double ceiling) {
            if (others.mg > 0d) {
                cards.add(build(text.get("cds.adultParacetamol.dailyOthers.summary",
                    "{0}: at least {1} mg per 24 h from other active orders at this hospital, against a maximum of {2} mg per 24 h; the dose of this order could not be read",
                    medication, number(others.mg), number(ceiling)), detail(null),
                    others.mg > ceiling ? ALERT : CdsCard.Indicator.INFO));
            }
        }

        /** Under the ceiling: what the prescriber should still weigh. Never blocks. */
        private void information(List<CdsCard> cards, double ceiling) {
            if (ceiling >= MAX_MG_PER_DAY && total > USUAL_MG_PER_DAY) {
                cards.add(build(text.get("cds.adultParacetamol.info.summary",
                    "{0}: {1} mg per day of paracetamol from active orders at this hospital. The usual adult dose is {2} mg per day; up to {3} mg per day only on medical advice, without additional hepatotoxicity risk factors",
                    medication, number(total), number(USUAL_MG_PER_DAY), number(MAX_MG_PER_DAY)),
                    detail(null), CdsCard.Indicator.INFO));
            } else if (ceiling > TWO_GRAM_GROUP_MG_PER_DAY && total > TWO_GRAM_GROUP_MG_PER_DAY) {
                cards.add(build(text.get("cds.adultParacetamol.lowerGroups.summary",
                    "{0}: {1} mg per 24 h of paracetamol from active orders at this hospital. A lower limit, down to {2} mg per 24 h, may apply: a condition that is not coded may still be present",
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

        /** With no other card, what the check could not judge is still never passed in silence. */
        void leftOutOfTheTotal(List<CdsCard> cards) {
            if (!cards.isEmpty()) {
                return;
            }
            if (risks.contains(ParacetamolRisk.HEPATIC_DEGREE_UNRECORDED)) {
                cards.add(build(text.get("cds.adultParacetamol.degree.summary",
                    "{0}: hepatocellular insufficiency on the problem list with no degree recorded: paracetamol is contraindicated if it is severe",
                    medication), detail(null), CdsCard.Indicator.INFO));
                return;
            }
            List<String> names = new ArrayList<>(facts.mayStillBeInUse());
            names.addAll(facts.combinations());
            names.addAll(others.uncountedNames);
            names.addAll(facts.notRecognised());
            // QA D1462: "4 cp" of a cited brand: its strength is on no item, and its name is all there is.
            boolean countUnread = context.proposedDoseMg() == null && context.proposedDoseUnits() != null
                && com.example.hms.service.allergy.DrugClassAllergy.carriesBrand(medication);
            if (facts.proposedCombination() || countUnread) {
                names.add(medication);
            }
            if (!names.isEmpty()) {
                cards.add(build(text.get("cds.adultParacetamol.leftOut.summary",
                    "{0}: not counted in the paracetamol total: {1}",
                    medication, String.join(NAME_SEPARATOR, names)), detail(null), CdsCard.Indicator.INFO));
            }
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
            if (risks.contains(ParacetamolRisk.SEVERE_HEPATIC)) {
                lowest = lowest.orLower(TWO_GRAM_GROUP_MG_PER_DAY, text.get("cds.adultParacetamol.rule.severe",
                    "the contraindication in severe hepatocellular insufficiency (RCP 4.3): the lowest ceiling is shown, and no dose is endorsed"));
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
                    totals.uncountedNames.add(rx.getMedicationName());
                    continue;
                }
                Double perDay = PediatricDoseRule.dosesOnADosingDay(rx.getFrequency(),
                    PrnMinInterval.highestDosesPerDay(PrnMinInterval.isAsNeeded(rx), rx.getPrnMinIntervalHours()));
                totals.mg += mg * (perDay == null ? 1d : perDay);
                totals.counted++;
                if (perDay == null && MarDoseSchedule.writtenWithNoCount(rx.getFrequency())) {
                    totals.unreadFrequency++;
                } else if (!PrnMinInterval.isAsNeeded(rx) && MarDoseSchedule.writtenWithNoCount(rx.getFrequency())) {
                    totals.statedCountOnly++;
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
            if (total == null) {
                return others.mg > 0d
                    ? text.get("cds.adultParacetamol.total.atLeast",
                        "24-hour total: at least {0} mg from other orders; the dose of this order could not be read in mg. It counts prescribed orders, not actual use.",
                        number(others.mg))
                    : text.get("cds.adultParacetamol.total.unknown",
                        "The 24-hour total could not be calculated: the dose of this order could not be read in mg.");
            }
            if (totalUnknown) {
                return text.get("cds.adultParacetamol.total.frequencyUnread",
                    "The 24-hour total could not be calculated from this frequency: at least {0} mg. It counts prescribed orders, not actual use.",
                    number(total));
            }
            boolean noFrequency = context.proposedPrnDosesPerDay() == null
                && (context.proposedFrequency() == null || context.proposedFrequency().isBlank());
            return noFrequency
                ? text.get("cds.adultParacetamol.total.noFrequency",
                    "24-hour total with one dose of this order, as no frequency was sent: {0} mg. It counts prescribed orders, not actual use.",
                    number(total))
                : text.get("cds.adultParacetamol.total",
                    "Calculated 24-hour total: {0} mg. It counts prescribed orders, not actual use.", number(total));
        }

        private String scopeSentence() {
            return text.get("cds.adultParacetamol.scope",
                "Orders and problems are read at this hospital only: check other facilities.");
        }

        private String reconcileSentence() {
            return text.get("cds.adultParacetamol.reconcile",
                "Please reconcile: check combination products, nonprescription medicines, actual use and risk factors.");
        }

        /** What applied, what is missing, what was left out, and the request to reconcile. */
        private String sentences() {
            List<String> parts = new ArrayList<>(basis());
            parts.addAll(orderSentences());
            String named = facts.combinations().isEmpty() ? ""
                : " (" + String.join(NAME_SEPARATOR, facts.combinations()) + ")";
            parts.add(text.get(COMBINATIONS_KEY, COMBINATIONS_TEXT, named));
            if (!facts.notRecognised().isEmpty()) {
                parts.add(text.get("cds.adultParacetamol.detail.notRecognised",
                    "Not in the catalogue and not a known brand, so not counted if they hold paracetamol: {0}.",
                    String.join(NAME_SEPARATOR, facts.notRecognised())));
            }
            parts.add(scopeSentence());
            parts.add(reconcileSentence());
            return String.join(" ", parts);
        }

        private List<String> orderSentences() {
            List<String> parts = new ArrayList<>();
            if (total != null && dosesPerDay == null && MarDoseSchedule.writtenWithNoCount(context.proposedFrequency())) {
                parts.add(text.get("cds.adultParacetamol.detail.proposedOneDose",
                    "This order is counted as one dose: its frequency \"{0}\" could not be read.",
                    context.proposedFrequency()));
            }
            if (facts.noHospital()) {
                parts.add(text.get("cds.adultParacetamol.detail.noHospital",
                    "No hospital is active: no other order and no problem could be read, so a lower limit may apply."));
            }
            if (facts.noActingHospital() && !facts.noHospital()) {
                parts.add(text.get("cds.adultParacetamol.detail.withheld",
                    "No hospital is acting: problems tagged sensitive are not read, so a lower limit may apply."));
            }
            if (others.counted > 0) {
                parts.add(text.get("cds.adultParacetamol.detail.counted",
                    "The total counts {0} other active order(s).", String.valueOf(others.counted)));
            }
            if (others.unreadFrequency > 0) {
                parts.add(text.get("cds.adultParacetamol.detail.oneDose",
                    "{0} of them counted as one dose: the frequency could not be read.",
                    String.valueOf(others.unreadFrequency)));
            }
            if (others.statedCountOnly > 0) {
                parts.add(text.get("cds.adultParacetamol.detail.statedCountOnly",
                    "{0} of them counted at the number of doses a day their frequency states, which could not be read in full: it may be more.",
                    String.valueOf(others.statedCountOnly)));
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
            }
            if (weight != null) {
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
                    "The weight on file was recorded {0} days ago.", String.valueOf(days)));
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
        private int statedCountOnly;
        private int noDuration;
        private final List<String> uncountedNames = new ArrayList<>();
    }
}
