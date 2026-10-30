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
import java.util.Locale;
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
 *       automatic mg/kg verdict is given; a {@code warning} asks for a
 *       pharmacist or physician review.</li>
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
 */
@Component
public class PediatricDoseRule implements CdsRule {

    private static final String ID = "pediatric-dose";
    private static final String SOURCE_LABEL = "HMS Pediatric Dose Check";
    private static final int PEDIATRIC_AGE_THRESHOLD_YEARS = 18;
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
        if (context.patient() == null) return List.of();
        LocalDate dob = context.patient().getDateOfBirth();
        if (dob == null) return List.of();
        LocalDate today = LocalDate.now();
        Period age = Period.between(dob, today);
        if (age.isNegative() || age.getYears() >= PEDIATRIC_AGE_THRESHOLD_YEARS) return List.of();

        Check check = new Check(context, age, ChronoUnit.MONTHS.between(dob, today));
        List<CdsCard> cards = new ArrayList<>();
        check.hardStop().ifPresent(cards::add);

        if (check.ageMonths < check.manualReviewUnderMonths()) {
            cards.add(warning(text.get("cds.pediatric.manualReview.summary",
                "{0}: child under {1} months - no automatic mg/kg check; a pharmacist or physician must review the dose",
                check.medication(), String.valueOf(check.manualReviewUnderMonths())), check.limitBasis()));
            return cards;
        }
        Double weight = context.patientWeightKg();
        if (weight == null || weight <= 0d) {
            cards.add(softAdvisory(context, age.getYears()));
            return cards;
        }
        check.staleWeightCard().ifPresent(cards::add);

        if (check.hasBands()) {
            cards.addAll(check.bandCards());
            return cards;
        }
        if (!check.hasMgLimit()) {
            cards.add(softAdvisory(context, age.getYears()));
            return cards;
        }
        cards.addAll(check.mgCards());
        return cards;
    }

    /** One evaluation's facts, so each check reads them the same way. */
    private final class Check {
        private final CdsRuleContext context;
        private final MedicationCatalogItem item;
        private final Period age;
        private final long ageMonths;
        private final Double dosesPerDay;

        Check(CdsRuleContext context, Period age, long ageMonths) {
            this.context = context;
            this.item = context.proposedCatalogItem();
            this.age = age;
            this.ageMonths = ageMonths;
            Integer interval = MarDoseSchedule.intervalHours(context.proposedFrequency());
            this.dosesPerDay = interval == null ? null : 24d / interval;
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

        /** The absolute daily maximum in mg: the one limit no override passes. */
        Optional<CdsCard> hardStop() {
            BigDecimal cap = item == null ? null : item.getPediatricMaxDailyMg();
            Double doseMg = context.proposedDoseMg();
            if (cap == null || doseMg == null || dosesPerDay == null) {
                return Optional.empty();
            }
            double daily = doseMg * dosesPerDay;
            if (daily <= cap.doubleValue()) {
                return Optional.empty();
            }
            String summary = text.get("cds.pediatric.hardStop.summary",
                "{0}: {1} mg per day is above the absolute daily maximum of {2} mg - this dose cannot be prescribed",
                medication(), number(daily), number(cap));
            String detail = text.get("cds.pediatric.hardStop.detail",
                "Child aged {0}. The absolute daily maximum cannot be overridden: reduce the dose or the frequency. Limit: {1}.",
                ageText(), limitBasis());
            return Optional.of(card(summary, detail, CdsCard.Indicator.CRITICAL, Boolean.TRUE));
        }

        Optional<CdsCard> staleWeightCard() {
            LocalDateTime weighedAt = context.patientWeightRecordedAt();
            if (weighedAt == null) {
                return Optional.empty();
            }
            long days = ChronoUnit.DAYS.between(weighedAt.toLocalDate(), LocalDate.now());
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
                return List.of(warning(text.get("cds.pediatric.band.none.summary",
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
                    medication(), number(units), unitOf(band), number(bandDose), number(weight))));
            }
            if (dosesPerDay != null && band.getMaxDosesPerDay() != null) {
                double daily = units * dosesPerDay;
                double bandDaily = bandDose * band.getMaxDosesPerDay();
                if (daily > bandDaily) {
                    cards.add(softStop(text.get("cds.pediatric.band.daily.summary",
                        "{0}: {1} {2} per day is above the {3} {2} per day of the weight band for {4} kg",
                        medication(), number(daily), unitOf(band), number(bandDaily), number(weight))));
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
                    number(mgPerKg), perDose.toPlainString(), medication())));
            }
            BigDecimal single = item.getPediatricMaxSingleDoseMg();
            if (single != null && doseMg > single.doubleValue()) {
                cards.add(softStop(text.get("cds.pediatric.singleDose.summary",
                    "{0}: {1} mg per dose is above the maximum of {2} mg per dose",
                    medication(), number(doseMg), number(single))));
            }
            BigDecimal perDay = item.getPediatricMaxDailyMgPerKg();
            if (dosesPerDay != null) {
                double dailyMgPerKg = mgPerKg * dosesPerDay;
                if (perDay != null && dailyMgPerKg > perDay.doubleValue()) {
                    cards.add(softStop(text.get("cds.pediatric.dailyMgPerKg.summary",
                        "{0}: {1} mg/kg per day is above the usual maximum of {2} mg/kg per day",
                        medication(), number(dailyMgPerKg), number(perDay))));
                }
            } else if (perDay != null || item.getPediatricMaxDailyMg() != null) {
                cards.add(warning(text.get("cds.pediatric.frequencyUnread.summary",
                    "{0}: the daily total could not be read from the frequency \"{1}\" - check it against the daily maximum",
                    medication(), context.proposedFrequency() == null ? "" : context.proposedFrequency()),
                    limitBasis()));
            }
            return cards;
        }

        private CdsCard softStop(String summary) {
            String detail = text.get("cds.pediatric.softStop.detail",
                "Child aged {0}, weight {1} kg. Reduce the dose, or override with a reason: the reason is recorded on the order and audited. Limit: {2}.",
                ageText(), number(context.patientWeightKg()), limitBasis());
            return card(summary, detail, CdsCard.Indicator.CRITICAL, null);
        }

        private String unitOf(PediatricWeightBand band) {
            return band.getDoseUnit() == null || band.getDoseUnit().isBlank()
                ? text.get("cds.pediatric.band.defaultUnit", "tablet(s)")
                : band.getDoseUnit();
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
    }

    private CdsCard warning(String summary, String limitBasis) {
        String detail = text.get("cds.pediatric.warning.detail", "Limit: {0}.", limitBasis);
        return card(summary, detail, CdsCard.Indicator.WARNING, null);
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

    private static String number(Double v) {
        return v == null ? "?" : String.format(Locale.ROOT, "%.2f", v);
    }

    private static String number(BigDecimal v) {
        return v == null ? "?" : v.stripTrailingZeros().toPlainString();
    }

    private static String number(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
