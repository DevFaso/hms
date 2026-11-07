package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.PediatricWeightBand;
import com.example.hms.service.impl.MarDoseSchedule;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The paediatric dose check, as real hospitals run it (round 18, QA D935).
 * Every limit comes from the hospital's catalogue item, set and approved by
 * its pharmacy; nothing here is a clinical value.
 *
 * <ol>
 *   <li><strong>Hard stop</strong>: the daily total in mg is above the item's
 *       absolute daily maximum. Weight-independent, checked at any age. The
 *       card is {@code critical} with {@code hardStop}: no override passes it.</li>
 *   <li><strong>Manual review</strong>: below the item's manual-review age
 *       (3 months by default; the catalogue sets 12 for morphine) no
 *       automatic mg/kg verdict is given; a {@code warning} with
 *       {@code reviewRequired} holds the signature until a pharmacist or
 *       another physician records the review (round 19, QA D1096).</li>
 *   <li><strong>Weight</strong>: no weight on file means no mg/kg check (a
 *       {@code warning}); a weight older than 30 days is flagged, and the
 *       checks still run on it.</li>
 *   <li><strong>Weight bands</strong> (artemether-lumefantrine): the dose in
 *       tablets is checked against the child's band, per dose and per day;
 *       a child outside every band needs a manual review.</li>
 *   <li><strong>Soft stops</strong>: above the usual maximum per dose (mg/kg,
 *       or the absolute mg per dose) or per day (mg/kg/day). The card is
 *       {@code critical}; the prescriber may override it with a reason, which
 *       is stored on the order and audited.</li>
 *   <li>With no limit on file, the round-16 advisory {@code warning} asks the
 *       prescriber to verify the weight-based dose.</li>
 * </ol>
 *
 * <p>Each card names the limit's source and whether it is a default pending
 * local approval, so a prescriber knows what the check rests on.
 *
 * <p>CR-01: a catalogue limit is the hospital's own, and none is seeded here. It applies to the route
 * its item records, and is compared with the route this order states, never replaced by it: a limit
 * for another route, or an order with no readable route, gives no mg/kg verdict and asks for the
 * review. A limit whose route is not recorded, or that is not {@code APPROVED} (the status used to
 * reach only the card's text), is not a cleared dose: its findings stand and the review is asked for
 * too. The manual review by age stays, and the absolute daily maximum still runs first, unless the
 * item records it for another route than the one ordered. The catalogue records no version or age
 * band for a limit beyond the manual-review age. Provisional, awaiting the named clinical approver.
 */
@Component
public class PediatricDoseRule implements CdsRule {

    private static final String ID = "pediatric-dose";
    private static final String SOURCE_LABEL = "HMS Pediatric Dose Check";
    public static final int PEDIATRIC_AGE_THRESHOLD_YEARS = 18;
    /** No automatic mg/kg verdict below this age unless the item says otherwise. */
    static final int DEFAULT_MANUAL_REVIEW_UNDER_MONTHS = 3;
    /** A weight older than this is flagged. */
    static final int WEIGHT_MAX_AGE_DAYS = 30;

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
        if (context.paracetamolFacts() == ParacetamolFacts.READ_FAILED && context.patient() != null
                && !isAdult(context.patient())) {
            return List.of(CdsRuleEngine.doseCheckFailed(text, context.proposedMedicationName(), id()));
        }
        if (context.patient() == null) return List.of();
        LocalDate dob = context.patient().getDateOfBirth();
        if (dob == null) return List.of();
        LocalDate today = LocalDate.now(java.time.ZoneId.systemDefault());
        Period age = Period.between(dob, today);
        if (age.isNegative() || age.getYears() >= PEDIATRIC_AGE_THRESHOLD_YEARS) return List.of();

        Check check = new Check(context, age, ChronoUnit.MONTHS.between(dob, today));
        List<CdsCard> cards = new ArrayList<>();
        Optional<CdsCard> hardStop = check.hardStop();
        hardStop.ifPresent(cards::add);
        cards.addAll(limitCards(context, check, age));
        // QA D1455: the hard stop says it itself. CR-01: with no verdict on this route, no daily limit is in play.
        if (hardStop.isEmpty() && check.noVerdictOnThisRoute().isEmpty()) {
            check.dailyTotalUnknown().ifPresent(cards::add);
        }
        return cards;
    }

    /** Whatever the age, the weight and the item leave to judge, after the hard stop. */
    private List<CdsCard> limitCards(CdsRuleContext context, Check check, Period age) {
        if (check.ageMonths < check.manualReviewUnderMonths()) {
            return List.of(reviewRequired(text.get("cds.pediatric.manualReview.summary",
                "{0}: child under {1} months - no automatic mg/kg check; a pharmacist or physician must review the dose",
                check.medication(), String.valueOf(check.manualReviewUnderMonths())), check.limitBasis()));
        }
        // CR-01: paracetamol with no limit on file for this order: nothing clears it. Another drug keeps its advice.
        if (context.paracetamolFacts().applies() && !check.hasLimits()) {
            return List.of(cannotAssess(text.get("cds.pediatric.limit.none.summary",
                "{0}: cannot assess, review required: no paracetamol limit on file applies to this order",
                check.medication()), check.limitBasis(), "paedNoLimit:" + context.proposedRoute().code()));
        }
        Optional<String> noVerdict = check.noVerdictOnThisRoute();
        if (noVerdict.isPresent()) {
            return List.of(cannotAssess(noVerdict.get(), check.limitBasis(), check.routeKey()));
        }
        List<CdsCard> cards = new ArrayList<>();
        check.limitNotEstablished().ifPresent(card -> cards.add(cannotAssess(card, check.limitBasis(), check.routeKey())));
        Double weight = context.patientWeightKg();
        if (weight == null || weight <= 0d) {
            cards.add(softAdvisory(context, age.getYears()));
            return cards;
        }
        check.staleWeightCard().ifPresent(cards::add);
        if (check.hasBands()) {
            cards.addAll(check.bandCards());
        } else if (check.hasMgLimit()) {
            cards.addAll(check.mgCards());
        } else {
            cards.add(softAdvisory(context, age.getYears()));
        }
        return cards;
    }

    /** Whether the patient is past the paediatric age: the one line between this rule and the adult checks. */
    static boolean isAdult(com.example.hms.model.Patient patient) {
        LocalDate dob = patient.getDateOfBirth();
        if (dob == null) {
            return false;
        }
        Period age = Period.between(dob, LocalDate.now(java.time.ZoneId.systemDefault()));
        return !age.isNegative() && age.getYears() >= PEDIATRIC_AGE_THRESHOLD_YEARS;
    }

    /**
     * The doses a daily limit counts, or {@code null} when none could be read. QA D1397: a
     * ticked interval is the most an as-needed order allows, so the daily limits judge it; a
     * frequency text read beside it can only ask for more doses, never fewer.
     */
    static Double dosesOnADosingDay(String frequency, Double prnDosesPerDay) {
        Double perDay = highest(MarDoseSchedule.dosesPerDay(frequency), prnDosesPerDay);
        return perDay == null ? null : Double.valueOf(Math.max(1d, perDay));
    }

    private static Double highest(Double read, Double fromInterval) {
        if (read == null || fromInterval == null) {
            return read == null ? fromInterval : read;
        }
        return Math.max(read, fromInterval);
    }

    /** One evaluation's facts, so each check reads them the same way. */
    private final class Check {
        private final CdsRuleContext context;
        private final MedicationCatalogItem item;
        private final Period age;
        private final long ageMonths;
        private final Double dosesPerDay;
        /**
         * QA D1455: a frequency the reader does not read has no known daily total, whatever
         * {@link #dosesPerDay} holds: the count the sentence states, a minimum, or nothing. A
         * ticked interval is known: it is the most the order allows.
         */
        private final boolean totalUnknown;

        Check(CdsRuleContext context, Period age, long ageMonths) {
            this.context = context;
            // QA D1462: a free-text brand of paracetamol is judged against the hospital's limits for
            // the drug, like a brand its catalogue item names. Its strength is not read from them.
            this.item = context.proposedCatalogItem() != null
                ? context.proposedCatalogItem() : context.paracetamolFacts().limits();
            this.age = age;
            this.ageMonths = ageMonths;
            // Review of round 18: the doses given on a dosing day - at least the
            // dose itself (q48h is still a full dose that day); a count that
            // does not divide the day ("5 fois par jour") is read as written.
            this.dosesPerDay = dosesOnADosingDay(context.proposedFrequency(), context.proposedPrnDosesPerDay());
            this.totalUnknown = context.proposedPrnDosesPerDay() == null
                && MarDoseSchedule.writtenWithNoCount(context.proposedFrequency());
        }

        String medication() {
            return context.proposedMedicationName() == null ? "" : context.proposedMedicationName();
        }

        int manualReviewUnderMonths() {
            Integer months = item == null ? null : item.getPediatricManualReviewUnderMonths();
            return months != null ? months : DEFAULT_MANUAL_REVIEW_UNDER_MONTHS;
        }

        boolean hasBands() {
            return item != null && item.getPediatricWeightBands() != null && !item.getPediatricWeightBands().isEmpty();
        }

        boolean hasMgLimit() {
            return item != null && (item.getPediatricMaxDoseMgPerKg() != null
                || item.getPediatricMaxDailyMgPerKg() != null || item.getPediatricMaxSingleDoseMg() != null
                || item.getPediatricMaxDailyMg() != null);
        }

        /** CR-01: what a cannot-assess card answers, for the urgent exception: the two routes and the status. */
        String routeKey() {
            return "paedLimit:" + context.proposedRoute().code() + ":" + limitRoute() + ":"
                + (item == null ? null : item.getPediatricDoseStatus());
        }

        /** The route the item records for its limits; {@code null} when it records none it can be read as. */
        private String limitRoute() {
            return item == null ? null : com.example.hms.service.emar.MedicationRoutes.canonical(item.getRoute());
        }

        boolean hasLimits() {
            return hasBands() || hasMgLimit();
        }

        /** Both routes are known, and they differ: the limit is not this order's. */
        private boolean limitIsForAnotherRoute() {
            String ordered = context.proposedRoute().code();
            return ordered != null && limitRoute() != null && !ordered.equals(limitRoute());
        }

        /** CR-01: why no mg/kg limit may judge this order: no readable route, or a limit for another one. */
        Optional<String> noVerdictOnThisRoute() {
            if (!hasLimits()) {
                return Optional.empty();
            }
            OrderedRoute route = context.proposedRoute();
            if (!route.stated()) {
                return Optional.of(text.get("cds.pediatric.route.notStated.summary",
                    "{0}: cannot assess, review required: this order states no route, and the limits on file apply to one",
                    medication()));
            }
            if (route.code() == null) {
                return Optional.of(text.get("cds.pediatric.route.unknown.summary",
                    "{0}: cannot assess, review required: the route \"{1}\" is not one this check can read",
                    medication(), route.written()));
            }
            if (limitIsForAnotherRoute()) {
                return Optional.of(text.get("cds.pediatric.route.other.summary",
                    "{0}: cannot assess, review required: the limits on file are for the route {1}, and this order is for the route {2}",
                    medication(), com.example.hms.service.emar.MedicationRoutes.label(limitRoute()),
                    com.example.hms.service.emar.MedicationRoutes.label(route.code())));
            }
            return route.contradictsFormulation()
                ? Optional.of(text.get("cds.pediatric.route.product.summary",
                    "{0}: cannot assess, review required: the route {1} does not agree with the product form {2}",
                    medication(), com.example.hms.service.emar.MedicationRoutes.label(route.code()), item.getForm()))
                : Optional.empty();
        }

        /** CR-01: a limit on file that is not an approved one for a recorded route clears nothing. */
        Optional<String> limitNotEstablished() {
            if (!hasLimits()) {
                return Optional.empty();
            }
            if (limitRoute() == null) {
                return Optional.of(text.get("cds.pediatric.limit.noRoute.summary",
                    "{0}: cannot assess, review required: the limit on file does not record its route, so a dose under it is not cleared",
                    medication()));
            }
            return item.getPediatricDoseStatus() == PediatricDoseLimitStatus.APPROVED ? Optional.empty()
                : Optional.of(text.get("cds.pediatric.limit.notApproved.summary",
                    "{0}: cannot assess, review required: the limit on file is not approved here, so a dose under it is not cleared",
                    medication()));
        }

        /** The absolute daily maximum in mg: the one limit no override passes. */
        Optional<CdsCard> hardStop() {
            BigDecimal cap = item == null ? null : item.getPediatricMaxDailyMg();
            Double doseMg = context.proposedDoseMg();
            if (cap == null || doseMg == null || limitIsForAnotherRoute()) {
                return Optional.empty();
            }
            // Review of round 18: an unreadable frequency (PRN, STAT, free text)
            // still counts at least this one dose: a single dose above the daily
            // cap is a hard stop whatever the frequency says. QA D1455: the card
            // then says "at least", and that the daily total is unknown.
            double daily = dosesPerDay == null ? doseMg : doseMg * dosesPerDay;
            if (daily <= cap.doubleValue()) {
                return Optional.empty();
            }
            String summary = totalUnknown
                ? text.get("cds.pediatric.hardStop.totalUnknown.summary",
                    "{0}: at least {1} mg per day is above the absolute daily maximum of {2} mg - this dose cannot be prescribed; the daily total is unknown: the frequency \"{3}\" could not be read",
                    medication(), number(daily), number(cap), frequencyWritten())
                : text.get("cds.pediatric.hardStop.summary",
                    "{0}: {1} mg per day is above the absolute daily maximum of {2} mg - this dose cannot be prescribed",
                    medication(), number(daily), number(cap));
            String detail = text.get("cds.pediatric.hardStop.detail",
                "Child aged {0}. The absolute daily maximum cannot be overridden: reduce the dose or the frequency. Limit: {1}.",
                ageText(), limitBasis());
            return Optional.of(card(summary, detail, CdsCard.Indicator.CRITICAL, Boolean.TRUE));
        }

        /**
         * QA D1455: "3 fois chaque jour" is never read as one dose a day: on every path, a limit on the day
         * that could not be judged says so. Not when the item holds no such limit.
         */
        Optional<CdsCard> dailyTotalUnknown() {
            boolean aDailyLimit = item != null && (item.getPediatricMaxDailyMg() != null
                || item.getPediatricMaxDailyMgPerKg() != null || hasBands());
            return totalUnknown && aDailyLimit ? Optional.of(frequencyUnreadCard()) : Optional.empty();
        }

        private CdsCard frequencyUnreadCard() {
            return warning(text.get("cds.pediatric.frequencyUnread.summary",
                "{0}: the daily total could not be read from the frequency \"{1}\" - check it against the daily maximum",
                medication(), frequencyWritten()), limitBasis());
        }

        private String frequencyWritten() {
            return context.proposedFrequency() == null ? "" : context.proposedFrequency();
        }

        Optional<CdsCard> staleWeightCard() {
            LocalDateTime weighedAt = context.patientWeightRecordedAt();
            if (weighedAt == null) {
                return Optional.empty();
            }
            long days = ChronoUnit.DAYS.between(weighedAt.toLocalDate(), LocalDate.now(java.time.ZoneId.systemDefault()));
            if (days <= WEIGHT_MAX_AGE_DAYS) {
                return Optional.empty();
            }
            return Optional.of(warning(text.get("cds.pediatric.weightStale.summary",
                "{0}: the weight on file ({1} kg) was recorded {2} days ago - weigh the child before relying on a weight-based dose",
                medication(), number(context.patientWeightKg()), String.valueOf(days)), limitBasis()));
        }

        /** Weight bands: the dose in units against the child's band, per dose and per day. */
        List<CdsCard> bandCards() {
            double weight = context.patientWeightKg();
            PediatricWeightBand band = item.getPediatricWeightBands().stream()
                .filter(b -> b != null && b.contains(weight))
                .findFirst().orElse(null);
            if (band == null) {
                return List.of(reviewRequired(text.get("cds.pediatric.band.none.summary",
                    "{0}: no weight band covers {1} kg - a pharmacist or physician must review the dose",
                    medication(), number(weight)), limitBasis()));
            }
            Double units = context.proposedDoseUnits();
            double bandDose = band.getDosePerAdministration() == null ? 0d : band.getDosePerAdministration().doubleValue();
            if (units == null) {
                return List.of(warning(text.get("cds.pediatric.band.unread.summary",
                    "{0}: dosed by weight band - {1} {2} per dose for {3} kg; the dose written could not be read as a count",
                    medication(), number(bandDose), unitOf(band), number(weight)), limitBasis()));
            }
            List<CdsCard> cards = new ArrayList<>();
            if (units > bandDose) {
                cards.add(softStop(text.get("cds.pediatric.band.perDose.summary",
                    "{0}: {1} {2} per dose is above the {3} {2} of the weight band for {4} kg",
                    medication(), number(units), unitOf(band), number(bandDose), number(weight)),
                    "bandDose:" + units + ":" + bandDose));
            }
            if (dosesPerDay != null && band.getMaxDosesPerDay() != null) {
                double daily = units * dosesPerDay;
                double bandDaily = bandDose * band.getMaxDosesPerDay();
                if (daily > bandDaily) {
                    cards.add(softStop(text.get("cds.pediatric.band.daily.summary",
                        "{0}: {1} {2} per day is above the {3} {2} per day of the weight band for {4} kg",
                        medication(), number(daily), unitOf(band), number(bandDaily), number(weight)),
                        "bandDaily:" + daily + ":" + bandDaily));
                }
            }
            return cards;
        }

        /** The mg/kg and absolute-mg soft stops. */
        List<CdsCard> mgCards() {
            Double doseMg = context.proposedDoseMg();
            double weight = context.patientWeightKg();
            if (doseMg == null) {
                return List.of(warning(text.get("cds.pediatric.doseUnread.summary",
                    "{0}: the dose written could not be read in mg - check it against the paediatric limits",
                    medication()), limitBasis()));
            }
            List<CdsCard> cards = new ArrayList<>();
            double mgPerKg = doseMg / weight;
            BigDecimal perDose = item.getPediatricMaxDoseMgPerKg();
            if (perDose != null && mgPerKg > perDose.doubleValue()) {
                cards.add(softStop(text.get("cds.pediatric.ceiling.summary",
                    "Pediatric dose exceeds ceiling: {0} mg/kg > {1} mg/kg max for {2}",
                    number(mgPerKg), number(perDose), medication()), "mgPerKg:" + mgPerKg + ":" + perDose));
            }
            BigDecimal single = item.getPediatricMaxSingleDoseMg();
            if (single != null && doseMg > single.doubleValue()) {
                cards.add(softStop(text.get("cds.pediatric.singleDose.summary",
                    "{0}: {1} mg per dose is above the maximum of {2} mg per dose",
                    medication(), number(doseMg), number(single)), "singleDose:" + doseMg + ":" + single));
            }
            BigDecimal perDay = item.getPediatricMaxDailyMgPerKg();
            if (dosesPerDay != null) {
                double dailyMgPerKg = mgPerKg * dosesPerDay;
                if (perDay != null && dailyMgPerKg > perDay.doubleValue()) {
                    cards.add(softStop(text.get("cds.pediatric.dailyMgPerKg.summary",
                        "{0}: {1} mg/kg per day is above the usual maximum of {2} mg/kg per day",
                        medication(), number(dailyMgPerKg), number(perDay)), "dailyMgPerKg:" + dailyMgPerKg + ":" + perDay));
                }
            } else if (!totalUnknown && (perDay != null || item.getPediatricMaxDailyMg() != null)) {
                cards.add(frequencyUnreadCard());
            }
            return cards;
        }

        /**
         * Above the usual maximum: critical at creation, as it has always been. Under an approved limit
         * of a recorded route (CR-02, provisional, for the neonatal/paediatric lead and the pharmacist)
         * it also asks the prescriber's fresh decision at the signature, tied by {@code key} to this
         * finding and this version of the order. A limit that is not established raises no such
         * decision by itself: it has its cannot-assess card.
         */
        private CdsCard softStop(String summary, String key) {
            String detail = text.get("cds.pediatric.softStop.detail",
                "Child aged {0}, weight {1} kg. Reduce the dose, or override with a reason: the reason is recorded on the order and audited. Limit: {2}.",
                ageText(), number(context.patientWeightKg()), limitBasis());
            if (limitNotEstablished().isPresent()) {
                return card(summary, detail, CdsCard.Indicator.CRITICAL, null);
            }
            return new CdsCard(summary, detail + " " + text.get("cds.pediatric.softStop.signature",
                    "The signature asks for the prescriber's decision again, for this finding and this version of the order."),
                CdsCard.Indicator.CRITICAL,
                new Source(text.get("cds.source.pediatricDose", SOURCE_LABEL), null, null),
                null, null, null, UUID.randomUUID().toString(), null, null, Boolean.TRUE, "paedAbove:" + key);
        }

        private String unitOf(PediatricWeightBand band) {
            return band.getDoseUnit() == null || band.getDoseUnit().isBlank()
                ? text.get("cds.pediatric.band.defaultUnit", "tablet(s)")
                : band.getDoseUnit();
        }

        /** A configured limit, with the decimals it was entered with (QA D1078: the reader's separator). */
        private String number(BigDecimal v) {
            return v == null ? "?" : text.decimal(v, Math.max(0, v.stripTrailingZeros().scale()));
        }

        /**
         * A computed value, at most two decimals and no trailing zeros (QA
         * D1133: "8000 mg", not "8000,00 mg"), with the reader's separator (QA D1078).
         */
        private String number(double v) {
            return text.measure(v, 2);
        }

        String ageText() {
            if (age.getYears() > 0) {
                return text.get("cds.pediatric.age.years", "{0} years", String.valueOf(age.getYears()));
            }
            if (ageMonths > 0) {
                return text.get("cds.pediatric.age.months", "{0} months", String.valueOf(ageMonths));
            }
            return text.get("cds.pediatric.age.days", "{0} days", String.valueOf(age.getDays()));
        }

        /** "WHO Pocket Book 2013 (default, pending local approval)". */
        String limitBasis() {
            if (item == null) {
                return text.get("cds.pediatric.limit.none", "no paediatric limit on file");
            }
            String source = item.getPediatricDoseSource() == null || item.getPediatricDoseSource().isBlank()
                ? text.get("cds.pediatric.limit.sourceUnknown", "source not stated")
                : item.getPediatricDoseSource().trim();
            String status = item.getPediatricDoseStatus() == PediatricDoseLimitStatus.APPROVED
                ? text.get("cds.pediatric.limit.approved", "approved locally")
                : text.get("cds.pediatric.limit.pending", "default, pending local approval");
            return source + " (" + status + ")";
        }

        private CdsCard warning(String summary, String limitBasis) {
            String detail = text.get("cds.pediatric.warning.detail", "Limit: {0}.", limitBasis);
            return card(summary, detail, CdsCard.Indicator.WARNING, null);
        }
    }

    /**
     * Round 19 (QA D1096): "a pharmacist or physician must review the dose"
     * was a plain warning, so a 174 mg/kg/day paracetamol order for a
     * 7-week-old signed and reached the pharmacy as an ordinary order. The
     * card now carries {@code reviewRequired}: the order is saved, and is not
     * signed until a pharmacist or another physician records the review with
     * a reason (PrescriptionServiceImpl.recordPediatricDoseReview).
     */
    private CdsCard reviewRequired(String summary, String limitBasis) {
        String detail = text.get("cds.pediatric.review.detail",
            "The order can be saved, but it is signed only once a pharmacist or another physician has recorded the dose review, with a reason. Limit: {0}.",
            limitBasis);
        return new CdsCard(
            summary, detail, CdsCard.Indicator.WARNING,
            new Source(text.get("cds.source.pediatricDose", SOURCE_LABEL), null, null),
            null, null, null, UUID.randomUUID().toString(), null, Boolean.TRUE
        );
    }

    /**
     * CR-01: a limit that cannot judge this order. The routine path is the recorded second-person
     * review, as {@link #reviewRequired}. Its key also admits the prescriber's explicit urgent
     * exception at the signature, so a lone doctor at night is not left with no way to treat
     * (PROVISIONAL, for the neonatal/paediatric lead and the pharmacist). The review by age and the
     * weight band card carry no key: they have no such exception.
     */
    private CdsCard cannotAssess(String summary, String limitBasis, String key) {
        String detail = text.get("cds.pediatric.cannotAssess.detail",
            "The order can be saved. It is signed once a pharmacist or another physician has recorded the dose review, with a reason. If it cannot wait, the prescriber may sign it as an urgent exception, with a reason; the review is then asked for urgently after the fact. Limit: {0}.",
            limitBasis);
        return new CdsCard(summary, detail, CdsCard.Indicator.WARNING,
            new Source(text.get("cds.source.pediatricDose", SOURCE_LABEL), null, null),
            null, null, null, UUID.randomUUID().toString(), null, Boolean.TRUE, null, key);
    }

    private CdsCard softAdvisory(CdsRuleContext context, int ageYears) {
        String summary = text.get("cds.pediatric.soft.summary",
            "Pediatric patient: verify weight-based dosing for {0}", context.proposedMedicationName());
        String detail = context.patientWeightKg() == null
            ? text.get("cds.pediatric.soft.detailNoWeight",
                "Patient is {0} years old (weight not on file). Confirm the prescribed dose"
                    + " against a pediatric reference before signing.",
                String.valueOf(ageYears))
            : text.get("cds.pediatric.soft.detail",
                "Patient is {0} years old (weight {1} kg). Confirm the prescribed dose"
                    + " against a pediatric reference before signing.",
                String.valueOf(ageYears), number(context.patientWeightKg()));
        return card(summary, detail, CdsCard.Indicator.WARNING, null);
    }

    private CdsCard card(String summary, String detail, CdsCard.Indicator indicator, Boolean hardStop) {
        return new CdsCard(
            summary, detail, indicator,
            new Source(text.get("cds.source.pediatricDose", SOURCE_LABEL), null, null),
            null, null, null, UUID.randomUUID().toString(), hardStop
        );
    }

    /*
     * QA D1078 / D1049 class (round 18): a measured value on the card carries the
     * reader's decimal separator ("12,50 mg/kg" in French and Spanish) through
     * CdsCardText.decimal, never a Locale.ROOT point. A computed value keeps two
     * decimals; a configured limit keeps the decimals it was entered with.
     */
    private String number(Double v) {
        // QA D1133: no padded zeros ("14 kg", not "14,00 kg").
        return v == null ? "?" : text.measure(v, 2);
    }

}
