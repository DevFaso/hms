package com.example.hms.cdshooks.rules;

import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;

import java.util.List;
import java.util.Set;

/**
 * What {@link AdultParacetamolRule} reads beyond the proposed order, loaded only when it
 * applies ({@link ParacetamolFactsReader}).
 *
 * @param applies       the proposed order holds paracetamol, alone or in a combination, for any patient;
 *                      a child gets only the contraindication, the paediatric rule keeps the rest
 * @param otherOrders   the patient's other running paracetamol orders, which the total counts
 * @param risks         the groups the patient's coded active problems put them in
 * @param mayStillBeInUse names of active orders past the window with no readable duration: not
 *                      counted, and never dropped in silence, since nothing shows they were stopped
 * @param combinations  names of active orders that are combination products: not counted, their
 *                      paracetamol content cannot be read
 * @param proposedCombination the proposed order itself is a combination: its dose is not counted
 * @param noActingHospital no hospital is acting (global view): rows tagged sensitive are not read
 * @param noHospital    no hospital is known: no order or problem could be read at all
 * @param limits        QA D1462: the paediatric mg limits of this drug for a free-text order, built by
 *                      the reader; the strength of a count is never read from it
 * @param notRecognised QA D1462: names of the other active orders that no catalogue item and no
 *                      cited brand names: if one holds paracetamol it is not counted, and that is said
 * @param nameNotRecognised QA D1462: the proposed name is of no catalogue item and no cited brand, so
 *                      this rule could not tell whether it holds paracetamol
 * @param documented    CR-03: the doses recorded as given in the last 24 hours, kept apart from the
 *                      orders' estimate, which they are never added to
 */
public record ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks,
                               List<String> mayStillBeInUse, List<String> combinations,
                               boolean proposedCombination, boolean noActingHospital, boolean noHospital,
                               MedicationCatalogItem limits, List<String> notRecognised,
                               boolean nameNotRecognised, Documented documented) {

    /** The rule does not apply: another drug. */
    public static final ParacetamolFacts NONE = new ParacetamolFacts(false, List.of(), Set.of());

    /** The rule cannot tell: the name is of nothing it knows. */
    public static final ParacetamolFacts NOT_RECOGNISED = new ParacetamolFacts(false, List.of(), Set.of(), List.of(),
        List.of(), false, false, false, null, List.of(), true);

    /** Facts with no record of the doses given: {@link Documented#NOT_READ}, which is unknown, not zero. */
    public ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks,
                            List<String> mayStillBeInUse, List<String> combinations,
                            boolean proposedCombination, boolean noActingHospital, boolean noHospital,
                            MedicationCatalogItem limits, List<String> notRecognised,
                            boolean nameNotRecognised) {
        this(applies, otherOrders, risks, mayStillBeInUse, combinations, proposedCombination, noActingHospital,
            noHospital, limits, notRecognised, nameNotRecognised, Documented.NOT_READ);
    }

    /** Facts with no free-text limits and nothing unrecognised beside them. */
    public ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks,
                            List<String> mayStillBeInUse, List<String> combinations,
                            boolean proposedCombination, boolean noActingHospital, boolean noHospital) {
        this(applies, otherOrders, risks, mayStillBeInUse, combinations, proposedCombination, noActingHospital,
            noHospital, null, List.of(), false);
    }

    /** Facts of a proposed order that is paracetamol alone, read at a hospital. */
    public ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks,
                            List<String> mayStillBeInUse, List<String> combinations) {
        this(applies, otherOrders, risks, mayStillBeInUse, combinations, false, false, false);
    }

    /** Facts with nothing left out of the total. */
    public ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks) {
        this(applies, otherOrders, risks, List.of(), List.of());
    }

    /**
     * CR-03: what the MAR records as given in the last 24 hours at this hospital.
     *
     * @param read      whether the record could be read; if not, the exposure is unknown, never zero
     * @param doses     the doses of paracetamol alone, whose amount the rule reads
     * @param unreadable names of the products given whose paracetamol content cannot be read:
     *                  combinations, and products no catalogue item or cited brand names
     */
    public record Documented(boolean read, List<Dose> doses, List<String> unreadable) {

        public static final Documented NOT_READ = new Documented(false, List.of(), List.of());
    }

    /** One recorded dose: when, what the MAR says was given, and the item that gives a count its strength. */
    public record Dose(java.time.LocalDateTime givenAt, String name, String dose, MedicationCatalogItem item) {
    }

    /**
     * Another running order, with the catalogue item that gives a count of tablets its
     * strength ({@code null} when none, or when the items its name names differ in strength),
     * and whether a duration could be read on it.
     */
    public record OtherOrder(Prescription order, MedicationCatalogItem item, boolean durationRead) {

        /** An order with a readable duration. */
        public OtherOrder(Prescription order, MedicationCatalogItem item) {
            this(order, item, true);
        }
    }
}
