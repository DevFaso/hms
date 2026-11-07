package com.example.hms.cdshooks.rules;

import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;

import java.util.List;
import java.util.Set;

/**
 * What {@link AdultParacetamolRule} reads beyond the proposed order, loaded only when it
 * applies ({@link ParacetamolFactsReader}).
 *
 * @param applies     the patient is an adult and the proposed order is paracetamol alone
 * @param otherOrders the patient's other running paracetamol orders
 * @param risks       the groups the patient's coded active problems put them in
 */
public record ParacetamolFacts(boolean applies, List<OtherOrder> otherOrders, Set<ParacetamolRisk> risks) {

    /** The rule does not apply: another drug, a child, or an unknown age. */
    public static final ParacetamolFacts NONE = new ParacetamolFacts(false, List.of(), Set.of());

    /**
     * Another running order, with the catalogue item that gives a count of tablets its
     * strength ({@code null} when none, or when the items its name names differ in strength).
     */
    public record OtherOrder(Prescription order, MedicationCatalogItem item) {
    }
}
