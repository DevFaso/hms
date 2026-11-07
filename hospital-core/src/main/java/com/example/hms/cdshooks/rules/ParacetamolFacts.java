package com.example.hms.cdshooks.rules;

import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;

import java.util.List;
import java.util.Set;

/**
 * What {@link AdultParacetamolRule} reads beyond the proposed order, loaded only when it
 * applies ({@link ParacetamolFactsReader}).
 *
 * @param applies       the patient is not known to be a child and the proposed order is paracetamol alone
 * @param otherOrders   the patient's other running paracetamol orders, which the total counts
 * @param risks         the groups the patient's coded active problems put them in
 * @param mayStillBeInUse names of active orders past the window with no readable duration: not
 *                      counted, and never dropped in silence, since nothing shows they were stopped
 * @param combinations  names of active orders that are combination products: not counted, their
 *                      paracetamol content cannot be read
 */
public record ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks,
                               List<String> mayStillBeInUse, List<String> combinations) {

    /** The rule does not apply: another drug or a child. */
    public static final ParacetamolFacts NONE = new ParacetamolFacts(false, List.of(), Set.of());

    /** Facts with nothing left out of the total. */
    public ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks) {
        this(applies, otherOrders, risks, List.of(), List.of());
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
